using Uncage.Services;
using Uncage.ViewModels;
using Uncage.Views;
using Microsoft.Extensions.Logging;
using ZXing.Net.Maui.Controls;

namespace Uncage;

public static class MauiProgram
{
	public static MauiApp CreateMauiApp()
	{
		var builder = MauiApp.CreateBuilder();
		builder
			.UseMauiApp<App>()
			.UseBarcodeReader()
			.ConfigureFonts(fonts =>
			{
				fonts.AddFont("OpenSans-Regular.ttf", "OpenSansRegular");
				fonts.AddFont("OpenSans-Semibold.ttf", "OpenSansSemibold");
				fonts.AddFont("MaterialIcons-Regular.ttf", "MaterialIcons");
			});

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
