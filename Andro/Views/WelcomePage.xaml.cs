using Andro.ViewModels;

namespace Andro.Views;

public partial class WelcomePage : ContentPage
{
	public WelcomePage(WelcomeViewModel viewModel)
	{
		InitializeComponent();
		BindingContext = viewModel;
	}
}
