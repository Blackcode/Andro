using Android.App;
using Android.Content.PM;
using Android.Content.Res;
using Android.OS;
using Android.Views;
using AndroidX.Core.View;

namespace Uncage;

[Activity(Theme = "@style/Maui.SplashTheme", MainLauncher = true, LaunchMode = LaunchMode.SingleTop, ConfigurationChanges = ConfigChanges.ScreenSize | ConfigChanges.Orientation | ConfigChanges.UiMode | ConfigChanges.ScreenLayout | ConfigChanges.SmallestScreenSize | ConfigChanges.Density)]
public class MainActivity : MauiAppCompatActivity
{
	protected override void OnCreate(Bundle? savedInstanceState)
	{
		base.OnCreate(savedInstanceState);
#if !DEBUG
		// Keep conversations out of screenshots, screen recordings and the recent-apps preview.
		// Debug builds allow screenshots so problems can be captured while developing.
		Window?.SetFlags(WindowManagerFlags.Secure, WindowManagerFlags.Secure);
#endif
		UpdateStatusBarIcons();
	}

	public override void OnConfigurationChanged(Configuration newConfig)
	{
		base.OnConfigurationChanged(newConfig);
		UpdateStatusBarIcons();
	}

	/// <summary>Dark status bar icons on the white light-mode header, light icons in dark mode (like WhatsApp).</summary>
	void UpdateStatusBarIcons()
	{
		if (Window is not { } window)
			return;
		var isNight = (Resources?.Configuration?.UiMode & UiMode.NightMask) == UiMode.NightYes;
		if (WindowCompat.GetInsetsController(window, window.DecorView) is not { } controller)
			return;
		controller.AppearanceLightStatusBars = !isNight;
		controller.AppearanceLightNavigationBars = !isNight;
	}
}
