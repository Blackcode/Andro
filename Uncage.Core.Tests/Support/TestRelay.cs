using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Uncage.Core.Crypto;
using Uncage.Core.Nostr;

namespace Uncage.Core.Tests.Support;

/// <summary>A minimal in-process NIP-01 relay with optional NIP-42 auth for reading gift wraps.</summary>
sealed class TestRelay : IAsyncDisposable
{
	readonly HttpListener _listener = new();
	readonly CancellationTokenSource _stop = new();
	readonly ConcurrentBag<Client> _clients = [];
	readonly Task _accept;

	public TestRelay(bool requireAuthForGiftWraps = false)
	{
		RequireAuth = requireAuthForGiftWraps;
		Port = FreePort();
		_listener.Prefixes.Add($"http://*:{Port}/");
		_listener.Start();
		_accept = Task.Run(AcceptLoop);
	}

	public int Port { get; }
	public string Url => $"ws://127.0.0.1:{Port}";
	public bool RequireAuth { get; }
	public ConcurrentDictionary<string, NostrEvent> Events { get; } = new();
	public ConcurrentBag<string> AuthenticatedPubKeys { get; } = [];

	public static int FreePort()
	{
		var l = new TcpListener(IPAddress.Loopback, 0);
		l.Start();
		var port = ((IPEndPoint)l.LocalEndpoint).Port;
		l.Stop();
		return port;
	}

	async Task AcceptLoop()
	{
		while (!_stop.IsCancellationRequested)
		{
			HttpListenerContext context;
			try { context = await _listener.GetContextAsync(); }
			catch { return; }
			if (!context.Request.IsWebSocketRequest)
			{
				context.Response.StatusCode = 400;
				context.Response.Close();
				continue;
			}
			var ws = (await context.AcceptWebSocketAsync(null)).WebSocket;
			var client = new Client(ws);
			_clients.Add(client);
			_ = Task.Run(() => Serve(client));
		}
	}

	async Task Serve(Client client)
	{
		if (RequireAuth)
			await client.Send($"[\"AUTH\",\"{client.Challenge}\"]");
		var buffer = new byte[256 * 1024];
		try
		{
			while (client.Socket.State == WebSocketState.Open)
			{
				using var ms = new MemoryStream();
				WebSocketReceiveResult r;
				do
				{
					r = await client.Socket.ReceiveAsync(buffer, _stop.Token);
					if (r.MessageType == WebSocketMessageType.Close) return;
					ms.Write(buffer, 0, r.Count);
				} while (!r.EndOfMessage);
				await Handle(client, JsonDocument.Parse(ms.ToArray()).RootElement);
			}
		}
		catch
		{
			// Client went away.
		}
	}

	async Task Handle(Client client, JsonElement msg)
	{
		switch (msg[0].GetString())
		{
			case "EVENT":
			{
				var e = NostrEvent.FromJson(msg[1]);
				if (!e.IsValid())
				{
					await client.Send($"[\"OK\",\"{e.Id}\",false,\"invalid: bad signature\"]");
					return;
				}
				var duplicate = !Events.TryAdd(e.Id, e);
				await client.Send($"[\"OK\",\"{e.Id}\",true,\"{(duplicate ? "duplicate: already have it" : "")}\"]");
				if (duplicate) return;
				foreach (var other in _clients)
				{
					foreach (var (subId, filters) in other.Subscriptions)
					{
						if (filters.Any(f => f.Matches(e)) && CanRead(other, e))
							await other.Send($"[\"EVENT\",\"{subId}\",{e.ToJson()}]");
					}
				}
				break;
			}
			case "REQ":
			{
				var subId = msg[1].GetString()!;
				var filters = msg.EnumerateArray().Skip(2).Select(ParseFilter).ToArray();
				if (RequireAuth && filters.Any(f => f.Kinds?.Contains(Kinds.GiftWrap) == true) && client.AuthedPubKey is null)
				{
					await client.Send($"[\"CLOSED\",\"{subId}\",\"auth-required: gift wraps are only served to their recipient\"]");
					return;
				}
				client.Subscriptions[subId] = filters;
				foreach (var f in filters)
				{
					var matches = Events.Values.Where(f.Matches).Where(e => CanRead(client, e)).OrderByDescending(e => e.CreatedAt);
					foreach (var e in f.Limit is { } limit ? matches.Take(limit) : matches)
						await client.Send($"[\"EVENT\",\"{subId}\",{e.ToJson()}]");
				}
				await client.Send($"[\"EOSE\",\"{subId}\"]");
				break;
			}
			case "CLOSE":
				client.Subscriptions.TryRemove(msg[1].GetString()!, out _);
				break;
			case "AUTH":
			{
				var e = NostrEvent.FromJson(msg[1]);
				var ok = e.IsValid() && e.Kind == Kinds.ClientAuth && e.FirstTagValue("challenge") == client.Challenge &&
					e.FirstTagValue("relay")?.StartsWith(Url, StringComparison.Ordinal) == true;
				if (ok)
				{
					client.AuthedPubKey = e.PubKey;
					AuthenticatedPubKeys.Add(e.PubKey);
				}
				await client.Send($"[\"OK\",\"{e.Id}\",{(ok ? "true" : "false")},\"{(ok ? "" : "invalid: bad auth")}\"]");
				break;
			}
		}
	}

	bool CanRead(Client client, NostrEvent e) =>
		!RequireAuth || e.Kind != Kinds.GiftWrap || e.TagValues("p").Contains(client.AuthedPubKey);

	static Filter ParseFilter(JsonElement f)
	{
		List<string>? Strings(string name) => f.TryGetProperty(name, out var v) ? [.. v.EnumerateArray().Select(x => x.GetString()!)] : null;
		var tags = f.EnumerateObject().Where(p => p.Name.StartsWith('#'))
			.ToDictionary(p => p.Name[1..], p => (IReadOnlyList<string>)[.. p.Value.EnumerateArray().Select(x => x.GetString()!)]);
		return new Filter
		{
			Ids = Strings("ids"),
			Authors = Strings("authors"),
			Kinds = f.TryGetProperty("kinds", out var k) ? [.. k.EnumerateArray().Select(x => x.GetInt32())] : null,
			Tags = tags.Count > 0 ? tags : null,
			Since = f.TryGetProperty("since", out var s) ? s.GetInt64() : null,
			Until = f.TryGetProperty("until", out var u) ? u.GetInt64() : null,
			Limit = f.TryGetProperty("limit", out var l) ? l.GetInt32() : null,
		};
	}

	public async ValueTask DisposeAsync()
	{
		_stop.Cancel();
		foreach (var c in _clients) c.Socket.Abort();
		_listener.Stop();
		_listener.Close();
		try { await _accept; } catch { }
	}

	sealed class Client(WebSocket socket)
	{
		readonly SemaphoreSlim _lock = new(1, 1);
		public WebSocket Socket { get; } = socket;
		public string Challenge { get; } = Hex.Encode(RandomNumberGenerator.GetBytes(8));
		public string? AuthedPubKey { get; set; }
		public ConcurrentDictionary<string, Filter[]> Subscriptions { get; } = new();

		public async Task Send(string text)
		{
			await _lock.WaitAsync();
			try { await Socket.SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, true, CancellationToken.None); }
			catch { }
			finally { _lock.Release(); }
		}
	}
}
