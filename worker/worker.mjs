const liveSessionsUrl = 'https://api.openai.com/v1/live/sessions';
const maximumSdpBytes = 64 * 1024;
const maximumSessionResponseBytes = 128 * 1024;
const encoder = new TextEncoder();

class RequestError extends Error {
  constructor(status, message, diagnostics) {
    super(message);
    this.status = status;
    this.diagnostics = diagnostics;
  }
}

function createResponse(status, body, contentType = 'application/json; charset=utf-8') {
  return new Response(body, {
    status,
    headers: {
      'Content-Type': contentType,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    },
  });
}

function createErrorResponse(status, message, diagnostics) {
  return createResponse(status, JSON.stringify({ error: message, ...diagnostics }));
}

const allowedUpstreamCodes = new Set([
  'invalid_api_key', 'invalid_request_error', 'invalid_value', 'invalid_sdp',
  'model_not_found', 'permission_denied', 'insufficient_quota', 'rate_limit_exceeded',
  'unsupported_country_region_territory', 'server_error', 'missing_required_parameter',
  'unknown_parameter', 'unsupported_parameter', 'session_creation_failed',
]);
const allowedUpstreamTypes = new Set([
  'invalid_request_error', 'authentication_error', 'permission_error',
  'rate_limit_error', 'server_error', 'api_error', 'insufficient_quota',
]);
const allowedExceptionNames = new Set(['Error', 'TypeError', 'RangeError', 'SyntaxError', 'DOMException', 'AbortError']);

function describeRequestFailure(error, stage) {
  const diagnostics = { diagnostic: 'upstream_request_failed', stage };
  if (allowedExceptionNames.has(error?.name)) diagnostics.exceptionName = error.name;
  const message = typeof error?.message === 'string' ? error.message.toLowerCase() : '';
  const reasons = [
    ['redirect', 'redirect_mode'], ['throwifaborted', 'abort_signal_method'],
    ['abortsignal', 'abort_signal'], ['signal', 'abort_signal'],
    ['global scope', 'global_scope_io'], ['different request', 'cross_request_io'],
    ['illegal invocation', 'invalid_receiver'], ['receiver', 'invalid_receiver'],
    ['certificate', 'tls'], ['tls', 'tls'], ['ssl', 'tls'],
    ['dns', 'dns'], ['resolve', 'dns'], ['hostname', 'dns'],
    ['network', 'network'], ['connection', 'network'], ['socket', 'network'],
    ['stream', 'stream'], ['body', 'stream'], ['encoding', 'encoding'],
  ];
  diagnostics.exceptionReason = reasons.find(([fragment]) => message.includes(fragment))?.[1] ?? 'unclassified';
  return diagnostics;
}

async function readUpstreamDiagnostics(response, signal) {
  const diagnostics = { diagnostic: 'upstream_rejected', upstreamStatus: response.status };
  try {
    const invalidError = new RequestError(502, 'Invalid upstream error.');
    const text = await readBoundedText(response.body, {
      maximumBytes: 16 * 1024, signal, invalidError, oversizedError: invalidError,
    });
    const error = JSON.parse(text)?.error;
    if (allowedUpstreamCodes.has(error?.code)) diagnostics.upstreamCode = error.code;
    if (allowedUpstreamTypes.has(error?.type)) diagnostics.upstreamType = error.type;
  } catch {
    // Never return free-form provider messages, request IDs, or headers.
  }
  return diagnostics;
}

function readCredentials(environment) {
  const apiKey = environment?.OPENAI_API_KEY?.trim();
  const clientToken = environment?.ROADVOICE_CLIENT_TOKEN?.trim();
  if (!apiKey || /\s/.test(apiKey) || !clientToken || clientToken.length < 32
    || clientToken.length > 1024 || /\s/.test(clientToken) || apiKey === clientToken) {
    throw new RequestError(503, 'Voice service is not configured.');
  }
  return { apiKey, clientToken };
}

async function hasValidToken(authorization, expectedToken) {
  if (!authorization || authorization.length > 8192) return false;
  const providedToken = /^Bearer (\S+)$/i.exec(authorization)?.[1];
  if (!providedToken) return false;
  const [providedHash, expectedHash] = await Promise.all([
    crypto.subtle.digest('SHA-256', encoder.encode(providedToken)),
    crypto.subtle.digest('SHA-256', encoder.encode(expectedToken)),
  ]);
  const providedBytes = new Uint8Array(providedHash);
  const expectedBytes = new Uint8Array(expectedHash);
  let difference = 0;
  // Compare every byte of the fixed-size digests without an early mismatch exit.
  for (let index = 0; index < expectedBytes.length; index += 1) {
    difference |= providedBytes[index] ^ expectedBytes[index];
  }
  return difference === 0;
}

function isAudioSdp(sdp) {
  return (sdp.startsWith('v=0\r\n') || sdp.startsWith('v=0\n'))
    && /^m=audio\s/m.test(sdp) && !sdp.includes('\u0000');
}

function awaitWithAbort(operation, signal) {
  if (signal.aborted) return Promise.reject(signal.reason);
  return new Promise((resolve, reject) => {
    const cancel = () => reject(signal.reason);
    signal.addEventListener('abort', cancel, { once: true });
    Promise.resolve().then(() => {
      signal.throwIfAborted();
      return operation();
    }).then(resolve, reject).finally(() => signal.removeEventListener('abort', cancel));
  });
}

async function readBoundedText(body, { maximumBytes, signal, invalidError, oversizedError }) {
  if (!body) throw invalidError;
  const reader = body.getReader();
  const chunks = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await awaitWithAbort(() => reader.read(), signal);
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > maximumBytes) throw oversizedError;
      chunks.push(value);
    }
    const bytes = new Uint8Array(totalBytes);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    try {
      return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch {
      throw invalidError;
    }
  } catch (error) {
    if (signal.aborted) throw signal.reason;
    throw error instanceof RequestError ? error : invalidError;
  } finally {
    // Cancel without waiting on an unresponsive producer's cancellation callback.
    void reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

function createSessionConfig() {
  return {
    model: 'gpt-live-1',
    store: false,
    instructions: 'You are a friendly voice companion. Speak naturally and keep answers concise unless the user asks for detail. Your responses are heard aloud. Do not ask the user to look at a screen. Delegate questions that need reasoning to your backend.',
    audio: { output: { voice: 'marin' } },
    delegation: {
      type: 'responses',
      responses: {
        model: 'gpt-5.6-terra',
        instructions: 'Answer the user accurately and concisely for a spoken conversation. Explain uncertainty when needed. You have no external tools or live data access.',
      },
    },
  };
}

/** Standard Fetch handler; injected dependencies keep tests local and unbilled. */
export function createWorker({
  fetchUpstream = (...arguments_) => fetch(...arguments_),
  bodyTimeoutMs = 10_000,
  upstreamTimeoutMs = 25_000,
  maxSessionStartsPerMinute = 6,
  now = Date.now,
} = {}) {
  let sessionStarts = [];
  let activeRequests = 0;

  return {
    async fetch(request, environment) {
      const url = new URL(request.url);
      if (url.protocol !== 'https:') return createErrorResponse(400, 'Use HTTPS.');
      if (url.search) return createErrorResponse(404, 'Not found.');
      if (request.method === 'GET' && url.pathname === '/healthz') {
        return createResponse(200, JSON.stringify({ healthy: true }));
      }
      if (url.pathname !== '/session') return createErrorResponse(404, 'Not found.');
      if (request.method !== 'POST') return createErrorResponse(405, 'Use POST.');
      // Native Android requests have no Origin. No browser CORS access is offered.
      if (request.headers.has('Origin')) return createErrorResponse(403, 'Browser requests are not supported.');

      let credentials;
      try {
        credentials = readCredentials(environment);
        if (!await hasValidToken(request.headers.get('Authorization'), credentials.clientToken)) {
          return createErrorResponse(401, 'Unauthorized.');
        }
      } catch {
        return createErrorResponse(503, 'Voice service is not configured.');
      }
      if (request.headers.get('Content-Type')?.split(';', 1)[0].trim().toLowerCase() !== 'application/sdp'
        || ![null, 'identity'].includes(request.headers.get('Content-Encoding'))) {
        return createErrorResponse(415, 'Send an uncompressed application/sdp body.');
      }
      if (Number(request.headers.get('Content-Length')) > maximumSdpBytes) {
        return createErrorResponse(413, 'SDP offer is too large.');
      }
      if (activeRequests >= 2) return createErrorResponse(429, 'Too many session requests. Try again shortly.');

      activeRequests += 1;
      const controller = new AbortController();
      const cancel = () => controller.abort(new RequestError(499, 'Request cancelled.'));
      request.signal.addEventListener('abort', cancel, { once: true });
      if (request.signal.aborted) cancel();
      let timer = setTimeout(() => controller.abort(new RequestError(408, 'Request timed out.')), bodyTimeoutMs);
      let requestStage = 'read_offer';
      try {
        const offer = await readBoundedText(request.body, {
          maximumBytes: maximumSdpBytes,
          signal: controller.signal,
          invalidError: new RequestError(400, 'Send a valid audio SDP offer.'),
          oversizedError: new RequestError(413, 'SDP offer is too large.'),
        });
        if (!isAudioSdp(offer)) throw new RequestError(400, 'Send a valid audio SDP offer.');
        controller.signal.throwIfAborted();
        const currentTime = now();
        sessionStarts = sessionStarts.filter(start => currentTime - start < 60_000);
        if (sessionStarts.length >= maxSessionStartsPerMinute) {
          throw new RequestError(429, 'Too many session requests. Try again shortly.');
        }
        sessionStarts.push(currentTime);
        clearTimeout(timer);
        timer = setTimeout(() => controller.abort(new RequestError(504, 'Voice service timed out.')), upstreamTimeoutMs);
        requestStage = 'create_session';
        // Never retry: a failed or cancelled POST may have created a billable session.
        const upstreamResponse = await awaitWithAbort(() => fetchUpstream(liveSessionsUrl, {
          method: 'POST',
          headers: { Authorization: `Bearer ${credentials.apiKey}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ session: createSessionConfig(), transport: { type: 'webrtc', sdp: offer } }),
          signal: controller.signal,
          // workerd rejects redirect:'error'; manual returns 3xx to the rejection below.
          redirect: 'manual',
        }), controller.signal);
        requestStage = 'read_answer';
        if (!upstreamResponse.ok) {
          const diagnostics = await readUpstreamDiagnostics(upstreamResponse, controller.signal);
          throw new RequestError(upstreamResponse.status === 429 ? 429 : 502,
            upstreamResponse.status === 429 ? 'Voice service is busy. Try again shortly.' : 'Voice session could not be created.', diagnostics);
        }
        const invalidAnswer = new RequestError(502, 'Voice service returned an invalid answer.', { diagnostic: 'invalid_upstream_response', upstreamStatus: upstreamResponse.status });
        const responseText = await readBoundedText(upstreamResponse.body, {
          maximumBytes: maximumSessionResponseBytes,
          signal: controller.signal,
          invalidError: invalidAnswer,
          oversizedError: invalidAnswer,
        });
        let sessionResponse;
        try { sessionResponse = JSON.parse(responseText); } catch { throw invalidAnswer; }
        const answer = sessionResponse?.transport?.sdp;
        if (sessionResponse?.transport?.type !== 'webrtc' || typeof answer !== 'string'
          || encoder.encode(answer).byteLength > maximumSdpBytes || !isAudioSdp(answer)) {
          throw invalidAnswer;
        }
        controller.signal.throwIfAborted();
        return createResponse(201, answer, 'application/sdp');
      } catch (error) {
        return error instanceof RequestError
          ? createErrorResponse(error.status, error.message, error.diagnostics)
          : createErrorResponse(502, 'Voice session could not be created.', describeRequestFailure(error, requestStage));
      } finally {
        clearTimeout(timer);
        request.signal.removeEventListener('abort', cancel);
        activeRequests -= 1;
      }
    },
  };
}

export default createWorker();
