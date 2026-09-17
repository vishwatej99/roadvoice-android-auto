import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createWorker } from './worker.mjs';

const environment = {
  OPENAI_API_KEY: 'fake-project-key-for-local-tests',
  ROADVOICE_CLIENT_TOKEN: 'fake-client-token-for-local-tests-0123456789',
};
const offer = 'v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=sendrecv\r\n';
const answer = offer.replace('o=- 1 1', 'o=- 2 2');
const headers = { Authorization: `Bearer ${environment.ROADVOICE_CLIENT_TOKEN}`, 'Content-Type': 'application/sdp' };

function liveResponse(sdp = answer, options = {}) {
  return Response.json({ session: { id: 'live_local_test' }, transport: { type: 'webrtc', sdp } }, {
    status: 201, ...options,
  });
}

function createTestWorker(options = {}) {
  return createWorker({ fetchUpstream: async () => liveResponse(), ...options });
}

function postSession(worker, options = {}, bindings = environment) {
  return worker.fetch(new Request('https://roadvoice.invalid/session', {
    method: 'POST', headers, body: offer, ...options,
  }), bindings);
}

test('returns process health without exposing or requiring secrets', async () => {
  const response = await createTestWorker().fetch(new Request('https://roadvoice.invalid/healthz'), {});
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { healthy: true });
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  assert.equal(response.headers.get('Access-Control-Allow-Origin'), null);
});

test('fails closed when either secret is missing, short, malformed, or reused', async () => {
  const worker = createTestWorker({ fetchUpstream: () => assert.fail('invalid secrets reached upstream') });
  const invalidBindings = [
    {}, { OPENAI_API_KEY: environment.OPENAI_API_KEY },
    { ...environment, ROADVOICE_CLIENT_TOKEN: 'short' },
    { ...environment, OPENAI_API_KEY: 'bad key' },
    { ...environment, OPENAI_API_KEY: environment.ROADVOICE_CLIENT_TOKEN },
  ];
  for (const bindings of invalidBindings) {
    const response = await postSession(worker, {}, bindings);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: 'Voice service is not configured.' });
  }
});

test('authenticates before reading the body or calling OpenAI', async () => {
  const worker = createTestWorker({ fetchUpstream: () => assert.fail('unauthorized request reached upstream') });
  for (const authorization of ['', 'Bearer incorrect', `Basic ${environment.ROADVOICE_CLIENT_TOKEN}`, `Bearer ${'x'.repeat(8192)}`]) {
    const response = await postSession(worker, { headers: { ...headers, Authorization: authorization } });
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), { error: 'Unauthorized.' });
  }
});

test('accepts only the native HTTPS session route without browser CORS access', async () => {
  const worker = createTestWorker({ fetchUpstream: () => assert.fail('unsupported route reached upstream') });
  for (const [url, method, status] of [
    ['https://roadvoice.invalid/session', 'GET', 405],
    ['https://roadvoice.invalid/session', 'OPTIONS', 405],
    ['https://roadvoice.invalid/session?url=https://other.invalid', 'POST', 404],
    ['https://roadvoice.invalid/token', 'GET', 404],
    ['http://roadvoice.invalid/session', 'POST', 400],
  ]) {
    assert.equal((await worker.fetch(new Request(url, { method }), environment)).status, status);
  }
  const response = await postSession(worker, { headers: { ...headers, Origin: 'https://other.invalid' } });
  assert.equal(response.status, 403);
  assert.equal(response.headers.get('Access-Control-Allow-Origin'), null);
});

test('sends exactly one fixed GPT-Live request and returns only the SDP answer', async () => {
  let attempts = 0;
  const worker = createTestWorker({
    fetchUpstream: async (url, options) => {
      attempts += 1;
      assert.equal(url, 'https://api.openai.com/v1/live/sessions');
      assert.equal(options.method, 'POST');
      assert.equal(options.redirect, 'manual');
      assert.deepEqual(options.headers, { Authorization: `Bearer ${environment.OPENAI_API_KEY}`, 'Content-Type': 'application/json' });
      const { session, transport } = JSON.parse(options.body);
      assert.deepEqual(transport, { type: 'webrtc', sdp: offer });
      assert.equal(session.model, 'gpt-live-1');
      assert.equal(session.store, false);
      assert.equal(session.audio.output.voice, 'marin');
      assert.equal(session.audio.format, undefined);
      assert.equal(session.delegation.type, 'responses');
      assert.equal(session.delegation.responses.model, 'gpt-5.6-terra');
      assert.equal(session.delegation.responses.tools, undefined);
      assert.equal(JSON.stringify(session).includes(environment.ROADVOICE_CLIENT_TOKEN), false);
      return liveResponse(answer, { headers: { Location: 'https://other.invalid/private-id', 'X-Secret': environment.OPENAI_API_KEY } });
    },
  });
  const response = await postSession(worker, {}, { ...environment, OPENAI_LIVE_MODEL: 'untrusted-model' });
  assert.equal(response.status, 201);
  assert.equal(response.headers.get('Content-Type'), 'application/sdp');
  assert.equal(response.headers.get('Location'), null);
  assert.equal(response.headers.get('X-Secret'), null);
  assert.equal(await response.text(), answer);
  assert.equal(attempts, 1);
});

test('rejects invalid audio offers, media types, encoding, and advertised size', async () => {
  const worker = createTestWorker({ fetchUpstream: () => assert.fail('invalid offer reached upstream') });
  for (const body of ['', '{}', 'v=0\r\nm=video 9 RTP/AVP 96\r\n', `${offer}\u0000`, new Uint8Array([255])]) {
    assert.equal((await postSession(worker, { body })).status, 400);
  }
  assert.equal((await postSession(worker, { headers: { ...headers, 'Content-Type': 'application/json' } })).status, 415);
  assert.equal((await postSession(worker, { headers: { ...headers, 'Content-Encoding': 'gzip' } })).status, 415);
  assert.equal((await postSession(worker, { headers: { ...headers, 'Content-Length': '65537' } })).status, 413);
});

test('limits streamed input even without Content-Length', async () => {
  let wasCancelled = false;
  const body = new ReadableStream({
    start(controller) { controller.enqueue(new TextEncoder().encode(offer + 'x'.repeat(65_536))); },
    cancel() { wasCancelled = true; },
  });
  const worker = createTestWorker({ fetchUpstream: () => assert.fail('oversized offer reached upstream') });
  assert.equal((await postSession(worker, { body, duplex: 'half' })).status, 413);
  assert.equal(wasCancelled, true);
});

test('times out an incomplete upload and releases the request slot', async () => {
  let wasCancelled = false;
  const body = new ReadableStream({ cancel() { wasCancelled = true; } });
  const worker = createTestWorker({ bodyTimeoutMs: 10 });
  assert.equal((await postSession(worker, { body, duplex: 'half' })).status, 408);
  assert.equal(wasCancelled, true);
  assert.equal((await postSession(worker)).status, 201);
});

test('rejects generic upstream failures without retries or private details', async () => {
  for (const status of [301, 400, 401, 403, 429, 500]) {
    let attempts = 0;
    const worker = createTestWorker({ fetchUpstream: async () => {
      attempts += 1;
      return new Response(`private ${environment.OPENAI_API_KEY}`, { status });
    } });
    const response = await postSession(worker);
    assert.equal(response.status, status === 429 ? 429 : 502);
    assert.equal((await response.text()).includes(environment.OPENAI_API_KEY), false);
    assert.equal(attempts, 1);
  }
});

test('does not expose network exceptions or retry ambiguous creation', async () => {
  let attempts = 0;
  const worker = createTestWorker({ fetchUpstream: async () => { attempts += 1; throw new Error(environment.OPENAI_API_KEY); } });
  const response = await postSession(worker);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), {
    error: 'Voice session could not be created.', diagnostic: 'upstream_request_failed',
    stage: 'create_session', exceptionName: 'Error', exceptionReason: 'unclassified',
  });
  assert.equal(attempts, 1);
});

test('returns only fixed authenticated upstream diagnostics and excludes private error fields', async () => {
  const worker = createTestWorker({ fetchUpstream: async () => Response.json({
    error: { code: 'invalid_api_key', type: 'authentication_error', message: environment.OPENAI_API_KEY, param: offer },
  }, { status: 401, headers: { 'X-Secret': environment.OPENAI_API_KEY } }) });
  const response = await postSession(worker);
  assert.deepEqual(await response.json(), {
    error: 'Voice session could not be created.', diagnostic: 'upstream_rejected',
    upstreamStatus: 401, upstreamCode: 'invalid_api_key', upstreamType: 'authentication_error',
  });
  const unknownWorker = createTestWorker({ fetchUpstream: async () => Response.json({
    error: { code: environment.OPENAI_API_KEY, type: offer, message: environment.ROADVOICE_CLIENT_TOKEN },
  }, { status: 400 }) });
  const unknownResponse = await postSession(unknownWorker);
  assert.deepEqual(await unknownResponse.json(), {
    error: 'Voice session could not be created.', diagnostic: 'upstream_rejected', upstreamStatus: 400,
  });
});

test('rejects invalid, legacy, non-audio, or oversized upstream JSON answers', async () => {
  const invalidAnswers = [
    '', 'null', '{"error":"unexpected"}', answer,
    JSON.stringify({ transport: { type: 'websocket', sdp: answer } }),
    JSON.stringify({ transport: { type: 'webrtc', sdp: 42 } }),
    JSON.stringify({ transport: { type: 'webrtc', sdp: 'not-sdp' } }),
    JSON.stringify({ transport: { type: 'webrtc', sdp: answer + 'x'.repeat(65_536) } }),
    JSON.stringify({ transport: { type: 'webrtc', sdp: answer }, metadata: 'x'.repeat(131_072) }),
  ];
  for (const body of invalidAnswers) {
    const worker = createTestWorker({ fetchUpstream: async () => new Response(body, { status: 201 }) });
    assert.equal((await postSession(worker)).status, 502);
  }
});

test('aborts a stalled upstream request at the deadline without retrying', async () => {
  let signal;
  let attempts = 0;
  const worker = createTestWorker({ upstreamTimeoutMs: 10, fetchUpstream: (_url, options) => {
    signal = options.signal;
    attempts += 1;
    return new Promise(() => {});
  } });
  assert.equal((await postSession(worker)).status, 504);
  assert.equal(signal.aborted, true);
  assert.equal(attempts, 1);
});

test('upstream timeout also covers a response body that stops arriving', async () => {
  let wasCancelled = false;
  const worker = createTestWorker({ upstreamTimeoutMs: 10, fetchUpstream: async () => new Response(new ReadableStream({
    start(controller) { controller.enqueue(new TextEncoder().encode('{')); },
    cancel() { wasCancelled = true; },
  }), { status: 201 }) });
  assert.equal((await postSession(worker)).status, 504);
  assert.equal(wasCancelled, true);
});

test('cancels pending OpenAI setup when the phone request is aborted', async () => {
  const controller = new AbortController();
  let upstreamSignal;
  let markStarted;
  const started = new Promise(resolve => { markStarted = resolve; });
  const worker = createTestWorker({ fetchUpstream: (_url, options) => {
    upstreamSignal = options.signal;
    markStarted();
    return new Promise(() => {});
  } });
  const pendingResponse = postSession(worker, { signal: controller.signal });
  await started;
  controller.abort();
  assert.equal((await pendingResponse).status, 499);
  assert.equal(upstreamSignal.aborted, true);
});

test('never starts OpenAI setup for an already cancelled phone request', async () => {
  const controller = new AbortController();
  controller.abort();
  const worker = createTestWorker({ fetchUpstream: () => assert.fail('cancelled request reached upstream') });
  assert.equal((await postSession(worker, { signal: controller.signal })).status, 499);
});

test('limits session starts to six per rolling minute per isolate', async () => {
  let currentTime = 100_000;
  let attempts = 0;
  const worker = createTestWorker({ now: () => currentTime, fetchUpstream: async () => { attempts += 1; return liveResponse(); } });
  for (let index = 0; index < 6; index += 1) assert.equal((await postSession(worker)).status, 201);
  assert.equal((await postSession(worker)).status, 429);
  assert.equal(attempts, 6);
  currentTime += 60_001;
  assert.equal((await postSession(worker)).status, 201);
});

test('limits concurrent setup to two and releases slots after cancellation', async () => {
  const controllers = [new AbortController(), new AbortController()];
  const pendingRequests = [];
  let markBothStarted;
  const bothStarted = new Promise(resolve => { markBothStarted = resolve; });
  let attempts = 0;
  const worker = createTestWorker({ fetchUpstream: () => {
    attempts += 1;
    if (attempts === 2) markBothStarted();
    return new Promise(() => {});
  } });
  for (const controller of controllers) pendingRequests.push(postSession(worker, { signal: controller.signal }));
  await bothStarted;
  assert.equal((await postSession(worker)).status, 429);
  for (const controller of controllers) controller.abort();
  assert.deepEqual((await Promise.all(pendingRequests)).map(response => response.status), [499, 499]);
});
