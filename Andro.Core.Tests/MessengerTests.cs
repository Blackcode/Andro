using System.Security.Cryptography;
using System.Text;
using Andro.Core.Chat;
using Andro.Core.Crypto;
using Andro.Core.Nostr;
using Andro.Core.Tests.Support;

namespace Andro.Core.Tests;

/// <summary>End-to-end: two users chatting through real WebSocket relays.</summary>
public class MessengerTests
{
	static readonly TimeSpan Wait = TimeSpan.FromSeconds(15);

	static Messenger CreateUser(NostrKeys keys, IEnumerable<string> relays, string? proxy = null)
	{
		var store = ChatStore.Open(null, Messenger.NewStorageKey());
		store.Settings = new ChatSettings { Relays = [.. relays], ProxyUrl = proxy };
		return new Messenger(keys, store);
	}

	static Task<ChatMessage> NextMessage(Messenger m, Func<ChatMessage, bool>? predicate = null)
	{
		var tcs = new TaskCompletionSource<ChatMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
		m.MessageAdded += msg =>
		{
			if (predicate?.Invoke(msg) ?? true)
				tcs.TrySetResult(msg);
		};
		return tcs.Task.WaitAsync(Wait);
	}

	[Fact]
	public async Task TwoUsersExchangeMessages()
	{
		await using var relay = new TestRelay();
		var aliceKeys = NostrKeys.Generate();
		var bobKeys = NostrKeys.Generate();
		await using var alice = CreateUser(aliceKeys, [relay.Url]);
		await using var bob = CreateUser(bobKeys, [relay.Url]);
		await alice.StartAsync();
		await bob.StartAsync();

		Assert.True(alice.AddContact(bobKeys.Npub, "Bob"));
		var bobReceives = NextMessage(bob);
		var sent = await alice.SendAsync(bobKeys.PublicKeyHex, "Hi Bob, can you read this?");
		Assert.Equal(MessageStatus.Sent, sent.Status);

		var received = await bobReceives;
		Assert.Equal("Hi Bob, can you read this?", received.Text);
		Assert.Equal(aliceKeys.PublicKeyHex, received.PeerPubKey);
		Assert.Equal(MessageStatus.Received, received.Status);
		Assert.True(bob.Store.FindContact(aliceKeys.PublicKeyHex)!.IsRequest);

		var aliceReceives = NextMessage(alice, m => m.Status == MessageStatus.Received);
		await bob.SendAsync(aliceKeys.PublicKeyHex, "Loud and clear", replyToId: received.Id);
		var reply = await aliceReceives;
		Assert.Equal("Loud and clear", reply.Text);
		Assert.Equal(received.Id, reply.ReplyToId);

		// Alice's own copy came back from the relay but was not duplicated.
		Assert.Equal(2, alice.Store.Messages(bobKeys.PublicKeyHex).Count);
		Assert.Single(alice.Store.Conversations());
		Assert.Equal(1, alice.Store.Conversations()[0].UnreadCount);

		// The relay only ever saw encrypted wraps from throwaway keys; never the authors or text.
		var wraps = relay.Events.Values.Where(e => e.Kind == Kinds.GiftWrap).ToList();
		Assert.NotEmpty(wraps);
		Assert.DoesNotContain(wraps, e => e.PubKey == aliceKeys.PublicKeyHex || e.PubKey == bobKeys.PublicKeyHex);
		Assert.DoesNotContain(relay.Events.Values, e => e.Content.Contains("can you read this") || e.Content.Contains("Loud and clear"));
	}

	[Fact]
	public async Task MessagesWaitOnRelaysUntilTheRecipientComesOnline()
	{
		await using var relay = new TestRelay();
		var bobKeys = NostrKeys.Generate();
		await using var alice = CreateUser(NostrKeys.Generate(), [relay.Url]);
		await alice.StartAsync();
		var sent = await alice.SendAsync(bobKeys.PublicKeyHex, "sent while you were offline");
		Assert.Equal(MessageStatus.Sent, sent.Status);

		await using var bob = CreateUser(bobKeys, [relay.Url]);
		var bobReceives = NextMessage(bob);
		await bob.StartAsync();
		Assert.Equal("sent while you were offline", (await bobReceives).Text);
	}

	[Fact]
	public async Task StillDeliversWhenSomeRelaysAreBlocked()
	{
		await using var relay = new TestRelay();
		var blocked = $"ws://127.0.0.1:{TestRelay.FreePort()}"; // nothing listening: like a relay the censor blocked
		var bobKeys = NostrKeys.Generate();
		await using var alice = CreateUser(NostrKeys.Generate(), [blocked, relay.Url]);
		await using var bob = CreateUser(bobKeys, [blocked, relay.Url]);
		await alice.StartAsync();
		await bob.StartAsync();

		var bobReceives = NextMessage(bob);
		// Reported as sent as soon as the working relay accepts, without waiting for the blocked one to time out.
		var sent = await alice.SendAsync(bobKeys.PublicKeyHex, "one relay is enough").WaitAsync(TimeSpan.FromSeconds(5));
		Assert.Equal(MessageStatus.Sent, sent.Status);
		Assert.Equal("one relay is enough", (await bobReceives).Text);
	}

	[Fact]
	public async Task AuthenticatesToRelaysThatProtectInboxes()
	{
		await using var relay = new TestRelay(requireAuthForGiftWraps: true);
		var bobKeys = NostrKeys.Generate();
		var aliceKeys = NostrKeys.Generate();
		await using var alice = CreateUser(aliceKeys, [relay.Url]);
		await using var bob = CreateUser(bobKeys, [relay.Url]);
		await alice.StartAsync();

		await alice.SendAsync(bobKeys.PublicKeyHex, "only you can fetch this");
		var bobReceives = NextMessage(bob);
		await bob.StartAsync();
		Assert.Equal("only you can fetch this", (await bobReceives).Text);
		Assert.Contains(bobKeys.PublicKeyHex, relay.AuthenticatedPubKeys);
	}

	[Fact]
	public async Task RoutesTrafficThroughSocks5Proxy()
	{
		await using var relay = new TestRelay();
		using var proxy = new Socks5Proxy();
		var bobKeys = NostrKeys.Generate();
		// "relay.test" only resolves inside the proxy, like a .onion address only resolves inside Tor.
		await using var alice = CreateUser(NostrKeys.Generate(), [$"ws://relay.test:{relay.Port}"], proxy.Url);
		await using var bob = CreateUser(bobKeys, [relay.Url]);
		await alice.StartAsync();
		await bob.StartAsync();

		var bobReceives = NextMessage(bob);
		var sent = await alice.SendAsync(bobKeys.PublicKeyHex, "via the proxy");
		Assert.Equal(MessageStatus.Sent, sent.Status);
		Assert.Equal("via the proxy", (await bobReceives).Text);
		Assert.Contains($"relay.test:{relay.Port}", proxy.Connects);
	}

	[Fact]
	public async Task FailedSendsCanBeRetried()
	{
		var down = $"ws://127.0.0.1:{TestRelay.FreePort()}";
		var bobKeys = NostrKeys.Generate();
		var store = ChatStore.Open(null, Messenger.NewStorageKey());
		store.Settings = new ChatSettings { Relays = [down] };
		await using var alice = new Messenger(NostrKeys.Generate(), store, publishTimeout: TimeSpan.FromSeconds(2));
		await alice.StartAsync();
		store.UpsertContact(new Contact { PubKey = bobKeys.PublicKeyHex, InboxRelays = [down], InboxRelaysFetchedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() });

		var failed = await alice.SendAsync(bobKeys.PublicKeyHex, "nobody is listening").WaitAsync(Wait);
		Assert.Equal(MessageStatus.Failed, failed.Status);

		await using var relay = new TestRelay();
		await alice.SetRelaysAsync([relay.Url]);
		store.UpsertContact(store.FindContact(bobKeys.PublicKeyHex)! with { InboxRelays = [relay.Url] });
		var retried = await alice.RetryAsync(failed);
		Assert.Equal(MessageStatus.Sent, retried.Status);
		Assert.Equal(failed.Id, retried.Id);
	}

	[Fact]
	public void StoreIsEncryptedOnDisk()
	{
		var path = Path.Combine(Path.GetTempPath(), $"andro-{Guid.NewGuid():N}.bin");
		try
		{
			var key = Messenger.NewStorageKey();
			var store = ChatStore.Open(path, key);
			var peer = NostrKeys.Generate().PublicKeyHex;
			store.UpsertContact(new Contact { PubKey = peer, Name = "Secret Contact" });
			store.TryAddMessage(new ChatMessage { Id = "a1", PeerPubKey = peer, AuthorPubKey = peer, Text = "meet at noon", CreatedAt = 1, Status = MessageStatus.Received });
			store.Flush();

			var raw = Encoding.UTF8.GetString(File.ReadAllBytes(path));
			Assert.DoesNotContain("meet at noon", raw);
			Assert.DoesNotContain("Secret Contact", raw);
			Assert.DoesNotContain(peer, raw);

			var reopened = ChatStore.Open(path, key);
			Assert.Equal("Secret Contact", reopened.FindContact(peer)!.Name);
			Assert.Equal("meet at noon", Assert.Single(reopened.Messages(peer)).Text);

			Assert.Throws<CryptographicException>(() => ChatStore.Open(path, Messenger.NewStorageKey()));
		}
		finally
		{
			File.Delete(path);
		}
	}
}
