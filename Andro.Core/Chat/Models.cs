using Andro.Core.Crypto;

namespace Andro.Core.Chat;

public enum MessageStatus
{
	/// <summary>Being encrypted and sent to relays.</summary>
	Sending,
	/// <summary>At least one of the recipient's relays accepted it.</summary>
	Sent,
	/// <summary>No relay accepted it; can be retried.</summary>
	Failed,
	/// <summary>Written by the other person.</summary>
	Received,
}

public sealed record ChatMessage
{
	/// <summary>Id of the NIP-17 rumor; the same on every device and relay.</summary>
	public required string Id { get; init; }
	/// <summary>The other person in this conversation (hex public key).</summary>
	public required string PeerPubKey { get; init; }
	public required string AuthorPubKey { get; init; }
	public required string Text { get; init; }
	public required long CreatedAt { get; init; }
	public required MessageStatus Status { get; init; }
	public string? ReplyToId { get; init; }
	/// <summary>How many relays accepted the message, for the delivery indicator.</summary>
	public int RelaysAccepted { get; init; }
	public bool IsOutgoing => Status != MessageStatus.Received;
	public DateTimeOffset Time => DateTimeOffset.FromUnixTimeSeconds(CreatedAt);
}

public sealed record Contact
{
	public required string PubKey { get; init; }
	/// <summary>Local nickname; never published.</summary>
	public string? Name { get; init; }
	/// <summary>Where this contact wants to receive messages (their kind 10050 list).</summary>
	public IReadOnlyList<string> InboxRelays { get; init; } = [];
	public long InboxRelaysFetchedAt { get; init; }
	/// <summary>Unknown sender who wrote first; shown as a message request.</summary>
	public bool IsRequest { get; init; }
	public long LastReadAt { get; init; }
	public string Npub => Nip19.EncodeNpub(PubKey);
	public string DisplayName => string.IsNullOrWhiteSpace(Name) ? ShortNpub(PubKey) : Name!;

	public static string ShortNpub(string pubKey)
	{
		var npub = Nip19.EncodeNpub(pubKey);
		return $"{npub[..12]}…{npub[^6..]}";
	}
}

public sealed record Conversation(Contact Contact, ChatMessage? LastMessage, int UnreadCount);

public sealed record ChatSettings
{
	public IReadOnlyList<string> Relays { get; init; } = DefaultRelays.All;
	/// <summary>SOCKS5/HTTP proxy for all traffic, e.g. socks5://127.0.0.1:9050 for Tor. Null for direct.</summary>
	public string? ProxyUrl { get; init; }
}
