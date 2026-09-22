using Uncage.ViewModels;

namespace Uncage.Views;

public partial class MyIdPage : ContentPage
{
	readonly MyIdViewModel _viewModel;

	public MyIdPage(MyIdViewModel viewModel)
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
