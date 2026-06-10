# AtsuPager — Hardened Bitcoin-Based Messenger

AtsuPager is a high-security communication platform for Android, designed with a "Zero-Trust" architecture and advanced anti-forensic capabilities. It uses Bitcoin's Elliptic Curve cryptography (Secp256k1) for identity and end-to-end encryption, ensuring total sovereignty over your data.

## 🛡️ Advanced Security Architecture

### 1. Cryptographic Identity & Anti-MITM
- **Self-Sovereign Identity**: Your ID is a Bitcoin Address (Legacy). Public keys are mathematically verified against the address, making it impossible for the signaling server to perform a Man-in-the-Middle (MITM) attack.
- **ECDH + AES-256-GCM**: Secure key exchange using Elliptic Curve Diffie-Hellman and authenticated encryption for all messages and metadata.
- **Hardware-Backed Keys**: Private keys and mnemonics (BIP39) are stored in the **Android Keystore**, utilizing **StrongBox** (Hardware Security Module) where available.

### 2. Anti-Forensic Data Protection
- **RAM Protection**: All sensitive data (keys, passwords, mnemonics, decrypted messages) are handled as `ByteArray` or `CharArray` and **forced-wiped with zeros** immediately after use to prevent data recovery from memory dumps.
- **Storage Security**: Local database is encrypted with **SQLCipher (AES-256)**.
- **Physical Data Destruction**: When messages are deleted or expired (TTL), the app executes a **SQLite VACUUM**, physically overwriting the deleted sectors on the disk to prevent forensic recovery.
- **Zero-Disk Audio**: Voice messages are encrypted in RAM during recording and streamed directly to disk, ensuring unencrypted audio never touches the physical storage.

### 3. Privacy & Stealth Features
- **Network Anonymity**: Built-in **Tor/Orbot (SOCKS5)** proxy support to hide your IP address from the signaling server.
- **Visual Privacy**: Protection against screenshots, screen recording, and exposure in the "Recent Apps" list via `FLAG_SECURE`.
- **Keyboard Security**: Disables personalized learning and cloud-sync for keyboards (Gboard, SwiftKey) to prevent keystroke logging.
- **Clipboard Protection**: Multi-layered clipboard management with an "is-sensitive" flag and automatic timed clearing (including background workers).
- **Multi-Profile Containerization**: Complete isolation between user profiles. Deleting a profile triggers a "Scorched Earth" wipe of all associated keys and files.

## 🚀 Technical Stack

- **Modern Android**: Kotlin, Jetpack Compose, Hilt (DI), Coroutines & Flow.
- **P2P Communication**: WebRTC for encrypted Voice/Video calls with "Stealth Mode" (TCP 8443 masking).
- **Decentralized Signaling**: Lightweight WebSocket signaling designed to be hosted on private VPS.
- **Push Notifications**: Integrated with **Ntfy.sh** for Google-free (FCM-free) notifications.
- **BIP39/Bitcoinj**: Professional-grade implementation of Bitcoin crypto standards.

## 🛠️ Build & Configuration

To build the project, provide your infrastructure URLs in `local.properties`:

```properties
# Your private signaling/file server URL
vps.url=https://your-signal-server.com
file.api.url=https://your-file-api.com
```

## ⚖️ Licensing

This project is licensed under the **GNU GPL v3**.

- **Open Source**: The code is transparent and available for audit.
- **Copyleft**: Any derivative works must also be open source under the same license.
- **Commercial Use**: Allowed under the terms of GPL v3. For proprietary integration, white-labeling, or commercial deployment without sharing source code, please contact the Author for a separate commercial license.

---
*Developed with ❤️ for the Privacy Community. Stay Sovereign.*
