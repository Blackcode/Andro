using Andro.Services;

namespace Andro.Views;

/// <summary>First screen: loads the saved identity, then goes to Chats (or Welcome on first run).</summary>
public partial class LoadingPage : ContentPage
{
	readonly ChatSession _session;
	bool _started;

	public LoadingPage(ChatSession session)
	{
		InitializeComponent();
		_session = session;
	}

	protected override void OnAppearing()
	{
		base.OnAppearing();
		if (_started)
			return;
		_started = true;
		// Navigate after the first layout pass: changing the Shell's current item while it is still
		// being shown fails on some platforms (notably Windows).
		Dispatcher.Dispatch(async () => await StartAsync());
	}

	async Task StartAsync()
	{
		Spinner.IsRunning = true;
		ErrorText.IsVisible = RetryButton.IsVisible = false;
		try
		{
			var hasIdentity = await _session.TryResumeAsync();
			await Shell.Current.GoToAsync(hasIdentity ? "//chats" : "//welcome");
		}
		catch (Exception e)
		{
			// Never let a startup problem close the app silently; show it so it can be reported.
			System.Diagnostics.Debug.WriteLine($"Startup failed: {e}");
			Spinner.IsRunning = false;
			ErrorText.Text = $"Andro could not start:\n{e.GetType().Name}: {e.Message}";
			ErrorText.IsVisible = RetryButton.IsVisible = true;
		}
	}

	async void OnRetryClicked(object? sender, EventArgs e) => await StartAsync();
}
