namespace Uncage.Core.Nostr;

/// <summary>NIP-17 private direct messages built on NIP-59 gift wraps.</summary>
public static class PrivateMessages
{
	/// <summary>Builds the unsigned kind-14 chat message.</summary>
	public static NostrEvent CreateChatMessage(string senderPubKey, IReadOnlyCollection<string> recipientPubKeys, string text, string? replyToId = null, long? createdAt = null)
	{
		var tags = new List<IReadOnlyList<string>>();
		foreach (var recipient in recipientPubKeys)
			tags.Add(["p", recipient]);
		if (replyToId is not null)
			tags.Add(["e", replyToId, "", "reply"]);
		return NostrEvent.CreateUnsigned(senderPubKey, Kinds.ChatMessage, text, tags, createdAt);
	}

	/// <summary>Builds the unsigned kind-15 file message: the content is the file's URL, the tags say how to decrypt it.</summary>
	public static NostrEvent CreateFileMessage(string senderPubKey, IReadOnlyCollection<string> recipientPubKeys, Media.Attachment attachment, long? createdAt = null)
	{
		var tags = new List<IReadOnlyList<string>>();
		foreach (var recipient in recipientPubKeys)
			tags.Add(["p", recipient]);
		tags.AddRange(attachment.ToTags());
		return NostrEvent.CreateUnsigned(senderPubKey, Kinds.FileMessage, attachment.Url, tags, createdAt);
	}

	/// <summary>Kind 10050: the relays where this user wants to receive private messages.</summary>
	public static NostrEvent CreateInboxRelayList(Crypto.NostrKeys keys, IEnumerable<string> relayUrls) =>
		NostrEvent.CreateSigned(keys, Kinds.DmRelayList, "", [.. relayUrls.Select(url => (IReadOnlyList<string>)["relay", url])]);

	public static IReadOnlyList<string> ParseInboxRelayList(NostrEvent e) =>
		e.Kind == Kinds.DmRelayList
			? [.. e.TagValues("relay").Where(RelayUrl.IsValid).Select(RelayUrl.Normalize).Distinct()]
			: [];

	/// <summary>The other participants of a chat message, from the point of view of <paramref name="me"/>.</summary>
	public static IReadOnlyList<string> Participants(NostrEvent rumor, string me) =>
		[.. rumor.TagValues("p").Append(rumor.PubKey).Where(p => p != me).Distinct().Order(StringComparer.Ordinal)];
}
