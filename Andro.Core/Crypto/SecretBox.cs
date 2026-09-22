using System.Security.Cryptography;
using BcChaCha20Poly1305 = Org.BouncyCastle.Crypto.Modes.ChaCha20Poly1305;
using Org.BouncyCastle.Crypto.Parameters;

namespace Andro.Core.Crypto;

/// <summary>
/// Authenticated encryption (ChaCha20-Poly1305) for data stored on the device, so the
/// local message history is unreadable without the key held in the platform keystore.
/// Format: 12-byte nonce || ciphertext || 16-byte tag.
/// </summary>
public static class SecretBox
{
	public const int KeySize = 32;
	const int NonceSize = 12;
	const int TagBits = 128;

	public static byte[] Seal(ReadOnlySpan<byte> plaintext, byte[] key)
	{
		var nonce = RandomNumberGenerator.GetBytes(NonceSize);
		var cipher = Create(forEncryption: true, key, nonce);
		var output = new byte[NonceSize + cipher.GetOutputSize(plaintext.Length)];
		nonce.CopyTo(output, 0);
		var written = cipher.ProcessBytes(plaintext, output.AsSpan(NonceSize));
		cipher.DoFinal(output.AsSpan(NonceSize + written));
		return output;
	}

	public static byte[] Open(ReadOnlySpan<byte> sealedData, byte[] key)
	{
		if (sealedData.Length < NonceSize + TagBits / 8)
			throw new CryptographicException("Sealed data is too short.");
		var cipher = Create(forEncryption: false, key, sealedData[..NonceSize].ToArray());
		var body = sealedData[NonceSize..];
		var output = new byte[cipher.GetOutputSize(body.Length)];
		try
		{
			var written = cipher.ProcessBytes(body, output);
			written += cipher.DoFinal(output.AsSpan(written));
			return output[..written];
		}
		catch (Org.BouncyCastle.Crypto.InvalidCipherTextException e)
		{
			throw new CryptographicException("Stored data failed authentication.", e);
		}
	}

	static BcChaCha20Poly1305 Create(bool forEncryption, byte[] key, byte[] nonce)
	{
		if (key.Length != KeySize)
			throw new ArgumentException("Key must be 32 bytes.", nameof(key));
		var cipher = new BcChaCha20Poly1305();
		cipher.Init(forEncryption, new AeadParameters(new KeyParameter(key), TagBits, nonce));
		return cipher;
	}
}
