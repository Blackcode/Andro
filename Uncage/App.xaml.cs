using AndroidPlatform = Microsoft.Maui.Controls.PlatformConfiguration.Android;
using AndroidSpecific = Microsoft.Maui.Controls.PlatformConfiguration.AndroidSpecific;

namespace Uncage;

public partial class App : Microsoft.Maui.Controls.Application
{
	public App()
	{
		InitializeComponent();
		// On Android the keyboard would otherwise "pan" the whole window up, pushing the header and
		// newest messages off screen. With Resize the window stays put and pages with
		// SafeAreaEdges="All" pad themselves above the keyboard instead. Fully qualified because
		// inside the Android build "Android" and "Application" also name Android SDK types.
		AndroidSpecific.Application.UseWindowSoftInputModeAdjust(
			On<AndroidPlatform>(), AndroidSpecific.WindowSoftInputModeAdjust.Resize);
	}

	protected override Window CreateWindow(IActivationState? activationState) => new(new AppShell());
}
