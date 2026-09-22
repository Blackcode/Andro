namespace Uncage.Core.Nostr;

public static class RelayUrl
{
	public static bool IsValid(string? url) =>
		Uri.TryCreate(url?.Trim(), UriKind.Absolute, out var uri) &&
		(uri.Scheme == "wss" || uri.Scheme == "ws") &&
		!string.IsNullOrEmpty(uri.Host);

	/// <summary>Lowercase scheme and host, no trailing slash, so the same relay is not listed twice.</summary>
	public static string Normalize(string url)
	{
		var uri = new Uri(url.Trim());
		var port = uri.IsDefaultPort ? "" : ":" + uri.Port;
		var path = uri.AbsolutePath.TrimEnd('/');
		return $"{uri.Scheme}://{uri.Host.ToLowerInvariant()}{port}{path}{uri.Query}";
	}

	public static bool IsOnion(string url) =>
		Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.Host.EndsWith(".onion", StringComparison.OrdinalIgnoreCase);
}
