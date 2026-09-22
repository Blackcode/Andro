using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Uncage.Core.Tests.Support;

/// <summary>A tiny SOCKS5 (RFC 1928, no auth, CONNECT only) proxy that records requested destinations.</summary>
sealed class Socks5Proxy : IDisposable
{
	readonly TcpListener _listener = new(IPAddress.Loopback, 0);
	readonly CancellationTokenSource _stop = new();

	public Socks5Proxy()
	{
		_listener.Start();
		Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
		_ = Task.Run(AcceptLoop);
	}

	public int Port { get; }
	public string Url => $"socks5://127.0.0.1:{Port}";
	/// <summary>"host:port" of every CONNECT, with the host exactly as the client sent it.</summary>
	public ConcurrentQueue<string> Connects { get; } = new();

	async Task AcceptLoop()
	{
		while (!_stop.IsCancellationRequested)
		{
			TcpClient client;
			try { client = await _listener.AcceptTcpClientAsync(_stop.Token); }
			catch { return; }
			_ = Task.Run(() => Handle(client));
		}
	}

	async Task Handle(TcpClient client)
	{
		using var _ = client;
		var s = client.GetStream();
		try
		{
			var head = await Read(s, 2);
			await Read(s, head[1]);
			await s.WriteAsync(new byte[] { 5, 0 }); // no authentication

			var req = await Read(s, 4);
			string host;
			switch (req[3])
			{
				case 1: host = new IPAddress(await Read(s, 4)).ToString(); break;
				case 3: host = Encoding.ASCII.GetString(await Read(s, (await Read(s, 1))[0])); break;
				case 4: host = new IPAddress(await Read(s, 16)).ToString(); break;
				default: return;
			}
			var portBytes = await Read(s, 2);
			var port = portBytes[0] << 8 | portBytes[1];
			Connects.Enqueue($"{host}:{port}");

			// This test proxy knows one name: "relay.test" means the local relay (a real one would be Tor).
			using var upstream = new TcpClient();
			await upstream.ConnectAsync(host == "relay.test" ? "127.0.0.1" : host, port);
			await s.WriteAsync(new byte[] { 5, 0, 0, 1, 0, 0, 0, 0, 0, 0 });

			var u = upstream.GetStream();
			await Task.WhenAny(s.CopyToAsync(u, _stop.Token), u.CopyToAsync(s, _stop.Token));
		}
		catch
		{
			// Connection closed.
		}
	}

	static async Task<byte[]> Read(Stream s, int count)
	{
		var buffer = new byte[count];
		await s.ReadExactlyAsync(buffer);
		return buffer;
	}

	public void Dispose()
	{
		_stop.Cancel();
		_listener.Stop();
	}
}
