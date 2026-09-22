using Uncage.Services;
using Uncage.ViewModels;
using Uncage.Views;
using Microsoft.Extensions.Logging;
using CommunityToolkit.Maui;
using ZXing.Net.Maui.Controls;

namespace Uncage;

public static class MauiProgram
{
	public static MauiApp CreateMauiApp()
	{
		var builder = MauiApp.CreateBuilder();
		// MediaElement is supported on every OS version this app targets (Android 8.0+, iOS 15+, Windows 10 1809+);
		// the analyzer only complains in the platform-neutral compile check.
#pragma warning disable CA1416
		builder
			.UseMauiApp<App>()
			// In-app video playback; no background playback service (it would add notification permissions).
			// Supported on every platform version this app targets (Android 8.0+).
			.UseMauiCommunityToolkitMediaElement(false)
			.UseBarcodeReader()
			.ConfigureFonts(fonts =>
			{
				fonts.AddFont("OpenSans-Regular.ttf", "OpenSansRegular");
				fonts.AddFont("OpenSans-Semibold.ttf", "OpenSansSemibold");
				fonts.AddFont("MaterialIcons-Regular.ttf", "MaterialIcons");
			});
#pragma warning restore CA1416

		builder.Services.AddSingleton<ChatSession>();

		builder.Services.AddTransient<LoadingPage>();
		builder.Services.AddTransient<WelcomePage>();
		builder.Services.AddTransient<WelcomeViewModel>();
		builder.Services.AddTransient<ChatsPage>();
		builder.Services.AddTransient<ChatsViewModel>();
		builder.Services.AddTransient<ChatPage>();
		builder.Services.AddTransient<ChatViewModel>();
		builder.Services.AddTransient<AddContactPage>();
		builder.Services.AddTransient<AddContactViewModel>();
		builder.Services.AddTransient<ScanPage>();
		builder.Services.AddTransient<MediaViewerPage>();
		builder.Services.AddTransient<MyIdPage>();
		builder.Services.AddTransient<MyIdViewModel>();
		builder.Services.AddTransient<SettingsPage>();
		builder.Services.AddTransient<SettingsViewModel>();

		RemoveTextFieldUnderlines();

#if DEBUG
		builder.Logging.AddDebug();
#endif

		return builder.Build();
	}

	/// <summary>Text fields sit inside rounded "pills", like WhatsApp; drop the platform underline/border.</summary>
	static void RemoveTextFieldUnderlines()
	{
		Microsoft.Maui.Handlers.EntryHandler.Mapper.AppendToMapping("Borderless", (handler, _) =>
		{
#if ANDROID
			handler.PlatformView.BackgroundTintList = Android.Content.Res.ColorStateList.ValueOf(Android.Graphics.Color.Transparent);
#elif IOS || MACCATALYST
			handler.PlatformView.BorderStyle = UIKit.UITextBorderStyle.None;
#elif WINDOWS
			handler.PlatformView.BorderThickness = new Microsoft.UI.Xaml.Thickness(0);
#endif
		});
		Microsoft.Maui.Handlers.EditorHandler.Mapper.AppendToMapping("Borderless", (handler, _) =>
		{
#if ANDROID
			handler.PlatformView.BackgroundTintList = Android.Content.Res.ColorStateList.ValueOf(Android.Graphics.Color.Transparent);
#elif WINDOWS
			handler.PlatformView.BorderThickness = new Microsoft.UI.Xaml.Thickness(0);
#endif
		});
	}
}
