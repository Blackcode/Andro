using Andro.Core.Chat;
using Andro.Core.Crypto;

namespace Andro.Core.Tests;

/// <summary>
/// Sends a real message between two fresh identities over the default public relays.
/// Opt-in because it needs internet access: set ANDRO_LIVE_TESTS=1.
/// </summary>
public class LiveRelayTests
{
	[Fact]
	public async Task ExchangesAMessageOverPublicRelays()
	{
		if (Environment.GetEnvironmentVariable("ANDRO_LIVE_TESTS") != "1")
			return;

		var bobKeys = NostrKeys.Generate();
		await using var alice = new Messenger(NostrKeys.Generate(), ChatStore.Open(null, Messenger.NewStorageKey()));
		await using var bob = new Messenger(bobKeys, ChatStore.Open(null, Messenger.NewStorageKey()));
		var received = new TaskCompletionSource<ChatMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
		bob.MessageAdded += m => received.TrySetResult(m);

		await alice.StartAsync();
		await bob.StartAsync();
		var text = $"live test {Guid.NewGuid():N}";
		var sent = await alice.SendAsync(bobKeys.PublicKeyHex, text);

		Assert.Equal(MessageStatus.Sent, sent.Status);
		Assert.Equal(text, (await received.Task.WaitAsync(TimeSpan.FromSeconds(60))).Text);
	}
}
