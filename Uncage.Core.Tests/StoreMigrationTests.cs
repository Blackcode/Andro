using System.Text;
using Uncage.Core.Chat;
using Uncage.Core.Crypto;

namespace Uncage.Core.Tests;

/// <summary>History files written by older versions must still open with sensible settings.</summary>
public class StoreMigrationTests
{
	[Theory]
	[InlineData("{\"Settings\":{\"Relays\":[\"wss://relay.example\"],\"ProxyUrl\":null}}")]
	[InlineData("{\"Settings\":{\"Relays\":null,\"MediaServers\":null}}")]
	[InlineData("{\"Settings\":null}")]
	[InlineData("{}")]
	public void MissingSettingsFallBackToDefaults(string json)
	{
		var path = Path.Combine(Path.GetTempPath(), $"uncage-{Guid.NewGuid():N}.bin");
		try
		{
			var key = Messenger.NewStorageKey();
			File.WriteAllBytes(path, SecretBox.Seal(Encoding.UTF8.GetBytes(json), key));
			var store = ChatStore.Open(path, key);
			Assert.NotNull(store.Settings);
			Assert.NotEmpty(store.Settings.Relays);
			Assert.Equal(DefaultRelays.MediaServers, store.Settings.MediaServers);
			Assert.Empty(store.Contacts());
			Assert.Empty(store.Conversations());
		}
		finally
		{
			File.Delete(path);
		}
	}
}
