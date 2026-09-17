# Testing and current status

The public baseline is RoadVoice 0.4.1. Its owner reported working GPT-Live
conversations in Android Auto, continued access to other car apps, and successful
use after the 0.4.1 close/restart update. These are observations on one setup, not
a compatibility guarantee for every vehicle.

## Automated checks

```sh
npm --prefix worker test
npm --prefix server test
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
```

On Windows use `gradlew.bat`. The Node tests fake OpenAI responses and do not need
credentials. The Android unit tests cover PCM buffering and cancellation of
session listeners after hang-up, including restarting a session.

For this source release, all 33 broker tests and five Android unit tests passed.
The debug APK also built with a separate application ID, and Android lint
completed with no errors and 15 warnings. The Gradle wrapper JAR matched its
official published SHA-256 checksum. No live API sessions were needed for these
publication checks.

## Checks on pull requests

GitHub Actions runs the fake-upstream broker tests and the Android debug build,
unit tests, and lint on pull requests and updates to `main`. Android checks use
JDK 17, SDK 36, the pinned Gradle wrapper, and a separate example application ID.
The workflow uses read-only repository permissions and actions pinned to commit
hashes. It does not deploy a Worker, sign a Play release, run device instrumentation,
or use OpenAI credentials. Passing these checks does not establish compatibility
with a physical phone or car.

## Session lifecycle validation

The 0.4.1 lifecycle fix cancels status-listener tasks when Core-Telecom invokes
the remote-disconnect callback. Previously those tasks could outlive the call
and leave the app stuck on “Ending conversation.” Repeated End can also cancel
lingering cleanup, and the car template keeps a Close action during ending.

Before this public release, a debug instrumentation test on a physical phone
connected a hosted voice session, invoked the registered remote-disconnect
callback, returned to idle, connected again, and ended through the actual
End and close app button. No voice service or microphone capture remained after
that test. It did not simulate a physical steering-wheel button.

The optional instrumentation in `androidTest` is for local diagnostics. Unlike
unit tests, a lifecycle run creates two real voice sessions and incurs API usage.
It requires an unlocked phone and separately configured debug-app credentials.
Do not run it automatically in CI or pass credentials in public test logs.

## What to check in your car

Set up and test while parked: Talk, an audible reply, Mute/Unmute, End, opening a
second session, and End and close app on the phone. Then check switching to Maps,
navigation prompts, media interruption, an incoming call, phone locking, and
Android Auto disconnect/reconnect. Check both audio paths separately.

The Car microphone preview uses Android Auto's `CarAudioRecord` for capture,
assistant audio attributes for playback, and a foreground playback service.
It avoids Telecom registration. Media may pause during a conversation; audio
focus changes or host microphone dismissal can stop the preview.

## Limitations

- No takeover of the car's assistant button or wake word.
- No ChatGPT subscription, memory, chat history, or connected-app integration.
- No actions in Maps, messaging, music, or other apps; using those apps alongside
  voice is different from the model controlling them.
- No custom animated face on the Android Auto display. A separate parked visual
  experiment rendered on the phone but was not exposed by the tested Auto host.
  That experiment and the abandoned subscription proof of concept are not part
  of this source release.
- No claim of public Google Play approval for the experimental assistant-like
  use of the car microphone.

Useful references: [Android Auto testing](https://developer.android.com/training/cars/testing),
[calling apps](https://developer.android.com/training/cars/communication/calling),
[car microphone](https://developer.android.com/training/cars/apps/library/car-microphone).
