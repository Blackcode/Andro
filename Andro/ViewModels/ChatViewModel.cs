using System.Collections.ObjectModel;
using Andro.Core.Chat;
using Andro.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Andro.ViewModels;

public partial class ChatViewModel(ChatSession session) : ObservableObject, IQueryAttributable
{
	Messenger? _messenger;
	string _peer = "";

	public ObservableCollection<MessageItem> Messages { get; } = [];

	[ObservableProperty]
	public partial string Title { get; set; } = "";

	[ObservableProperty]
	public partial string Draft { get; set; } = "";

	[ObservableProperty]
	public partial bool IsRequest { get; set; }

	/// <summary>Raised when the list should scroll to the newest message.</summary>
	public event Action<MessageItem>? ScrollRequested;

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

		var contact = _messenger.Store.FindContact(_peer);
		Title = contact?.DisplayName ?? Core.Chat.Contact.ShortNpub(_peer);
		IsRequest = contact?.IsRequest ?? false;

		Messages.Clear();
		foreach (var message in _messenger.Store.Messages(_peer))
			Messages.Add(new MessageItem(message));
		_messenger.MarkRead(_peer);
		if (Messages.Count > 0)
			ScrollRequested?.Invoke(Messages[^1]);
	}

	public void OnDisappearing()
	{
		if (_messenger is null)
			return;
		_messenger.MessageAdded -= OnMessageAdded;
		_messenger.MessageUpdated -= OnMessageUpdated;
		_messenger.MarkRead(_peer);
	}

	void OnMessageAdded(ChatMessage message)
	{
		if (message.PeerPubKey != _peer)
			return;
		Ui.OnMainThread(() =>
		{
			if (Messages.Any(m => m.Id == message.Id))
				return;
			var item = new MessageItem(message);
			var index = Messages.Count;
			while (index > 0 && Messages[index - 1].Model.CreatedAt > message.CreatedAt)
				index--;
			Messages.Insert(index, item);
			ScrollRequested?.Invoke(item);
		});
	}

	void OnMessageUpdated(ChatMessage message)
	{
		if (message.PeerPubKey != _peer)
			return;
		Ui.OnMainThread(() => Messages.FirstOrDefault(m => m.Id == message.Id)?.Update(message));
	}

	[RelayCommand]
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
	async Task RetryAsync(MessageItem item)
	{
		if (_messenger is null || item.Model.Status != MessageStatus.Failed)
			return;
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
	async Task MoreAsync()
	{
		if (_messenger is null || Ui.CurrentPage is not { } page)
			return;
		var choice = await page.DisplayActionSheetAsync(Title, "Cancel", "Delete conversation", "Rename", "Copy their ID");
		switch (choice)
		{
			case "Rename":
				var name = await page.DisplayPromptAsync("Rename", "Only you see this name.", initialValue: Title, maxLength: 60);
				if (!string.IsNullOrWhiteSpace(name))
				{
					_messenger.AddContact(_peer, name);
					Title = name.Trim();
					IsRequest = false;
				}
				break;
			case "Copy their ID":
				await Clipboard.Default.SetTextAsync(Core.Crypto.Nip19.EncodeNpub(_peer));
				break;
			case "Delete conversation":
				if (await Ui.Confirm("Delete conversation?", "Messages are removed from this device. Copies may remain on relays, encrypted.", "Delete"))
				{
					_messenger.RemoveContact(_peer);
					await Shell.Current.GoToAsync("..");
				}
				break;
		}
	}
}

public sealed partial class MessageItem : ObservableObject
{
	public MessageItem(ChatMessage message)
	{
		Model = message;
		Id = message.Id;
		Text = message.Text;
		Time = message.Time.ToLocalTime().ToString("t");
		IsOutgoing = message.IsOutgoing;
		Update(message);
	}

	public string Id { get; }
	public string Text { get; }
	public string Time { get; }
	public bool IsOutgoing { get; }

	[ObservableProperty]
	public partial ChatMessage Model { get; private set; }

	[ObservableProperty]
	public partial string StatusGlyph { get; private set; } = "";

	[ObservableProperty]
	public partial bool IsFailed { get; private set; }

	public void Update(ChatMessage message)
	{
		Model = message;
		IsFailed = message.Status == MessageStatus.Failed;
		StatusGlyph = message.Status switch
		{
			MessageStatus.Sending => "🕓",
			MessageStatus.Sent => message.RelaysAccepted > 1 ? "✓✓" : "✓",
			MessageStatus.Failed => "⚠ tap to retry",
			_ => "",
		};
	}
}
