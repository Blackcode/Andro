namespace Uncage.Core.Crypto;

public static class Hex
{
	public static string Encode(ReadOnlySpan<byte> bytes) => Convert.ToHexStringLower(bytes);

	public static byte[] Decode(string hex) => Convert.FromHexString(hex);

	/// <summary>True when <paramref name="hex"/> is exactly <paramref name="byteLength"/> bytes of lowercase hex.</summary>
	public static bool IsLowerHex(string? hex, int byteLength)
	{
		if (hex is null || hex.Length != byteLength * 2)
			return false;
		foreach (var c in hex)
		{
			if (c is not ((>= '0' and <= '9') or (>= 'a' and <= 'f')))
				return false;
		}
		return true;
	}
}
