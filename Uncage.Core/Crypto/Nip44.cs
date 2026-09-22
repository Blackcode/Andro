using System.Buffers.Binary;
using System.Numerics;
using System.Security.Cryptography;
using System.Text;
using Org.BouncyCastle.Crypto.Engines;
using Org.BouncyCastle.Crypto.Parameters;

namespace Uncage.Core.Crypto;

/// <summary>
/// NIP-44 version 2 payload encryption (secp256k1 ECDH, HKDF-SHA256, ChaCha20, HMAC-SHA256).
/// Audited design; see https://github.com/nostr-protocol/nips/blob/master/44.md.
/// </summary>
public static class Nip44
{
	const byte Version = 2;
	const int MinPlaintextSize = 1;
	/// <summary>Largest plaintext every deployed NIP-44 implementation accepts.</summary>
	public const int MaxPlaintextSize = 65535;
	static readonly byte[] Salt = "nip44-v2"u8.ToArray();

	public static byte[] ConversationKey(NostrKeys mine, string theirPublicKeyHex) =>
		HKDF.Extract(HashAlgorithmName.SHA256, mine.SharedSecretX(theirPublicKeyHex), Salt);

	public static string Encrypt(string plaintext, byte[] conversationKey) =>
		Encrypt(plaintext, conversationKey, RandomNumberGenerator.GetBytes(32));

	public static string Encrypt(string plaintext, byte[] conversationKey, byte[] nonce)
	{
		var (chachaKey, chachaNonce, hmacKey) = MessageKeys(conversationKey, nonce);
		var ciphertext = ChaCha20(chachaKey, chachaNonce, Pad(plaintext));
		var mac = HmacAad(hmacKey, ciphertext, nonce);

		var payload = new byte[1 + nonce.Length + ciphertext.Length + mac.Length];
		payload[0] = Version;
		nonce.CopyTo(payload, 1);
		ciphertext.CopyTo(payload, 1 + nonce.Length);
		mac.CopyTo(payload, 1 + nonce.Length + ciphertext.Length);
		return Convert.ToBase64String(payload);
	}

	public static string Decrypt(string payload, byte[] conversationKey)
	{
		var (nonce, ciphertext, mac) = DecodePayload(payload);
		var (chachaKey, chachaNonce, hmacKey) = MessageKeys(conversationKey, nonce);
		if (!CryptographicOperations.FixedTimeEquals(HmacAad(hmacKey, ciphertext, nonce), mac))
			throw new CryptographicException("invalid MAC");
		return Unpad(ChaCha20(chachaKey, chachaNonce, ciphertext));
	}

	public static int CalcPaddedLength(int unpaddedLength)
	{
		if (unpaddedLength <= 32)
			return 32;
		var nextPower = 1 << (BitOperations.Log2((uint)(unpaddedLength - 1)) + 1);
		var chunk = nextPower <= 256 ? 32 : nextPower / 8;
		return chunk * ((unpaddedLength - 1) / chunk + 1);
	}

	internal static (byte[] ChachaKey, byte[] ChachaNonce, byte[] HmacKey) MessageKeys(byte[] conversationKey, byte[] nonce)
	{
		if (conversationKey.Length != 32)
			throw new ArgumentException("invalid conversation_key length", nameof(conversationKey));
		if (nonce.Length != 32)
			throw new ArgumentException("invalid nonce length", nameof(nonce));
		var keys = HKDF.Expand(HashAlgorithmName.SHA256, conversationKey, 76, nonce);
		return (keys[..32], keys[32..44], keys[44..76]);
	}

	static byte[] Pad(string plaintext)
	{
		var unpadded = Encoding.UTF8.GetBytes(plaintext);
		if (unpadded.Length is < MinPlaintextSize or > MaxPlaintextSize)
			throw new ArgumentException("invalid plaintext length", nameof(plaintext));
		var padded = new byte[2 + CalcPaddedLength(unpadded.Length)];
		BinaryPrimitives.WriteUInt16BigEndian(padded, (ushort)unpadded.Length);
		unpadded.CopyTo(padded, 2);
		return padded;
	}

	static string Unpad(byte[] padded)
	{
		var length = BinaryPrimitives.ReadUInt16BigEndian(padded);
		if (length == 0 || padded.Length != 2 + CalcPaddedLength(length) || 2 + length > padded.Length)
			throw new CryptographicException("invalid padding");
		return new UTF8Encoding(false, throwOnInvalidBytes: true).GetString(padded, 2, length);
	}

	static (byte[] Nonce, byte[] Ciphertext, byte[] Mac) DecodePayload(string payload)
	{
		if (payload.Length == 0 || payload[0] == '#')
			throw new NotSupportedException("unknown encryption version");
		if (payload.Length is < 132 or > 87472)
			throw new CryptographicException("invalid payload size");

		byte[] data;
		try
		{
			data = Convert.FromBase64String(payload);
		}
		catch (FormatException e)
		{
			throw new CryptographicException("invalid base64", e);
		}
		if (data.Length is < 99 or > 65603)
			throw new CryptographicException("invalid data size");
		if (data[0] != Version)
			throw new NotSupportedException($"unknown encryption version {data[0]}");

		return (data[1..33], data[33..^32], data[^32..]);
	}

	static byte[] HmacAad(byte[] key, byte[] message, byte[] aad)
	{
		if (aad.Length != 32)
			throw new ArgumentException("AAD associated data must be 32 bytes", nameof(aad));
		byte[] data = [.. aad, .. message];
		return HMACSHA256.HashData(key, data);
	}

	static byte[] ChaCha20(byte[] key, byte[] nonce, byte[] data)
	{
		var engine = new ChaCha7539Engine();
		engine.Init(true, new ParametersWithIV(new KeyParameter(key), nonce));
		var output = new byte[data.Length];
		engine.ProcessBytes(data, 0, data.Length, output, 0);
		return output;
	}
}
