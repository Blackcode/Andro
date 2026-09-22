using Andro.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Andro.ViewModels;

public partial class WelcomeViewModel(ChatSession session) : ObservableObject
{
	[ObservableProperty]
	public partial string BackupKey { get; set; } = "";

	[ObservableProperty]
	public partial bool IsBusy { get; set; }

	[RelayCommand]
	async Task CreateAsync()
	{
		IsBusy = true;
		try
		{
			await session.CreateIdentityAsync();
			await Shell.Current.GoToAsync("//chats");
		}
		catch (Exception e)
		{
			await Ui.Alert("Could not create identity", $"{e.GetType().Name}: {e.Message}");
		}
		finally
		{
			IsBusy = false;
		}
	}

	[RelayCommand]
	async Task RestoreAsync()
	{
		if (string.IsNullOrWhiteSpace(BackupKey))
		{
			await Ui.Alert("Backup key needed", "Paste the secret key (nsec1…) you saved from your previous device.");
			return;
		}
		IsBusy = true;
		try
		{
			await session.RestoreIdentityAsync(BackupKey);
			BackupKey = "";
			await Shell.Current.GoToAsync("//chats");
		}
		catch (Exception e) when (e is FormatException or ArgumentException)
		{
			await Ui.Alert("Invalid key", "That is not a valid secret key. It should start with nsec1.");
		}
		catch (Exception e)
		{
			await Ui.Alert("Could not restore identity", $"{e.GetType().Name}: {e.Message}");
		}
		finally
		{
			IsBusy = false;
		}
	}
}
