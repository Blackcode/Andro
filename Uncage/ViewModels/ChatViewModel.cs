using System.Collections.ObjectModel;
using Uncage.Core.Chat;
using Uncage.Services;
using Uncage.Views;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Uncage.ViewModels;

public partial class ChatViewModel(ChatSession session) : ObservableObject, IQueryAttributable
{
	static readonly TimeSpan GroupGap = TimeSpan.FromMinutes(5);

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
	public partial string Draft { get; set; } = "";

	[ObservableProperty]
	public partial bool IsRequest { get; set; }

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
		if (_messenger is null)
			return;
		_messenger.MessageAdded -= OnMessageAdded;
		_messenger.MessageUpdated -= OnMessageUpdated;
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
		Rows.Add(new MessageItem(message, startsGroup));
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

	bool CanSend() => !string.IsNullOrWhiteSpace(Draft);

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
		catch (ArgumentException e)
		{
			Draft = text;
			await Ui.Alert("Not sent", e.Message);
		}
	}

	[RelayCommand]
	async Task MessageTappedAsync(MessageItem item)
	{
		if (_messenger is null || Ui.CurrentPage is not { } page)
			return;
		var failed = item.Model.Status == MessageStatus.Failed;
		var choice = failed
			? await page.DisplayActionSheetAsync("Not delivered", "Cancel", null, "Retry", "Copy")
			: await page.DisplayActionSheetAsync(null, "Cancel", null, "Copy");
		if (choice == "Copy")
			await Clipboard.Default.SetTextAsync(item.Text);
		else if (choice == "Retry")
			await _messenger.RetryAsync(item.Model);
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
	async Task ContactInfoAsync()
	{
		if (_messenger is null || Ui.CurrentPage is not { } page)
			return;
		var choice = await page.DisplayActionSheetAsync(Title, "Cancel", "Delete chat", "Rename", "Copy their ID");
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
		Id = message.Id;
		Text = message.Text;
		Time = message.Time.ToLocalTime().ToString("t");
		IsOutgoing = message.IsOutgoing;
		StartsGroup = startsGroup;
		Update(message);
	}

	public string Id { get; }
	public string Text { get; }
	public string Time { get; }
	public bool IsOutgoing { get; }
	/// <summary>First bubble of a run: drawn with a tail and a little extra space above.</summary>
	public bool StartsGroup { get; }

	[ObservableProperty]
	public partial ChatMessage Model { get; private set; }

	[ObservableProperty]
	public partial string StatusGlyph { get; private set; } = "";

	[ObservableProperty]
	public partial bool IsFailed { get; private set; }

	/// <summary>Stored on more than one relay (shown as a double tick).</summary>
	[ObservableProperty]
	public partial bool IsWidelyStored { get; private set; }

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
}
