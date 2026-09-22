namespace Andro.Services;

static class Ui
{
	public static Page? CurrentPage => Shell.Current?.CurrentPage;

	public static Task Alert(string title, string message) =>
		CurrentPage?.DisplayAlertAsync(title, message, "OK") ?? Task.CompletedTask;

	public static Task<bool> Confirm(string title, string message, string accept, string cancel = "Cancel") =>
		CurrentPage?.DisplayAlertAsync(title, message, accept, cancel) ?? Task.FromResult(false);

	public static void OnMainThread(Action action)
	{
		if (MainThread.IsMainThread)
			action();
		else
			MainThread.BeginInvokeOnMainThread(action);
	}

	public static string FormatTime(DateTimeOffset time)
	{
		var local = time.ToLocalTime();
		var today = DateTimeOffset.Now.Date;
		if (local.Date == today)
			return local.ToString("t");
		if (local.Date > today.AddDays(-7))
			return local.ToString("ddd");
		return local.ToString("d");
	}
}
