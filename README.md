# AtsuPager — Hardened Bitcoin-Based Messenger

AtsuPager is a high-security communication platform for Android, designed with a "Zero-Trust" architecture and advanced anti-forensic capabilities. It uses Bitcoin's Elliptic Curve cryptography (Secp256k1) for identity and end-to-end encryption, ensuring total sovereignty over your data.

## 🛡️ Advanced Security Architecture

### 1. Cryptographic Identity & Anti-MITM
- **Self-Sovereign Identity**: Your ID is a Bitcoin Address (Legacy). Public keys are mathematically verified against the address, making it impossible for the signaling server to perform a Man-in-the-Middle (MITM) attack.
- **ECDH + AES-256-GCM**: Secure key exchange using Elliptic Curve Diffie-Hellman and authenticated encryption for all messages and metadata.
- **Hardware-Backed Keys**: Private keys and mnemonics (BIP39) are stored in the **Android Keystore**, utilizing **StrongBox** (Hardware Security Module) where available.

### 2. Anti-Forensic Data Protection
- **Zero-Latency RAM Encryption**: Sensitive data (keys, passwords, decrypted messages) and voice messages are handled in volatile memory and **forced-wiped with zeros** immediately after use. Unencrypted raw audio never touches physical storage.
- **Physical Data Shredding**: When messages are deleted or expired (TTL), the app executes a **SQLite VACUUM**, physically overwriting deleted sectors on the disk to prevent forensic recovery.
- **Multi-Profile Containerization**: Complete isolation between profiles. Deleting a profile triggers a "Scorched Earth" wipe of all associated keys and files.

### 3. Privacy & Stealth Features
- **Traffic Masking**: WebRTC Voice/Video traffic can be masked as standard HTTPS (TCP 8443) to bypass Deep Packet Inspection (DPI).
- **Visual Privacy**: Protection against screenshots and exposure in the "Recent Apps" list via `FLAG_SECURE`.
- **Integrity Protection**: Built-in detection for Root access and Emulator environments to prevent side-channel attacks.
- **Keyboard Security**: Enforces `noPersonalizedLearning` to prevent Gboard/SwiftKey from logging sensitive keystrokes.

## 🎮 Secure P2P Gaming
AtsuPager features a built-in P2P game engine, allowing users to interact within a secure environment:
- **Games:** Chess, Checkers, and Backgammon.
- **Privacy First:** Game states are synchronized via end-to-end encrypted signals. No third-party game servers are involved.

## 🗺️ Roadmap
- **Native Onion Routing:** Direct support for .onion addresses for server-less communication.
- **Encrypted Group Calls:** Fully secure multi-party Voice and Video communication.
- **Advanced Obfuscation:** Enhanced traffic camouflage for extremely restrictive network environments.

## 📦 Downloads & Verification
The latest signed APK is available in the [Releases](https://github.com/AtsuPager/AtsuPager-Android/releases) section.

**Current APK SHA-256 Checksum:**
`697cf586ef79e56882ca57432472d7b3ce3cb4e51c95f761e0c232139f9901d0`

## 🧪 Beta Testing
You can test the application using our infrastructure. After installation, go to **Settings -> Access Status** and enter one of the following activation codes:

1. `L4FN-FMGP-DUHC-53JM`
2. `THEM-TKH6-VRCN-WQB9`
3. `6TV5-5RYW-SXCG-ZBB3`
4. `FGKP-8N6M-SUA8-7T9E`
5. `JM8C-TT42-6JMY-Z7BB`
6. `G4BV-87WJ-CGPD-PYEQ`
7. `PBZX-PDVR-5CN6-CS6S`
8. `8ATF-VWB6-WTHF-S73Z`
9. `2UTQ-FVR7-ZMQY-4FU8`
10. `XJSM-DZ84-H3EB-S78J`

## 💰 Support & Donations
- **Bitcoin (BTC):** `bc1qcyk460u3t3fh0n4t0rqdjmwrzx89flgrfpgcet`
- **Ethereum (ETH):** `0x517a367a8Bcc2C5E7e2Eb98D8b40305Ba1706529`

## ⚖️ Licensing
**Proprietary / All Rights Reserved.**

This source code is published for transparency, security audit, and educational purposes only.
- **No Commercial Use**: Use of this code for any commercial gain is strictly prohibited.
- **No Redistribution**: You may not copy, modify, or redistribute the source code in any form.
- **Audit Only**: Access to the code is provided so users can verify the security claims of the application.

---
*Developed with ❤️ for the Privacy Community. Stay Sovereign.*
