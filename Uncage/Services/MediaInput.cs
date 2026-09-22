using Plugin.Maui.Audio;

namespace Uncage.Services;

/// <summary>A picked or recorded file, ready to send.</summary>
public sealed record MediaFile(byte[] Content, string MimeType, double? DurationSeconds = null);

/// <summary>Taking and picking photos, videos and audio files.</summary>
public static class MediaInput
{
	/// <summary>
	/// Photos are resized and re-encoded on the device: smaller uploads (which matters over Tor or slow,
	/// throttled networks), correct rotation, and no EXIF metadata such as GPS location or camera serial.
	/// </summary>
	static MediaPickerOptions PhotoOptions(string title) => new()
	{
		Title = title,
		MaximumWidth = 1600,
		MaximumHeight = 1600,
		CompressionQuality = 80,
		RotateImage = true,
		PreserveMetaData = false,
	};

	public static async Task<MediaFile?> CapturePhotoAsync() =>
		await ReadAsync(await MediaPicker.Default.CapturePhotoAsync(PhotoOptions("Take photo")), "image/jpeg");

	/// <summary>Up to 10 photos at once, like WhatsApp.</summary>
	public static async Task<IReadOnlyList<MediaFile>> PickPhotosAsync()
	{
		var options = PhotoOptions("Send photos");
		options.SelectionLimit = 10;
		return await ReadAllAsync(await MediaPicker.Default.PickPhotosAsync(options), "image/jpeg");
	}

	public static async Task<MediaFile?> CaptureVideoAsync() =>
		await ReadAsync(await MediaPicker.Default.CaptureVideoAsync(new MediaPickerOptions { Title = "Record video" }), "video/mp4");

	public static async Task<IReadOnlyList<MediaFile>> PickVideosAsync() =>
		await ReadAllAsync(await MediaPicker.Default.PickVideosAsync(new MediaPickerOptions { Title = "Send videos", SelectionLimit = 10 }), "video/mp4");

	static async Task<IReadOnlyList<MediaFile>> ReadAllAsync(IEnumerable<FileResult>? files, string fallbackType)
	{
		var result = new List<MediaFile>();
		foreach (var file in files ?? [])
		{
			if (await ReadAsync(file, fallbackType) is { } media)
				result.Add(media);
		}
		return result;
	}

	public static async Task<MediaFile?> PickAudioAsync()
	{
		var types = new FilePickerFileType(new Dictionary<DevicePlatform, IEnumerable<string>>
		{
			[DevicePlatform.Android] = ["audio/*"],
			[DevicePlatform.iOS] = ["public.audio"],
			[DevicePlatform.MacCatalyst] = ["public.audio"],
			[DevicePlatform.WinUI] = [".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac"],
		});
		return await ReadAsync(await FilePicker.Default.PickAsync(new PickOptions { PickerTitle = "Send audio", FileTypes = types }), "audio/mpeg");
	}

	static async Task<MediaFile?> ReadAsync(FileResult? file, string fallbackType)
	{
		if (file is null)
			return null;
		await using var stream = await file.OpenReadAsync();
		using var buffer = new MemoryStream();
		await stream.CopyToAsync(buffer);
		var type = string.IsNullOrWhiteSpace(file.ContentType) ? TypeFromExtension(file.FileName) ?? fallbackType : file.ContentType;
		return new MediaFile(buffer.ToArray(), type.ToLowerInvariant());
	}

	static string? TypeFromExtension(string name) => Path.GetExtension(name).ToLowerInvariant() switch
	{
		".jpg" or ".jpeg" => "image/jpeg",
		".png" => "image/png",
		".webp" => "image/webp",
		".heic" => "image/heic",
		".mp4" => "video/mp4",
		".mov" => "video/quicktime",
		".3gp" => "video/3gpp",
		".webm" => "video/webm",
		".mp3" => "audio/mpeg",
		".m4a" or ".aac" => "audio/mp4",
		".ogg" or ".opus" => "audio/ogg",
		".wav" => "audio/wav",
		".flac" => "audio/flac",
		_ => null,
	};

	public static string ExtensionFor(string mimeType) => mimeType switch
	{
		"image/png" => ".png",
		"image/webp" => ".webp",
		"image/heic" => ".heic",
		var t when t.StartsWith("image/") => ".jpg",
		"video/quicktime" => ".mov",
		"video/webm" => ".webm",
		"video/3gpp" => ".3gp",
		var t when t.StartsWith("video/") => ".mp4",
		"audio/mpeg" => ".mp3",
		"audio/ogg" => ".ogg",
		"audio/wav" => ".wav",
		"audio/flac" => ".flac",
		var t when t.StartsWith("audio/") => ".m4a",
		_ => ".bin",
	};
}

/// <summary>Records a voice message (AAC in an .m4a container, like WhatsApp's voice notes).</summary>
public sealed class VoiceRecorder
{
	IAudioRecorder? _recorder;
	DateTime _started;
	bool _isAac;

	public bool IsRecording => _recorder?.IsRecording == true;

	public TimeSpan Elapsed => IsRecording ? DateTime.UtcNow - _started : TimeSpan.Zero;

	/// <returns>False if the microphone isn't available or permission was refused.</returns>
	public async Task<bool> StartAsync()
	{
		var status = await Permissions.CheckStatusAsync<Permissions.Microphone>();
		if (status != PermissionStatus.Granted)
			status = await Permissions.RequestAsync<Permissions.Microphone>();
		if (status != PermissionStatus.Granted)
			return false;

		_recorder = AudioManager.Current.CreateRecorder();
		if (!_recorder.CanRecordAudio)
			return false;
		// Compact AAC where available (Android 12+ and all other platforms); WAV on older Android.
		_isAac = !OperatingSystem.IsAndroid() || OperatingSystem.IsAndroidVersionAtLeast(31);
		await _recorder.StartAsync(new AudioRecorderOptions
		{
#pragma warning disable CA1416 // Guarded by the platform check above.
			Encoding = _isAac ? Plugin.Maui.Audio.Encoding.Aac : Plugin.Maui.Audio.Encoding.Wav,
#pragma warning restore CA1416
			ThrowIfNotSupported = false,
		});
		_started = DateTime.UtcNow;
		return true;
	}

	/// <summary>Stops and returns the recording, or null if it was too short to be meant.</summary>
	public async Task<MediaFile?> StopAsync()
	{
		if (_recorder is not { IsRecording: true } recorder)
			return null;
		var duration = DateTime.UtcNow - _started;
		var source = await recorder.StopAsync();
		_recorder = null;
		if (duration < TimeSpan.FromSeconds(1))
			return null;
		await using var stream = source.GetAudioStream();
		using var buffer = new MemoryStream();
		await stream.CopyToAsync(buffer);
		DeleteTempFile(source);
		return new MediaFile(buffer.ToArray(), _isAac ? "audio/mp4" : "audio/wav", Math.Round(duration.TotalSeconds, 1));
	}

	public async Task CancelAsync()
	{
		if (_recorder is not { IsRecording: true } recorder)
			return;
		var source = await recorder.StopAsync();
		_recorder = null;
		DeleteTempFile(source);
	}

	// The plugin records to a temporary file; don't leave an unencrypted copy behind.
	static void DeleteTempFile(IAudioSource source)
	{
		if (source is FileAudioSource file && File.Exists(file.GetFilePath()))
			File.Delete(file.GetFilePath());
	}
}
