using Android.App;
using Android.Content.PM;
using Android.OS;
using Android.Views;

namespace Uncage;

[Activity(Theme = "@style/Maui.SplashTheme", MainLauncher = true, LaunchMode = LaunchMode.SingleTop, ConfigurationChanges = ConfigChanges.ScreenSize | ConfigChanges.Orientation | ConfigChanges.UiMode | ConfigChanges.ScreenLayout | ConfigChanges.SmallestScreenSize | ConfigChanges.Density)]
public class MainActivity : MauiAppCompatActivity
{
	protected override void OnCreate(Bundle? savedInstanceState)
	{
		base.OnCreate(savedInstanceState);
		// Keep conversations out of screenshots, screen recordings and the recent-apps preview.
		Window?.SetFlags(WindowManagerFlags.Secure, WindowManagerFlags.Secure);
	}
}
