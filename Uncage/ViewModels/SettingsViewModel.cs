using System.Collections.ObjectModel;
using Uncage.Core.Chat;
using Uncage.Core.Nostr;
using Uncage.Core.Relays;
using Uncage.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Uncage.ViewModels;

public partial class SettingsViewModel(ChatSession session) : ObservableObject
{
	bool _loading;

	public ObservableCollection<RelayItem> Relays { get; } = [];

	public ObservableCollection<string> MediaServers { get; } = [];

	public ObservableCollection<BlockedItem> Blocked { get; } = [];

	[ObservableProperty]
	public partial string NewMediaServer { get; set; } = "";

	[ObservableProperty]
	public partial bool HasBlocked { get; set; }

	[ObservableProperty]
	public partial string NewRelay { get; set; } = "";

	[ObservableProperty]
	public partial bool UseProxy { get; set; }

	[ObservableProperty]
	public partial string ProxyUrl { get; set; } = DefaultRelays.TorProxy;

	[ObservableProperty]
	public partial string? SecretKey { get; set; }

	[ObservableProperty]
	public partial bool IsSecretVisible { get; set; }

	public void OnAppearing()
	{
		_loading = true;
		var settings = session.Current.Store.Settings;
		UseProxy = !string.IsNullOrEmpty(settings.ProxyUrl);
		ProxyUrl = settings.ProxyUrl ?? DefaultRelays.TorProxy;
		_loading = false;
		SecretKey = null;
		IsSecretVisible = false;
		session.Current.ConnectionChanged += RefreshRelays;
		RefreshRelays();
		RefreshMediaServers();
		RefreshBlocked();
	}

	public void OnDisappearing()
	{
		if (session.Messenger is { } messenger)
			messenger.ConnectionChanged -= RefreshRelays;
	}

	void RefreshRelays() => Ui.OnMainThread(() =>
	{
		if (session.Messenger is not { } messenger)
			return;
		var live = messenger.Relays.ToDictionary(r => r.Url);
		Relays.Clear();
		foreach (var url in messenger.Store.Settings.Relays)
			Relays.Add(new RelayItem(url, live.GetValueOrDefault(url)));
	});

	void RefreshMediaServers()
	{
		MediaServers.Clear();
		foreach (var server in session.Current.Store.Settings.MediaServers)
			MediaServers.Add(server);
	}

	void RefreshBlocked()
	{
		Blocked.Clear();
		foreach (var contact in session.Current.Store.BlockedContacts())
			Blocked.Add(new BlockedItem(contact.PubKey, contact.DisplayName));
		HasBlocked = Blocked.Count > 0;
	}

	[RelayCommand]
	async Task AddMediaServerAsync()
	{
		var url = NewMediaServer.Trim();
		if (!url.Contains("://"))
			url = "https://" + url;
		if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("https" or "http"))
		{
			await Ui.Alert("Invalid server", "Media server addresses look like https://blossom.example.com");
			return;
		}
		session.Current.SetMediaServers([.. session.Current.Store.Settings.MediaServers, url]);
		NewMediaServer = "";
		RefreshMediaServers();
	}

	[RelayCommand]
	async Task RemoveMediaServerAsync(string server)
	{
		var remaining = session.Current.Store.Settings.MediaServers.Where(s => s != server).ToList();
		if (remaining.Count == 0)
		{
			await Ui.Alert("Keep at least one server", "Photos, videos and voice messages are stored on these servers.");
			return;
		}
		session.Current.SetMediaServers(remaining);
		RefreshMediaServers();
	}

	[RelayCommand]
	void ResetMediaServers()
	{
		session.Current.SetMediaServers(DefaultRelays.MediaServers);
		RefreshMediaServers();
	}

	[RelayCommand]
	void Unblock(BlockedItem item)
	{
		session.Current.Unblock(item.PubKey);
		RefreshBlocked();
	}

	[RelayCommand]
	async Task AddRelayAsync()
	{
		var url = NewRelay.Trim();
		if (!url.Contains("://"))
			url = "wss://" + url;
		if (!RelayUrl.IsValid(url))
		{
			await Ui.Alert("Invalid relay", "Relay addresses look like wss://relay.example.com (or ws://….onion over Tor).");
			return;
		}
		await session.Current.SetRelaysAsync([.. session.Current.Store.Settings.Relays, url]);
		NewRelay = "";
		RefreshRelays();
	}

	[RelayCommand]
	async Task RemoveRelayAsync(RelayItem relay)
	{
		var remaining = session.Current.Store.Settings.Relays.Where(r => r != relay.Url).ToList();
		if (remaining.Count == 0)
		{
			await Ui.Alert("Keep at least one relay", "Messages travel through relays; without any you can't send or receive.");
			return;
		}
		await session.Current.SetRelaysAsync(remaining);
		RefreshRelays();
	}

	[RelayCommand]
	async Task ResetRelaysAsync()
	{
		await session.Current.SetRelaysAsync(DefaultRelays.All);
		RefreshRelays();
	}

	partial void OnUseProxyChanged(bool value)
	{
		if (!_loading)
			_ = ApplyProxyAsync();
	}

	[RelayCommand]
	async Task ApplyProxyAsync()
	{
		var proxy = UseProxy ? ProxyUrl.Trim() : null;
		if (proxy is not null && !ConnectionSettings.IsValidProxyUrl(proxy))
		{
			await Ui.Alert("Invalid proxy", "Use socks5://127.0.0.1:9050 for Tor (Orbot), or http://host:port.");
			return;
		}
		await session.Current.SetProxyAsync(proxy);
		RefreshRelays();
	}

	[RelayCommand]
	async Task RevealSecretAsync()
	{
		if (IsSecretVisible)
		{
			SecretKey = null;
			IsSecretVisible = false;
			return;
		}
		if (!await Ui.Confirm("Show secret key?", "Anyone who sees this key can read all your messages and pretend to be you. Only write it down somewhere safe; never send it to anyone.", "Show"))
			return;
		SecretKey = await session.RevealSecretKeyAsync();
		IsSecretVisible = SecretKey is not null;
	}

	[RelayCommand]
	async Task CopySecretAsync()
	{
		if (SecretKey is null)
			return;
		await Clipboard.Default.SetTextAsync(SecretKey);
		await Ui.Alert("Copied", "Paste it into your password manager, then clear the clipboard.");
	}

	[RelayCommand]
	async Task DeleteIdentityAsync()
	{
		if (!await Ui.Confirm("Erase everything?", "Your identity and all messages will be erased from this device. Without your backup key this cannot be undone.", "Erase"))
			return;
		OnDisappearing();
		await session.DeleteIdentityAsync();
		await Shell.Current.GoToAsync("//welcome");
	}
}

public sealed record BlockedItem(string PubKey, string Name);

public sealed class RelayItem(string url, RelayConnection? connection)
{
	public string Url { get; } = url;
	public bool IsOnion { get; } = RelayUrl.IsOnion(url);
	public string Status { get; } = connection?.Status switch
	{
		RelayStatus.Connected => "● connected",
		RelayStatus.Connecting => "○ connecting…",
		_ => connection?.LastError is { } error ? $"✕ {error}" : "✕ offline",
	};
	public bool IsConnected { get; } = connection?.Status == RelayStatus.Connected;
}
