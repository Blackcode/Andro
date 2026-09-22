using System.Collections.Concurrent;
using System.Net;
using System.Text;
using System.Text.Json;
using Uncage.Core.Media;
using Uncage.Core.Nostr;

namespace Uncage.Core.Tests.Support;

/// <summary>A minimal in-process Blossom server (BUD-01/02) that checks upload authorization.</summary>
sealed class FakeBlossom : IDisposable
{
	readonly HttpListener _listener = new();

	public FakeBlossom()
	{
		Port = TestRelay.FreePort();
		_listener.Prefixes.Add($"http://*:{Port}/");
		_listener.Start();
		_ = Task.Run(Loop);
	}

	public int Port { get; }
	public string Url => $"http://127.0.0.1:{Port}";
	public ConcurrentDictionary<string, byte[]> Blobs { get; } = new();
	public ConcurrentBag<string> Uploaders { get; } = [];
	/// <summary>Serve these bytes instead of the real blob (a malicious or broken server).</summary>
	public byte[]? Tamper { get; set; }
	/// <summary>Like many public media hosts: refuse uploads not labelled as image/video/audio.</summary>
	public bool MediaTypesOnly { get; set; }

	async Task Loop()
	{
		while (_listener.IsListening)
		{
			HttpListenerContext ctx;
			try { ctx = await _listener.GetContextAsync(); } catch { return; }
			try { await Handle(ctx); } catch { ctx.Response.StatusCode = 500; }
			finally { ctx.Response.Close(); }
		}
	}

	async Task Handle(HttpListenerContext ctx)
	{
		var path = ctx.Request.Url!.AbsolutePath.Trim('/');
		if (ctx.Request.HttpMethod == "PUT" && path == "upload")
		{
			using var ms = new MemoryStream();
			await ctx.Request.InputStream.CopyToAsync(ms);
			var blob = ms.ToArray();
			var sha = MediaCrypto.Sha256Hex(blob);

			var header = ctx.Request.Headers["Authorization"] ?? "";
			var auth = header.StartsWith("Nostr ") ? NostrEvent.FromJson(Encoding.UTF8.GetString(Convert.FromBase64String(header[6..]))) : null;
			if (auth is null || !auth.IsValid() || auth.Kind != 24242 || auth.FirstTagValue("t") != "upload" || auth.FirstTagValue("x") != sha)
			{
				ctx.Response.StatusCode = 401;
				return;
			}
			var type = ctx.Request.ContentType ?? "";
			if (MediaTypesOnly && !(type.StartsWith("image/") || type.StartsWith("video/") || type.StartsWith("audio/")))
			{
				ctx.Response.StatusCode = 415;
				ctx.Response.Headers["X-Reason"] = "Unsupported Media Type";
				return;
			}
			Uploaders.Add(auth.PubKey);
			Blobs[sha] = blob;
			ctx.Response.StatusCode = 200;
			ctx.Response.ContentType = "application/json";
			var body = JsonSerializer.Serialize(new { url = $"{Url}/{sha}", sha256 = sha, size = blob.Length, type = "application/octet-stream" });
			await ctx.Response.OutputStream.WriteAsync(Encoding.UTF8.GetBytes(body));
			return;
		}
		if (ctx.Request.HttpMethod == "GET" && Blobs.TryGetValue(path.Split('.')[0], out var stored))
		{
			var bytes = Tamper ?? stored;
			ctx.Response.StatusCode = 200;
			ctx.Response.ContentLength64 = bytes.Length;
			await ctx.Response.OutputStream.WriteAsync(bytes);
			return;
		}
		ctx.Response.StatusCode = 404;
	}

	public void Dispose()
	{
		_listener.Stop();
		_listener.Close();
	}
}
