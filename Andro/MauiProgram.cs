using Andro.Services;
using Andro.ViewModels;
using Andro.Views;
using Microsoft.Extensions.Logging;

namespace Andro;

public static class MauiProgram
{
	public static MauiApp CreateMauiApp()
	{
		var builder = MauiApp.CreateBuilder();
		builder
			.UseMauiApp<App>()
			.ConfigureFonts(fonts =>
			{
				fonts.AddFont("OpenSans-Regular.ttf", "OpenSansRegular");
				fonts.AddFont("OpenSans-Semibold.ttf", "OpenSansSemibold");
			});

		builder.Services.AddSingleton<ChatSession>();
		builder.Services.AddSingleton<AppShell>();

		builder.Services.AddTransient<LoadingPage>();
		builder.Services.AddTransient<WelcomePage>();
		builder.Services.AddTransient<WelcomeViewModel>();
		builder.Services.AddTransient<ChatsPage>();
		builder.Services.AddTransient<ChatsViewModel>();
		builder.Services.AddTransient<ChatPage>();
		builder.Services.AddTransient<ChatViewModel>();
		builder.Services.AddTransient<AddContactPage>();
		builder.Services.AddTransient<AddContactViewModel>();
		builder.Services.AddTransient<MyIdPage>();
		builder.Services.AddTransient<MyIdViewModel>();
		builder.Services.AddTransient<SettingsPage>();
		builder.Services.AddTransient<SettingsViewModel>();

#if DEBUG
		builder.Logging.AddDebug();
#endif

		return builder.Build();
	}
}
