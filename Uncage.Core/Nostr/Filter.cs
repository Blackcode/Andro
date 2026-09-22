using System.Text;

namespace Uncage.Core.Nostr;

/// <summary>A NIP-01 subscription filter.</summary>
public sealed class Filter
{
	public IReadOnlyList<string>? Ids { get; init; }
	public IReadOnlyList<string>? Authors { get; init; }
	public IReadOnlyList<int>? Kinds { get; init; }
	/// <summary>Tag filters keyed by single-letter tag name, e.g. "p" → pubkeys.</summary>
	public IReadOnlyDictionary<string, IReadOnlyList<string>>? Tags { get; init; }
	public long? Since { get; init; }
	public long? Until { get; init; }
	public int? Limit { get; init; }

	public string ToJson()
	{
		var sb = new StringBuilder("{");
		var first = true;
		void Key(string name)
		{
			if (!first) sb.Append(',');
			first = false;
			NostrJson.WriteString(sb, name);
			sb.Append(':');
		}

		if (Ids is not null) { Key("ids"); NostrJson.WriteStringArray(sb, Ids); }
		if (Authors is not null) { Key("authors"); NostrJson.WriteStringArray(sb, Authors); }
		if (Kinds is not null) { Key("kinds"); sb.Append('[').AppendJoin(',', Kinds).Append(']'); }
		if (Tags is not null)
		{
			foreach (var (name, values) in Tags)
			{
				Key("#" + name);
				NostrJson.WriteStringArray(sb, values);
			}
		}
		if (Since is not null) { Key("since"); sb.Append(Since.Value); }
		if (Until is not null) { Key("until"); sb.Append(Until.Value); }
		if (Limit is not null) { Key("limit"); sb.Append(Limit.Value); }
		return sb.Append('}').ToString();
	}

	/// <summary>Whether <paramref name="e"/> satisfies this filter (used to sanity-check what relays send back).</summary>
	public bool Matches(NostrEvent e)
	{
		if (Ids is not null && !Ids.Contains(e.Id)) return false;
		if (Authors is not null && !Authors.Contains(e.PubKey)) return false;
		if (Kinds is not null && !Kinds.Contains(e.Kind)) return false;
		if (Since is not null && e.CreatedAt < Since) return false;
		if (Until is not null && e.CreatedAt > Until) return false;
		if (Tags is not null)
		{
			foreach (var (name, values) in Tags)
			{
				if (!e.TagValues(name).Any(values.Contains))
					return false;
			}
		}
		return true;
	}
}
