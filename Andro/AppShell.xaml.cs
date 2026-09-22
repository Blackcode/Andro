using Andro.Views;

namespace Andro;

public partial class AppShell : Shell
{
	public AppShell()
	{
		InitializeComponent();
		Routing.RegisterRoute("chat", typeof(ChatPage));
		Routing.RegisterRoute("addcontact", typeof(AddContactPage));
		Routing.RegisterRoute("scan", typeof(ScanPage));
		Routing.RegisterRoute("myid", typeof(MyIdPage));
		Routing.RegisterRoute("settings", typeof(SettingsPage));
	}
}
