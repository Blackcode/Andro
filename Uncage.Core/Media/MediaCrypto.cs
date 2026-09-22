using System.Security.Cryptography;
using Org.BouncyCastle.Crypto;
using Org.BouncyCastle.Crypto.Engines;
using Org.BouncyCastle.Crypto.Modes;
using Org.BouncyCastle.Crypto.Parameters;

namespace Uncage.Core.Media;

/// <summary>
/// AES-256-GCM for attachments, as NIP-17 file messages specify ("encryption-algorithm": "aes-gcm").
/// Every file gets a fresh random key; the key travels only inside the end-to-end encrypted message,
/// so the file server stores data it cannot read.
/// </summary>
public static class MediaCrypto
{
	public const string Algorithm = "aes-gcm";
	const int TagBits = 128;

	public sealed record EncryptedFile(byte[] Ciphertext, byte[] Key, byte[] Nonce, string CiphertextSha256, string PlaintextSha256);

	public static EncryptedFile Encrypt(ReadOnlySpan<byte> plaintext)
	{
		var key = RandomNumberGenerator.GetBytes(32);
		var nonce = RandomNumberGenerator.GetBytes(12);
		var ciphertext = Encrypt(plaintext, key, nonce);
		return new EncryptedFile(ciphertext, key, nonce, Sha256Hex(ciphertext), Sha256Hex(plaintext));
	}

	/// <summary>Deterministic for a given key and nonce: used to rebuild an upload after a restart.</summary>
	public static byte[] Encrypt(ReadOnlySpan<byte> plaintext, byte[] key, byte[] nonce) =>
		Process(forEncryption: true, plaintext, key, nonce);

	/// <exception cref="CryptographicException">Wrong key, or the file was modified.</exception>
	public static byte[] Decrypt(ReadOnlySpan<byte> ciphertext, byte[] key, byte[] nonce)
	{
		if (key.Length is not (16 or 24 or 32))
			throw new CryptographicException("Invalid AES key length.");
		if (nonce.Length is < 12 or > 32)
			throw new CryptographicException("Invalid AES-GCM nonce length.");
		try
		{
			return Process(forEncryption: false, ciphertext, key, nonce);
		}
		catch (InvalidCipherTextException e)
		{
			throw new CryptographicException("Attachment failed authentication.", e);
		}
	}

	public static string Sha256Hex(ReadOnlySpan<byte> data) => Crypto.Hex.Encode(SHA256.HashData(data));

	static byte[] Process(bool forEncryption, ReadOnlySpan<byte> input, byte[] key, byte[] nonce)
	{
		var cipher = new GcmBlockCipher(new AesEngine());
		cipher.Init(forEncryption, new AeadParameters(new KeyParameter(key), TagBits, nonce));
		var output = new byte[cipher.GetOutputSize(input.Length)];
		var written = cipher.ProcessBytes(input, output);
		written += cipher.DoFinal(output.AsSpan(written));
		return written == output.Length ? output : output[..written];
	}
}
