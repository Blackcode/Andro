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
		try
		{
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
		catch (Exception e)
		{
			await ShowFailureAsync(e);
		}
	}

	protected override void OnDisappearing()
	{
		StopCamera();
		base.OnDisappearing();
	}

	protected override void OnNavigatedFrom(NavigatedFromEventArgs args)
	{
		base.OnNavigatedFrom(args);
		// Release the camera as soon as the page is left; on Android the camera library can
		// crash if frames keep arriving for a page that is being torn down.
		try
		{
			Reader.Handler?.DisconnectHandler();
		}
		catch (Exception e)
		{
			System.Diagnostics.Debug.WriteLine($"Camera release failed: {e}");
		}
	}

	void OnBarcodesDetected(object? sender, BarcodeDetectionEventArgs e)
	{
		if (Volatile.Read(ref _done) == 1)
			return;
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
			try
			{
				StopCamera();
				Vibrate();
				await Shell.Current.GoToAsync("..", new Dictionary<string, object> { ["pubkey"] = pubKey });
			}
			catch (Exception ex)
			{
				await ShowFailureAsync(ex);
			}
		});
	}

	void OnTorchClicked(object? sender, EventArgs e)
	{
		try
		{
			Reader.IsTorchOn = !Reader.IsTorchOn;
			TorchButton.Text = Reader.IsTorchOn ? "Turn off light" : "Turn on light";
		}
		catch (Exception ex)
		{
			System.Diagnostics.Debug.WriteLine($"Torch failed: {ex}");
			TorchButton.IsVisible = false;
		}
	}

	void StopCamera()
	{
		try
		{
			Reader.IsDetecting = false;
			if (Reader.IsTorchOn)
				Reader.IsTorchOn = false;
		}
		catch (Exception e)
		{
			System.Diagnostics.Debug.WriteLine($"Stopping camera failed: {e}");
		}
	}

	static void Vibrate()
	{
		// Nice to have only: never let a missing permission or motor break the scan.
		try
		{
			HapticFeedback.Default.Perform(HapticFeedbackType.Click);
		}
		catch (Exception e)
		{
			System.Diagnostics.Debug.WriteLine($"Haptic feedback failed: {e}");
		}
	}

	async Task ShowFailureAsync(Exception e)
	{
		System.Diagnostics.Debug.WriteLine($"Scanner failed: {e}");
		Hint.Text = $"The scanner stopped: {e.Message}\nYou can go back and paste the ID instead.";
		await Task.CompletedTask;
	}
}
