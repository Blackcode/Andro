using Andro.ViewModels;

namespace Andro.Views;

public partial class ChatsPage : ContentPage
{
	readonly ChatsViewModel _viewModel;

	public ChatsPage(ChatsViewModel viewModel)
	{
		InitializeComponent();
		BindingContext = _viewModel = viewModel;
	}

	protected override void OnAppearing()
	{
		base.OnAppearing();
		_viewModel.OnAppearing();
	}
}
