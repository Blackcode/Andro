using System.Text.Json;
using Uncage.Core.Crypto;
using Uncage.Core.Nostr;

namespace Uncage.Core.Tests;

public class EventTests
{
	static JsonElement Fixture(string name) =>
		JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Vectors", name))).RootElement;

	[Fact]
	public void OpensTheNip17SpecExampleAsReceiverAndSender()
	{
		var f = Fixture("nip17-example.json");
		var sender = NostrKeys.Parse(f.GetProperty("sender_nsec").GetString()!);
		var receiver = NostrKeys.Parse(f.GetProperty("receiver_nsec").GetString()!);

		var toReceiver = GiftWrap.Unwrap(NostrEvent.FromJson(f.GetProperty("to_receiver")), receiver);
		Assert.Equal(Kinds.ChatMessage, toReceiver.Kind);
		Assert.Equal("Hola, que tal?", toReceiver.Content);
		Assert.Equal(sender.PublicKeyHex, toReceiver.PubKey);
		Assert.Equal([sender.PublicKeyHex], PrivateMessages.Participants(toReceiver, receiver.PublicKeyHex));

		var toSelf = GiftWrap.Unwrap(NostrEvent.FromJson(f.GetProperty("to_sender")), sender);
		Assert.Equal(toReceiver.Id, toSelf.Id);
		Assert.Equal([receiver.PublicKeyHex], PrivateMessages.Participants(toSelf, sender.PublicKeyHex));
	}

	[Fact]
	public void OpensTheNip59SpecExample()
	{
		var f = Fixture("nip59-example.json");
		var recipient = NostrKeys.FromSecret(Hex.Decode(f.GetProperty("recipient_sec").GetString()!));
		var expected = NostrEvent.FromJson(f.GetProperty("rumor"));

		Assert.True(expected.HasValidId());
		Assert.True(NostrEvent.FromJson(f.GetProperty("seal")).IsValid());

		var rumor = GiftWrap.Unwrap(NostrEvent.FromJson(f.GetProperty("wrap")), recipient);
		Assert.Equal(expected.Id, rumor.Id);
		Assert.Equal("Are you going to the party tonight?", rumor.Content);
	}

	[Fact]
	public void WrapsAndUnwrapsForEachParticipant()
	{
		var alice = NostrKeys.Generate();
		var bob = NostrKeys.Generate();
		var rumor = PrivateMessages.CreateChatMessage(alice.PublicKeyHex, [bob.PublicKeyHex], "héllo \"bob\" 🦄\n\u0001");

		var toBob = GiftWrap.Wrap(alice, rumor, bob.PublicKeyHex);
		var toAlice = GiftWrap.Wrap(alice, rumor, alice.PublicKeyHex);

		Assert.NotEqual(alice.PublicKeyHex, toBob.PubKey);
		Assert.Equal([bob.PublicKeyHex], toBob.TagValues("p"));
		Assert.InRange(toBob.CreatedAt, rumor.CreatedAt - (long)GiftWrap.TimestampJitter.TotalSeconds, rumor.CreatedAt);

		var received = GiftWrap.Unwrap(NostrEvent.FromJson(toBob.ToJson()), bob);
		Assert.Equal(rumor.Id, received.Id);
		Assert.Equal(rumor.Content, received.Content);
		Assert.Equal(rumor.Id, GiftWrap.Unwrap(toAlice, alice).Id);

		Assert.Throws<InvalidDataException>(() => GiftWrap.Unwrap(toBob, NostrKeys.Generate()));
	}

	[Fact]
	public void RejectsImpersonation()
	{
		// Mallory seals a rumor that claims to be written by Alice.
		var alice = NostrKeys.Generate();
		var bob = NostrKeys.Generate();
		var mallory = NostrKeys.Generate();
		var forged = PrivateMessages.CreateChatMessage(alice.PublicKeyHex, [bob.PublicKeyHex], "send me money");

		var seal = NostrEvent.CreateSigned(mallory, Kinds.Seal, Nip44.Encrypt(forged.ToJson(), Nip44.ConversationKey(mallory, bob.PublicKeyHex)));
		var wrapKey = NostrKeys.Generate();
		var wrap = NostrEvent.CreateSigned(wrapKey, Kinds.GiftWrap, Nip44.Encrypt(seal.ToJson(), Nip44.ConversationKey(wrapKey, bob.PublicKeyHex)), [["p", bob.PublicKeyHex]]);

		var e = Assert.Throws<InvalidDataException>(() => GiftWrap.Unwrap(wrap, bob));
		Assert.Contains("impersonation", e.Message);
	}

	[Fact]
	public void RejectsTamperedWraps()
	{
		var alice = NostrKeys.Generate();
		var bob = NostrKeys.Generate();
		var wrap = GiftWrap.Wrap(alice, PrivateMessages.CreateChatMessage(alice.PublicKeyHex, [bob.PublicKeyHex], "hi"), bob.PublicKeyHex);
		var tampered = new NostrEvent { Id = wrap.Id, PubKey = wrap.PubKey, CreatedAt = wrap.CreatedAt + 1, Kind = wrap.Kind, Tags = wrap.Tags, Content = wrap.Content, Sig = wrap.Sig };
		Assert.Throws<InvalidDataException>(() => GiftWrap.Unwrap(tampered, bob));
	}

	[Fact]
	public void IdsMatchJavaScriptSerialization()
	{
		// Pinned against nostr-tools getEventHash(), which hashes JSON.stringify output.
		var e = NostrEvent.CreateUnsigned(
			"611df01bfcf85c26ae65453b772d8f1dfd25c264621c0277e1fc1518686faef9", 14,
			"é \"q\" \\ \n\t\u0007 🦄 </script>", [["p", "x"]], 1700000000);
		Assert.Equal("38d8b85a3ed01544bbb82138d359f992f154f6bef209ffff0df2d4c50c4c705e", e.Id);
		var json = e.ToJson();
		Assert.Contains("é \\\"q\\\" \\\\ \\n\\t\\u0007 🦄 </script>", json);
		Assert.Equal(e.Id, NostrEvent.FromJson(json).Id);
		Assert.True(NostrEvent.FromJson(json).HasValidId());
	}

	[Fact]
	public void InboxRelayListRoundTrips()
	{
		var keys = NostrKeys.Generate();
		var e = PrivateMessages.CreateInboxRelayList(keys, ["wss://Relay.Example.com/", "wss://relay.example.com", "https://not-a-relay"]);
		Assert.True(e.IsValid());
		Assert.Equal(["wss://relay.example.com"], PrivateMessages.ParseInboxRelayList(e));
	}

	[Fact]
	public void FiltersSerializeAndMatch()
	{
		var filter = new Filter { Kinds = [1059], Tags = new Dictionary<string, IReadOnlyList<string>> { ["p"] = ["ab"] }, Since = 10, Limit = 5 };
		Assert.Equal("{\"kinds\":[1059],\"#p\":[\"ab\"],\"since\":10,\"limit\":5}", filter.ToJson());
		Assert.True(filter.Matches(NostrEvent.CreateUnsigned("00", 1059, "", [["p", "ab"]], 11)));
		Assert.False(filter.Matches(NostrEvent.CreateUnsigned("00", 1059, "", [["p", "cd"]], 11)));
		Assert.False(filter.Matches(NostrEvent.CreateUnsigned("00", 1059, "", [["p", "ab"]], 9)));
	}
}
