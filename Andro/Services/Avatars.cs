namespace Andro.Services;

/// <summary>Stable, readable avatar colors derived from a public key, so a contact always gets the same one.</summary>
static class Avatars
{
	static readonly Color[] Palette =
	[
		Color.FromArgb("#00A884"), Color.FromArgb("#53BDEB"), Color.FromArgb("#FC9775"), Color.FromArgb("#A791FF"),
		Color.FromArgb("#FF72A1"), Color.FromArgb("#25D366"), Color.FromArgb("#FFBC38"), Color.FromArgb("#5F66CD"),
		Color.FromArgb("#02A698"), Color.FromArgb("#E26AB6"),
	];

	public static Color ColorFor(string pubKey) =>
		Palette[Convert.ToInt32(pubKey[..2], 16) % Palette.Length];

	public static string InitialFor(string name) =>
		string.IsNullOrWhiteSpace(name) ? "?" : char.ToUpperInvariant(name.TrimStart()[0]).ToString();
}
