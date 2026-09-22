using System.Security.Cryptography;
using Uncage.Core.Chat;
using Uncage.Core.Crypto;
using Uncage.Core.Media;
using Uncage.Core.Nostr;
using Uncage.Core.Tests.Support;

namespace Uncage.Core.Tests;

public class MediaTests
{
	static readonly TimeSpan Wait = TimeSpan.FromSeconds(15);

	static Messenger CreateUser(NostrKeys keys, string relay, params string[] mediaServers)
	{
		var store = ChatStore.Open(null, Messenger.NewStorageKey());
		store.Settings = new ChatSettings { Relays = [relay], MediaServers = mediaServers };
		return new Messenger(keys, store, publishTimeout: TimeSpan.FromSeconds(5));
	}

	static Task<ChatMessage> Next(Messenger m, Func<ChatMessage, bool>? filter = null)
	{
		var tcs = new TaskCompletionSource<ChatMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
		m.MessageAdded += msg => { if (filter?.Invoke(msg) ?? true) tcs.TrySetResult(msg); };
		return tcs.Task.WaitAsync(Wait);
	}

	[Fact]
	public void AesGcmRoundTripsAndDetectsTampering()
	{
		var data = RandomNumberGenerator.GetBytes(100_000);
		var file = MediaCrypto.Encrypt(data);
		Assert.NotEqual(data, file.Ciphertext[..data.Length]);
		Assert.Equal(data, MediaCrypto.Decrypt(file.Ciphertext, file.Key, file.Nonce));
		Assert.Equal(file.Ciphertext, MediaCrypto.Encrypt(data, file.Key, file.Nonce));

		file.Ciphertext[10] ^= 1;
		Assert.Throws<CryptographicException>(() => MediaCrypto.Decrypt(file.Ciphertext, file.Key, file.Nonce));
	}

	[Fact]
	public void FileMessageTagsRoundTrip()
	{
		var attachment = new Attachment
		{
			Url = "https://blossom.example/abc",
			Fallbacks = ["https://mirror.example/abc"],
			MimeType = "image/jpeg",
			Key = new string('a', 64),
			Nonce = new string('b', 24),
			EncryptedSha256 = new string('c', 64),
			PlainSha256 = new string('d', 64),
			Size = 1234,
			Dimensions = "800x600",
			DurationSeconds = 3.5,
		};
		var rumor = PrivateMessages.CreateFileMessage(new string('e', 64), [new string('f', 64)], attachment);
		Assert.Equal(Kinds.FileMessage, rumor.Kind);
		Assert.Equal(attachment.Url, rumor.Content);
		Assert.Equal(attachment, Attachment.FromRumor(rumor) with { Fallbacks = attachment.Fallbacks });
		Assert.Equal(attachment.Fallbacks, Attachment.FromRumor(rumor)!.Fallbacks);
		Assert.Equal(AttachmentKind.Image, attachment.Kind);
	}

	[Fact]
	public async Task SendsAPhotoEndToEndThroughBlossom()
	{
		await using var relay = new TestRelay();
		using var blossom1 = new FakeBlossom();
		using var blossom2 = new FakeBlossom();
		var offline = $"http://127.0.0.1:{TestRelay.FreePort()}"; // a blocked media server
		var aliceKeys = NostrKeys.Generate();
		var bobKeys = NostrKeys.Generate();
		await using var alice = CreateUser(aliceKeys, relay.Url, offline, blossom1.Url, blossom2.Url);
		await using var bob = CreateUser(bobKeys, relay.Url, blossom2.Url);
		await alice.StartAsync();
		await bob.StartAsync();

		var photo = RandomNumberGenerator.GetBytes(300_000);
		var bobGets = Next(bob);
		var sent = await alice.SendFileAsync(bobKeys.PublicKeyHex, photo, "image/jpeg", "1600x1200");
		Assert.Equal(MessageStatus.Sent, sent.Status);
		Assert.DoesNotContain("upload-", sent.Id);

		var received = await bobGets;
		Assert.Equal(AttachmentKind.Image, received.Attachment!.Kind);
		Assert.Equal("1600x1200", received.Attachment.Dimensions);
		Assert.Equal("📷 Photo", received.Text);
		Assert.Equal(photo, await bob.GetAttachmentAsync(received.Attachment));

		// Servers only ever saw ciphertext, uploaded under throwaway keys.
		var stored = Assert.Single(blossom1.Blobs).Value;
		Assert.NotEqual(photo, stored[..photo.Length]);
		Assert.DoesNotContain(aliceKeys.PublicKeyHex, blossom1.Uploaders);
		Assert.Equal(2, received.Attachment.Locations.Count());

		// Alice's own copy is shown from the device, and not duplicated by the relay echo.
		Assert.Equal(photo, await alice.GetAttachmentAsync(sent.Attachment!));
		Assert.Single(alice.Store.Messages(bobKeys.PublicKeyHex));
	}

	[Fact]
	public async Task RejectsTamperedDownloadsAndFallsBackToAnotherServer()
	{
		await using var relay = new TestRelay();
		using var bad = new FakeBlossom();
		using var good = new FakeBlossom();
		var bobKeys = NostrKeys.Generate();
		await using var alice = CreateUser(NostrKeys.Generate(), relay.Url, bad.Url, good.Url);
		await using var bob = CreateUser(bobKeys, relay.Url, bad.Url);
		await alice.StartAsync();
		await bob.StartAsync();

		var audio = RandomNumberGenerator.GetBytes(50_000);
		var bobGets = Next(bob);
		await alice.SendFileAsync(bobKeys.PublicKeyHex, audio, "audio/mp4", durationSeconds: 4.2);
		var received = await bobGets;
		bad.Tamper = RandomNumberGenerator.GetBytes(50_016);

		Assert.Equal(audio, await bob.GetAttachmentAsync(received.Attachment!));
		Assert.Equal(4.2, received.Attachment!.DurationSeconds);
	}

	[Fact]
	public async Task UploadsToMediaOnlyServersWithTheFileType()
	{
		await using var relay = new TestRelay();
		using var picky = new FakeBlossom { MediaTypesOnly = true };
		var bobKeys = NostrKeys.Generate();
		await using var alice = CreateUser(NostrKeys.Generate(), relay.Url, picky.Url);
		await using var bob = CreateUser(bobKeys, relay.Url, picky.Url);
		await alice.StartAsync();
		await bob.StartAsync();

		var photo = RandomNumberGenerator.GetBytes(10_000);
		var bobGets = Next(bob);
		var sent = await alice.SendFileAsync(bobKeys.PublicKeyHex, photo, "image/jpeg");
		Assert.Equal(MessageStatus.Sent, sent.Status);
		Assert.Equal(photo, await bob.GetAttachmentAsync((await bobGets).Attachment!));
	}

	[Fact]
	public async Task FailedUploadsCanBeRetried()
	{
		await using var relay = new TestRelay();
		var port = TestRelay.FreePort();
		var bobKeys = NostrKeys.Generate();
		await using var alice = CreateUser(NostrKeys.Generate(), relay.Url, $"http://127.0.0.1:{port}");
		await alice.StartAsync();

		var video = RandomNumberGenerator.GetBytes(20_000);
		var failed = await alice.SendFileAsync(bobKeys.PublicKeyHex, video, "video/mp4");
		Assert.Equal(MessageStatus.Failed, failed.Status);
		Assert.Contains($"127.0.0.1:", alice.LastError); // names the server that refused, and why

		using var server = new FakeBlossom();
		alice.SetMediaServers([server.Url]);
		var retried = await alice.RetryAsync(failed);
		Assert.Equal(MessageStatus.Sent, retried.Status);
		Assert.Single(server.Blobs);
		Assert.Single(alice.Store.Messages(bobKeys.PublicKeyHex));
	}

	[Fact]
	public async Task BlockedContactsCannotReachYou()
	{
		await using var relay = new TestRelay();
		using var blossom = new FakeBlossom();
		var aliceKeys = NostrKeys.Generate();
		var bobKeys = NostrKeys.Generate();
		await using var alice = CreateUser(aliceKeys, relay.Url, blossom.Url);
		await using var bob = CreateUser(bobKeys, relay.Url, blossom.Url);
		await alice.StartAsync();
		await bob.StartAsync();

		bob.Block(aliceKeys.PublicKeyHex);
		var anything = Next(bob);
		await alice.SendAsync(bobKeys.PublicKeyHex, "are you there?");
		await alice.SendFileAsync(bobKeys.PublicKeyHex, [1, 2, 3], "image/png");
		await Assert.ThrowsAsync<TimeoutException>(() => anything.WaitAsync(TimeSpan.FromSeconds(2)));

		Assert.Empty(bob.Store.Messages(aliceKeys.PublicKeyHex));
		Assert.Empty(bob.Store.Conversations());
		Assert.Single(bob.Store.BlockedContacts());
		await Assert.ThrowsAsync<InvalidOperationException>(() => bob.SendAsync(aliceKeys.PublicKeyHex, "hi"));

		bob.Unblock(aliceKeys.PublicKeyHex);
		var afterUnblock = Next(bob);
		await alice.SendAsync(bobKeys.PublicKeyHex, "and now?");
		Assert.Equal("and now?", (await afterUnblock).Text);
	}
}
