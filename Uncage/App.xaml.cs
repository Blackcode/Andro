namespace Uncage;

public partial class App : Microsoft.Maui.Controls.Application
{
	public App()
	{
		// Keeping text boxes above the keyboard is done per page with SafeAreaEdges="All"
		// (Android 15+ draws apps edge to edge, so the old adjustResize setting no longer applies).
		InitializeComponent();
	}

	protected override Window CreateWindow(IActivationState? activationState) => new(new AppShell());
}
