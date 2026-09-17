# Credits and inspiration

## Jonathan Roomer and NightBloodRemote

[Jonathan Roomer](https://github.com/jonathanroomer) created
[NightBloodRemote](https://github.com/jonathanroomer/NightBloodRemote), an
experimental iPhone companion with voice, animated characters, and CarPlay support.
Seeing that project was the starting point for RoadVoice.

RoadVoice explores the same broad idea on Android Auto, with a different goal and
implementation: an API-backed voice conversation that can coexist with other car
apps. It does not use NightBloodRemote's iOS code, Blender assets, character art,
Apple entitlements, or Codex Remote subscription connection. No NightBloodRemote
source or assets are bundled in this repository. Please credit Jonathan's work
when discussing what inspired this project.

NightBloodRemote is independently maintained and MIT-licensed. Its authors have
not endorsed this project and are not responsible for its support.

## Development

I used OpenAI Codex for implementation, debugging, testing, and documentation.
The project grew through hands-on phone and car testing.

## Dependencies and tooling

- Android and AndroidX: Car App Library, Core-Telecom, lifecycle, and core APIs.
- Kotlin and kotlinx.coroutines.
- WebRTC, distributed through the `io.github.webrtc-sdk:android` package.
- OkHttp for HTTP session setup.
- Cloudflare Workers and Wrangler for the hosted broker.
- Node.js and its built-in test runner for broker development.
- Gradle and the Android Gradle Plugin for builds.

Dependency versions are declared in the build files. Their licenses remain in
effect independently of this repository's MIT license. The Gradle wrapper is
Gradle's standard Apache-2.0-licensed build tooling; the binary is included only
so contributors can use the pinned build version.
Its license is included in [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).
