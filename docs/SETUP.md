# Set up your own RoadVoice

This is the route I used: an Android app installed through a private Google Play
test, plus a small Cloudflare Worker on the free plan. Once configured, the phone
uses its own internet connection. You do not need to keep a computer running.

## What you need

- An Android phone and compatible Android Auto head unit. The app's minimum is
  Android 9/API 28, but that alone does not establish car compatibility.
- An OpenAI API project with billing and access to `gpt-live-1` and the configured
  delegation model, currently `gpt-5.6-terra`. A ChatGPT subscription is separate.
- Your own Cloudflare account. Workers Free is enough for the small setup service
  within its limits; OpenAI usage is still billed separately.
- Android Studio, Android SDK 36, and JDK 17. Gradle 8.11.1 is pinned by the wrapper.
- Node.js 22.9 or later; Node.js 24 LTS is a suitable choice.
- A Google Play developer account for the installation route below.

## 1. Get the source

```sh
git clone https://github.com/vishwatej99/roadvoice-android-auto.git
cd roadvoice-android-auto
```

Use your own fork if you plan to publish changes. Never add real credentials to
the example files or commit local settings.

## 2. Deploy your voice server

Create a [Cloudflare account](https://dash.cloudflare.com/sign-up) and use the
Workers Free plan. From the repository:

```sh
cd worker
npx wrangler@4 login
```

Choose a Worker name in `wrangler.jsonc`. The supplied name is `roadvoice-voice`;
change it if that name already identifies another Worker in your account.

Copy `.dev.vars.example` to `.dev.vars` and edit the copy locally:

```dotenv
OPENAI_API_KEY=your-own-openai-project-key
ROADVOICE_CLIENT_TOKEN=your-own-long-random-token
```

The second value is a separate password for your phone, not another OpenAI key.
Generate it locally, for example:

```sh
node -e "console.log(require('node:crypto').randomBytes(32).toString('base64url'))"
```

Keep `.dev.vars` private. It is ignored by Git. Deploy the code and upload both
values as Worker secrets:

```sh
npx wrangler@4 deploy --secrets-file .dev.vars
```

Wrangler prints your own HTTPS Worker URL. Save it for the phone. Do not use a URL
from someone else's installation.

The `/healthz` path should return `{"healthy":true}`. This only checks that the
Worker runs; it does not verify API billing, model access, or a working voice call.
For later secret rotation, use `npx wrangler@4 secret put OPENAI_API_KEY` or
`npx wrangler@4 secret put ROADVOICE_CLIENT_TOKEN` and enter the value at the prompt.
Update the phone if you rotate its token.

See [Worker details](../worker/README.md) and
[Cloudflare's secret instructions](https://developers.cloudflare.com/workers/configuration/secrets/).

## 3. Build the Android app

Open `android/` in Android Studio and install the SDK components it requests.
Android Studio can create your machine's ignored `local.properties`. For a
terminal build, set `ANDROID_HOME` to your SDK and `JAVA_HOME` to JDK 17.

**Choose your own permanent application ID before uploading to Play.** Package
names are globally unique. The source defaults to `dev.roadvoice`, which belongs
to the original installation; you cannot upload another app under that ID.

Pass `-ProadvoiceApplicationId=com.yourname.roadvoice` to Gradle, replacing the
example with your own ID. Or put `roadvoiceApplicationId=...` in your local
Gradle user properties. Keep using the same value for future updates. The Kotlin
namespace can stay `dev.roadvoice`.

For a phone-only debug build:

```sh
cd android
./gradlew -ProadvoiceApplicationId=com.yourname.roadvoice assembleDebug testDebugUnitTest lintDebug
```

On Windows use `gradlew.bat` instead of `./gradlew`. In PowerShell quote the
property argument: `"-ProadvoiceApplicationId=com.yourname.roadvoice"`.
The debug APK is at
`app/build/outputs/apk/debug/app-debug.apk`. Its ID has a `.debug` suffix and it can
coexist with the release app. A sideloaded debug build is useful for phone tests;
it does not guarantee launcher access in an actual car.

### Sign your Play build

Create and retain your own upload keystore using Android Studio or JDK `keytool`.
Keep it outside this repository and back it up privately. Never use another
person's signing key. The build reads these environment variables:

| Variable | Value you provide |
| --- | --- |
| `ROADVOICE_UPLOAD_STORE_FILE` | Absolute path to your PKCS12 upload keystore |
| `ROADVOICE_UPLOAD_STORE_PASSWORD` | Keystore password |
| `ROADVOICE_UPLOAD_KEY_ALIAS` | Key alias |
| `ROADVOICE_UPLOAD_KEY_PASSWORD` | Key password |

Set them locally without committing them or putting real passwords in shared
shell commands. With those variables configured, run from `android/`:

```sh
./gradlew -ProadvoiceApplicationId=com.yourname.roadvoice bundleRelease lintRelease
```

Upload `app/build/outputs/bundle/release/app-release.aab` to your Play test. The
release is non-debuggable and requires HTTPS. A missing signing configuration
fails the release build instead of silently using a debug key. For subsequent
uploads, increase `versionCode` in `app/build.gradle.kts` and retain the same app
ID and upload identity.

## 4. Install through your private Google Play test

Register in [Google Play Console](https://play.google.com/console), complete its
identity/device verification steps, and create an app entry for your build.
Google currently lists a US$25 one-time developer registration fee; check its
[registration page](https://support.google.com/googleplay/android-developer/answer/6112435)
for your current requirements.

Create an **Internal testing** release, upload your signed AAB, and select a
tester list containing only the Google accounts you want to use it. Review and
accept Google's declarations yourself, then roll out that internal release.
Open the test invitation with the same account on the phone, join the test, and
install from Play. Future releases may take time to appear as an Update.

Use your own invitation and developer account. Nothing in this repo grants access
to the original private test. You do not need a public production listing for
this personal workflow. Internal testing is not public policy approval; follow
Google's current category and distribution requirements if you distribute more
widely. See [Android Auto testing](https://developer.android.com/training/cars/testing).

## 5. Connect the phone

Open the app while parked and complete its requested microphone, notification,
and nearby-device permissions. Enter your Worker **base URL** and your separate
`ROADVOICE_CLIENT_TOKEN`. Do not append `/session` and do not enter the OpenAI key
in the phone's token field. Save the connection, then test Talk and End.

The OpenAI key stays in your Worker. The phone stores the broker token encrypted
with an Android Keystore key. Anyone who gets that token can create sessions on
your broker, so keep it private.

## 6. Try Android Auto

Connect to your car and look for **ChatGPT Voice for Android Auto** in its app
launcher. The unreviewed private Play listing may temporarily show your package
name instead of the app's normal display name.

To try the non-call route, enable **Car microphone preview** on the phone while
parked, then start **Talk from the car's app screen**. Starting from the phone
continues to use the regular call path. The preview avoids creating a Telecom
call, which is useful on head units that block other features during a call.
It remains experimental and is off by default.

Check an audible reply, Mute, End, reopening, Maps, and interruptions on your
setup. Media can pause during voice and an audio-focus change can end the preview.
If this route is unreliable, turn the preview off to return to the call path.

## Troubleshooting

| Problem | First thing to check |
| --- | --- |
| Voice server access refused | The saved phone token must exactly match the Worker's `ROADVOICE_CLIENT_TOKEN`. Save it again after rotation. |
| Setup failed or no reply | Check API billing/model access, phone internet, Worker deployment, and the base URL. A healthy `/healthz` response alone is insufficient. |
| App missing from the car launcher | Confirm the correct private Play account/build and check Android Auto's Customize launcher settings. |
| Other car features blocked during a call | Try Car microphone preview, started from the car screen, while parked. |
| Stuck on Ending | Confirm version 0.4.1 or later, try End again, then End and close app. Report a reproducible failure without private logs. |
| Local HTTP URL rejected | Normal release builds require HTTPS. Loopback HTTP is only for debug/USB testing. |

The [local Node broker](../server/README.md) is optional for USB development. It
is not needed for the hosted setup described here.
