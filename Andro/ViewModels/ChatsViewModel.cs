using System.Collections.ObjectModel;
using Andro.Core.Chat;
using Andro.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Andro.ViewModels;

public partial class ChatsViewModel(ChatSession session) : ObservableObject
{
	Messenger? _subscribed;

	public ObservableCollection<ConversationItem> Conversations { get; } = [];

	[ObservableProperty]
	public partial string ConnectionText { get; set; } = "";

	[ObservableProperty]
	public partial bool IsOffline { get; set; }

	[ObservableProperty]
	public partial bool IsEmpty { get; set; }

	public void OnAppearing()
	{
		var messenger = session.Messenger;
		if (messenger is null)
			return;
		if (_subscribed != messenger)
		{
			if (_subscribed is not null)
				Unsubscribe(_subscribed);
			messenger.MessageAdded += OnMessage;
			messenger.MessageUpdated += OnMessage;
			messenger.ContactsChanged += Refresh;
			messenger.ConnectionChanged += UpdateConnection;
			_subscribed = messenger;
		}
		Refresh();
		UpdateConnection();
	}

	void Unsubscribe(Messenger messenger)
	{
		messenger.MessageAdded -= OnMessage;
		messenger.MessageUpdated -= OnMessage;
		messenger.ContactsChanged -= Refresh;
		messenger.ConnectionChanged -= UpdateConnection;
	}

	void OnMessage(ChatMessage _) => Refresh();

	void Refresh() => Ui.OnMainThread(() =>
	{
		if (session.Messenger is not { } messenger)
			return;
		Conversations.Clear();
		foreach (var conversation in messenger.Store.Conversations())
			Conversations.Add(new ConversationItem(conversation));
		IsEmpty = Conversations.Count == 0;
	});

	void UpdateConnection() => Ui.OnMainThread(() =>
	{
		if (session.Messenger is not { } messenger)
			return;
		var connected = messenger.ConnectedRelays;
		var total = messenger.Relays.Count;
		var viaTor = !string.IsNullOrEmpty(messenger.Store.Settings.ProxyUrl) ? " via proxy" : "";
		IsOffline = connected == 0;
		ConnectionText = connected == 0 ? $"Connecting to {total} relays{viaTor}…" : $"Connected to {connected} of {total} relays{viaTor}";
	});

	[RelayCommand]
	static Task OpenAsync(ConversationItem item) =>
		Shell.Current.GoToAsync($"chat?peer={item.PubKey}");

	[RelayCommand]
	static Task NewChatAsync() => Shell.Current.GoToAsync("addcontact");

	[RelayCommand]
	static Task MyIdAsync() => Shell.Current.GoToAsync("myid");

	[RelayCommand]
	static Task SettingsAsync() => Shell.Current.GoToAsync("settings");
}

public sealed class ConversationItem(Conversation conversation)
{
	public string PubKey { get; } = conversation.Contact.PubKey;
	public string Title { get; } = conversation.Contact.DisplayName;
	public string Initial { get; } = conversation.Contact.DisplayName[..1].ToUpperInvariant();
	public string Preview { get; } = conversation.LastMessage switch
	{
		null => "No messages yet",
		{ IsOutgoing: true } m => "You: " + m.Text,
		{ } m => m.Text,
	};
	public string Time { get; } = conversation.LastMessage is { } m ? Ui.FormatTime(m.Time) : "";
	public int UnreadCount { get; } = conversation.UnreadCount;
	public bool HasUnread => UnreadCount > 0;
	public bool IsRequest { get; } = conversation.Contact.IsRequest;
}
