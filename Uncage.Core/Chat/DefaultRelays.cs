namespace Uncage.Core.Chat;

/// <summary>
/// Well-known public relays run by different operators in different countries. Users can replace
/// them (including with .onion relays over Tor); a censor has to block all of them to stop delivery.
/// </summary>
public static class DefaultRelays
{
	public static readonly IReadOnlyList<string> All =
	[
		"wss://relay.damus.io",
		"wss://nos.lol",
		"wss://relay.primal.net",
		"wss://nostr.mom",
		"wss://offchain.pub",
	];

	/// <summary>
	/// Public Blossom servers for encrypted attachments. Uploads go to all of them, so any one being
	/// blocked or offline doesn't lose the file. Users can replace them in Settings.
	/// </summary>
	public static readonly IReadOnlyList<string> MediaServers =
	[
		// Servers that inspect file contents (e.g. blossom.band) can't store encrypted files, so they're not listed.
		"https://blossom.primal.net",
		"https://24242.io",
	];

	/// <summary>How many relays to advertise as our inbox (NIP-17 recommends a small list).</summary>
	public const int InboxCount = 3;

	public const string TorProxy = "socks5://127.0.0.1:9050";
}
