using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Andro.Core.Crypto;
using Andro.Core.Nostr;

namespace Andro.Core.Relays;

public enum RelayStatus
{
	Disconnected,
	Connecting,
	Connected,
}

/// <summary>
/// One WebSocket connection to a relay (NIP-01). Reconnects with backoff, restores subscriptions,
/// resends unacknowledged events, and answers NIP-42 AUTH challenges only when the relay demands it
/// (so we don't identify ourselves to relays that don't need to know who we are).
/// </summary>
public sealed class RelayConnection : IAsyncDisposable
{
	const int MaxMessageBytes = 512 * 1024;
	const string AuthRequired = "auth-required:";

	readonly ConnectionSettings _settings;
	readonly NostrKeys? _authKeys;
	readonly CancellationTokenSource _stop = new();
	readonly SemaphoreSlim _sendLock = new(1, 1);
	readonly ConcurrentDictionary<string, Filter[]> _subscriptions = new();
	readonly ConcurrentDictionary<string, PendingPublish> _unacknowledged = new();
	ClientWebSocket? _socket;
	Task? _loop;
	string? _challenge;
	bool _authenticated;
	int _authInFlight;

	public RelayConnection(string url, ConnectionSettings settings, NostrKeys? authKeys = null)
	{
		Url = RelayUrl.Normalize(url);
		_settings = settings;
		_authKeys = authKeys;
	}

	public string Url { get; }

	public RelayStatus Status { get; private set; } = RelayStatus.Disconnected;

	/// <summary>Why the last connection attempt or request failed, for display.</summary>
	public string? LastError { get; private set; }

	public event Action<RelayConnection>? StatusChanged;

	/// <summary>A verified event arrived for a subscription (subscription id, event).</summary>
	public event Action<RelayConnection, string, NostrEvent>? EventReceived;

	/// <summary>The relay has sent all stored events for a subscription.</summary>
	public event Action<RelayConnection, string>? EndOfStoredEvents;

	public void Start() => _loop ??= Task.Run(RunAsync);

	public void Subscribe(string subscriptionId, params Filter[] filters)
	{
		_subscriptions[subscriptionId] = filters;
		_ = SendIfConnectedAsync(RequestMessage(subscriptionId, filters));
	}

	public void Unsubscribe(string subscriptionId)
	{
		if (_subscriptions.TryRemove(subscriptionId, out _))
			_ = SendIfConnectedAsync($"[\"CLOSE\",{Json(subscriptionId)}]");
	}

	/// <summary>Sends the event and waits for the relay's OK. Unsent events are retried after reconnects until the timeout.</summary>
	public async Task<PublishResult> PublishAsync(NostrEvent e, TimeSpan timeout, CancellationToken cancellationToken = default)
	{
		var pending = new PendingPublish(EventMessage(e));
		pending = _unacknowledged.GetOrAdd(e.Id, pending);
		Start();
		await SendIfConnectedAsync(pending.Message);

		try
		{
			return await pending.Result.Task.WaitAsync(timeout, cancellationToken);
		}
		catch (TimeoutException)
		{
			return new PublishResult(Url, false, Status == RelayStatus.Connected ? "timed out waiting for relay" : LastError ?? "could not connect");
		}
		finally
		{
			_unacknowledged.TryRemove(new KeyValuePair<string, PendingPublish>(e.Id, pending));
		}
	}

	async Task RunAsync()
	{
		var attempt = 0;
		while (!_stop.IsCancellationRequested)
		{
			using var socket = CreateSocket();
			try
			{
				SetStatus(RelayStatus.Connecting);
				using (var connectTimeout = CancellationTokenSource.CreateLinkedTokenSource(_stop.Token))
				{
					connectTimeout.CancelAfter(_settings.ConnectTimeout);
					await socket.ConnectAsync(new Uri(Url), connectTimeout.Token);
				}

				_socket = socket;
				_challenge = null;
				_authenticated = false;
				attempt = 0;
				LastError = null;
				SetStatus(RelayStatus.Connected);

				foreach (var (id, filters) in _subscriptions)
					await SendAsync(RequestMessage(id, filters));
				foreach (var pending in _unacknowledged.Values)
					await SendAsync(pending.Message);

				await ReceiveLoopAsync(socket);
			}
			catch (Exception e) when (!_stop.IsCancellationRequested)
			{
				LastError = Describe(e);
			}
			catch
			{
				// Shutting down.
			}
			finally
			{
				_socket = null;
				SetStatus(RelayStatus.Disconnected);
			}

			if (_stop.IsCancellationRequested)
				break;

			// Exponential backoff with jitter so reconnects from many clients don't align.
			var delay = Math.Min(_settings.MaxReconnectDelay.TotalMilliseconds, 1000 * Math.Pow(2, attempt++));
			delay = delay / 2 + RandomNumberGenerator.GetInt32((int)Math.Max(1, delay / 2));
			try
			{
				await Task.Delay(TimeSpan.FromMilliseconds(delay), _stop.Token);
			}
			catch (OperationCanceledException)
			{
				break;
			}
		}
	}

	ClientWebSocket CreateSocket()
	{
		var socket = new ClientWebSocket();
		socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(30);
		if (!string.IsNullOrWhiteSpace(_settings.ProxyUrl))
		{
			var proxy = new Uri(_settings.ProxyUrl.Trim());
			// socks5h/socks4a are the curl spellings for "resolve names at the proxy", which .NET always does.
			var scheme = proxy.Scheme switch { "socks5h" => "socks5", "socks4a" => "socks4a", _ => proxy.Scheme };
			socket.Options.Proxy = new WebProxy(new UriBuilder(proxy) { Scheme = scheme }.Uri);
		}
		return socket;
	}

	async Task ReceiveLoopAsync(ClientWebSocket socket)
	{
		var buffer = new byte[16 * 1024];
		using var message = new MemoryStream();
		while (socket.State == WebSocketState.Open && !_stop.IsCancellationRequested)
		{
			var result = await socket.ReceiveAsync(buffer, _stop.Token);
			if (result.MessageType == WebSocketMessageType.Close)
			{
				LastError = string.IsNullOrEmpty(result.CloseStatusDescription) ? "relay closed the connection" : result.CloseStatusDescription;
				return;
			}

			message.Write(buffer, 0, result.Count);
			if (message.Length > MaxMessageBytes)
				throw new InvalidDataException("Relay sent an oversized message.");
			if (!result.EndOfMessage)
				continue;

			if (result.MessageType == WebSocketMessageType.Text)
			{
				try
				{
					Handle(message.GetBuffer().AsMemory(0, (int)message.Length));
				}
				catch (Exception e) when (e is JsonException or FormatException or InvalidOperationException or KeyNotFoundException)
				{
					// Ignore malformed messages; one bad relay message must not drop the connection.
				}
			}
			message.SetLength(0);
		}
	}

	void Handle(ReadOnlyMemory<byte> utf8)
	{
		using var doc = JsonDocument.Parse(utf8);
		var root = doc.RootElement;
		if (root.ValueKind != JsonValueKind.Array || root.GetArrayLength() < 2)
			return;

		switch (root[0].GetString())
		{
			case "EVENT" when root.GetArrayLength() >= 3:
			{
				var subscriptionId = root[1].GetString() ?? "";
				if (!_subscriptions.TryGetValue(subscriptionId, out var filters))
					return;
				var e = NostrEvent.FromJson(root[2]);
				// Never trust the relay: only accept correctly signed events we actually asked for.
				if (filters.Any(f => f.Matches(e)) && e.IsValid())
					EventReceived?.Invoke(this, subscriptionId, e);
				break;
			}
			case "EOSE":
				EndOfStoredEvents?.Invoke(this, root[1].GetString() ?? "");
				break;
			case "OK" when root.GetArrayLength() >= 3:
			{
				var id = root[1].GetString() ?? "";
				var accepted = root[2].GetBoolean();
				var text = root.GetArrayLength() >= 4 ? root[3].GetString() ?? "" : "";
				if (!accepted && CanAuthenticate && text.StartsWith(AuthRequired, StringComparison.Ordinal) && _unacknowledged.TryGetValue(id, out var retry) && !retry.RetriedAfterAuth)
				{
					retry.RetriedAfterAuth = true;
					_ = AuthenticateThenSendAsync(retry.Message);
				}
				else if (_unacknowledged.TryGetValue(id, out var pending))
				{
					pending.Result.TrySetResult(new PublishResult(Url, accepted || text.StartsWith("duplicate:", StringComparison.Ordinal), text));
				}
				break;
			}
			case "CLOSED":
			{
				var id = root[1].GetString() ?? "";
				var text = root.GetArrayLength() >= 3 ? root[2].GetString() ?? "" : "";
				if (CanAuthenticate && text.StartsWith(AuthRequired, StringComparison.Ordinal) && _subscriptions.TryGetValue(id, out var filters) && !_authenticated)
					_ = AuthenticateThenSendAsync(RequestMessage(id, filters));
				else
					LastError = $"subscription closed: {text}";
				break;
			}
			case "AUTH":
				_challenge = root[1].GetString();
				break;
			case "NOTICE":
				LastError = root[1].GetString();
				break;
		}
	}

	bool CanAuthenticate => _authKeys is not null && _challenge is not null;

	/// <summary>NIP-42: prove who we are with a signed ephemeral event, then resend the rejected request.</summary>
	async Task AuthenticateThenSendAsync(string message)
	{
		if (_authKeys is null)
			return;

		if (!_authenticated && _challenge is not null && Interlocked.CompareExchange(ref _authInFlight, 1, 0) == 0)
		{
			try
			{
				var auth = NostrEvent.CreateSigned(_authKeys, Kinds.ClientAuth, "", [["relay", Url], ["challenge", _challenge]]);
				var pending = _unacknowledged.GetOrAdd(auth.Id, _ => new PendingPublish($"[\"AUTH\",{auth.ToJson()}]"));
				await SendIfConnectedAsync(pending.Message);
				var result = await pending.Result.Task.WaitAsync(TimeSpan.FromSeconds(15), _stop.Token);
				_unacknowledged.TryRemove(auth.Id, out _);
				_authenticated = result.Accepted;
				if (!result.Accepted)
					LastError = $"authentication rejected: {result.Message}";
			}
			catch (Exception e) when (e is TimeoutException or OperationCanceledException)
			{
				LastError = "authentication timed out";
			}
			finally
			{
				Interlocked.Exchange(ref _authInFlight, 0);
			}
		}
		else
		{
			// Another request is already authenticating; give it a moment.
			for (var i = 0; i < 30 && _authInFlight == 1; i++)
				await Task.Delay(500, _stop.Token).ConfigureAwait(false);
		}

		if (_authenticated)
			await SendIfConnectedAsync(message);
	}

	async Task SendIfConnectedAsync(string message)
	{
		if (Status != RelayStatus.Connected)
			return;
		try
		{
			await SendAsync(message);
		}
		catch (Exception e) when (e is WebSocketException or ObjectDisposedException or InvalidOperationException or OperationCanceledException)
		{
			// The receive loop notices the broken socket and reconnects; pending items are resent then.
		}
	}

	async Task SendAsync(string message)
	{
		var socket = _socket;
		if (socket is null)
			return;
		await _sendLock.WaitAsync(_stop.Token);
		try
		{
			await socket.SendAsync(Encoding.UTF8.GetBytes(message), WebSocketMessageType.Text, true, _stop.Token);
		}
		finally
		{
			_sendLock.Release();
		}
	}

	void SetStatus(RelayStatus status)
	{
		if (Status == status)
			return;
		Status = status;
		StatusChanged?.Invoke(this);
	}

	static string Describe(Exception e)
	{
		var inner = e;
		while (inner.InnerException is not null)
			inner = inner.InnerException;
		return e is OperationCanceledException ? "connection timed out" : inner.Message;
	}

	static string Json(string value)
	{
		var sb = new StringBuilder();
		NostrJson.WriteString(sb, value);
		return sb.ToString();
	}

	static string EventMessage(NostrEvent e) => $"[\"EVENT\",{e.ToJson()}]";

	static string RequestMessage(string subscriptionId, Filter[] filters) =>
		$"[\"REQ\",{Json(subscriptionId)},{string.Join(',', filters.Select(f => f.ToJson()))}]";

	public async ValueTask DisposeAsync()
	{
		if (_stop.IsCancellationRequested)
			return;
		_stop.Cancel();
		var socket = _socket;
		if (socket is not null)
		{
			try
			{
				using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
				await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "bye", timeout.Token);
			}
			catch
			{
				// Best effort.
			}
			socket.Abort();
		}
		if (_loop is not null)
		{
			try
			{
				await _loop.WaitAsync(TimeSpan.FromSeconds(5));
			}
			catch
			{
				// Best effort.
			}
		}
		foreach (var pending in _unacknowledged.Values)
			pending.Result.TrySetResult(new PublishResult(Url, false, "connection closed"));
		_stop.Dispose();
	}

	sealed class PendingPublish(string message)
	{
		public string Message { get; } = message;
		public TaskCompletionSource<PublishResult> Result { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
		public bool RetriedAfterAuth { get; set; }
	}
}
