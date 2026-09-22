using System.Collections.Concurrent;
using System.Security.Cryptography;
using Uncage.Core.Crypto;

namespace Uncage.Core.Media;

/// <summary>
/// Decrypted attachments kept on the device so they aren't downloaded again. Stored re-encrypted with
/// the history key (<see cref="SecretBox"/>), never as plain files. Null directory keeps them in memory.
/// </summary>
public sealed class MediaCache(string? directory, byte[] key)
{
	readonly ConcurrentDictionary<string, byte[]> _memory = new();

	public void Put(string encryptedSha256, byte[] plaintext)
	{
		if (directory is null)
		{
			_memory[encryptedSha256] = plaintext;
			return;
		}
		Directory.CreateDirectory(directory);
		var path = PathFor(encryptedSha256);
		File.WriteAllBytes(path + ".tmp", SecretBox.Seal(plaintext, key));
		File.Move(path + ".tmp", path, overwrite: true);
	}

	public byte[]? TryGet(string encryptedSha256)
	{
		if (directory is null)
			return _memory.GetValueOrDefault(encryptedSha256);
		var path = PathFor(encryptedSha256);
		if (!File.Exists(path))
			return null;
		try
		{
			return SecretBox.Open(File.ReadAllBytes(path), key);
		}
		catch (CryptographicException)
		{
			File.Delete(path);
			return null;
		}
	}

	public void Clear()
	{
		_memory.Clear();
		if (directory is not null && Directory.Exists(directory))
			Directory.Delete(directory, recursive: true);
	}

	string PathFor(string sha256) =>
		Hex.IsLowerHex(sha256, 32) ? Path.Combine(directory!, sha256 + ".bin") : throw new ArgumentException("Invalid hash.", nameof(sha256));
}
