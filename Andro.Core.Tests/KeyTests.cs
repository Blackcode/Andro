using System.Security.Cryptography;
using Andro.Core.Crypto;

namespace Andro.Core.Tests;

public class KeyTests
{
	// Examples from NIP-19.
	[Theory]
	[InlineData("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6", "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d")]
	[InlineData("npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg", "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e")]
	public void NpubRoundTrips(string npub, string hex)
	{
		Assert.Equal(hex, Nip19.DecodeNpub(npub));
		Assert.Equal(npub, Nip19.EncodeNpub(hex));
		Assert.Equal(hex, Nip19.TryParsePublicKey(npub));
		Assert.Equal(hex, Nip19.TryParsePublicKey("nostr:" + npub));
		Assert.Equal(hex, Nip19.TryParsePublicKey(hex.ToUpperInvariant()));
	}

	[Fact]
	public void AcceptsNprofileFromOtherApps()
	{
		// Example from NIP-19.
		Assert.Equal("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d", Nip19.TryParsePublicKey(
			"nostr:nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p"));
	}

	[Fact]
	public void NsecRoundTrips()
	{
		const string nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5";
		var keys = NostrKeys.Parse(nsec);
		Assert.Equal("67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa", keys.SecretKeyHex);
		Assert.Equal(nsec, keys.Nsec);
		Assert.Equal(keys.PublicKeyHex, NostrKeys.Parse(keys.SecretKeyHex).PublicKeyHex);
	}

	[Theory]
	[InlineData("")]
	[InlineData("npub1")]
	[InlineData("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w7")] // bad checksum
	[InlineData("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5")] // wrong kind
	[InlineData("zz")]
	public void BadPublicKeysAreRejected(string input) => Assert.Null(Nip19.TryParsePublicKey(input));

	[Fact]
	public void SignaturesVerify()
	{
		var keys = NostrKeys.Generate();
		var hash = SHA256.HashData("hello"u8);
		var sig = keys.Sign(hash);
		Assert.True(NostrKeys.VerifySignature(keys.PublicKeyHex, hash, sig));

		sig[0] ^= 1;
		Assert.False(NostrKeys.VerifySignature(keys.PublicKeyHex, hash, sig));
		Assert.False(NostrKeys.VerifySignature(NostrKeys.Generate().PublicKeyHex, hash, keys.Sign(hash)));
	}

	[Fact]
	public void SecretBoxRoundTripsAndDetectsTampering()
	{
		var key = RandomNumberGenerator.GetBytes(SecretBox.KeySize);
		var sealedData = SecretBox.Seal("history"u8, key);
		Assert.Equal("history"u8.ToArray(), SecretBox.Open(sealedData, key));

		sealedData[^1] ^= 1;
		Assert.Throws<CryptographicException>(() => SecretBox.Open(sealedData, key));
		Assert.Throws<CryptographicException>(() => SecretBox.Open(SecretBox.Seal("x"u8, key), RandomNumberGenerator.GetBytes(32)));
	}
}
