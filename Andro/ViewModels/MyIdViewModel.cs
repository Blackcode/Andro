using Andro.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using QRCoder;

namespace Andro.ViewModels;

public partial class MyIdViewModel(ChatSession session) : ObservableObject
{
	[ObservableProperty]
	public partial string Npub { get; set; } = "";

	[ObservableProperty]
	public partial string ShortId { get; set; } = "";

	[ObservableProperty]
	public partial Color AvatarColor { get; set; } = Colors.Gray;

	[ObservableProperty]
	public partial ImageSource? QrCode { get; set; }

	public void OnAppearing()
	{
		if (session.Messenger is not { } messenger || Npub == messenger.Npub)
			return;
		Npub = messenger.Npub;
		ShortId = Core.Chat.Contact.ShortNpub(messenger.PubKey);
		AvatarColor = Avatars.ColorFor(messenger.PubKey);
		var png = new PngByteQRCode(QRCodeGenerator.GenerateQrCode("nostr:" + Npub, QRCodeGenerator.ECCLevel.M)).GetGraphic(12);
		QrCode = ImageSource.FromStream(() => new MemoryStream(png));
	}

	[RelayCommand]
	async Task CopyAsync()
	{
		await Clipboard.Default.SetTextAsync(Npub);
		await Ui.Alert("Copied", "Your ID is on the clipboard.");
	}

	[RelayCommand]
	Task ShareAsync() => Share.Default.RequestAsync(new ShareTextRequest
	{
		Title = "My Andro ID",
		Text = $"Chat with me privately on Andro: {Npub}",
	});

	[RelayCommand]
	static Task ScanAsync() => Shell.Current.GoToAsync("scan?next=addcontact");
}
