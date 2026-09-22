using System.Globalization;
using System.Text;

namespace Andro.Core.Nostr;

/// <summary>
/// Compact JSON writer matching JavaScript's JSON.stringify, which NIP-01 event ids are hashed over.
/// System.Text.Json escapes non-ASCII characters, which would produce different ids than other clients.
/// </summary>
static class NostrJson
{
	public static void WriteString(StringBuilder sb, string value)
	{
		sb.Append('"');
		for (var i = 0; i < value.Length; i++)
		{
			var c = value[i];
			switch (c)
			{
				case '"': sb.Append("\\\""); break;
				case '\\': sb.Append("\\\\"); break;
				case '\b': sb.Append("\\b"); break;
				case '\f': sb.Append("\\f"); break;
				case '\n': sb.Append("\\n"); break;
				case '\r': sb.Append("\\r"); break;
				case '\t': sb.Append("\\t"); break;
				default:
					if (c < 0x20)
					{
						sb.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
					}
					else if (char.IsSurrogate(c) && !(char.IsHighSurrogate(c) && i + 1 < value.Length && char.IsLowSurrogate(value[i + 1])))
					{
						if (char.IsLowSurrogate(c) && i > 0 && char.IsHighSurrogate(value[i - 1]))
							sb.Append(c);
						else
							sb.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
					}
					else
					{
						sb.Append(c);
					}
					break;
			}
		}
		sb.Append('"');
	}

	public static void WriteTags(StringBuilder sb, IReadOnlyList<IReadOnlyList<string>> tags)
	{
		sb.Append('[');
		for (var i = 0; i < tags.Count; i++)
		{
			if (i > 0) sb.Append(',');
			WriteStringArray(sb, tags[i]);
		}
		sb.Append(']');
	}

	public static void WriteStringArray(StringBuilder sb, IEnumerable<string> values)
	{
		sb.Append('[');
		var first = true;
		foreach (var value in values)
		{
			if (!first) sb.Append(',');
			WriteString(sb, value);
			first = false;
		}
		sb.Append(']');
	}
}
