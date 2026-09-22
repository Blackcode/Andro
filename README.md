# Uncage

A private chat app, similar to WhatsApp, built to keep working under government censorship.
It's a .NET MAUI app with one codebase for **Android, iOS, macOS and Windows**.

## How it avoids censorship

| Threat | What Uncage does |
|---|---|
| A central server gets blocked or ordered to shut down | There is no Uncage server. Messages go through several independent **Nostr relays** run by different people in different countries. Blocking some of them doesn't stop delivery: a message counts as sent once any relay accepts it. Users can add any relay, including their own. |
| Accounts tied to phone numbers or SIM registration | There are no accounts. Your identity is a key pair created on your phone. People add you by your ID (`npub1…`), shared as a QR code or text. |
| Reading messages or mapping who talks to whom | Messages are end-to-end encrypted with **NIP-44** (audited by Cure53) and wrapped with **NIP-59/NIP-17**. A relay sees only an encrypted blob for your key, signed by a throwaway key, with a randomized timestamp. It can't see the sender, the text, or when the message was really sent. |
| The ISP sees which relays you use, or blocks them by IP or DNS | The **Route through Tor** setting sends everything through a SOCKS5 proxy (Orbot on Android). Host names are resolved by the proxy, so DNS lookups don't leak, and `.onion` relays work. Without Tor, traffic is ordinary HTTPS/WebSocket on port 443. |
| A seized or inspected phone | History is encrypted on disk (ChaCha20-Poly1305). The keys are kept in the platform keystore. Android screenshots and the recent-apps preview are blocked, and cloud backup is disabled. Settings has "Erase identity". |

Uncage uses the open Nostr protocol (NIP-01/17/19/42/44/59). It can exchange private messages with any other Nostr client that supports NIP-17, and its message format is tested against the protocol's official test vectors and reference implementation.

### Limitations

- **Messages arrive while the app is open.** Push notifications would need Google or Apple servers, which can be blocked and which reveal who receives messages. An Android background service is a possible next step.
- **No forward secrecy.** If your secret key is stolen, past messages stored on relays can be decrypted. Keep the backup key offline.
- **Relays can refuse to store messages.** That's why several are used; add relays you trust.
- **One-to-one chats only**, text only for now.
- Anyone can create an identity with any name, so check a contact's ID in person. **New chat → Scan their QR code** reads the code on their **My ID** screen, which also accepts `npub`/`nprofile` QR codes from other Nostr apps. Scanning happens entirely on the phone.

## Solution layout

Open **`Uncage.sln`** in Visual Studio 2022 (17.12 or later) or Visual Studio 2026 with the *.NET Multi-platform App UI development* workload, or in Rider.

```
Uncage.sln
├─ src/
│  ├─ Uncage/             MAUI app (Android, iOS, Mac Catalyst, Windows): pages, view models, platform code
│  └─ Uncage.Core/        Plain .NET 10 library, no UI: identity, encryption, relays, chat engine
└─ tests/
   └─ Uncage.Core.Tests/  xUnit tests, including end-to-end chats through an in-process relay
```

`Uncage.Core` does all the protocol work, so it can be tested on any machine without phones or emulators:

- `Crypto/`: secp256k1 keys and Schnorr signatures (NBitcoin.Secp256k1), NIP-19 `npub`/`nsec`, NIP-44 v2 encryption, and at-rest encryption.
- `Nostr/`: events, filters, gift wraps (NIP-59), private messages (NIP-17).
- `Relays/`: WebSocket relay connections with reconnect and backoff, NIP-42 auth only when a relay demands it, proxy support, and a relay pool.
- `Chat/`: `Messenger` (send/receive/retry, contact inbox discovery) and `ChatStore` (encrypted history).

## Build and run

Prerequisites: [.NET 10 SDK](https://dotnet.microsoft.com/download) and `dotnet workload install maui`
(on Linux only `maui-android` is available). Android needs the Android SDK and JDK 17. iOS and Mac Catalyst need a Mac with Xcode.

```bash
dotnet test Uncage.Core.Tests                                         # protocol and chat engine tests
dotnet build Uncage/Uncage.csproj -f net10.0-android -t:Run            # Android emulator or device
dotnet build Uncage/Uncage.csproj -f net10.0-ios -t:Run                # macOS only
dotnet build Uncage/Uncage.csproj -f net10.0-maccatalyst -t:Run        # macOS only
dotnet build Uncage/Uncage.csproj -f net10.0-windows10.0.19041.0 -t:Run   # Windows only
```

To check that the app's XAML and C# compile on a machine without the MAUI workload or platform SDKs, run `dotnet build Uncage/Uncage.csproj -p:UncageVerifyBuild=true`.

To send a real message between two new identities over the default public relays, run `UNCAGE_LIVE_TESTS=1 dotnet test Uncage.Core.Tests --filter LiveRelayTests`.

CI (`.github/workflows/build.yml`) runs the tests and builds the Android APK (a downloadable artifact) and the Windows app. Tagged versions are published as GitHub Releases (see below).

## Publishing a release

Every APK must be signed with the same key, or Android won't install it as an update and people would lose their chats.

**One-time setup:**
1. On your PC, run `powershell -ExecutionPolicy Bypass -File tools\create-release-key.ps1`. It creates `uncage-release.keystore` (never committed) and copies it, base64-encoded, to your clipboard.
2. On GitHub, go to **Settings → Secrets and variables → Actions** and add three repository secrets:
   - `ANDROID_KEYSTORE_BASE64`: paste from the clipboard.
   - `ANDROID_KEYSTORE_PASSWORD`: the password you chose.
   - `ANDROID_KEY_ALIAS`: `uncage`
3. Back up the keystore and its password somewhere private, such as a password manager. If you lose them, you can never update installed apps.

**Each release:**

```bash
git tag v1.0.0
git push origin v1.0.0
```

The **Release** workflow builds a signed `Uncage-1.0.0.apk` and publishes it on the repository's **Releases** page with its SHA-256 hash. Share that page or the APK link. Once the secrets exist, the regular **Build** workflow signs its APKs with the same key.

## Using Tor

1. Install **Orbot** (Android) from F-Droid or Google Play and start it. On desktop, run Tor or Tor Browser.
2. In Uncage: **Settings → Route through Tor**. The default proxy is `socks5://127.0.0.1:9050`. Tor Browser uses port `9150`.
3. Optionally add `ws://….onion` relays. They are reachable only through Tor, so they can't be blocked by IP or DNS.
