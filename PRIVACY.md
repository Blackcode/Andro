# Uncage Privacy Policy

_Last updated: September 22, 2026_

Uncage is a private messaging app. It was built so that no company, including its developers, can read your messages or see who you talk to. This policy explains what happens to your information when you use it.

## Summary

- **No account and no phone number.** Your identity is a cryptographic key created on your device.
- **End-to-end encrypted.** Only you and the people you write to can read your messages, photos, videos and voice messages.
- **No analytics, no advertising, no tracking.** The app contains no analytics or advertising code and collects no usage statistics.
- **The developers run no servers** and receive no data from the app.

## Information on your device

- **Your identity key.** Created on your device and stored in the platform's secure storage (Android Keystore, Apple Keychain, or Windows data protection). It never leaves your device unless you copy it yourself, for example as a backup.
- **Your messages and contacts.** Stored only on your device, encrypted with a key held in the same secure storage. Contact names are nicknames you type in yourself; they are never uploaded.
- **Photos, videos and audio you send or open** are cached on your device, also encrypted.
- On Android, the app turns off cloud backup of its data and, in released versions, blocks screenshots and the recent-apps preview of your chats.

You can erase your identity, messages and media from the device at any time under **Settings → Erase identity and chats**, or by uninstalling the app.

## Information that leaves your device

To deliver messages without a central server, Uncage uses the open Nostr protocol and independent third-party servers. Anyone can operate these servers, and you can choose which ones to use in **Settings**.

**Relays** store and forward messages. What a relay receives:
- Messages encrypted end-to-end (NIP-44), each wrapped in a further encrypted envelope (NIP-17/NIP-59). A relay can see the recipient's public key, an encrypted blob, and a randomized timestamp. It cannot see the sender, the content, the real time of sending, or the message type.
- A list of the relays where you prefer to receive messages (your public "inbox" list), so others can reach you.
- When a relay requires it, a signed proof that you control your public key (NIP-42), so it only gives your messages to you.

**Media servers** (Blossom servers) store photos, videos, audio and files. Each file is encrypted on your device with a new random key before it is uploaded, and the key is sent only inside the end-to-end encrypted message. Media servers store unreadable data, and uploads are signed with a throwaway key rather than your identity.

**Network information.** Like any internet service, relays and media servers can see the IP address connections come from, unless you turn on **Settings → Route through Tor**. With Tor on, they see a Tor exit address instead, and your internet provider only sees that you use Tor.

Relays and media servers are operated by third parties with their own policies. Encrypted messages and files may stay on them after you delete them from your device; without the keys on your and your contacts' devices, that data can't be read.

## Device permissions

- **Camera:** to scan contacts' QR codes and take photos and videos to send. It is only used while you do this.
- **Microphone:** only while you record a voice message.
- **Photos and files:** only the items you choose to send. Photos are resized and their metadata, including location, is removed before they are encrypted.
- **Internet:** to reach relays and media servers.

## Children

Uncage is not directed at children under 13, and we do not knowingly provide it to them.

## Sharing and selling

The developers never receive your data, so we do not share or sell it.

## Changes

We'll update this page when the app's handling of data changes, and change the date above.

## Contact

Questions about privacy: open an issue at https://github.com/Blackcode/Uncage/issues
