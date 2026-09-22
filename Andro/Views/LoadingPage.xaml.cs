using Andro.Services;

namespace Andro.Views;

public partial class LoadingPage : ContentPage
{
	readonly ChatSession _session;

	public LoadingPage(ChatSession session)
	{
		InitializeComponent();
		_session = session;
	}

	protected override async void OnAppearing()
	{
		base.OnAppearing();
		var hasIdentity = await _session.TryResumeAsync();
		await Shell.Current.GoToAsync(hasIdentity ? "//chats" : "//welcome");
	}
}
