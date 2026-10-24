# Contributing to Webmote

Thank you for your interest in contributing to Webmote, a remote for Android TV in your phone's or computer's browser! Contributions are welcome, and this page
keeps the process clear and simple.

## Ways to Contribute

- Report bugs or request features using [GitHub Issues](../../issues).
- Improve documentation (README, code comments, the docs folder).
- Submit Pull Requests (PRs) with bug fixes, improvements, or new features.

## Where Things Are

- The remote page (`app/src/main/assets/index.html`): one file, no build step, no frameworks.
- The TV side: the HTTP server, pairing, and the keys (`app/src/main/java/`).

## Code Style

- Plain Java 17 and the Android SDK, no extra libraries unless they really pay for themselves.
- Match the code around you: its naming, comment style and size.
- Comments say why, not what.
- Keep things simple (KISS principle). The APK stays small.

## Pull Request Guidelines

1. **Fork** the repository.
2. Create a new branch for your feature or bugfix:
   ```bash
   git checkout -b feature/my-new-feature
   ```
3. Commit changes with clear, descriptive messages.
4. Make sure it builds and the tests pass: `./gradlew assembleRelease testReleaseUnitTest lintRelease`.
5. Open a Pull Request and describe your changes.

## Tests

- The JVM tests run with `./gradlew testReleaseUnitTest`. Add tests for logic that doesn't need a TV.
- Test on a real TV when you can: pairing, the D-pad, Home, Back, Apps and typing. Say what you tested in the PR, and what you couldn't.
- Screenshots in issues and PRs: hide IP addresses, network names, device names and accounts.

## Community

We value respectful collaboration. By contributing, you agree to follow our [Code of Conduct](CODE_OF_CONDUCT.md).

Thank you for helping make this project welcoming!
