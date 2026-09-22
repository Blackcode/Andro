namespace Andro.Core.Crypto;

/// <summary>BIP-173 bech32, as used by NIP-19 for npub/nsec strings.</summary>
public static class Bech32
{
	const string Charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
	static readonly uint[] Generator = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3];

	public static string Encode(string hrp, ReadOnlySpan<byte> data)
	{
		var values = ConvertBits(data, 8, 5, pad: true);
		var checksum = CreateChecksum(hrp, values);
		var chars = new char[hrp.Length + 1 + values.Length + checksum.Length];
		hrp.CopyTo(chars);
		chars[hrp.Length] = '1';
		var i = hrp.Length + 1;
		foreach (var v in values) chars[i++] = Charset[v];
		foreach (var v in checksum) chars[i++] = Charset[v];
		return new string(chars);
	}

	public static (string Hrp, byte[] Data) Decode(string input)
	{
		ArgumentNullException.ThrowIfNull(input);
		if (input.Length is < 8 or > 5000)
			throw new FormatException("Invalid bech32 length.");
		if (input.ToLowerInvariant() != input && input.ToUpperInvariant() != input)
			throw new FormatException("Mixed-case bech32 string.");
		input = input.ToLowerInvariant();

		var separator = input.LastIndexOf('1');
		if (separator < 1 || separator + 7 > input.Length)
			throw new FormatException("Invalid bech32 separator position.");

		var hrp = input[..separator];
		var values = new byte[input.Length - separator - 1];
		for (var i = 0; i < values.Length; i++)
		{
			var index = Charset.IndexOf(input[separator + 1 + i]);
			if (index < 0)
				throw new FormatException("Invalid bech32 character.");
			values[i] = (byte)index;
		}

		if (Polymod([.. ExpandHrp(hrp), .. values]) != 1)
			throw new FormatException("Invalid bech32 checksum.");

		return (hrp, ConvertBits(values.AsSpan(0, values.Length - 6), 5, 8, pad: false));
	}

	static byte[] CreateChecksum(string hrp, byte[] values)
	{
		var polymod = Polymod([.. ExpandHrp(hrp), .. values, 0, 0, 0, 0, 0, 0]) ^ 1;
		var result = new byte[6];
		for (var i = 0; i < 6; i++)
			result[i] = (byte)((polymod >> (5 * (5 - i))) & 31);
		return result;
	}

	static byte[] ExpandHrp(string hrp)
	{
		var result = new byte[hrp.Length * 2 + 1];
		for (var i = 0; i < hrp.Length; i++)
		{
			result[i] = (byte)(hrp[i] >> 5);
			result[i + hrp.Length + 1] = (byte)(hrp[i] & 31);
		}
		return result;
	}

	static uint Polymod(ReadOnlySpan<byte> values)
	{
		uint chk = 1;
		foreach (var v in values)
		{
			var top = chk >> 25;
			chk = ((chk & 0x1ffffff) << 5) ^ v;
			for (var i = 0; i < 5; i++)
			{
				if (((top >> i) & 1) != 0)
					chk ^= Generator[i];
			}
		}
		return chk;
	}

	static byte[] ConvertBits(ReadOnlySpan<byte> data, int fromBits, int toBits, bool pad)
	{
		var acc = 0;
		var bits = 0;
		var maxv = (1 << toBits) - 1;
		var result = new List<byte>(data.Length * fromBits / toBits + 1);
		foreach (var value in data)
		{
			if (value >> fromBits != 0)
				throw new FormatException("Invalid data for bit conversion.");
			acc = (acc << fromBits) | value;
			bits += fromBits;
			while (bits >= toBits)
			{
				bits -= toBits;
				result.Add((byte)((acc >> bits) & maxv));
			}
		}
		if (pad)
		{
			if (bits > 0)
				result.Add((byte)((acc << (toBits - bits)) & maxv));
		}
		else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0)
		{
			throw new FormatException("Invalid padding in bech32 data.");
		}
		return [.. result];
	}
}
