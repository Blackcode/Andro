using System.Security.Cryptography;
using Andro.Core.Chat;
using Andro.Core.Crypto;

namespace Andro.Services;

/// <summary>
/// Owns the user's identity and the running <see cref="Messenger"/>. The secret key and the key that
/// encrypts the message history are kept in the platform keystore (Android Keystore, iOS Keychain,
/// Windows DPAPI) through <see cref="SecureStorage"/>, never in plain files.
/// </summary>
public sealed class ChatSession
{
	const string SecretKeyName = "andro.identity.secret";
	const string StorageKeyName = "andro.history.key";

	readonly SemaphoreSlim _gate = new(1, 1);

	public Messenger? Messenger { get; private set; }

	public Messenger Current => Messenger ?? throw new InvalidOperationException("No identity is loaded.");

	/// <summary>Loads the identity saved on this device, if any, and connects.</summary>
	public async Task<bool> TryResumeAsync()
	{
		await _gate.WaitAsync();
		try
		{
			if (Messenger is not null)
				return true;
			var secret = await SecureStorage.Default.GetAsync(SecretKeyName);
			if (string.IsNullOrEmpty(secret))
				return false;
			await StartAsync(NostrKeys.Parse(secret));
			return true;
		}
		finally
		{
			_gate.Release();
		}
	}

	public Task CreateIdentityAsync() => SetIdentityAsync(NostrKeys.Generate());

	/// <exception cref="FormatException">The backup key is not a valid nsec.</exception>
	public Task RestoreIdentityAsync(string nsec) => SetIdentityAsync(NostrKeys.Parse(nsec));

	async Task SetIdentityAsync(NostrKeys keys)
	{
		await _gate.WaitAsync();
		try
		{
			await SecureStorage.Default.SetAsync(SecretKeyName, keys.Nsec);
			await StartAsync(keys);
		}
		finally
		{
			_gate.Release();
		}
	}

	/// <summary>The backup key (nsec). Anyone who has it can read all messages and write as this user.</summary>
	public Task<string?> RevealSecretKeyAsync() => SecureStorage.Default.GetAsync(SecretKeyName);

	/// <summary>Erases the identity and the message history from this device.</summary>
	public async Task DeleteIdentityAsync()
	{
		await _gate.WaitAsync();
		try
		{
			var messenger = Messenger;
			Messenger = null;
			if (messenger is not null)
			{
				await messenger.DisposeAsync();
				ChatStore.Delete(HistoryPath(messenger.PubKey));
			}
			SecureStorage.Default.Remove(SecretKeyName);
			SecureStorage.Default.Remove(StorageKeyName);
		}
		finally
		{
			_gate.Release();
		}
	}

	async Task StartAsync(NostrKeys keys)
	{
		if (Messenger is not null)
			await Messenger.DisposeAsync();

		var storageKey = await StorageKeyAsync();
		var path = HistoryPath(keys.PublicKeyHex);
		ChatStore store;
		try
		{
			store = ChatStore.Open(path, storageKey);
		}
		catch (CryptographicException)
		{
			// History encrypted under a key that no longer exists (e.g. keystore reset): start fresh,
			// keeping the unreadable file aside rather than destroying it.
			File.Move(path, path + ".unreadable", overwrite: true);
			store = ChatStore.Open(path, storageKey);
		}

		Messenger = new Messenger(keys, store);
		await Messenger.StartAsync();
	}

	static async Task<byte[]> StorageKeyAsync()
	{
		var stored = await SecureStorage.Default.GetAsync(StorageKeyName);
		if (!string.IsNullOrEmpty(stored))
			return Convert.FromBase64String(stored);
		var key = Messenger.NewStorageKey();
		await SecureStorage.Default.SetAsync(StorageKeyName, Convert.ToBase64String(key));
		return key;
	}

	static string HistoryPath(string pubKey) =>
		Path.Combine(FileSystem.AppDataDirectory, $"history-{pubKey[..16]}.bin");
}
