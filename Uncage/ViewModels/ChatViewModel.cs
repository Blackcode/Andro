using System.Collections.ObjectModel;
using Plugin.Maui.Audio;
using Uncage.Core.Chat;
using Uncage.Core.Media;
using Uncage.Services;
using Uncage.Views;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Uncage.ViewModels;

public partial class ChatViewModel(ChatSession session) : ObservableObject, IQueryAttributable
{
	static readonly TimeSpan GroupGap = TimeSpan.FromMinutes(5);

	readonly VoiceRecorder _recorder = new();
	IDispatcherTimer? _recordingTimer;
	IAudioPlayer? _player;
	MessageItem? _playing;
	Messenger? _messenger;
	string _peer = "";

	/// <summary>Date separators (<see cref="DateItem"/>) and messages (<see cref="MessageItem"/>), oldest first.</summary>
	public ObservableCollection<object> Rows { get; } = [];

	[ObservableProperty]
	public partial string Title { get; set; } = "";

	[ObservableProperty]
	public partial string Initial { get; set; } = "";

	[ObservableProperty]
	public partial bool HasName { get; set; }

	[ObservableProperty]
	public partial Color AvatarColor { get; set; } = Colors.Gray;

	[ObservableProperty]
	[NotifyCanExecuteChangedFor(nameof(SendCommand))]
	[NotifyPropertyChangedFor(nameof(HasDraft))]
	public partial string Draft { get; set; } = "";

	/// <summary>Like WhatsApp: the send button becomes a microphone when there is no text.</summary>
	public bool HasDraft => !string.IsNullOrWhiteSpace(Draft);

	[ObservableProperty]
	public partial bool IsRequest { get; set; }

	[ObservableProperty]
	[NotifyPropertyChangedFor(nameof(CanWrite))]
	public partial bool IsBlocked { get; set; }

	[ObservableProperty]
	[NotifyPropertyChangedFor(nameof(CanWrite))]
	public partial bool IsRecording { get; set; }

	[ObservableProperty]
	public partial string RecordingTime { get; set; } = "0:00";

	/// <summary>The normal message box is shown (not recording, not blocked).</summary>
	public bool CanWrite => !IsBlocked && !IsRecording;

	/// <summary>Raised when the list should scroll to the newest row.</summary>
	public event Action<object>? ScrollRequested;

	public void ApplyQueryAttributes(IDictionary<string, object> query)
	{
		if (query.TryGetValue("peer", out var peer))
			_peer = (string)peer;
	}

	public void OnAppearing()
	{
		_messenger = session.Messenger;
		if (_messenger is null || _peer.Length == 0)
			return;
		_messenger.MessageAdded += OnMessageAdded;
		_messenger.MessageUpdated += OnMessageUpdated;
		_messenger.MessageReplaced += OnMessageReplaced;

		UpdateHeader();
		Rows.Clear();
		foreach (var message in _messenger.Store.Messages(_peer))
			Append(message);
		_messenger.MarkRead(_peer);
		if (Rows.Count > 0)
			ScrollRequested?.Invoke(Rows[^1]);
	}

	public void OnDisappearing()
	{
		StopPlayback();
		if (IsRecording)
			_ = CancelRecordingAsync();
		if (_messenger is null)
			return;
		_messenger.MessageAdded -= OnMessageAdded;
		_messenger.MessageUpdated -= OnMessageUpdated;
		_messenger.MessageReplaced -= OnMessageReplaced;
		_messenger.MarkRead(_peer);
	}

	void UpdateHeader()
	{
		var contact = _messenger?.Store.FindContact(_peer);
		Title = contact?.DisplayName ?? Core.Chat.Contact.ShortNpub(_peer);
		HasName = !string.IsNullOrWhiteSpace(contact?.Name);
		Initial = Avatars.InitialFor(contact?.Name ?? "");
		AvatarColor = Avatars.ColorFor(_peer);
		IsRequest = contact?.IsRequest ?? false;
		IsBlocked = contact?.IsBlocked ?? false;
	}

	/// <summary>Adds a message at the end, with a date separator when the day changes.</summary>
	void Append(ChatMessage message)
	{
		var previous = Rows.OfType<MessageItem>().LastOrDefault();
		var day = message.Time.ToLocalTime().Date;
		var newDay = previous is null || previous.Model.Time.ToLocalTime().Date != day;
		if (newDay)
			Rows.Add(new DateItem(Ui.FormatDay(message.Time)));

		// Like WhatsApp: the first bubble of a run from the same person gets a "tail".
		var startsGroup = newDay || previous!.IsOutgoing != message.IsOutgoing || message.Time - previous.Model.Time > GroupGap;
		var item = new MessageItem(message, startsGroup) { Tap = MessageTappedCommand };
		Rows.Add(item);
		if (item.IsImage)
			_ = LoadPreviewAsync(item);
	}

	/// <summary>Photos are fetched (or read from the encrypted cache) as soon as they're shown.</summary>
	async Task LoadPreviewAsync(MessageItem item)
	{
		// Our own photos come from the local cache, even while still uploading.
		if (_messenger is null || item.Model.Attachment is null)
			return;
		item.IsLoading = true;
		try
		{
			var bytes = await _messenger.GetAttachmentAsync(item.Model.Attachment!);
			if (bytes is null)
				item.LoadFailed = true;
			else
				item.Preview = ImageSource.FromStream(() => new MemoryStream(bytes));
		}
		catch (Exception)
		{
			item.LoadFailed = true;
		}
		finally
		{
			item.IsLoading = false;
		}
	}

	void OnMessageAdded(ChatMessage message)
	{
		if (message.PeerPubKey != _peer)
			return;
		Ui.OnMainThread(() =>
		{
			if (Rows.OfType<MessageItem>().Any(m => m.Id == message.Id))
				return;
			var last = Rows.OfType<MessageItem>().LastOrDefault();
			if (last is null || last.Model.CreatedAt <= message.CreatedAt)
			{
				Append(message);
			}
			else
			{
				// Arrived out of order (e.g. synced from a relay later): rebuild so dates and groups stay right.
				var all = Rows.OfType<MessageItem>().Select(m => m.Model).Append(message).OrderBy(m => m.CreatedAt).ToList();
				Rows.Clear();
				foreach (var m in all)
					Append(m);
			}
			if (IsRequest)
				UpdateHeader();
			ScrollRequested?.Invoke(Rows[^1]);
		});
	}

	void OnMessageUpdated(ChatMessage message)
	{
		if (message.PeerPubKey != _peer)
			return;
		Ui.OnMainThread(() => Rows.OfType<MessageItem>().FirstOrDefault(m => m.Id == message.Id)?.Update(message));
	}

	/// <summary>An attachment finished uploading and got its final id.</summary>
	void OnMessageReplaced(string oldId, ChatMessage message)
	{
		if (message.PeerPubKey != _peer)
			return;
		Ui.OnMainThread(() => Rows.OfType<MessageItem>().FirstOrDefault(m => m.Id == oldId)?.Update(message));
	}

	bool CanSend() => HasDraft;

	[RelayCommand(CanExecute = nameof(CanSend))]
	async Task SendAsync()
	{
		var text = Draft.Trim();
		if (text.Length == 0 || _messenger is null)
			return;
		Draft = "";
		if (IsRequest)
			AcceptRequest();
		try
		{
			await _messenger.SendAsync(_peer, text);
		}
		catch (Exception e) when (e is ArgumentException or InvalidOperationException)
		{
			Draft = text;
			await Ui.Alert("Not sent", e.Message);
		}
	}

	[RelayCommand]
	async Task AttachAsync()
	{
		if (Ui.CurrentPage is not { } page)
			return;
		var choice = await page.DisplayActionSheetAsync("Send", "Cancel", null, "Photo from gallery", "Video from gallery", "Record video", "Audio file");
		await SendMediaAsync(choice switch
		{
			"Photo from gallery" => MediaInput.PickPhotosAsync,
			"Video from gallery" => MediaInput.PickVideosAsync,
			"Record video" => One(MediaInput.CaptureVideoAsync),
			"Audio file" => One(MediaInput.PickAudioAsync),
			_ => null,
		});
	}

	[RelayCommand]
	Task CameraAsync() => SendMediaAsync(One(MediaInput.CapturePhotoAsync));

	static Func<Task<IReadOnlyList<MediaFile>>> One(Func<Task<MediaFile?>> source) =>
		async () => await source() is { } file ? [file] : [];

	async Task SendMediaAsync(Func<Task<IReadOnlyList<MediaFile>>>? source)
	{
		if (source is null || _messenger is null)
			return;
		IReadOnlyList<MediaFile> files;
		try
		{
			files = await source();
		}
		catch (PermissionException)
		{
			await Ui.Alert("Permission needed", "Allow access in your phone's settings to send photos and videos.");
			return;
		}
		catch (FeatureNotSupportedException)
		{
			await Ui.Alert("Not available", "This device can't do that.");
			return;
		}
		foreach (var file in files)
			await SendFileAsync(file);
	}

	async Task SendFileAsync(MediaFile file)
	{
		if (_messenger is null)
			return;
		if (IsRequest)
			AcceptRequest();
		try
		{
			var sent = await _messenger.SendFileAsync(_peer, file.Content, file.MimeType, durationSeconds: file.DurationSeconds);
			if (sent.Status == MessageStatus.Failed && _messenger.LastError is { } reason)
				await Ui.Alert("Not sent", $"{reason} Tap the message to try again, or check the media servers in Settings.");
		}
		catch (Exception e) when (e is ArgumentException or InvalidOperationException)
		{
			await Ui.Alert("Not sent", e.Message);
		}
	}

	[RelayCommand]
	async Task StartRecordingAsync()
	{
		if (!await _recorder.StartAsync())
		{
			await Ui.Alert("Microphone needed", "Allow microphone access in your phone's settings to send voice messages.");
			return;
		}
		IsRecording = true;
		RecordingTime = "0:00";
		_recordingTimer = Application.Current!.Dispatcher.CreateTimer();
		_recordingTimer.Interval = TimeSpan.FromMilliseconds(500);
		_recordingTimer.Tick += (_, _) => RecordingTime = MessageItem.FormatDuration(_recorder.Elapsed.TotalSeconds);
		_recordingTimer.Start();
	}

	[RelayCommand]
	async Task SendRecordingAsync()
	{
		StopTimer();
		IsRecording = false;
		var recording = await _recorder.StopAsync();
		if (recording is not null)
			await SendFileAsync(recording);
	}

	[RelayCommand]
	async Task CancelRecordingAsync()
	{
		StopTimer();
		IsRecording = false;
		await _recorder.CancelAsync();
	}

	void StopTimer()
	{
		_recordingTimer?.Stop();
		_recordingTimer = null;
	}

	[RelayCommand]
	async Task MessageTappedAsync(MessageItem item)
	{
		if (_messenger is null || Ui.CurrentPage is not { } page)
			return;

		if (item.Model.Status == MessageStatus.Failed)
		{
			var retry = await page.DisplayActionSheetAsync("Not delivered", "Cancel", null, "Retry", item.HasText ? "Copy" : "Delete");
			if (retry == "Retry")
				await _messenger.RetryAsync(item.Model);
			else if (retry == "Copy")
				await Clipboard.Default.SetTextAsync(item.Text);
			return;
		}

		switch (item.Model.Attachment?.Kind)
		{
			case AttachmentKind.Image or AttachmentKind.Video:
				await Shell.Current.GoToAsync("media", new Dictionary<string, object> { ["message"] = item.Model });
				break;
			case AttachmentKind.Audio:
				await TogglePlaybackAsync(item);
				break;
			case AttachmentKind.File:
				await ShareFileAsync(item);
				break;
			default:
				if (await page.DisplayActionSheetAsync(null, "Cancel", null, "Copy") == "Copy")
					await Clipboard.Default.SetTextAsync(item.Text);
				break;
		}
	}

	async Task TogglePlaybackAsync(MessageItem item)
	{
		if (_playing == item)
		{
			StopPlayback();
			return;
		}
		StopPlayback();
		item.IsLoading = true;
		var bytes = await _messenger!.GetAttachmentAsync(item.Model.Attachment!);
		item.IsLoading = false;
		if (bytes is null)
		{
			item.LoadFailed = true;
			return;
		}
		_player = AudioManager.Current.CreatePlayer(new MemoryStream(bytes));
		_player.PlaybackEnded += OnPlaybackEnded;
		_playing = item;
		item.IsPlaying = true;
		_player.Play();
	}

	void OnPlaybackEnded(object? sender, EventArgs e) => Ui.OnMainThread(() =>
	{
		// Ignore events from a player that was already stopped or replaced.
		if (sender is not null && ReferenceEquals(sender, _player))
			StopPlayback();
	});

	void StopPlayback()
	{
		// Detach and clear first: on some platforms Stop() raises PlaybackEnded synchronously,
		// which used to call back into here and recurse until the stack overflowed.
		var player = _player;
		_player = null;
		if (_playing is not null)
			_playing.IsPlaying = false;
		_playing = null;
		if (player is null)
			return;
		player.PlaybackEnded -= OnPlaybackEnded;
		player.Stop();
		player.Dispose();
	}

	async Task ShareFileAsync(MessageItem item)
	{
		var bytes = await _messenger!.GetAttachmentAsync(item.Model.Attachment!);
		if (bytes is null)
		{
			await Ui.Alert("Not available", "The file couldn't be downloaded from any of its servers.");
			return;
		}
		// Sharing hands the decrypted file to another app; it's removed from our cache the next time a chat opens.
		var path = Path.Combine(FileSystem.CacheDirectory, "share-" + Guid.NewGuid().ToString("N") + MediaInput.ExtensionFor(item.Model.Attachment!.MimeType));
		await File.WriteAllBytesAsync(path, bytes);
		await Share.Default.RequestAsync(new ShareFileRequest { Title = "Open file", File = new ShareFile(path, item.Model.Attachment.MimeType) });
	}

	[RelayCommand]
	void AcceptRequest()
	{
		if (_messenger is null)
			return;
		_messenger.AddContact(_peer, null);
		IsRequest = false;
	}

	[RelayCommand]
	async Task BlockAsync()
	{
		if (_messenger is null)
			return;
		if (!await Ui.Confirm($"Block {Title}?", "Blocked contacts can't send you messages. They are not told that you blocked them.", "Block"))
			return;
		_messenger.Block(_peer);
		UpdateHeader();
	}

	[RelayCommand]
	void Unblock()
	{
		_messenger?.Unblock(_peer);
		UpdateHeader();
	}

	[RelayCommand]
	async Task ContactInfoAsync()
	{
		if (_messenger is null || Ui.CurrentPage is not { } page)
			return;
		var choice = await page.DisplayActionSheetAsync(Title, "Cancel", "Delete chat", "Rename", "Copy their ID", IsBlocked ? "Unblock" : "Block");
		switch (choice)
		{
			case "Rename":
				var name = await page.DisplayPromptAsync("Rename", "Only you see this name.", initialValue: HasName ? Title : "", maxLength: 60);
				if (!string.IsNullOrWhiteSpace(name))
				{
					_messenger.AddContact(_peer, name);
					UpdateHeader();
				}
				break;
			case "Copy their ID":
				await Clipboard.Default.SetTextAsync(Core.Crypto.Nip19.EncodeNpub(_peer));
				break;
			case "Block":
				await BlockAsync();
				break;
			case "Unblock":
				Unblock();
				break;
			case "Delete chat":
				if (await Ui.Confirm("Delete this chat?", "Messages are removed from this device. Encrypted copies may remain on relays.", "Delete"))
				{
					_messenger.RemoveContact(_peer);
					await Shell.Current.GoToAsync("..");
				}
				break;
		}
	}
}

/// <summary>"Today", "Yesterday"… chip between messages of different days.</summary>
public sealed record DateItem(string Text);

public sealed partial class MessageItem : ObservableObject
{
	public MessageItem(ChatMessage message, bool startsGroup)
	{
		Model = message;
		Text = message.Text;
		Time = message.Time.ToLocalTime().ToString("t");
		IsOutgoing = message.IsOutgoing;
		StartsGroup = startsGroup;
		var kind = message.Attachment?.Kind;
		IsImage = kind == AttachmentKind.Image;
		IsVideo = kind == AttachmentKind.Video;
		IsAudio = kind == AttachmentKind.Audio;
		IsFile = kind == AttachmentKind.File;
		HasText = message.Attachment is null;
		MediaLabel = message.Attachment is { } a
			? a.DurationSeconds is { } d ? FormatDuration(d) : SizeLabel(a.Size)
			: "";
		Update(message);
	}

	/// <summary>Set by the chat; carried by the item so the bubble needs no RelativeSource binding.</summary>
	public System.Windows.Input.ICommand? Tap { get; init; }
	public string Id => Model.Id;
	public string Text { get; }
	public string Time { get; }
	public bool IsOutgoing { get; }
	/// <summary>First bubble of a run: drawn with a tail and a little extra space above.</summary>
	public bool StartsGroup { get; }
	public bool HasText { get; }
	public bool IsImage { get; }
	public bool IsVideo { get; }
	public bool IsAudio { get; }
	public bool IsFile { get; }
	/// <summary>Duration for audio/video, size for other files.</summary>
	public string MediaLabel { get; }

	[ObservableProperty]
	[NotifyPropertyChangedFor(nameof(Id))]
	public partial ChatMessage Model { get; private set; }

	[ObservableProperty]
	public partial string StatusGlyph { get; private set; } = "";

	[ObservableProperty]
	public partial bool IsFailed { get; private set; }

	/// <summary>Stored on more than one relay (shown as a double tick).</summary>
	[ObservableProperty]
	public partial bool IsWidelyStored { get; private set; }

	[ObservableProperty]
	[NotifyPropertyChangedFor(nameof(HasPreview))]
	public partial ImageSource? Preview { get; set; }

	public bool HasPreview => Preview is not null;

	[ObservableProperty]
	public partial bool IsLoading { get; set; }

	[ObservableProperty]
	public partial bool LoadFailed { get; set; }

	[ObservableProperty]
	[NotifyPropertyChangedFor(nameof(PlayGlyph))]
	public partial bool IsPlaying { get; set; }

	public string PlayGlyph => IsPlaying ? Icons.Pause : Icons.Play;

	public void Update(ChatMessage message)
	{
		Model = message;
		IsFailed = message.Status == MessageStatus.Failed;
		IsWidelyStored = message.Status == MessageStatus.Sent && message.RelaysAccepted > 1;
		StatusGlyph = GlyphFor(message);
	}

	/// <summary>
	/// Clock while sending, one tick once a relay accepted it, two ticks once several relays hold it
	/// (Nostr has no read receipts, so ticks mean "safely stored for delivery", not "read").
	/// </summary>
	public static string GlyphFor(ChatMessage message) => message.Status switch
	{
		MessageStatus.Sending => Icons.Clock,
		MessageStatus.Sent => message.RelaysAccepted > 1 ? Icons.DoneAll : Icons.Done,
		MessageStatus.Failed => Icons.Error,
		_ => "",
	};

	public static string FormatDuration(double seconds)
	{
		var t = TimeSpan.FromSeconds(Math.Max(0, seconds));
		return t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");
	}

	static string SizeLabel(long bytes) => bytes switch
	{
		< 1024 * 1024 => $"{Math.Max(1, bytes / 1024)} KB",
		_ => $"{bytes / (1024.0 * 1024):0.#} MB",
	};
}
