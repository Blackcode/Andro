using Uncage.Core.Crypto;
using Uncage.Core.Nostr;

namespace Uncage.Core.Media;

public enum AttachmentKind
{
	File,
	Image,
	Video,
	Audio,
}

/// <summary>
/// An encrypted file shared in a chat: the NIP-17 kind 15 "file message" fields.
/// The file itself lives, encrypted, on one or more Blossom servers.
/// </summary>
public sealed record Attachment
{
	public required string Url { get; init; }
	public IReadOnlyList<string> Fallbacks { get; init; } = [];
	public required string MimeType { get; init; }
	public required string Key { get; init; }
	public required string Nonce { get; init; }
	/// <summary>SHA-256 of the encrypted file (what servers store and address it by).</summary>
	public required string EncryptedSha256 { get; init; }
	/// <summary>SHA-256 of the original file.</summary>
	public string? PlainSha256 { get; init; }
	public long Size { get; init; }
	/// <summary>"WIDTHxHEIGHT" for images and videos, when known.</summary>
	public string? Dimensions { get; init; }
	/// <summary>Length of audio or video, when known.</summary>
	public double? DurationSeconds { get; init; }

	public AttachmentKind Kind => KindOf(MimeType);

	public static AttachmentKind KindOf(string mimeType) => mimeType.Split('/')[0].ToLowerInvariant() switch
	{
		"image" => AttachmentKind.Image,
		"video" => AttachmentKind.Video,
		"audio" => AttachmentKind.Audio,
		_ => AttachmentKind.File,
	};

	/// <summary>All locations to try, primary first.</summary>
	public IEnumerable<string> Locations => Fallbacks.Prepend(Url).Distinct();

	public IEnumerable<IReadOnlyList<string>> ToTags()
	{
		yield return ["file-type", MimeType];
		yield return ["encryption-algorithm", MediaCrypto.Algorithm];
		yield return ["decryption-key", Key];
		yield return ["decryption-nonce", Nonce];
		yield return ["x", EncryptedSha256];
		if (PlainSha256 is not null)
			yield return ["ox", PlainSha256];
		if (Size > 0)
			yield return ["size", Size.ToString(System.Globalization.CultureInfo.InvariantCulture)];
		if (Dimensions is not null)
			yield return ["dim", Dimensions];
		if (DurationSeconds is { } duration)
			yield return ["duration", duration.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture)];
		foreach (var fallback in Fallbacks)
			yield return ["fallback", fallback];
	}

	/// <summary>Reads a kind 15 rumor; null if it is not an AES-GCM file message we can open.</summary>
	public static Attachment? FromRumor(NostrEvent rumor)
	{
		if (rumor.Kind != Kinds.FileMessage || !Uri.TryCreate(rumor.Content.Trim(), UriKind.Absolute, out var url) || url.Scheme is not ("https" or "http"))
			return null;
		var algorithm = rumor.FirstTagValue("encryption-algorithm");
		var key = rumor.FirstTagValue("decryption-key");
		var nonce = rumor.FirstTagValue("decryption-nonce");
		var x = rumor.FirstTagValue("x")?.ToLowerInvariant();
		if (!string.Equals(algorithm, MediaCrypto.Algorithm, StringComparison.OrdinalIgnoreCase) || !IsHex(key) || !IsHex(nonce) || !Hex.IsLowerHex(x, 32))
			return null;

		return new Attachment
		{
			Url = url.ToString(),
			Fallbacks = [.. rumor.TagValues("fallback").Where(f => Uri.TryCreate(f, UriKind.Absolute, out var u) && u.Scheme is "https" or "http")],
			MimeType = rumor.FirstTagValue("file-type") is { Length: > 0 } type ? type.ToLowerInvariant() : "application/octet-stream",
			Key = key!.ToLowerInvariant(),
			Nonce = nonce!.ToLowerInvariant(),
			EncryptedSha256 = x!,
			PlainSha256 = rumor.FirstTagValue("ox")?.ToLowerInvariant(),
			Size = long.TryParse(rumor.FirstTagValue("size"), out var size) ? size : 0,
			Dimensions = rumor.FirstTagValue("dim"),
			DurationSeconds = double.TryParse(rumor.FirstTagValue("duration"), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var d) ? d : null,
		};
	}

	static bool IsHex(string? value) =>
		value is { Length: > 0 } && value.Length % 2 == 0 && value.All(Uri.IsHexDigit);
}
