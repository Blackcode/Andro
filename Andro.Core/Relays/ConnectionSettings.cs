namespace Andro.Core.Relays;

public sealed record ConnectionSettings
{
	/// <summary>
	/// Optional proxy all relay traffic goes through, e.g. <c>socks5://127.0.0.1:9050</c> for Tor
	/// (Orbot on Android) or an HTTP(S) proxy. With SOCKS5 the proxy resolves host names, so DNS
	/// lookups don't leak and .onion relays work.
	/// </summary>
	public string? ProxyUrl { get; init; }

	public TimeSpan ConnectTimeout { get; init; } = TimeSpan.FromSeconds(30);

	public TimeSpan MaxReconnectDelay { get; init; } = TimeSpan.FromSeconds(60);

	public static bool IsValidProxyUrl(string? url) =>
		Uri.TryCreate(url?.Trim(), UriKind.Absolute, out var uri) &&
		uri.Scheme is "socks5" or "socks5h" or "socks4" or "socks4a" or "http" or "https" &&
		!string.IsNullOrEmpty(uri.Host) && uri.Port > 0;
}
