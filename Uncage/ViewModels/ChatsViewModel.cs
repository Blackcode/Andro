using System.Collections.ObjectModel;
using Uncage.Core.Chat;
using Uncage.Services;
using Uncage.Views;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Uncage.ViewModels;

public enum ChatFilter
{
	All,
	Unread,
	Requests,
}

public partial class ChatsViewModel(ChatSession session) : ObservableObject
{
	Messenger? _subscribed;
	IReadOnlyList<Conversation> _all = [];

	public ObservableCollection<ConversationItem> Conversations { get; } = [];

	[ObservableProperty]
	public partial string SearchText { get; set; } = "";

	[ObservableProperty]
	[NotifyPropertyChangedFor(nameof(IsAll), nameof(IsUnread), nameof(IsRequests))]
	public partial ChatFilter Filter { get; set; }

	[ObservableProperty]
	public partial string ConnectionText { get; set; } = "";

	[ObservableProperty]
	public partial bool IsOffline { get; set; }

	[ObservableProperty]
	public partial string EmptyText { get; set; } = "";

	public bool IsAll => Filter == ChatFilter.All;
	public bool IsUnread => Filter == ChatFilter.Unread;
	public bool IsRequests => Filter == ChatFilter.Requests;

	public void OnAppearing()
	{
		var messenger = session.Messenger;
		if (messenger is null)
			return;
		if (_subscribed != messenger)
		{
			if (_subscribed is not null)
			{
				_subscribed.MessageAdded -= OnMessage;
				_subscribed.MessageUpdated -= OnMessage;
				_subscribed.ContactsChanged -= Refresh;
				_subscribed.ConnectionChanged -= UpdateConnection;
			}
			messenger.MessageAdded += OnMessage;
			messenger.MessageUpdated += OnMessage;
			messenger.ContactsChanged += Refresh;
			messenger.ConnectionChanged += UpdateConnection;
			_subscribed = messenger;
		}
		Refresh();
		UpdateConnection();
	}

	partial void OnSearchTextChanged(string value) => ApplyFilter();

	partial void OnFilterChanged(ChatFilter value) => ApplyFilter();

	void OnMessage(ChatMessage _) => Refresh();

	void Refresh() => Ui.OnMainThread(() =>
	{
		if (session.Messenger is not { } messenger)
			return;
		_all = messenger.Store.Conversations();
		ApplyFilter();
	});

	void ApplyFilter()
	{
		var search = SearchText.Trim();
		var shown = _all.Where(c => Filter switch
		{
			ChatFilter.Unread => c.UnreadCount > 0,
			ChatFilter.Requests => c.Contact.IsRequest,
			_ => true,
		}).Where(c => search.Length == 0
			|| c.Contact.DisplayName.Contains(search, StringComparison.CurrentCultureIgnoreCase)
			|| (c.LastMessage?.Text.Contains(search, StringComparison.CurrentCultureIgnoreCase) ?? false));

		Conversations.Clear();
		foreach (var conversation in shown)
			Conversations.Add(new ConversationItem(conversation));

		EmptyText = _all.Count == 0
			? "No chats yet.\nTap the green button to start one, or share your ID from the My ID tab."
			: search.Length > 0 ? $"No chats match \"{search}\"."
			: Filter == ChatFilter.Unread ? "No unread chats."
			: Filter == ChatFilter.Requests ? "No message requests." : "";
	}

	void UpdateConnection() => Ui.OnMainThread(() =>
	{
		if (session.Messenger is not { } messenger)
			return;
		var connected = messenger.ConnectedRelays;
		var viaProxy = !string.IsNullOrEmpty(messenger.Store.Settings.ProxyUrl) ? " via Tor" : "";
		IsOffline = connected == 0;
		ConnectionText = connected == 0
			? $"Connecting{viaProxy}…"
			: $"Connected to {connected} of {messenger.Relays.Count} relays{viaProxy}";
	});

	[RelayCommand]
	void SetFilter(ChatFilter filter) => Filter = filter;

	[RelayCommand]
	static Task OpenAsync(ConversationItem item) => Shell.Current.GoToAsync($"chat?peer={item.PubKey}");

	[RelayCommand]
	static Task NewChatAsync() => Shell.Current.GoToAsync("addcontact");

	[RelayCommand]
	static Task ScanAsync() => Shell.Current.GoToAsync("scan?next=addcontact");
}

public sealed class ConversationItem
{
	public ConversationItem(Conversation conversation)
	{
		var contact = conversation.Contact;
		var last = conversation.LastMessage;
		PubKey = contact.PubKey;
		Title = contact.DisplayName;
		Initial = Avatars.InitialFor(contact.Name ?? "");
		HasName = !string.IsNullOrWhiteSpace(contact.Name);
		AvatarColor = Avatars.ColorFor(contact.PubKey);
		Preview = last?.Text.ReplaceLineEndings(" ") ?? (contact.IsRequest ? "Message request" : "Tap to start chatting");
		Time = last is null ? "" : Ui.FormatTime(last.Time);
		UnreadCount = conversation.UnreadCount;
		IsRequest = contact.IsRequest;
		StatusGlyph = last is { IsOutgoing: true } ? MessageItem.GlyphFor(last) : "";
		IsFailed = last?.Status == MessageStatus.Failed;
	}

	public string PubKey { get; }
	public string Title { get; }
	public string Initial { get; }
	/// <summary>Unnamed contacts show a person icon instead of an initial.</summary>
	public bool HasName { get; }
	public Color AvatarColor { get; }
	public string Preview { get; }
	public string Time { get; }
	public int UnreadCount { get; }
	public bool HasUnread => UnreadCount > 0;
	public string UnreadText => UnreadCount > 99 ? "99+" : UnreadCount.ToString();
	public bool IsRequest { get; }
	public string StatusGlyph { get; }
	public bool HasStatus => StatusGlyph.Length > 0;
	public bool IsFailed { get; }
}
