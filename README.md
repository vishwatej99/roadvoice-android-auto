# RoadVoice: GPT-Live voice for Android Auto

Finally, an alternative to Gemini in Android Auto if you are also frustrated with Gemini's robotic voice mode. Try out this ChatGPT plugin for Android Auto.

I've used GPT-Live-1 voice through their API. GPT-Live-1 has more natural interruption handling and an overall better experience, much like talking to an actual person. At least for now, this seems like the best option available.

The original idea came from [Jonathan Roomer's NightBloodRemote](https://github.com/jonathanroomer/NightBloodRemote). He built something similar for iPhone and CarPlay, and seeing his project made me want to try the Android Auto side of it. Do check out his project if you want something for Apple CarPlay.

## What works

This is the version I'm currently using, `0.4.1`.

On my setup, I can:

* Have GPT-Live voice conversations through Android Auto, in park mode as well as while driving, just like the Gemini assistant.
* Switch to Maps and other Android Auto apps without ending the conversation.
* Talk, mute, end the conversation, and start another one afterward.
* Run everything through a hosted setup using the phone's internet, so there is no laptop sitting in the car or server running locally.
* Use GPT-Live-1 for the voice conversation, with `gpt-5.6-terra` configured for delegated reasoning.

This is still an experimental personal project. I've tested it on my own phone and car, but I obviously haven't tested every Android phone or Android Auto head unit. Android Auto can also behave differently depending on the car.

There are currently two ways of handling the car audio.

The regular path uses Android's calling integration. This is the more established route, but Android Auto may show a call-style screen while you're talking.

I also added an optional `Car microphone preview`. This tries to use the Android Auto microphone directly without registering the session as a call. It is off by default because I'm still treating it as experimental.

One thing to be clear about: RoadVoice is an alternative voice experience inside Android Auto. It does not replace Google Assistant at the system level.

It cannot take over "Hey Google" or the steering-wheel assistant button, and it does not automatically control Maps, messages, Spotify, or other Android Auto apps.

There are also no external tools connected to the model by default.

## How I set it up

The setup I ended up using is:

1. Create a Google Play developer account.
2. Build the Android app and upload it to a private `Internal testing` track.
3. Create a free Cloudflare account and deploy the Worker included in this repo.
4. Store your OpenAI API key in Cloudflare as a secret, along with a separate random access token for the phone.
5. Install the app through your private Play test.
6. Add the Worker URL and access token in the app.
7. Connect to Android Auto and test it in the car.

The Cloudflare Worker is only used to set up the OpenAI session.

Your API key stays on the Worker instead of being stored inside the Android app. Once the session is created, the actual voice connection is directly between the phone and OpenAI over WebRTC.

```text
Android app ── private access token ──> Your Cloudflare Worker
                                             │
                                      Your OpenAI API key
                                             │
                                             ▼
                                      Session setup

Android app <──────── voice over WebRTC ────────> OpenAI
```

[Follow the full setup guide →](https://github.com/vishwatej99/roadvoice-android-auto/blob/main/docs/SETUP.md)

You'll need your own OpenAI account, API key, Cloudflare setup, Google Play developer account, and Android upload signing key.

Nothing in this repo connects to my OpenAI account or my Cloudflare Worker. The code is open source, but the actual service is something you host for yourself.

I'm using the Cloudflare Workers free tier for this. OpenAI API usage is still paid, and Google Play developer registration also has a fee.

Since those things can change, check the current [Cloudflare Workers limits](https://developers.cloudflare.com/workers/platform/limits/) and [Google Play registration requirements](https://support.google.com/googleplay/android-developer/answer/6112435) before setting everything up.

## A few things to know

Do the initial setup and testing while parked. Especially the audio controls.

The `Car microphone preview` is still experimental. Things like audio routing, reconnects, navigation interruptions, and different head units can behave differently, so you'll need to test it on your own setup.

This is also not a Google-approved general assistant replacement.

The current Car App Library service declares the calling category for the call-based mode. The non-call microphone path is still experimental. The fact that this works in my private Play test does not mean Google would approve the same thing for a public Play Store release.

There is an animated orb on the phone, but there is currently no NightBlood-style animated face on the Android Auto display.

I actually tried building a separate parked-screen prototype for that. Android Auto didn't expose it the way I expected during testing, so I left it out.

The Cloudflare broker is also meant for personal use. The shared token and the current rate limiting are enough for my setup, but this is not meant to be a proper multi-user authentication or billing system.

## The code

| Folder                                                                                | What's in it                                                                |
| ------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| [`android/`](https://github.com/vishwatej99/roadvoice-android-auto/blob/main/android) | Native Kotlin app, WebRTC audio, Android Auto screen, and session lifecycle |
| [`worker/`](https://github.com/vishwatej99/roadvoice-android-auto/blob/main/worker)   | Cloudflare Worker used for hosted session setup                             |
| [`server/`](https://github.com/vishwatej99/roadvoice-android-auto/blob/main/server)   | Optional Node.js broker I used for local USB development                    |
| [`docs/`](https://github.com/vishwatej99/roadvoice-android-auto/blob/main/docs)       | Setup, testing, and other project notes                                     |

The Worker and local broker tests use fake upstream responses, so running them does not spend OpenAI API credits.

```bash
npm --prefix worker test
npm --prefix server test
```

Android build and test commands are in the [setup guide](https://github.com/vishwatej99/roadvoice-android-auto/blob/main/docs/SETUP.md).

I've also documented what I've actually tested, and what I haven't, in [TESTING.md](https://github.com/vishwatej99/roadvoice-android-auto/blob/main/docs/TESTING.md).

## Credits

I used OpenAI Codex quite a bit while building, debugging, and testing this.

A lot of the work was honestly trial and error between the code, the phone, and the actual car, especially when it came to Android Auto audio routing and making sure sessions closed properly.

Thanks also to the AndroidX, WebRTC, Kotlin, OkHttp, and Cloudflare projects that made most of this possible.

More details are in [ACKNOWLEDGEMENTS.md](https://github.com/vishwatej99/roadvoice-android-auto/blob/main/ACKNOWLEDGEMENTS.md).

## Contributing

If you try this on a different phone or car, I'd genuinely be interested in knowing what works and what doesn't.

If you open an issue, it would help if you include your phone, Android version, Android Auto version, car/head unit, which audio mode you used, and the steps to reproduce the problem.

See [CONTRIBUTING.md](https://github.com/vishwatej99/roadvoice-android-auto/blob/main/CONTRIBUTING.md) and [SECURITY.md](https://github.com/vishwatej99/roadvoice-android-auto/blob/main/SECURITY.md).

## License

[MIT](https://github.com/vishwatej99/roadvoice-android-auto/blob/main/LICENSE)

This is an independent project. It is not an official OpenAI, Google, Cloudflare, or NightBloodRemote product. Their respective names and trademarks belong to their owners.
