# RoadVoice Cloudflare Worker

This is a small authenticated session-setup service for the Android app. It has
no runtime npm dependencies. Start with the [setup guide](../docs/SETUP.md).

Each user deploys their own Worker. Set `OPENAI_API_KEY` and
`ROADVOICE_CLIENT_TOKEN` as secret bindings; never put their values in
`wrangler.jsonc`. There is no default endpoint, shared account, or API credit
included with the source.

The Worker creates a `gpt-live-1` session with voice `marin`, `store: false`, and
Responses delegation through `gpt-5.6-terra`. The model instructions include no
external tools or live-data access. Voice audio goes directly between the Android
WebRTC peer and OpenAI. Cloudflare handles the initial SDP exchange, not the audio.

## Deploy and test

```sh
npm test
npx wrangler@4 login
```

Choose a Worker name in `wrangler.jsonc`. Copy `.dev.vars.example` to the ignored
`.dev.vars`, fill it with your own credentials, then deploy:

```sh
npx wrangler@4 deploy --secrets-file .dev.vars
```

The resulting HTTPS URL is the base URL to enter in your Android app. `/healthz`
only verifies process health. A real voice test is a separate, billable check.

Cloudflare Workers Free is the intended starting point, subject to the current
[plan limits](https://developers.cloudflare.com/workers/platform/limits/).
No paid storage, Workers AI, or Durable Objects are configured. OpenAI usage is
billed to your API project.

## Request contract

- `GET /healthz`: `200 {"healthy":true}`. No credential or model check.
- `POST /session`: `Authorization: Bearer <your client token>`,
  `Content-Type: application/sdp`, with a raw audio WebRTC offer.
- Success: `201 application/sdp`, containing only the remote SDP answer.
- Errors are generic JSON. They never include raw credentials, request IDs,
  upstream free-form messages, conversation content, or SDP.

Authenticated failures may include fixed diagnostic categories, HTTP status,
and allowlisted provider codes. Offers and answers are bounded to 64 KiB;
upstream JSON to 128 KiB. Upload timeout is 10 seconds and upstream fetch/body
timeout is 25 seconds. Redirects are rejected, and session creation is not
automatically retried because the first request may already have created a
billable session.

The setup limiter allows two requests in flight and six attempts per rolling
minute **per Worker isolate**. It resets with that isolate and is neither a
global quota nor a spending cap. After setup, the Worker cannot enforce the
WebRTC conversation duration. This is a personal broker, not a multi-user service.

The public HTTPS address is protected by the broker token. Google Play tester
restrictions do not authenticate requests to the Worker. A person who obtains
the token can use the endpoint until you rotate it.

## Privacy and protocol

The code does not log credentials, SDP, or conversations. Wrangler configuration
disables application observability logs, traces, and preview URLs; this does not
describe every provider-side platform/security record. Requests with browser
Origin headers are rejected. There is no browser CORS API.

The fixed upstream is `POST https://api.openai.com/v1/live/sessions`. A successful
HTTP exchange starts the session. The Android app creates the `oai-events` data
channel before its offer, waits for ICE gathering, then waits for `session.started`.
Ending sends `session.close` and awaits `session.closed` within a bounded window
while releasing local audio resources.

`npm test` uses fake upstream responses without API keys or network calls. It
covers authentication, model configuration, size/time limits, cancellation,
rate limits, and the no-retry behavior.

References: [Cloudflare secrets](https://developers.cloudflare.com/workers/configuration/secrets/),
[GPT-Live WebRTC](https://developers.openai.com/api/docs/guides/voice-webrtc),
[Responses delegation](https://developers.openai.com/api/docs/guides/live-delegation).
