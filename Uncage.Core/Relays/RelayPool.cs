using System.Collections.Concurrent;
using System.Security.Cryptography;
using Uncage.Core.Crypto;
using Uncage.Core.Nostr;

namespace Uncage.Core.Relays;

/// <summary>
/// Connections to many relays at once. Messages are published to several relays and read from all
/// of them, so blocking or losing any single relay does not stop the conversation.
/// </summary>
public sealed class RelayPool : IAsyncDisposable
{
	readonly object _gate = new();
	readonly NostrKeys? _authKeys;
	readonly Dictionary<string, RelayConnection> _relays = [];
	readonly ConcurrentDictionary<string, Filter[]> _subscriptions = new();
	readonly HashSet<string> _seen = [];
	readonly Queue<string> _seenOrder = new();
	ConnectionSettings _settings;

	public RelayPool(ConnectionSettings settings, NostrKeys? authKeys = null)
	{
		_settings = settings;
		_authKeys = authKeys;
	}

	public ConnectionSettings Settings => _settings;

	/// <summary>An event arrived on a pool subscription; duplicates from other relays are dropped.</summary>
	public event Action<string, NostrEvent>? EventReceived;

	/// <summary>A relay finished sending stored events for a subscription (subscription id, relay url).</summary>
	public event Action<string, string>? EndOfStoredEvents;

	public event Action? StatusChanged;

	public IReadOnlyList<RelayConnection> Relays
	{
		get
		{
			lock (_gate)
				return [.. _relays.Values];
		}
	}

	public int ConnectedCount => Relays.Count(r => r.Status == RelayStatus.Connected);

	/// <summary>Connects to exactly these relays, keeping connections that are already open.</summary>
	public async Task SetRelaysAsync(IEnumerable<string> urls)
	{
		var wanted = urls.Where(RelayUrl.IsValid).Select(RelayUrl.Normalize).ToHashSet();
		List<RelayConnection> removed;
		lock (_gate)
		{
			removed = [.. _relays.Values.Where(r => !wanted.Contains(r.Url))];
			foreach (var relay in removed)
				_relays.Remove(relay.Url);
			foreach (var url in wanted.Where(u => !_relays.ContainsKey(u)))
				_relays[url] = CreateConnection(url);
		}
		foreach (var relay in removed)
			await relay.DisposeAsync();
		StatusChanged?.Invoke();
	}

	/// <summary>Changes the proxy (for example turning Tor on) by reconnecting every relay.</summary>
	public async Task UpdateSettingsAsync(ConnectionSettings settings)
	{
		List<string> urls;
		List<RelayConnection> old;
		lock (_gate)
		{
			_settings = settings;
			urls = [.. _relays.Keys];
			old = [.. _relays.Values];
			_relays.Clear();
		}
		foreach (var relay in old)
			await relay.DisposeAsync();
		await SetRelaysAsync(urls);
	}

	public void Subscribe(string subscriptionId, params Filter[] filters)
	{
		_subscriptions[subscriptionId] = filters;
		foreach (var relay in Relays)
			relay.Subscribe(subscriptionId, filters);
	}

	public void Unsubscribe(string subscriptionId)
	{
		_subscriptions.TryRemove(subscriptionId, out _);
		foreach (var relay in Relays)
			relay.Unsubscribe(subscriptionId);
	}

	/// <summary>
	/// Publishes to the given relays, reusing pool connections and opening temporary ones for
	/// relays outside the pool (such as a contact's inbox relays).
	/// </summary>
	public async Task<IReadOnlyList<PublishResult>> PublishAsync(NostrEvent e, IEnumerable<string> urls, TimeSpan timeout, CancellationToken cancellationToken = default)
	{
		var targets = urls.Where(RelayUrl.IsValid).Select(RelayUrl.Normalize).Distinct().ToList();
		var tasks = targets.Select(async url =>
		{
			var (relay, temporary) = Acquire(url);
			try
			{
				return await relay.PublishAsync(e, timeout, cancellationToken);
			}
			finally
			{
				if (temporary)
					await relay.DisposeAsync();
			}
		});
		return await Task.WhenAll(tasks);
	}

	/// <summary>
	/// One-off query: collects matching events until every relay has sent EOSE or the timeout passes.
	/// With <paramref name="firstAnswerIsEnough"/>, stops as soon as any relay has finished with results,
	/// so unreachable (blocked) relays don't delay the answer.
	/// </summary>
	public async Task<IReadOnlyList<NostrEvent>> FetchAsync(Filter[] filters, IEnumerable<string>? urls, TimeSpan timeout, CancellationToken cancellationToken = default, bool firstAnswerIsEnough = false)
	{
		var targets = (urls ?? Relays.Select(r => r.Url)).Where(RelayUrl.IsValid).Select(RelayUrl.Normalize).Distinct().ToList();
		var results = new ConcurrentDictionary<string, NostrEvent>();
		var subscriptionId = "q" + Hex.Encode(RandomNumberGenerator.GetBytes(6));
		var enough = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
		using var stop = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);

		var tasks = targets.Select(async url =>
		{
			var (relay, temporary) = Acquire(url);
			var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
			void OnEvent(RelayConnection _, string id, NostrEvent e)
			{
				if (id == subscriptionId && filters.Any(f => f.Matches(e)))
					results.TryAdd(e.Id, e);
			}
			void OnEose(RelayConnection _, string id)
			{
				if (id != subscriptionId)
					return;
				done.TrySetResult();
				if (firstAnswerIsEnough && !results.IsEmpty)
					enough.TrySetResult();
			}

			relay.EventReceived += OnEvent;
			relay.EndOfStoredEvents += OnEose;
			try
			{
				relay.Subscribe(subscriptionId, filters);
				await done.Task.WaitAsync(timeout, stop.Token);
			}
			catch (Exception e) when (e is TimeoutException or OperationCanceledException)
			{
				// Use whatever arrived.
			}
			finally
			{
				relay.EventReceived -= OnEvent;
				relay.EndOfStoredEvents -= OnEose;
				relay.Unsubscribe(subscriptionId);
				if (temporary)
					await relay.DisposeAsync();
			}
		}).ToList();

		await Task.WhenAny(Task.WhenAll(tasks), enough.Task);
		await stop.CancelAsync();
		await Task.WhenAll(tasks);
		cancellationToken.ThrowIfCancellationRequested();
		return [.. results.Values.OrderByDescending(e => e.CreatedAt)];
	}

	(RelayConnection Relay, bool Temporary) Acquire(string url)
	{
		lock (_gate)
		{
			if (_relays.TryGetValue(url, out var existing))
				return (existing, false);
		}
		var relay = new RelayConnection(url, _settings, _authKeys);
		relay.Start();
		return (relay, true);
	}

	RelayConnection CreateConnection(string url)
	{
		var relay = new RelayConnection(url, _settings, _authKeys);
		relay.EventReceived += OnEvent;
		relay.EndOfStoredEvents += (r, id) => EndOfStoredEvents?.Invoke(id, r.Url);
		relay.StatusChanged += _ => StatusChanged?.Invoke();
		foreach (var (id, filters) in _subscriptions)
			relay.Subscribe(id, filters);
		relay.Start();
		return relay;
	}

	void OnEvent(RelayConnection relay, string subscriptionId, NostrEvent e)
	{
		if (!_subscriptions.ContainsKey(subscriptionId))
			return;
		lock (_seen)
		{
			if (!_seen.Add(e.Id))
				return;
			_seenOrder.Enqueue(e.Id);
			if (_seenOrder.Count > 10_000)
				_seen.Remove(_seenOrder.Dequeue());
		}
		EventReceived?.Invoke(subscriptionId, e);
	}

	public async ValueTask DisposeAsync()
	{
		List<RelayConnection> all;
		lock (_gate)
		{
			all = [.. _relays.Values];
			_relays.Clear();
		}
		foreach (var relay in all)
			await relay.DisposeAsync();
	}
}
