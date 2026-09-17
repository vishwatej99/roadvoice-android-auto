# RoadVoice session broker

This small Node.js server authenticates the Android app and creates a GPT-Live WebRTC session. Audio then flows directly between the Android peer and OpenAI. The OpenAI project key stays on this server. There are no external npm dependencies.

## Run

Use Node.js 22.9 or later (or Node.js 24 LTS). Copy `.env.example` to `.env`, enter your OpenAI project API key, and generate a separate random `ROADVOICE_CLIENT_TOKEN` using the command in that file. Your project needs access to `gpt-live-1` and the configured Responses delegation model. There is no automatic fallback to another voice model. Do not put the OpenAI key in the Android app.

Start the broker from this directory, then connect your phone by USB with USB debugging enabled and authorize this computer:

```text
npm start
adb reverse tcp:8787 tcp:8787
```

Enter `http://127.0.0.1:8787` and the same client token in the Android app. Keep `HOST=127.0.0.1`. The debug Android build must permit cleartext HTTP for this loopback address. This setup uses no public tunnel; do not publish this HTTP listener. No browser CORS configuration is needed.

USB forwards session setup requests to this computer. Both the phone and the computer still need internet access to OpenAI. Keep the phone connected to the computer while creating sessions, and repeat `adb reverse` after reconnecting if the forwarding was lost. Moving the phone's USB cable to a car disconnects this broker connection; a simultaneous car test requires a connection arrangement that keeps USB to the computer, such as wireless Android Auto.

`npm test` runs local tests with fake upstream responses and no OpenAI network requests or billing.

## Wire contract

- `POST /session`: `Authorization: Bearer <ROADVOICE_CLIENT_TOKEN>` and `Content-Type: application/sdp`; body is the raw WebRTC offer, at most 65,536 bytes. The SDP must begin with `v=0` and include an audio media section.
- Success: HTTP `201`, `Content-Type: application/sdp`, raw SDP answer. Apply it as the peer's remote answer. Create an `oai-events` data channel before creating the offer and wait for ICE gathering before sending the offer. No API key, ephemeral key, session identifier, or upstream `Location` is returned through HTTP.
- Errors: JSON `{"error":"human-readable generic message"}`. `400` invalid offer, `401` invalid client token, `408` input timeout, `413` oversized body, `415` content type/encoding, `429` rate limit, `502` upstream failure/invalid answer, `504` upstream timeout. Unknown routes return `404`; non-POST session requests return `405`.
- `GET /healthz`: `200 {"healthy":true}` reports process health only. It does not check model access, billing, or OpenAI availability.

The server sends one authenticated JSON `POST https://api.openai.com/v1/live/sessions` with this structure:

```json
{
  "session": {
    "model": "gpt-live-1",
    "store": false,
    "instructions": "Server-owned instructions for a spoken conversation.",
    "audio": { "output": { "voice": "marin" } },
    "delegation": {
      "type": "responses",
      "responses": {
        "model": "gpt-5.6-terra",
        "instructions": "Server-owned backend instructions."
      }
    }
  },
  "transport": { "type": "webrtc", "sdp": "<offer>" }
}
```

It validates the response's `transport.sdp` and returns only that SDP. Configurable values are `OPENAI_LIVE_MODEL` (default `gpt-live-1`), `OPENAI_DELEGATION_MODEL` (default `gpt-5.6-terra`), and `OPENAI_VOICE` (default `marin`). `OPENAI_REALTIME_MODEL` is not used. No client-specified URL, model, voice, or tool is accepted through the broker. The default delegation has no external tools or live-data access.

The HTTP request starts the GPT-Live session: wait for `session.started` on the data channel rather than sending `session.start`. Use WebRTC audio tracks for input and output; omit `audio.format` and audio chunk events. When ending a conversation, send `session.close`, await `session.closed`, then release the peer and microphone. If that acknowledgement times out, final usage remains unconfirmed even after local cleanup.

## Limits and operational scope

This is a single-owner prototype. Setup allows two requests in flight and six session-creation attempts per minute per server process, with a 10-second body timeout and a 25-second upstream timeout. Offers and answers are limited to 64 KiB, and the upstream JSON response is limited to 128 KiB. Limits reset on process restart and do not coordinate across replicas. In-flight HTTP setup is aborted when the client disconnects. Session creation is never automatically retried: an uncertain result may already have created a session.

The broker cannot enforce a conversation-duration limit after setup, since media and session events use the direct WebRTC peer. GPT-Live bills session duration, and delegated Responses usage is billed separately. Treat the client token like a password, rotate it if exposed, and set account/project spending controls separately. Use one process for this personal prototype; a multi-user service requires individual authentication, shared quotas, and server-side session controls. The broker does not log authorization headers, SDP, conversations, or upstream error details.

Implementation references: [GPT-Live WebRTC guide](https://developers.openai.com/api/docs/guides/voice-webrtc#connect-a-browser-to-gpt-live), [GPT-Live-1 model](https://developers.openai.com/api/docs/models/gpt-live-1), [Responses delegation](https://developers.openai.com/api/docs/guides/live-delegation#configure-responses-delegation), [session configuration](https://developers.openai.com/api/docs/guides/live-conversations#configuration-fields).
