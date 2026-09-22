using Andro.ViewModels;

namespace Andro.Views;

public partial class ChatPage : ContentPage
{
	readonly ChatViewModel _viewModel;

	public ChatPage(ChatViewModel viewModel)
	{
		InitializeComponent();
		BindingContext = _viewModel = viewModel;
		_viewModel.ScrollRequested += item => MessageList.ScrollTo(item, position: ScrollToPosition.End, animate: false);
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

public sealed class MessageTemplateSelector : DataTemplateSelector
{
	public DataTemplate? Incoming { get; set; }
	public DataTemplate? Outgoing { get; set; }

	protected override DataTemplate OnSelectTemplate(object item, BindableObject container) =>
		(item is MessageItem { IsOutgoing: true } ? Outgoing : Incoming)!;
}
