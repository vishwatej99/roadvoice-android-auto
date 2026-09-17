import assert from 'node:assert/strict';
import { once } from 'node:events';
import { request as httpRequest } from 'node:http';
import { test } from 'node:test';
import { createBroker, readConfig } from './broker.mjs';

const config = readConfig({
  OPENAI_API_KEY: 'fake-server-key-for-local-tests',
  ROADVOICE_CLIENT_TOKEN: 'fake-client-token-for-local-tests-0123456789',
});
const offer = 'v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=sendrecv\r\n';
const answer = offer.replace('o=- 1 1', 'o=- 2 2');
const headers = { Authorization: `Bearer ${config.clientToken}`, 'Content-Type': 'application/sdp' };

function liveResponse(sdp = answer, options = {}) {
  return Response.json({ session: { id: 'live_local_test' }, transport: { type: 'webrtc', sdp } }, {
    status: 201,
    ...options,
  });
}

async function startBroker(t, options = {}) {
  const server = createBroker(config, {
    fetchUpstream: async () => liveResponse(),
    ...options,
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise(resolve => {
    server.close(resolve);
    server.closeAllConnections();
  }));
  return `http://127.0.0.1:${server.address().port}`;
}

function postSession(base, options = {}) {
  return fetch(`${base}/session`, { method: 'POST', headers, body: offer, ...options });
}

test('rejects missing, short, or reused credentials without including them in errors', () => {
  assert.throws(() => readConfig({}), /OPENAI_API_KEY/);
  assert.throws(() => readConfig({ OPENAI_API_KEY: 'fake', ROADVOICE_CLIENT_TOKEN: 'short' }), /random token/);
  assert.throws(() => readConfig({ OPENAI_API_KEY: config.clientToken, ROADVOICE_CLIENT_TOKEN: config.clientToken }), /separate token/);
  assert.throws(() => readConfig({ OPENAI_API_KEY: config.apiKey, ROADVOICE_CLIENT_TOKEN: config.clientToken, PORT: '0' }), /PORT/);
  assert.equal(config.host, '127.0.0.1');
  assert.equal(config.model, 'gpt-live-1');
  assert.equal(config.delegationModel, 'gpt-5.6-terra');
  assert.equal(config.voice, 'marin');
});

test('uses GPT-Live configuration and never falls back to a legacy Realtime model', () => {
  const credentials = { OPENAI_API_KEY: config.apiKey, ROADVOICE_CLIENT_TOKEN: config.clientToken };
  assert.equal(readConfig({ ...credentials, OPENAI_REALTIME_MODEL: 'gpt-realtime-2.1' }).model, 'gpt-live-1');
  const customConfig = readConfig({ ...credentials, OPENAI_LIVE_MODEL: 'gpt-live-1', OPENAI_DELEGATION_MODEL: 'gpt-5.6-luna' });
  assert.equal(customConfig.model, 'gpt-live-1');
  assert.equal(customConfig.delegationModel, 'gpt-5.6-luna');
  assert.throws(() => readConfig({ ...credentials, OPENAI_LIVE_MODEL: 'not a model' }), /OPENAI_LIVE_MODEL/);
  assert.throws(() => readConfig({ ...credentials, OPENAI_DELEGATION_MODEL: 'not a model' }), /OPENAI_DELEGATION_MODEL/);
});

test('returns only health status and never exposes configuration', async t => {
  const base = await startBroker(t);
  const response = await fetch(`${base}/healthz`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { healthy: true });
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(response.headers.get('access-control-allow-origin'), null);
});

test('authenticates before sending any data to the fixed upstream', async t => {
  let attempts = 0;
  const base = await startBroker(t, { fetchUpstream: async () => { attempts += 1; throw new Error('unreachable'); } });
  for (const authorization of ['', 'Bearer incorrect', `Basic ${config.clientToken}`]) {
    const response = await postSession(base, { headers: { ...headers, Authorization: authorization } });
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), { error: 'Unauthorized.' });
  }
  assert.equal(attempts, 0);
});

test('creates a GPT-Live session as JSON and returns only its SDP answer', async t => {
  let attempts = 0;
  const base = await startBroker(t, {
    fetchUpstream: async (url, options) => {
      attempts += 1;
      assert.equal(url, 'https://api.openai.com/v1/live/sessions');
      assert.equal(options.method, 'POST');
      assert.equal(options.redirect, 'error');
      assert.deepEqual(options.headers, { Authorization: `Bearer ${config.apiKey}`, 'Content-Type': 'application/json' });
      const { session, transport } = JSON.parse(options.body);
      assert.deepEqual(transport, { type: 'webrtc', sdp: offer });
      assert.equal(session.type, undefined);
      assert.equal(session.model, config.model);
      assert.equal(session.store, false);
      assert.equal(session.audio.output.voice, 'marin');
      assert.equal(session.audio.format, undefined);
      assert.equal(session.delegation.type, 'responses');
      assert.equal(session.delegation.responses.model, 'gpt-5.6-terra');
      assert.ok(session.delegation.responses.instructions.length > 0);
      assert.equal(session.api_key, undefined);
      return liveResponse(answer, { headers: { Location: 'https://example.invalid/secret-id', 'X-Secret': config.apiKey } });
    },
  });
  const response = await postSession(base);
  assert.equal(response.status, 201);
  assert.equal(response.headers.get('content-type'), 'application/sdp');
  assert.equal(response.headers.get('location'), null);
  assert.equal(response.headers.get('x-secret'), null);
  assert.equal(await response.text(), answer);
  assert.equal(attempts, 1);
});

test('rejects malformed SDP, non-SDP content, compressed content, and oversized offers before upstream', async t => {
  let attempts = 0;
  const base = await startBroker(t, { fetchUpstream: async () => { attempts += 1; throw new Error('unreachable'); } });
  for (const body of ['', '{}', 'v=0\r\nm=video 9 RTP/AVP 96\r\n', `${offer}\u0000`]) {
    assert.equal((await postSession(base, { body })).status, 400);
  }
  assert.equal((await postSession(base, { headers: { ...headers, 'Content-Type': 'application/json' } })).status, 415);
  assert.equal((await postSession(base, { headers: { ...headers, 'Content-Encoding': 'gzip' } })).status, 415);
  assert.equal((await postSession(base, { body: 'x'.repeat(65_537) })).status, 413);
  assert.equal(attempts, 0);
});

test('enforces body size on a chunked upload without Content-Length', async t => {
  const base = await startBroker(t, { fetchUpstream: async () => assert.fail('oversized offer reached upstream') });
  const responseStatus = await new Promise((resolve, reject) => {
    const request = httpRequest(`${base}/session`, { method: 'POST', headers }, response => {
      response.resume();
      response.on('end', () => resolve(response.statusCode));
    });
    request.on('error', reject);
    request.write(offer);
    request.end('x'.repeat(65_536));
  });
  assert.equal(responseStatus, 413);
});

test('times out an incomplete upload without starting a session', async t => {
  const base = await startBroker(t, { bodyTimeoutMs: 40, fetchUpstream: async () => assert.fail('incomplete offer reached upstream') });
  const status = await new Promise((resolve, reject) => {
    const request = httpRequest(`${base}/session`, { method: 'POST', headers }, response => {
      response.resume();
      response.on('end', () => { resolve(response.statusCode); request.destroy(); });
    });
    request.on('error', reject);
    request.write(offer);
  });
  assert.equal(status, 408);
});

test('does not act as a proxy for alternate paths, query strings, or methods', async t => {
  const base = await startBroker(t, { fetchUpstream: async () => assert.fail('unsupported route reached upstream') });
  assert.equal((await fetch(`${base}/session?url=https://example.invalid`, { method: 'POST', headers, body: offer })).status, 404);
  assert.equal((await fetch(`${base}/session`)).status, 405);
  assert.equal((await fetch(`${base}/token`)).status, 404);
});

test('upstream failures are generic and never retried', async t => {
  for (const status of [400, 401, 403, 429, 500]) {
    let attempts = 0;
    const base = await startBroker(t, {
      fetchUpstream: async () => {
        attempts += 1;
        return new Response(`private upstream detail ${config.apiKey}`, { status });
      },
    });
    const response = await postSession(base);
    assert.equal(response.status, status === 429 ? 429 : 502);
    const body = await response.text();
    assert.equal(body.includes(config.apiKey), false);
    assert.equal(body.includes('private upstream detail'), false);
    assert.equal(attempts, 1);
  }
});

test('network failures do not reveal exception messages or retry session creation', async t => {
  let attempts = 0;
  const base = await startBroker(t, { fetchUpstream: async () => { attempts += 1; throw new Error(config.apiKey); } });
  const response = await postSession(base);
  assert.equal(response.status, 502);
  assert.equal((await response.text()).includes(config.apiKey), false);
  assert.equal(attempts, 1);
});

test('rejects invalid and oversized upstream answers', async t => {
  const invalidAnswers = [
    '',
    'null',
    '{"error":"unexpected"}',
    answer,
    JSON.stringify({ transport: { type: 'websocket', sdp: answer } }),
    JSON.stringify({ transport: { type: 'webrtc', sdp: 42 } }),
    JSON.stringify({ transport: { type: 'webrtc', sdp: 'not-sdp' } }),
    JSON.stringify({ transport: { type: 'webrtc', sdp: answer + 'x'.repeat(65_536) } }),
    JSON.stringify({ transport: { type: 'webrtc', sdp: answer }, metadata: 'x'.repeat(131_072) }),
  ];
  for (const body of invalidAnswers) {
    const base = await startBroker(t, { fetchUpstream: async () => new Response(body, { status: 201 }) });
    assert.equal((await postSession(base)).status, 502);
  }
});

test('aborts a stalled upstream request at the deadline and does not retry', async t => {
  let attempts = 0;
  let wasAborted = false;
  const base = await startBroker(t, {
    upstreamTimeoutMs: 40,
    fetchUpstream: (_url, { signal }) => {
      attempts += 1;
      return new Promise((_resolve, reject) => signal.addEventListener('abort', () => {
        wasAborted = true;
        reject(signal.reason);
      }, { once: true }));
    },
  });
  const response = await postSession(base);
  assert.equal(response.status, 504);
  assert.equal(wasAborted, true);
  assert.equal(attempts, 1);
});

test('cancels upstream setup when the Android client disconnects', async t => {
  let markStarted;
  let markAborted;
  const started = new Promise(resolve => { markStarted = resolve; });
  const aborted = new Promise(resolve => { markAborted = resolve; });
  const base = await startBroker(t, {
    fetchUpstream: (_url, { signal }) => {
      markStarted();
      return new Promise((_resolve, reject) => signal.addEventListener('abort', () => {
        markAborted();
        reject(signal.reason);
      }, { once: true }));
    },
  });
  const request = httpRequest(`${base}/session`, { method: 'POST', headers });
  request.on('error', () => {});
  request.end(offer);
  await started;
  request.destroy();
  await Promise.race([aborted, new Promise((_resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('upstream was not aborted')), 1_000);
    timer.unref();
  })]);
});

test('limits authenticated session creation and permits new setup after the window expires', async t => {
  let currentTime = 100_000;
  let attempts = 0;
  const base = await startBroker(t, {
    maxSessionStartsPerMinute: 2,
    now: () => currentTime,
    fetchUpstream: async () => { attempts += 1; return liveResponse(); },
  });
  assert.equal((await postSession(base)).status, 201);
  assert.equal((await postSession(base)).status, 201);
  assert.equal((await postSession(base)).status, 429);
  assert.equal(attempts, 2);
  currentTime += 60_001;
  assert.equal((await postSession(base)).status, 201);
  assert.equal(attempts, 3);
});
