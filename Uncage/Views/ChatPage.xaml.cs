using Uncage.ViewModels;

namespace Uncage.Views;

public partial class ChatPage : ContentPage
{
	readonly ChatViewModel _viewModel;

	public ChatPage(ChatViewModel viewModel)
	{
		InitializeComponent();
		BindingContext = _viewModel = viewModel;
		_viewModel.ScrollRequested += row => MessageList.ScrollTo(row, position: ScrollToPosition.End, animate: false);
	}

	protected override void OnAppearing()
	{
		base.OnAppearing();
		_viewModel.OnAppearing();
	}

	protected override void OnDisappearing()
	{
		base.OnDisappearing();
		_viewModel.OnDisappearing();
	}
}

public sealed class ChatRowTemplateSelector : DataTemplateSelector
{
	public DataTemplate? Date { get; set; }
	public DataTemplate? Incoming { get; set; }
	public DataTemplate? Outgoing { get; set; }

	protected override DataTemplate OnSelectTemplate(object item, BindableObject container) => (item switch
	{
		DateItem => Date,
		MessageItem { IsOutgoing: true } => Outgoing,
		_ => Incoming,
	})!;
}
