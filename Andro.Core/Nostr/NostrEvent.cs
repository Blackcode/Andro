using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Andro.Core.Crypto;

namespace Andro.Core.Nostr;

/// <summary>A NIP-01 event. <see cref="Sig"/> is null for unsigned "rumors" (NIP-59).</summary>
public sealed class NostrEvent
{
	public required string Id { get; init; }
	public required string PubKey { get; init; }
	public required long CreatedAt { get; init; }
	public required int Kind { get; init; }
	public required IReadOnlyList<IReadOnlyList<string>> Tags { get; init; }
	public required string Content { get; init; }
	public string? Sig { get; init; }

	public static NostrEvent CreateSigned(NostrKeys keys, int kind, string content, IReadOnlyList<IReadOnlyList<string>>? tags = null, long? createdAt = null)
	{
		var unsigned = CreateUnsigned(keys.PublicKeyHex, kind, content, tags, createdAt);
		return new NostrEvent
		{
			Id = unsigned.Id,
			PubKey = unsigned.PubKey,
			CreatedAt = unsigned.CreatedAt,
			Kind = kind,
			Tags = unsigned.Tags,
			Content = content,
			Sig = Hex.Encode(keys.Sign(Hex.Decode(unsigned.Id))),
		};
	}

	public static NostrEvent CreateUnsigned(string pubKey, int kind, string content, IReadOnlyList<IReadOnlyList<string>>? tags = null, long? createdAt = null)
	{
		tags ??= [];
		var time = createdAt ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();
		return new NostrEvent
		{
			Id = ComputeId(pubKey, time, kind, tags, content),
			PubKey = pubKey,
			CreatedAt = time,
			Kind = kind,
			Tags = tags,
			Content = content,
		};
	}

	public static string ComputeId(string pubKey, long createdAt, int kind, IReadOnlyList<IReadOnlyList<string>> tags, string content)
	{
		var sb = new StringBuilder("[0,");
		NostrJson.WriteString(sb, pubKey);
		sb.Append(',').Append(createdAt).Append(',').Append(kind).Append(',');
		NostrJson.WriteTags(sb, tags);
		sb.Append(',');
		NostrJson.WriteString(sb, content);
		sb.Append(']');
		return Hex.Encode(SHA256.HashData(Encoding.UTF8.GetBytes(sb.ToString())));
	}

	/// <summary>The id matches the content (rumors included).</summary>
	public bool HasValidId() =>
		Hex.IsLowerHex(Id, 32) && Hex.IsLowerHex(PubKey, 32) && Id == ComputeId(PubKey, CreatedAt, Kind, Tags, Content);

	/// <summary>The id matches the content and the signature is valid for <see cref="PubKey"/>.</summary>
	public bool IsValid() =>
		HasValidId() && Sig is not null && Hex.IsLowerHex(Sig, 64) &&
		NostrKeys.VerifySignature(PubKey, Hex.Decode(Id), Hex.Decode(Sig));

	public IEnumerable<string> TagValues(string name) =>
		Tags.Where(t => t.Count >= 2 && t[0] == name).Select(t => t[1]);

	public string? FirstTagValue(string name) => TagValues(name).FirstOrDefault();

	public string ToJson()
	{
		var sb = new StringBuilder();
		WriteJson(sb);
		return sb.ToString();
	}

	internal void WriteJson(StringBuilder sb)
	{
		sb.Append("{\"id\":");
		NostrJson.WriteString(sb, Id);
		sb.Append(",\"pubkey\":");
		NostrJson.WriteString(sb, PubKey);
		sb.Append(",\"created_at\":").Append(CreatedAt);
		sb.Append(",\"kind\":").Append(Kind);
		sb.Append(",\"tags\":");
		NostrJson.WriteTags(sb, Tags);
		sb.Append(",\"content\":");
		NostrJson.WriteString(sb, Content);
		if (Sig is not null)
		{
			sb.Append(",\"sig\":");
			NostrJson.WriteString(sb, Sig);
		}
		sb.Append('}');
	}

	public static NostrEvent FromJson(string json)
	{
		using var doc = JsonDocument.Parse(json);
		return FromJson(doc.RootElement);
	}

	/// <exception cref="FormatException">The JSON is not a well-formed event.</exception>
	public static NostrEvent FromJson(JsonElement e)
	{
		try
		{
			var tags = new List<IReadOnlyList<string>>();
			foreach (var tag in e.GetProperty("tags").EnumerateArray())
				tags.Add([.. tag.EnumerateArray().Select(v => v.GetString() ?? "")]);

			string? sig = null;
			if (e.TryGetProperty("sig", out var sigElement) && sigElement.ValueKind == JsonValueKind.String)
				sig = sigElement.GetString();

			return new NostrEvent
			{
				Id = e.GetProperty("id").GetString() ?? "",
				PubKey = e.GetProperty("pubkey").GetString() ?? "",
				CreatedAt = e.GetProperty("created_at").GetInt64(),
				Kind = e.GetProperty("kind").GetInt32(),
				Tags = tags,
				Content = e.GetProperty("content").GetString() ?? "",
				Sig = string.IsNullOrEmpty(sig) ? null : sig,
			};
		}
		catch (Exception ex) when (ex is KeyNotFoundException or InvalidOperationException or FormatException)
		{
			throw new FormatException("Malformed nostr event.", ex);
		}
	}
}
