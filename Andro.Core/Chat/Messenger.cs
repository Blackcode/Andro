using System.Security.Cryptography;
using Andro.Core.Crypto;
using Andro.Core.Nostr;
using Andro.Core.Relays;

namespace Andro.Core.Chat;

/// <summary>
/// The chat engine: receives and sends NIP-17 private messages through a pool of relays.
///
/// What relays can see: that someone sent an encrypted blob to your public key at a randomized time.
/// They cannot see the sender, the text, or the real time. Your network provider sees only TLS
/// connections to relays (or, with a Tor proxy, only Tor traffic).
/// </summary>
public sealed class Messenger : IAsyncDisposable
{
	const string InboxSubscription = "inbox";
	static readonly TimeSpan QueryTimeout = TimeSpan.FromSeconds(8);
	static readonly TimeSpan InboxRelayCacheTime = TimeSpan.FromHours(6);

	readonly NostrKeys _keys;
	readonly ChatStore _store;
	readonly RelayPool _pool;
	readonly TimeSpan _publishTimeout;
	int _started;

	public Messenger(NostrKeys keys, ChatStore store, TimeSpan? publishTimeout = null)
	{
		_publishTimeout = publishTimeout ?? TimeSpan.FromSeconds(30);
		_keys = keys;
		_store = store;
		_pool = new RelayPool(new ConnectionSettings { ProxyUrl = store.Settings.ProxyUrl }, keys);
		_pool.EventReceived += OnEvent;
		_pool.EndOfStoredEvents += OnEndOfStoredEvents;
		_pool.StatusChanged += () => ConnectionChanged?.Invoke();
	}

	public string PubKey => _keys.PublicKeyHex;

	public string Npub => _keys.Npub;

	public ChatStore Store => _store;

	public IReadOnlyList<RelayConnection> Relays => _pool.Relays;

	public int ConnectedRelays => _pool.ConnectedCount;

	/// <summary>A message was added (received, or sent from this or another device).</summary>
	public event Action<ChatMessage>? MessageAdded;

	/// <summary>A sent message changed status.</summary>
	public event Action<ChatMessage>? MessageUpdated;

	public event Action? ContactsChanged;

	public event Action? ConnectionChanged;

	public async Task StartAsync()
	{
		if (Interlocked.Exchange(ref _started, 1) == 1)
			return;
		await _pool.SetRelaysAsync(_store.Settings.Relays);
		SubscribeInbox();
		_ = PublishInboxRelaysAsync();
	}

	/// <summary>Sends <paramref name="text"/> to <paramref name="peerPubKey"/>, end-to-end encrypted.</summary>
	/// <exception cref="ArgumentException">The message is empty or too long.</exception>
	public async Task<ChatMessage> SendAsync(string peerPubKey, string text, string? replyToId = null, CancellationToken cancellationToken = default)
	{
		text = text.Trim();
		if (text.Length == 0)
			throw new ArgumentException("Message is empty.", nameof(text));

		var rumor = PrivateMessages.CreateChatMessage(_keys.PublicKeyHex, [peerPubKey], text, replyToId);
		NostrEvent wrapForPeer, wrapForSelf;
		try
		{
			wrapForPeer = GiftWrap.Wrap(_keys, rumor, peerPubKey);
			wrapForSelf = GiftWrap.Wrap(_keys, rumor, _keys.PublicKeyHex);
		}
		catch (ArgumentException e)
		{
			throw new ArgumentException("Message is too long.", nameof(text), e);
		}

		EnsureContact(peerPubKey, isRequest: false);
		var message = new ChatMessage
		{
			Id = rumor.Id,
			PeerPubKey = peerPubKey,
			AuthorPubKey = _keys.PublicKeyHex,
			Text = text,
			CreatedAt = rumor.CreatedAt,
			Status = MessageStatus.Sending,
			ReplyToId = replyToId,
		};
		_store.TryAddMessage(message);
		MessageAdded?.Invoke(message);

		return await DeliverAsync(message, wrapForPeer, wrapForSelf, cancellationToken);
	}

	/// <summary>Sends a failed message again (same id, fresh encryption).</summary>
	public async Task<ChatMessage> RetryAsync(ChatMessage message, CancellationToken cancellationToken = default)
	{
		var rumor = PrivateMessages.CreateChatMessage(_keys.PublicKeyHex, [message.PeerPubKey], message.Text, message.ReplyToId, message.CreatedAt);
		var sending = message with { Status = MessageStatus.Sending };
		_store.UpdateMessage(sending);
		MessageUpdated?.Invoke(sending);
		return await DeliverAsync(sending, GiftWrap.Wrap(_keys, rumor, message.PeerPubKey), GiftWrap.Wrap(_keys, rumor, _keys.PublicKeyHex), cancellationToken);
	}

	async Task<ChatMessage> DeliverAsync(ChatMessage message, NostrEvent wrapForPeer, NostrEvent wrapForSelf, CancellationToken cancellationToken)
	{
		var peerRelays = await InboxRelaysAsync(message.PeerPubKey, cancellationToken);
		// Our own copy lets our other devices (or a reinstall) see what we sent.
		_ = _pool.PublishAsync(wrapForSelf, MyInboxRelays(), _publishTimeout, cancellationToken);

		var gate = new object();
		var accepted = 0;
		var pending = peerRelays.Count;
		var firstResult = new TaskCompletionSource<ChatMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
		var current = message;

		// Report "sent" as soon as one relay accepts; blocked relays must not hold the message up.
		// Later acceptances only raise the relay count.
		foreach (var relay in peerRelays)
		{
			_ = Task.Run(async () =>
			{
				var results = await _pool.PublishAsync(wrapForPeer, [relay], _publishTimeout, cancellationToken);
				ChatMessage? update = null;
				lock (gate)
				{
					pending--;
					if (results.Any(r => r.Accepted))
					{
						accepted++;
						update = current = current with { Status = MessageStatus.Sent, RelaysAccepted = accepted };
					}
					else if (pending == 0 && accepted == 0)
					{
						update = current = current with { Status = MessageStatus.Failed };
					}
				}
				if (update is not null)
				{
					_store.UpdateMessage(update);
					MessageUpdated?.Invoke(update);
					firstResult.TrySetResult(update);
				}
			}, CancellationToken.None);
		}
		if (peerRelays.Count == 0)
		{
			current = current with { Status = MessageStatus.Failed };
			_store.UpdateMessage(current);
			MessageUpdated?.Invoke(current);
			return current;
		}
		return await firstResult.Task;
	}

	/// <summary>Adds or renames a contact. Returns false if <paramref name="publicKey"/> is not a valid npub/hex key.</summary>
	public bool AddContact(string publicKey, string? name)
	{
		var pubKey = Nip19.TryParsePublicKey(publicKey);
		if (pubKey is null || pubKey == _keys.PublicKeyHex)
			return false;
		var existing = _store.FindContact(pubKey);
		_store.UpsertContact((existing ?? new Contact { PubKey = pubKey }) with
		{
			Name = string.IsNullOrWhiteSpace(name) ? existing?.Name : name.Trim(),
			IsRequest = false,
		});
		ContactsChanged?.Invoke();
		_ = InboxRelaysAsync(pubKey, CancellationToken.None);
		return true;
	}

	public void RemoveContact(string pubKey)
	{
		_store.RemoveContact(pubKey);
		ContactsChanged?.Invoke();
	}

	public void MarkRead(string pubKey)
	{
		var contact = _store.FindContact(pubKey);
		if (contact is null)
			return;
		_store.UpsertContact(contact with { LastReadAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() });
		ContactsChanged?.Invoke();
	}

	/// <summary>Replaces the relay list, reconnects, and republishes where we receive messages.</summary>
	public async Task SetRelaysAsync(IEnumerable<string> relays)
	{
		var list = relays.Where(RelayUrl.IsValid).Select(RelayUrl.Normalize).Distinct().ToList();
		if (list.Count == 0)
			throw new ArgumentException("At least one relay is required.", nameof(relays));
		_store.Settings = _store.Settings with { Relays = list };
		await _pool.SetRelaysAsync(list);
		await PublishInboxRelaysAsync();
	}

	/// <summary>Routes all traffic through a proxy (e.g. Tor), or directly when null.</summary>
	public async Task SetProxyAsync(string? proxyUrl)
	{
		if (proxyUrl is not null && !ConnectionSettings.IsValidProxyUrl(proxyUrl))
			throw new ArgumentException("Invalid proxy URL.", nameof(proxyUrl));
		_store.Settings = _store.Settings with { ProxyUrl = proxyUrl?.Trim() };
		await _pool.UpdateSettingsAsync(_pool.Settings with { ProxyUrl = proxyUrl?.Trim() });
	}

	IReadOnlyList<string> MyInboxRelays() => [.. _store.Settings.Relays.Take(DefaultRelays.InboxCount)];

	async Task PublishInboxRelaysAsync()
	{
		var list = PrivateMessages.CreateInboxRelayList(_keys, MyInboxRelays());
		try
		{
			await _pool.PublishAsync(list, _store.Settings.Relays, _publishTimeout);
		}
		catch (Exception)
		{
			// Retried on next start or relay change.
		}
	}

	/// <summary>
	/// Where to deliver to <paramref name="pubKey"/>: their published kind 10050 list, looked up on our
	/// relays. If they have none, fall back to our relays (other Andro users read the same defaults).
	/// </summary>
	async Task<IReadOnlyList<string>> InboxRelaysAsync(string pubKey, CancellationToken cancellationToken)
	{
		var contact = _store.FindContact(pubKey);
		var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
		if (contact is { InboxRelays.Count: > 0 } && now - contact.InboxRelaysFetchedAt < InboxRelayCacheTime.TotalSeconds)
			return contact.InboxRelays;

		try
		{
			var found = await _pool.FetchAsync(
				[new Filter { Authors = [pubKey], Kinds = [Kinds.DmRelayList], Limit = 1 }],
				_store.Settings.Relays, QueryTimeout, cancellationToken, firstAnswerIsEnough: true);
			var relays = found.Select(PrivateMessages.ParseInboxRelayList).FirstOrDefault(r => r.Count > 0);
			if (relays is not null)
			{
				contact = _store.FindContact(pubKey) ?? new Contact { PubKey = pubKey };
				_store.UpsertContact(contact with { InboxRelays = relays, InboxRelaysFetchedAt = now });
				return relays;
			}
		}
		catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
		{
			// Fall through to the fallback.
		}
		return contact?.InboxRelays is { Count: > 0 } cached ? cached : _store.Settings.Relays;
	}

	void SubscribeInbox()
	{
		// Wrap timestamps are randomized up to two days into the past, so look back that far plus a margin.
		long? since = _store.LastSyncAt > 0
			? _store.LastSyncAt - (long)(GiftWrap.TimestampJitter + TimeSpan.FromHours(1)).TotalSeconds
			: null;
		_pool.Subscribe(InboxSubscription, new Filter
		{
			Kinds = [Kinds.GiftWrap],
			Tags = new Dictionary<string, IReadOnlyList<string>> { ["p"] = [_keys.PublicKeyHex] },
			Since = since,
		});
	}

	void OnEndOfStoredEvents(string subscriptionId, string relay)
	{
		if (subscriptionId == InboxSubscription)
			_store.LastSyncAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
	}

	void OnEvent(string subscriptionId, NostrEvent wrap)
	{
		if (subscriptionId != InboxSubscription || !_store.TryMarkProcessed(wrap.Id))
			return;

		NostrEvent rumor;
		try
		{
			rumor = GiftWrap.Unwrap(wrap, _keys);
		}
		catch (InvalidDataException)
		{
			return; // Forged, corrupted, or not for us.
		}

		if (rumor.Kind != Kinds.ChatMessage || string.IsNullOrEmpty(rumor.Content))
			return;

		var outgoing = rumor.PubKey == _keys.PublicKeyHex;
		var peers = PrivateMessages.Participants(rumor, _keys.PublicKeyHex);
		// Only one-to-one chats for now; group messages are shown in the author's conversation.
		var peer = outgoing ? peers.FirstOrDefault() : rumor.PubKey;
		if (peer is null)
			return;

		var message = new ChatMessage
		{
			Id = rumor.Id,
			PeerPubKey = peer,
			AuthorPubKey = rumor.PubKey,
			Text = rumor.Content,
			CreatedAt = rumor.CreatedAt,
			Status = outgoing ? MessageStatus.Sent : MessageStatus.Received,
			ReplyToId = rumor.Tags.FirstOrDefault(t => t.Count >= 2 && t[0] == "e")?[1],
		};

		EnsureContact(peer, isRequest: !outgoing);
		if (_store.TryAddMessage(message))
			MessageAdded?.Invoke(message);
	}

	void EnsureContact(string pubKey, bool isRequest)
	{
		if (_store.FindContact(pubKey) is not null)
			return;
		_store.UpsertContact(new Contact { PubKey = pubKey, IsRequest = isRequest });
		ContactsChanged?.Invoke();
	}

	/// <summary>A new random 32-byte key for <see cref="ChatStore"/>; keep it in the platform keystore.</summary>
	public static byte[] NewStorageKey() => RandomNumberGenerator.GetBytes(SecretBox.KeySize);

	public async ValueTask DisposeAsync()
	{
		await _pool.DisposeAsync();
		_store.Flush();
	}
}
