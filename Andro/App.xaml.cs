using AndroidPlatform = Microsoft.Maui.Controls.PlatformConfiguration.Android;
using AndroidSpecific = Microsoft.Maui.Controls.PlatformConfiguration.AndroidSpecific;

namespace Andro;

public partial class App : Microsoft.Maui.Controls.Application
{
	readonly AppShell _shell;

	public App(AppShell shell)
	{
		InitializeComponent();
		_shell = shell;
		// Keep the message box visible above the keyboard. Fully qualified because inside the
		// Android build "Android" and "Application" also name Android SDK types.
		AndroidSpecific.Application.UseWindowSoftInputModeAdjust(
			On<AndroidPlatform>(), AndroidSpecific.WindowSoftInputModeAdjust.Resize);
	}

	protected override Window CreateWindow(IActivationState? activationState) => new(_shell);
}
