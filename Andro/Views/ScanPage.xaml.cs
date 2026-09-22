using Andro.Core.Crypto;
using ZXing.Net.Maui;

namespace Andro.Views;

/// <summary>
/// Reads a contact's ID from a QR code (their My ID screen, or an npub/nprofile QR from any Nostr app)
/// and returns it to the page that opened the scanner as the "pubkey" query parameter.
/// Decoding happens on the device; nothing leaves the phone.
/// </summary>
public partial class ScanPage : ContentPage
{
	int _done;

	public ScanPage()
	{
		InitializeComponent();
		Reader.Options = new BarcodeReaderOptions
		{
			Formats = BarcodeFormat.QrCode,
			AutoRotate = true,
			Multiple = false,
		};
	}

	protected override async void OnAppearing()
	{
		base.OnAppearing();
		var status = await Permissions.CheckStatusAsync<Permissions.Camera>();
		if (status != PermissionStatus.Granted)
			status = await Permissions.RequestAsync<Permissions.Camera>();
		if (status != PermissionStatus.Granted)
		{
			await DisplayAlertAsync("Camera access needed",
				"Allow camera access in your phone's settings to scan QR codes. You can also paste the ID instead.", "OK");
			await Shell.Current.GoToAsync("..");
			return;
		}
		Reader.IsDetecting = true;
	}

	protected override void OnDisappearing()
	{
		Reader.IsDetecting = false;
		Reader.IsTorchOn = false;
		base.OnDisappearing();
	}

	void OnBarcodesDetected(object? sender, BarcodeDetectionEventArgs e)
	{
		var pubKey = e.Results.Select(r => Nip19.TryParsePublicKey(r.Value)).FirstOrDefault(k => k is not null);
		if (pubKey is null)
		{
			MainThread.BeginInvokeOnMainThread(() => Hint.Text = "That QR code isn't a contact ID. Ask them to open My ID.");
			return;
		}
		if (Interlocked.Exchange(ref _done, 1) == 1)
			return;

		MainThread.BeginInvokeOnMainThread(async () =>
		{
			Reader.IsDetecting = false;
			try
			{
				HapticFeedback.Default.Perform(HapticFeedbackType.Click);
			}
			catch (FeatureNotSupportedException)
			{
			}
			await Shell.Current.GoToAsync("..", new Dictionary<string, object> { ["pubkey"] = pubKey });
		});
	}

	void OnTorchClicked(object? sender, EventArgs e)
	{
		Reader.IsTorchOn = !Reader.IsTorchOn;
		TorchButton.Text = Reader.IsTorchOn ? "Turn off light" : "Turn on light";
	}
}
