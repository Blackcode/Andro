using System.Text.Json;
using System.Text.Json.Serialization;
using Uncage.Core.Crypto;

namespace Uncage.Core.Chat;

/// <summary>
/// Contacts, message history and settings. Everything is written to disk encrypted with
/// <see cref="SecretBox"/>; the key lives in the platform keystore, not next to the file.
/// Thread-safe.
/// </summary>
public sealed class ChatStore
{
	const int MaxProcessedIds = 20_000;

	readonly object _gate = new();
	readonly string? _path;
	readonly byte[] _key;
	readonly StoreData _data;
	Timer? _saveTimer;

	ChatStore(string? path, byte[] key, StoreData data)
	{
		_path = path;
		_key = key;
		_data = data;
	}

	/// <summary>Opens (or creates) the encrypted store at <paramref name="path"/>; null keeps it in memory.</summary>
	/// <exception cref="System.Security.Cryptography.CryptographicException">The file exists but the key is wrong or it was tampered with.</exception>
	public static ChatStore Open(string? path, byte[] key)
	{
		var data = new StoreData();
		if (path is not null && File.Exists(path))
		{
			var json = SecretBox.Open(File.ReadAllBytes(path), key);
			data = JsonSerializer.Deserialize(json, StoreJsonContext.Default.StoreData) ?? new StoreData();
			// Files from older versions may lack sections that were added later.
			data.Settings ??= new ChatSettings();
			data.Contacts ??= [];
			data.Messages ??= [];
			data.ProcessedWrapIds ??= [];
		}
		return new ChatStore(path, key, data);
	}

	public ChatSettings Settings
	{
		get { lock (_gate) return _data.Settings; }
		set { lock (_gate) _data.Settings = value; ScheduleSave(); }
	}

	/// <summary>Unix time up to which the inbox has been fully synchronized.</summary>
	public long LastSyncAt
	{
		get { lock (_gate) return _data.LastSyncAt; }
		set { lock (_gate) _data.LastSyncAt = Math.Max(_data.LastSyncAt, value); ScheduleSave(); }
	}

	public Contact? FindContact(string pubKey)
	{
		lock (_gate)
			return _data.Contacts.GetValueOrDefault(pubKey);
	}

	public void UpsertContact(Contact contact)
	{
		lock (_gate)
			_data.Contacts[contact.PubKey] = contact;
		ScheduleSave();
	}

	public void RemoveContact(string pubKey)
	{
		lock (_gate)
		{
			_data.Contacts.Remove(pubKey);
			_data.Messages.RemoveAll(m => m.PeerPubKey == pubKey);
		}
		ScheduleSave();
	}

	public IReadOnlyList<Contact> Contacts()
	{
		lock (_gate)
			return [.. _data.Contacts.Values.OrderBy(c => c.DisplayName, StringComparer.CurrentCultureIgnoreCase)];
	}

	public IReadOnlyList<Conversation> Conversations()
	{
		lock (_gate)
		{
			var lastByPeer = _data.Messages.GroupBy(m => m.PeerPubKey).ToDictionary(g => g.Key, g => g.MaxBy(m => m.CreatedAt));
			return [.. _data.Contacts.Values
				.Where(c => !c.IsBlocked)
				.Select(c => new Conversation(
					c,
					lastByPeer.GetValueOrDefault(c.PubKey),
					_data.Messages.Count(m => m.PeerPubKey == c.PubKey && m.Status == MessageStatus.Received && m.CreatedAt > c.LastReadAt)))
				.OrderByDescending(c => c.LastMessage?.CreatedAt ?? 0)
				.ThenBy(c => c.Contact.DisplayName, StringComparer.CurrentCultureIgnoreCase)];
		}
	}

	public IReadOnlyList<Contact> BlockedContacts()
	{
		lock (_gate)
			return [.. _data.Contacts.Values.Where(c => c.IsBlocked).OrderBy(c => c.DisplayName, StringComparer.CurrentCultureIgnoreCase)];
	}

	/// <summary>Swaps a message for a new version with a different id (e.g. once an upload completes).</summary>
	public void ReplaceMessage(string oldId, ChatMessage message)
	{
		lock (_gate)
		{
			var index = _data.Messages.FindIndex(m => m.Id == oldId);
			if (index >= 0)
				_data.Messages[index] = message;
			else if (!_data.Messages.Exists(m => m.Id == message.Id))
				_data.Messages.Add(message);
		}
		ScheduleSave();
	}

	public IReadOnlyList<ChatMessage> Messages(string peerPubKey)
	{
		lock (_gate)
			return [.. _data.Messages.Where(m => m.PeerPubKey == peerPubKey).OrderBy(m => m.CreatedAt).ThenBy(m => m.Id, StringComparer.Ordinal)];
	}

	public ChatMessage? FindMessage(string id)
	{
		lock (_gate)
			return _data.Messages.Find(m => m.Id == id);
	}

	/// <summary>Adds the message unless one with the same id exists. Returns whether it was added.</summary>
	public bool TryAddMessage(ChatMessage message)
	{
		lock (_gate)
		{
			if (_data.Messages.Exists(m => m.Id == message.Id))
				return false;
			_data.Messages.Add(message);
		}
		ScheduleSave();
		return true;
	}

	public void UpdateMessage(ChatMessage message)
	{
		lock (_gate)
		{
			var index = _data.Messages.FindIndex(m => m.Id == message.Id);
			if (index < 0)
				return;
			_data.Messages[index] = message;
		}
		ScheduleSave();
	}

	/// <summary>Records a gift wrap as handled. Returns false if it was handled before.</summary>
	public bool TryMarkProcessed(string wrapId)
	{
		lock (_gate)
		{
			if (_data.ProcessedWrapIds.Contains(wrapId))
				return false;
			_data.ProcessedWrapIds.Add(wrapId);
			if (_data.ProcessedWrapIds.Count > MaxProcessedIds)
				_data.ProcessedWrapIds.RemoveRange(0, _data.ProcessedWrapIds.Count - MaxProcessedIds);
		}
		ScheduleSave();
		return true;
	}

	/// <summary>Writes pending changes now.</summary>
	public void Flush()
	{
		if (_path is null)
			return;
		byte[] json;
		lock (_gate)
		{
			_saveTimer?.Dispose();
			_saveTimer = null;
			json = JsonSerializer.SerializeToUtf8Bytes(_data, StoreJsonContext.Default.StoreData);
		}
		var sealedData = SecretBox.Seal(json, _key);
		var temp = _path + ".tmp";
		Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(_path))!);
		File.WriteAllBytes(temp, sealedData);
		File.Move(temp, _path, overwrite: true);
	}

	/// <summary>Removes the history file (used when the identity is deleted).</summary>
	public static void Delete(string path)
	{
		if (File.Exists(path))
			File.Delete(path);
	}

	void ScheduleSave()
	{
		if (_path is null)
			return;
		lock (_gate)
			_saveTimer ??= new Timer(_ => Flush(), null, TimeSpan.FromMilliseconds(500), Timeout.InfiniteTimeSpan);
	}

	internal sealed class StoreData
	{
		public Dictionary<string, Contact> Contacts { get; set; } = [];
		public List<ChatMessage> Messages { get; set; } = [];
		public List<string> ProcessedWrapIds { get; set; } = [];
		public long LastSyncAt { get; set; }
		public ChatSettings Settings { get; set; } = new();
	}
}

[JsonSourceGenerationOptions(UseStringEnumConverter = true, IgnoreReadOnlyProperties = true)]
[JsonSerializable(typeof(ChatStore.StoreData))]
partial class StoreJsonContext : JsonSerializerContext;
