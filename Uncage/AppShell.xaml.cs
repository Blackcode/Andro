using Uncage.Views;

namespace Uncage;

public partial class AppShell : Shell
{
	public AppShell()
	{
		InitializeComponent();
		Routing.RegisterRoute("chat", typeof(ChatPage));
		Routing.RegisterRoute("addcontact", typeof(AddContactPage));
		Routing.RegisterRoute("scan", typeof(ScanPage));
		Routing.RegisterRoute("media", typeof(MediaViewerPage));
	}
}
