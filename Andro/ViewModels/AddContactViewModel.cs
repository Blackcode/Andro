using Andro.Core.Crypto;
using Andro.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Andro.ViewModels;

public partial class AddContactViewModel(ChatSession session) : ObservableObject, IQueryAttributable
{
	/// <summary>Receives the ID found by the QR scanner.</summary>
	public void ApplyQueryAttributes(IDictionary<string, object> query)
	{
		if (query.TryGetValue("pubkey", out var value) && value is string pubKey && Nip19.TryParsePublicKey(pubKey) is { } hex)
			PublicKey = Nip19.EncodeNpub(hex);
	}

	[RelayCommand]
	static Task ScanAsync() => Shell.Current.GoToAsync("scan");

	[ObservableProperty]
	public partial string PublicKey { get; set; } = "";

	[ObservableProperty]
	public partial string Name { get; set; } = "";

	[RelayCommand]
	async Task PasteAsync()
	{
		var text = await Clipboard.Default.GetTextAsync();
		if (!string.IsNullOrWhiteSpace(text))
			PublicKey = text.Trim();
	}

	[RelayCommand]
	async Task SaveAsync()
	{
		var pubKey = Nip19.TryParsePublicKey(PublicKey);
		if (pubKey is null)
		{
			await Ui.Alert("Invalid ID", "Paste the ID your contact shared with you. It starts with npub1.");
			return;
		}
		if (!session.Current.AddContact(pubKey, Name))
		{
			await Ui.Alert("That's you", "This is your own ID. Share it with others so they can add you.");
			return;
		}
		PublicKey = "";
		Name = "";
		await Shell.Current.GoToAsync($"../chat?peer={pubKey}");
	}
}
