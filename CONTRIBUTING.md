# Contributing

Issues and focused pull requests are welcome. This is a small personal project,
so please keep changes easy to understand and test.

For a bug, include what you expected, what happened, how to reproduce it, the app
version, and whether you used the regular call path or Car microphone preview.
Phone, Android Auto, and head-unit versions can help explain compatibility issues.
Do not attach API keys, access tokens, private server URLs, account IDs, unfiltered
device logs, or screenshots containing personal information.

Run the Node test suites for broker changes and relevant Gradle unit tests/lint
for Android changes. A passing build is not proof of working car audio. Describe
any actual phone or head-unit testing separately. Test setup, controls, and
interruption behavior while parked.

Keep credentials out of source and use your own app ID and signing key for Play
testing. Match the surrounding Kotlin/JavaScript naming and formatting. Changes
that touch audio routing should preserve reliable End and close/reopen behavior.

Contributions are made under this repository's MIT license. Retain existing
attribution, including the credit to Jonathan Roomer and NightBloodRemote.
