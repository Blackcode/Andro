using Microsoft.Maui.Controls.PlatformConfiguration;
using Microsoft.Maui.Controls.PlatformConfiguration.AndroidSpecific;

namespace Andro;

public partial class App : Microsoft.Maui.Controls.Application
{
	readonly AppShell _shell;

	public App(AppShell shell)
	{
		InitializeComponent();
		_shell = shell;
		// Keep the message box visible above the keyboard.
		On<Android>().UseWindowSoftInputModeAdjust(WindowSoftInputModeAdjust.Resize);
	}

	protected override Window CreateWindow(IActivationState? activationState) => new(_shell);
}
