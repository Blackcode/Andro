using System.Security.Cryptography;
using System.Text.Json;
using Uncage.Core.Crypto;

namespace Uncage.Core.Nostr;

/// <summary>
/// NIP-59 gift wrapping. The real message (an unsigned "rumor") is encrypted inside a seal signed by
/// the sender, which is encrypted again inside a wrapper signed by a throwaway key. Relays only see
/// the recipient's key and a random timestamp: not who sent it, when, or what kind of message it is.
/// </summary>
public static class GiftWrap
{
	/// <summary>How far back seal and wrapper timestamps are randomized.</summary>
	public static readonly TimeSpan TimestampJitter = TimeSpan.FromDays(2);

	public static NostrEvent Wrap(NostrKeys sender, NostrEvent rumor, string recipientPubKey, long? now = null)
	{
		if (rumor.Sig is not null)
			throw new ArgumentException("Rumors must be unsigned.", nameof(rumor));
		if (rumor.PubKey != sender.PublicKeyHex)
			throw new ArgumentException("Rumor author must be the sender.", nameof(rumor));

		var time = now ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();

		var seal = NostrEvent.CreateSigned(
			sender,
			Kinds.Seal,
			Nip44.Encrypt(rumor.ToJson(), Nip44.ConversationKey(sender, recipientPubKey)),
			tags: [],
			createdAt: RandomPast(time));

		var ephemeral = NostrKeys.Generate();
		return NostrEvent.CreateSigned(
			ephemeral,
			Kinds.GiftWrap,
			Nip44.Encrypt(seal.ToJson(), Nip44.ConversationKey(ephemeral, recipientPubKey)),
			tags: [["p", recipientPubKey]],
			createdAt: RandomPast(time));
	}

	/// <summary>
	/// Opens a gift wrap addressed to <paramref name="recipient"/> and returns the authenticated rumor.
	/// The rumor's author is guaranteed to be the key that signed the seal.
	/// </summary>
	/// <exception cref="InvalidDataException">The wrap is malformed, forged, or not for this recipient.</exception>
	public static NostrEvent Unwrap(NostrEvent wrap, NostrKeys recipient)
	{
		if (wrap.Kind != Kinds.GiftWrap)
			throw new InvalidDataException("Not a gift wrap.");
		if (!wrap.IsValid())
			throw new InvalidDataException("Gift wrap signature is invalid.");

		var seal = DecryptEvent(wrap.Content, recipient, wrap.PubKey, "gift wrap");
		if (seal.Kind != Kinds.Seal || seal.Tags.Count != 0)
			throw new InvalidDataException("Gift wrap does not contain a seal.");
		if (!seal.IsValid())
			throw new InvalidDataException("Seal signature is invalid.");

		var rumor = DecryptEvent(seal.Content, recipient, seal.PubKey, "seal");
		if (rumor.PubKey != seal.PubKey)
			throw new InvalidDataException("Rumor author does not match the seal signer (impersonation attempt).");
		if (!rumor.HasValidId())
			throw new InvalidDataException("Rumor id does not match its content.");

		return new NostrEvent
		{
			Id = rumor.Id,
			PubKey = rumor.PubKey,
			CreatedAt = rumor.CreatedAt,
			Kind = rumor.Kind,
			Tags = rumor.Tags,
			Content = rumor.Content,
		};
	}

	static NostrEvent DecryptEvent(string payload, NostrKeys recipient, string counterpartyPubKey, string layer)
	{
		try
		{
			var json = Nip44.Decrypt(payload, Nip44.ConversationKey(recipient, counterpartyPubKey));
			return NostrEvent.FromJson(json);
		}
		catch (Exception e) when (e is CryptographicException or NotSupportedException or ArgumentException or FormatException or JsonException)
		{
			throw new InvalidDataException($"Could not decrypt {layer}.", e);
		}
	}

	static long RandomPast(long now) =>
		now - RandomNumberGenerator.GetInt32((int)TimestampJitter.TotalSeconds);
}
