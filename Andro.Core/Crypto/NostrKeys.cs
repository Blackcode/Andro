using System.Security.Cryptography;
using NBitcoin.Secp256k1;

namespace Andro.Core.Crypto;

/// <summary>
/// A user's identity: a secp256k1 key pair. There is no phone number or account;
/// the public key is the address people send messages to.
/// </summary>
public sealed class NostrKeys
{
	readonly ECPrivKey _privateKey;
	readonly byte[] _secret;

	NostrKeys(byte[] secret)
	{
		if (secret.Length != 32 || !ECPrivKey.TryCreate(secret, out var key) || key is null)
			throw new ArgumentException("Invalid secp256k1 private key.", nameof(secret));
		_secret = secret;
		_privateKey = key;
		PublicKeyHex = Hex.Encode(key.CreateXOnlyPubKey().ToBytes());
	}

	/// <summary>32-byte x-only public key, lowercase hex (the form used inside events).</summary>
	public string PublicKeyHex { get; }

	public string SecretKeyHex => Hex.Encode(_secret);

	public string Npub => Nip19.EncodeNpub(PublicKeyHex);

	public string Nsec => Nip19.EncodeNsec(_secret);

	public static NostrKeys Generate()
	{
		while (true)
		{
			var secret = RandomNumberGenerator.GetBytes(32);
			if (ECPrivKey.TryCreate(secret, out _))
				return new NostrKeys(secret);
		}
	}

	public static NostrKeys FromSecret(ReadOnlySpan<byte> secret) => new(secret.ToArray());

	/// <summary>Accepts an nsec1… string or 64 hex characters.</summary>
	public static NostrKeys Parse(string secret)
	{
		secret = secret.Trim();
		if (secret.StartsWith("nsec1", StringComparison.OrdinalIgnoreCase))
			return new NostrKeys(Nip19.DecodeNsec(secret));
		if (secret.Length == 64)
			return new NostrKeys(Hex.Decode(secret));
		throw new FormatException("Expected an nsec1… key or 64 hex characters.");
	}

	/// <summary>BIP-340 Schnorr signature over a 32-byte message hash.</summary>
	public byte[] Sign(ReadOnlySpan<byte> hash32)
	{
		var auxRand = RandomNumberGenerator.GetBytes(32);
		var signature = _privateKey.SignBIP340(hash32, new BIP340NonceFunction(auxRand));
		var bytes = new byte[64];
		signature.WriteToSpan(bytes);
		return bytes;
	}

	/// <summary>Unhashed x coordinate of (this secret × their public key), as NIP-44 requires.</summary>
	public byte[] SharedSecretX(string publicKeyHex)
	{
		var theirs = LiftX(publicKeyHex);
		var shared = theirs.GetSharedPubkey(_privateKey);
		return shared.ToBytes(compressed: true)[1..];
	}

	public static bool VerifySignature(string publicKeyHex, ReadOnlySpan<byte> hash32, ReadOnlySpan<byte> signature64)
	{
		if (!Hex.IsLowerHex(publicKeyHex, 32) || signature64.Length != 64 || hash32.Length != 32)
			return false;
		if (!ECXOnlyPubKey.TryCreate(Hex.Decode(publicKeyHex), out var pub) || pub is null)
			return false;
		if (!SecpSchnorrSignature.TryCreate(signature64, out var sig) || sig is null)
			return false;
		return pub.SigVerifyBIP340(sig, hash32);
	}

	public static bool IsValidPublicKey(string? publicKeyHex) =>
		Hex.IsLowerHex(publicKeyHex, 32) && ECXOnlyPubKey.TryCreate(Hex.Decode(publicKeyHex!), out _);

	static ECPubKey LiftX(string publicKeyHex)
	{
		if (!Hex.IsLowerHex(publicKeyHex, 32))
			throw new ArgumentException("Public key must be 64 lowercase hex characters.", nameof(publicKeyHex));
		var compressed = new byte[33];
		compressed[0] = 0x02;
		Hex.Decode(publicKeyHex).CopyTo(compressed, 1);
		if (!ECPubKey.TryCreate(compressed, null, out _, out var point) || point is null)
			throw new ArgumentException("Public key is not a valid secp256k1 point.", nameof(publicKeyHex));
		return point;
	}
}
