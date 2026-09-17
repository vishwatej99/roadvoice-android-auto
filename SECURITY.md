# Security and privacy

This is a self-hosted personal project. Each installation should use its owner's
OpenAI account, Worker, and credentials.

- The OpenAI API key belongs in Cloudflare secret bindings or the local server's
  ignored `.env`. Never put it in Kotlin, Gradle resources, a screenshot, or a commit.
- The phone stores a separate broker token encrypted with an Android Keystore key.
  The app does not contain a preconfigured server URL or token.
- A broker token grants access to create sessions billed to that broker's OpenAI
  account. Treat it as a password. Private Play distribution is not server authentication.
- The release app requires HTTPS. Debug loopback HTTP is for USB development only.
- The broker sends session-setup metadata to OpenAI. WebRTC voice audio travels
  directly between the phone and OpenAI. Provider-side processing and retention
  are governed by their terms and settings; `store: false` is not a promise that
  no service metadata exists.
- The broker does not intentionally log credentials, SDP, or conversation content.
  Worker observability logging is disabled in the supplied config.
- The rate limiter is in memory, per process or Worker isolate. It is not a shared
  spending cap. The broker cannot enforce a call-duration limit after WebRTC setup.
- There is no user-account system, ChatGPT OAuth, or subscription-token reuse.

Before publishing your own fork, check both tracked files and commit history.
Keep signing keys, service-account files, local settings, screenshots, device dumps,
and generated build reports out of Git. The example env files intentionally have
blank credentials.

If a credential is exposed, revoke or rotate it first. Removing a file from the
latest commit does not remove earlier history or invalidate the credential.

For a vulnerability, use this repository's GitHub **Security → Report a vulnerability**
option if available. If it is unavailable, open an issue asking for a private
reporting channel without posting exploit details, credentials, or personal data.
