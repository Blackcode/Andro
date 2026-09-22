namespace Uncage.Services;

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

	/// <summary>Chat list style: time today, "Yesterday", weekday this week, otherwise the date.</summary>
	public static string FormatTime(DateTimeOffset time)
	{
		var local = time.ToLocalTime();
		var today = DateTimeOffset.Now.Date;
		if (local.Date == today)
			return local.ToString("t");
		if (local.Date == today.AddDays(-1))
			return "Yesterday";
		if (local.Date > today.AddDays(-7))
			return local.ToString("dddd");
		return local.ToString("d");
	}

	/// <summary>Date separator inside a chat: "Today", "Yesterday", weekday, or a full date.</summary>
	public static string FormatDay(DateTimeOffset time)
	{
		var day = time.ToLocalTime().Date;
		var today = DateTimeOffset.Now.Date;
		if (day == today)
			return "Today";
		if (day == today.AddDays(-1))
			return "Yesterday";
		if (day > today.AddDays(-7))
			return day.ToString("dddd");
		return day.ToString("D");
	}
}
