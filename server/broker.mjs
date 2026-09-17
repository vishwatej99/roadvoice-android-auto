import { createHash, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';

const liveSessionsUrl = 'https://api.openai.com/v1/live/sessions';
const maximumSdpBytes = 64 * 1024;
const maximumSessionResponseBytes = 128 * 1024;

class RequestError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export function readConfig(environment = process.env) {
  const apiKey = environment.OPENAI_API_KEY?.trim();
  const clientToken = environment.ROADVOICE_CLIENT_TOKEN?.trim();
  if (!apiKey || /\s/.test(apiKey)) throw new Error('Set OPENAI_API_KEY on the server.');
  if (!clientToken || clientToken.length < 32 || /\s/.test(clientToken)) {
    throw new Error('Set ROADVOICE_CLIENT_TOKEN to a random token of at least 32 characters.');
  }
  if (apiKey === clientToken) throw new Error('Use a separate token for ROADVOICE_CLIENT_TOKEN.');
  const port = Number(environment.PORT ?? 8787);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT must be between 1 and 65535.');
  const model = environment.OPENAI_LIVE_MODEL?.trim() || 'gpt-live-1';
  const delegationModel = environment.OPENAI_DELEGATION_MODEL?.trim() || 'gpt-5.6-terra';
  const voice = environment.OPENAI_VOICE?.trim() || 'marin';
  if (!/^[a-zA-Z0-9._-]{1,100}$/.test(model)) throw new Error('Invalid OPENAI_LIVE_MODEL.');
  if (!/^[a-zA-Z0-9._-]{1,100}$/.test(delegationModel)) throw new Error('Invalid OPENAI_DELEGATION_MODEL.');
  if (!/^[a-zA-Z0-9_-]{1,64}$/.test(voice)) throw new Error('Invalid OPENAI_VOICE.');
  return { apiKey, clientToken, model, delegationModel, voice, port, host: environment.HOST?.trim() || '127.0.0.1' };
}

function hashToken(token) {
  return createHash('sha256').update(token).digest();
}

function isAudioSdp(sdp) {
  return sdp.startsWith('v=0\r\n') || sdp.startsWith('v=0\n')
    ? /^m=audio\s/m.test(sdp) && !sdp.includes('\u0000')
    : false;
}

function sendResponse(response, status, body, contentType = 'application/json; charset=utf-8') {
  if (response.destroyed || response.writableEnded) return;
  response.writeHead(status, {
    'Content-Type': contentType,
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    Connection: 'close',
  });
  response.end(body);
}

function readOffer(request, timeoutMs, signal) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let isFinished = false;
    const timer = setTimeout(() => finish(new RequestError(408, 'Request timed out.')), timeoutMs);
    timer.unref();

    function finish(error) {
      if (isFinished) return;
      isFinished = true;
      clearTimeout(timer);
      request.off('data', receiveChunk);
      request.off('end', finishReading);
      request.off('error', failReading);
      signal.removeEventListener('abort', cancelReading);
      if (error) {
        request.pause();
        reject(error);
      } else {
        resolve(Buffer.concat(chunks).toString('utf8'));
      }
    }
    function receiveChunk(chunk) {
      size += chunk.length;
      if (size > maximumSdpBytes) finish(new RequestError(413, 'SDP offer is too large.'));
      else chunks.push(chunk);
    }
    function finishReading() { finish(); }
    function failReading() { finish(new RequestError(400, 'Request could not be read.')); }
    function cancelReading() { finish(new RequestError(400, 'Request cancelled.')); }
    request.on('data', receiveChunk);
    request.on('end', finishReading);
    request.on('error', failReading);
    signal.addEventListener('abort', cancelReading, { once: true });
    if (signal.aborted) cancelReading();
  });
}

async function readAnswer(upstreamResponse) {
  if (!upstreamResponse.body) throw new RequestError(502, 'Voice service returned an invalid answer.');
  const reader = upstreamResponse.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maximumSessionResponseBytes) throw new RequestError(502, 'Voice service returned an invalid answer.');
      chunks.push(Buffer.from(value));
    }
    let result;
    try {
      result = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
      throw new RequestError(502, 'Voice service returned an invalid answer.');
    }
    const answer = result?.transport?.sdp;
    if (result?.transport?.type !== 'webrtc' || typeof answer !== 'string'
      || Buffer.byteLength(answer) > maximumSdpBytes || !isAudioSdp(answer)) {
      throw new RequestError(502, 'Voice service returned an invalid answer.');
    }
    return answer;
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

/** Dependencies can be injected by local tests; upstream URL is never client-controlled. */
export function createBroker(config, {
  fetchUpstream = fetch,
  bodyTimeoutMs = 10_000,
  upstreamTimeoutMs = 25_000,
  maxSessionStartsPerMinute = 6,
  now = Date.now,
} = {}) {
  const expectedTokenHash = hashToken(config.clientToken);
  let sessionStarts = [];
  let activeRequests = 0;
  const sessionConfig = {
    model: config.model,
    store: false,
    instructions: 'You are a friendly voice companion. Speak naturally and keep answers concise unless the user asks for detail. Your responses are heard aloud. Do not ask the user to look at a screen. Delegate questions that need reasoning to your backend.',
    audio: { output: { voice: config.voice } },
    delegation: {
      type: 'responses',
      responses: {
        model: config.delegationModel,
        instructions: 'Answer the user accurately and concisely for a spoken conversation. Explain uncertainty when needed. You have no external tools or live data access.',
      },
    },
  };

  const server = createServer({ maxHeaderSize: 8 * 1024 }, async (request, response) => {
    if (request.method === 'GET' && request.url === '/healthz') {
      sendResponse(response, 200, JSON.stringify({ healthy: true }));
      return;
    }
    if (request.url !== '/session') {
      sendResponse(response, 404, JSON.stringify({ error: 'Not found.' }));
      return;
    }
    if (request.method !== 'POST') {
      sendResponse(response, 405, JSON.stringify({ error: 'Use POST.' }));
      return;
    }
    const authorization = request.headers.authorization ?? '';
    const token = /^Bearer (\S+)$/i.exec(authorization)?.[1] ?? '';
    if (!token || !timingSafeEqual(hashToken(token), expectedTokenHash)) {
      sendResponse(response, 401, JSON.stringify({ error: 'Unauthorized.' }));
      return;
    }
    if (request.headers['content-type']?.split(';', 1)[0].trim().toLowerCase() !== 'application/sdp'
      || (request.headers['content-encoding'] && request.headers['content-encoding'] !== 'identity')) {
      sendResponse(response, 415, JSON.stringify({ error: 'Send an uncompressed application/sdp body.' }));
      return;
    }
    if (Number(request.headers['content-length']) > maximumSdpBytes) {
      sendResponse(response, 413, JSON.stringify({ error: 'SDP offer is too large.' }));
      return;
    }
    if (activeRequests >= 2) {
      sendResponse(response, 429, JSON.stringify({ error: 'Too many session requests. Try again shortly.' }));
      return;
    }

    activeRequests += 1;
    const controller = new AbortController();
    let upstreamTimer;
    let hasTimedOut = false;
    const cancel = () => controller.abort();
    const cancelUnfinished = () => { if (!response.writableEnded) cancel(); };
    request.on('aborted', cancel);
    response.on('close', cancelUnfinished);
    // Request errors may arrive after the body reader has detached its listener.
    request.on('error', cancel);

    try {
      const offer = await readOffer(request, bodyTimeoutMs, controller.signal);
      if (!isAudioSdp(offer)) throw new RequestError(400, 'Send a valid audio SDP offer.');
      const currentTime = now();
      sessionStarts = sessionStarts.filter(start => currentTime - start < 60_000);
      if (sessionStarts.length >= maxSessionStartsPerMinute) {
        throw new RequestError(429, 'Too many session requests. Try again shortly.');
      }
      sessionStarts.push(currentTime);
      upstreamTimer = setTimeout(() => {
        hasTimedOut = true;
        controller.abort();
      }, upstreamTimeoutMs);
      upstreamTimer.unref();
      // POST has no automatic retry: an ambiguous failure could have created a billable session.
      const upstreamResponse = await fetchUpstream(liveSessionsUrl, {
        method: 'POST',
        headers: { Authorization: `Bearer ${config.apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ session: sessionConfig, transport: { type: 'webrtc', sdp: offer } }),
        signal: controller.signal,
        redirect: 'error',
      });
      if (!upstreamResponse.ok) {
        await upstreamResponse.body?.cancel();
        if (upstreamResponse.status === 429) throw new RequestError(429, 'Voice service is busy. Try again shortly.');
        throw new RequestError(502, 'Voice session could not be created.');
      }
      const answer = await readAnswer(upstreamResponse);
      if (!controller.signal.aborted) sendResponse(response, 201, answer, 'application/sdp');
    } catch (error) {
      const status = hasTimedOut ? 504 : error instanceof RequestError ? error.status : 502;
      const message = hasTimedOut ? 'Voice service timed out.'
        : error instanceof RequestError ? error.message : 'Voice session could not be created.';
      sendResponse(response, status, JSON.stringify({ error: message }));
    } finally {
      clearTimeout(upstreamTimer);
      request.off('aborted', cancel);
      response.off('close', cancelUnfinished);
      // Keep the error listener to absorb late socket errors; request is no longer retained.
      activeRequests -= 1;
    }
  });
  server.requestTimeout = bodyTimeoutMs + 1_000;
  server.headersTimeout = bodyTimeoutMs;
  server.keepAliveTimeout = 1_000;
  return server;
}
