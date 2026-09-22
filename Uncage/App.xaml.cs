using AndroidPlatform = Microsoft.Maui.Controls.PlatformConfiguration.Android;
using AndroidSpecific = Microsoft.Maui.Controls.PlatformConfiguration.AndroidSpecific;

namespace Uncage;

public partial class App : Microsoft.Maui.Controls.Application
{
	public App()
	{
		InitializeComponent();
		// Keep the message box visible above the keyboard. Fully qualified because inside the
		// Android build "Android" and "Application" also name Android SDK types.
		AndroidSpecific.Application.UseWindowSoftInputModeAdjust(
			On<AndroidPlatform>(), AndroidSpecific.WindowSoftInputModeAdjust.Resize);
	}

	protected override Window CreateWindow(IActivationState? activationState) => new(new AppShell());
}
