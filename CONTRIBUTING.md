# Contributing to NavBeam

Thank you for your interest in contributing to NavBeam! Because NavBeam is a clean-room, vendor-neutral Android project licensed under **GPLv3**, please follow the guidelines below before opening a Pull Request.

---

## Before You Start

- **Large features or architectural changes:** Open an issue first to discuss the design before writing code.
- **Bug reports:** Include steps to reproduce, device/Android version, and (if possible) the exported diagnostics `.zip` from **Menu → Diagnostics & Bug Reports**.
- **Hardware profiles:** Only submit clean-room, user-authored profile definitions. **Never** include copyrighted manufacturer firmware, proprietary keys, or third-party binaries.

---

## Development Setup

1. Fork the repository and create a branch from `main`:
   ```bash
   git checkout -b fix/short-description
   ```
   Use clear prefixes like `feat/`, `fix/`, `docs/`, or `refactor/`.
2. Build the debug APK locally:
   ```bash
   ./gradlew :app:assembleDebug
   ```
3. Run unit tests:
   ```bash
   ./gradlew testDebugUnitTest
   ```

See [docs/TESTING.md](docs/TESTING.md) for the full testing strategy and conventions.

---

## Pull Request Policy

- **Keep PRs focused:** One logical change or bug fix per PR.
- **No proprietary assets:** Do not commit manufacturer keys, proprietary libraries, or `local.properties` / keystore files.
- **Verify your build:** Ensure `./gradlew :app:assembleDebug` and `./gradlew test` pass before requesting review.
- **Hardware-in-the-loop changes:** If your PR modifies the Wi-Fi discovery, RTP/H.264 streaming, or dash control plane, clearly state what hardware/Android version you tested on (or note if it was only build/unit-tested).
- **Clean-break code:** Avoid dead code, unused imports, or unnecessary backward-compatibility shims.
- **License:** By submitting a PR, you agree that your contributions will be licensed under the project's [GNU General Public License v3 (GPLv3)](LICENSE).

---

## Review Process

Maintainers review PRs as time permits. Please respond to review comments in the same PR thread and push follow-up commits rather than opening a new PR.
