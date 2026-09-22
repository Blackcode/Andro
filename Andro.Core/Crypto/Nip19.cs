namespace Andro.Core.Crypto;

/// <summary>NIP-19 human-readable key encodings (npub1…, nsec1…).</summary>
public static class Nip19
{
	public static string EncodeNpub(string publicKeyHex) => Bech32.Encode("npub", Hex.Decode(publicKeyHex));

	public static string EncodeNsec(ReadOnlySpan<byte> secret) => Bech32.Encode("nsec", secret);

	public static string DecodeNpub(string npub) => Hex.Encode(DecodeKey(npub, "npub"));

	public static byte[] DecodeNsec(string nsec) => DecodeKey(nsec, "nsec");

	/// <summary>Accepts npub1… or 64 hex characters and returns the hex public key, or null if invalid.</summary>
	public static string? TryParsePublicKey(string? input)
	{
		if (string.IsNullOrWhiteSpace(input))
			return null;
		input = input.Trim();
		if (input.StartsWith("nostr:", StringComparison.OrdinalIgnoreCase))
			input = input[6..];
		try
		{
			var hex = input.StartsWith("npub1", StringComparison.OrdinalIgnoreCase) ? DecodeNpub(input) : input.ToLowerInvariant();
			return NostrKeys.IsValidPublicKey(hex) ? hex : null;
		}
		catch (FormatException)
		{
			return null;
		}
	}

	static byte[] DecodeKey(string value, string expectedHrp)
	{
		var (hrp, data) = Bech32.Decode(value.Trim());
		if (hrp != expectedHrp)
			throw new FormatException($"Expected a {expectedHrp} key, got {hrp}.");
		if (data.Length != 32)
			throw new FormatException("Key must be 32 bytes.");
		return data;
	}
}
