using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Uncage.Core.Crypto;

namespace Uncage.Core.Tests;

/// <summary>Official NIP-44 v2 vectors from https://github.com/paulmillr/nip44.</summary>
public class Nip44Tests
{
	static readonly JsonElement V2 = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Vectors", "nip44.vectors.json"))).RootElement.GetProperty("v2");

	static IEnumerable<JsonElement> Valid(string name) => V2.GetProperty("valid").GetProperty(name).EnumerateArray();
	static IEnumerable<JsonElement> Invalid(string name) => V2.GetProperty("invalid").GetProperty(name).EnumerateArray();
	static string S(JsonElement e, string name) => e.GetProperty(name).GetString()!;

	[Fact]
	public void ConversationKeys()
	{
		foreach (var v in Valid("get_conversation_key"))
		{
			var key = Nip44.ConversationKey(NostrKeys.FromSecret(Hex.Decode(S(v, "sec1"))), S(v, "pub2"));
			Assert.Equal(S(v, "conversation_key"), Hex.Encode(key));
		}
	}

	[Fact]
	public void InvalidConversationKeysAreRejected()
	{
		foreach (var v in Invalid("get_conversation_key"))
		{
			Assert.ThrowsAny<ArgumentException>(() =>
				Nip44.ConversationKey(NostrKeys.FromSecret(Hex.Decode(S(v, "sec1"))), S(v, "pub2")));
		}
	}

	[Fact]
	public void MessageKeys()
	{
		var mk = V2.GetProperty("valid").GetProperty("get_message_keys");
		var conversationKey = Hex.Decode(S(mk, "conversation_key"));
		foreach (var v in mk.GetProperty("keys").EnumerateArray())
		{
			var (chachaKey, chachaNonce, hmacKey) = Nip44.MessageKeys(conversationKey, Hex.Decode(S(v, "nonce")));
			Assert.Equal(S(v, "chacha_key"), Hex.Encode(chachaKey));
			Assert.Equal(S(v, "chacha_nonce"), Hex.Encode(chachaNonce));
			Assert.Equal(S(v, "hmac_key"), Hex.Encode(hmacKey));
		}
	}

	[Fact]
	public void PaddedLengths()
	{
		foreach (var v in Valid("calc_padded_len"))
			Assert.Equal(v[1].GetInt32(), Nip44.CalcPaddedLength(v[0].GetInt32()));
	}

	[Fact]
	public void EncryptDecrypt()
	{
		foreach (var v in Valid("encrypt_decrypt"))
		{
			var sec1 = NostrKeys.FromSecret(Hex.Decode(S(v, "sec1")));
			var sec2 = NostrKeys.FromSecret(Hex.Decode(S(v, "sec2")));
			var conversationKey = Nip44.ConversationKey(sec1, sec2.PublicKeyHex);
			Assert.Equal(S(v, "conversation_key"), Hex.Encode(conversationKey));
			Assert.Equal(conversationKey, Nip44.ConversationKey(sec2, sec1.PublicKeyHex));

			var payload = Nip44.Encrypt(S(v, "plaintext"), conversationKey, Hex.Decode(S(v, "nonce")));
			Assert.Equal(S(v, "payload"), payload);
			Assert.Equal(S(v, "plaintext"), Nip44.Decrypt(payload, conversationKey));
		}
	}

	[Fact]
	public void EncryptDecryptLongMessages()
	{
		foreach (var v in Valid("encrypt_decrypt_long_msg"))
		{
			var plaintext = string.Concat(Enumerable.Repeat(S(v, "pattern"), v.GetProperty("repeat").GetInt32()));
			Assert.Equal(S(v, "plaintext_sha256"), Hex.Encode(SHA256.HashData(Encoding.UTF8.GetBytes(plaintext))));

			var conversationKey = Hex.Decode(S(v, "conversation_key"));
			var payload = Nip44.Encrypt(plaintext, conversationKey, Hex.Decode(S(v, "nonce")));
			Assert.Equal(S(v, "payload_sha256"), Hex.Encode(SHA256.HashData(Encoding.UTF8.GetBytes(payload))));
			Assert.Equal(plaintext, Nip44.Decrypt(payload, conversationKey));
		}
	}

	[Fact]
	public void InvalidMessageLengthsAreRejected()
	{
		foreach (var v in Invalid("encrypt_msg_lengths"))
			Assert.Throws<ArgumentException>(() => Nip44.Encrypt(new string('a', v.GetInt32()), new byte[32]));
	}

	[Fact]
	public void InvalidPayloadsAreRejected()
	{
		foreach (var v in Invalid("decrypt"))
		{
			var e = Record.Exception(() => Nip44.Decrypt(S(v, "payload"), Hex.Decode(S(v, "conversation_key"))));
			Assert.True(e is CryptographicException or NotSupportedException, $"{S(v, "note")}: got {e?.GetType().Name ?? "no exception"}");
		}
	}
}
