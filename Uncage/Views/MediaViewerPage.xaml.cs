using CommunityToolkit.Maui.Views;
using Uncage.Core.Chat;
using Uncage.Core.Media;
using Uncage.Services;

namespace Uncage.Views;

/// <summary>Full-screen photo, or in-app video playback. Decryption happens on the device.</summary>
public partial class MediaViewerPage : ContentPage, IQueryAttributable
{
	readonly ChatSession _session;
	ChatMessage? _message;
	string? _tempFile;

	public MediaViewerPage(ChatSession session)
	{
		InitializeComponent();
		_session = session;
	}

	public void ApplyQueryAttributes(IDictionary<string, object> query)
	{
		_message = query.TryGetValue("message", out var m) ? m as ChatMessage : null;
		Title = _message?.Time.ToLocalTime().ToString("g") ?? "";
	}

	protected override async void OnAppearing()
	{
		base.OnAppearing();
		if (_message?.Attachment is not { } attachment || _session.Messenger is not { } messenger || Photo.IsVisible || Player.IsVisible)
			return;
		try
		{
			var bytes = await messenger.GetAttachmentAsync(attachment);
			if (bytes is null)
			{
				Show("This file couldn't be downloaded from any of its servers. They may be blocked where you are: try turning on Tor in Settings.");
				return;
			}
			if (attachment.Kind == AttachmentKind.Image)
			{
				Photo.Source = ImageSource.FromStream(() => new MemoryStream(bytes));
				Photo.IsVisible = true;
			}
			else
			{
				// The video player needs a file. It's a temporary decrypted copy, deleted when leaving this page.
				_tempFile = Path.Combine(FileSystem.CacheDirectory, "play-" + Guid.NewGuid().ToString("N") + MediaInput.ExtensionFor(attachment.MimeType));
				await File.WriteAllBytesAsync(_tempFile, bytes);
				Player.Source = MediaSource.FromFile(_tempFile);
				Player.IsVisible = true;
			}
			Spinner.IsRunning = Spinner.IsVisible = false;
		}
		catch (Exception e)
		{
			Show($"Couldn't open this file: {e.Message}");
		}
	}

	void Show(string error)
	{
		Spinner.IsRunning = Spinner.IsVisible = false;
		ErrorText.Text = error;
		ErrorText.IsVisible = true;
	}

	protected override void OnNavigatedFrom(NavigatedFromEventArgs args)
	{
		base.OnNavigatedFrom(args);
		try
		{
			Player.Stop();
			Player.Handler?.DisconnectHandler();
		}
		catch (Exception)
		{
			// Player was never started.
		}
		if (_tempFile is not null && File.Exists(_tempFile))
			File.Delete(_tempFile);
	}
}
