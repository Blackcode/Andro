using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Uncage.Core.Crypto;
using Uncage.Core.Nostr;
using Uncage.Core.Relays;

namespace Uncage.Core.Media;

/// <summary>
/// Minimal Blossom client (BUD-01/02): stores and fetches blobs addressed by their SHA-256.
/// Uploads are authorized with a throwaway key per upload, so servers can't link files to the user.
/// All traffic uses the same proxy as the relays (e.g. Tor).
/// </summary>
public sealed class BlossomClient : IDisposable
{
	const int KindBlossomAuth = 24242;
	readonly HttpClient _http;

	public BlossomClient(ConnectionSettings settings)
	{
		var handler = new SocketsHttpHandler
		{
			ConnectTimeout = settings.ConnectTimeout,
			AutomaticDecompression = DecompressionMethods.All,
		};
		if (!string.IsNullOrWhiteSpace(settings.ProxyUrl))
		{
			var proxy = new Uri(settings.ProxyUrl.Trim());
			var scheme = proxy.Scheme == "socks5h" ? "socks5" : proxy.Scheme;
			handler.Proxy = new WebProxy(new UriBuilder(proxy) { Scheme = scheme }.Uri);
			handler.UseProxy = true;
		}
		_http = new HttpClient(handler) { Timeout = TimeSpan.FromMinutes(5) };
		_http.DefaultRequestHeaders.UserAgent.ParseAdd("Uncage");
	}

	/// <summary>Uploads to every server in parallel and returns the URLs that accepted it.</summary>
	public async Task<IReadOnlyList<string>> UploadAsync(byte[] blob, IEnumerable<string> servers, CancellationToken cancellationToken = default)
	{
		var sha256 = MediaCrypto.Sha256Hex(blob);
		var uploads = servers.Select(s => s.TrimEnd('/')).Distinct().Select(async server =>
		{
			try
			{
				return await UploadOneAsync(server, blob, sha256, cancellationToken);
			}
			catch (Exception e) when (e is HttpRequestException or TaskCanceledException or JsonException or InvalidDataException && !cancellationToken.IsCancellationRequested)
			{
				return null;
			}
		});
		return [.. (await Task.WhenAll(uploads)).OfType<string>()];
	}

	async Task<string?> UploadOneAsync(string server, byte[] blob, string sha256, CancellationToken cancellationToken)
	{
		var auth = NostrEvent.CreateSigned(NostrKeys.Generate(), KindBlossomAuth, "Upload encrypted attachment",
		[
			["t", "upload"],
			["x", sha256],
			["expiration", DateTimeOffset.UtcNow.AddMinutes(10).ToUnixTimeSeconds().ToString(System.Globalization.CultureInfo.InvariantCulture)],
		]);

		using var request = new HttpRequestMessage(HttpMethod.Put, server + "/upload");
		request.Headers.Authorization = new AuthenticationHeaderValue("Nostr", Convert.ToBase64String(Encoding.UTF8.GetBytes(auth.ToJson())));
		request.Headers.Add("X-SHA-256", sha256);
		request.Content = new ByteArrayContent(blob);
		request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");

		using var response = await _http.SendAsync(request, cancellationToken);
		if (!response.IsSuccessStatusCode)
			return null;

		using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
		var root = json.RootElement;
		// The descriptor must describe exactly what we sent.
		if (root.TryGetProperty("sha256", out var reported) && !string.Equals(reported.GetString(), sha256, StringComparison.OrdinalIgnoreCase))
			throw new InvalidDataException("Server stored a different blob.");
		return root.TryGetProperty("url", out var url) && Uri.TryCreate(url.GetString(), UriKind.Absolute, out var uri)
			? uri.ToString()
			: $"{server}/{sha256}";
	}

	/// <summary>
	/// Downloads the blob from the first location that returns bytes with the expected SHA-256
	/// (so a malicious or broken server can't substitute content).
	/// </summary>
	public async Task<byte[]?> DownloadAsync(IEnumerable<string> urls, string expectedSha256, long maxBytes, CancellationToken cancellationToken = default)
	{
		foreach (var url in urls)
		{
			try
			{
				using var response = await _http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
				if (!response.IsSuccessStatusCode || response.Content.Headers.ContentLength > maxBytes)
					continue;
				await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
				using var buffer = new MemoryStream();
				var chunk = new byte[81920];
				int read;
				while ((read = await stream.ReadAsync(chunk, cancellationToken)) > 0)
				{
					buffer.Write(chunk, 0, read);
					if (buffer.Length > maxBytes)
						break;
				}
				if (buffer.Length > maxBytes)
					continue;
				var bytes = buffer.ToArray();
				if (MediaCrypto.Sha256Hex(bytes) == expectedSha256)
					return bytes;
			}
			catch (Exception e) when (e is HttpRequestException or TaskCanceledException or IOException && !cancellationToken.IsCancellationRequested)
			{
				// Try the next location.
			}
		}
		return null;
	}

	public void Dispose() => _http.Dispose();
}
