// Push wake-up layer: UnifiedPush (RFC 8030 + RFC 8291) and optional APNs. Payloads never carry
// numbers, names or message text; they only tell the app to reconnect.
//
// `options.pushSender(record, payload)` replaces the transport for every provider. `record` is the
// stored endpoint ({ provider, endpoint | token, ... }) and `payload` is { kind, id, ttlMs, reason? }.
// Reject with { code: 'endpoint_gone' } to have the endpoint deleted; other failures are ignored.
import { createCipheriv, createECDH, createPrivateKey, hkdfSync, randomBytes, sign } from 'node:crypto';
import dns from 'node:dns';
import { readFile } from 'node:fs/promises';
import http2 from 'node:http2';
import https from 'node:https';
import { isIP } from 'node:net';
import { atomicWriteJson, hasOnlyKeys, isObject, NUMBER_PATTERN, readJsonFile } from './common.js';

export const PUSH_ENDPOINT_GONE = 'endpoint_gone';
export const CALL_ENDED_PUSH_REASONS = Object.freeze(['timeout', 'declined', 'left', 'disconnected', 'media_unavailable']);
const MAX_ENDPOINT_LENGTH = 2_048;
const RESPONSE_CAP_BYTES = 4_096;
const WEB_PUSH_RECORD_SIZE = 4_096;
const APNS_JWT_MAX_AGE_MS = 50 * 60_000;

export class PushGoneError extends Error {
  constructor() {
    super('Push endpoint is no longer valid');
    this.code = PUSH_ENDPOINT_GONE;
  }
}

function sleep(ms) {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    timer.unref?.();
  });
}

// ---------------------------------------------------------------------------------------------
// Address classification (SSRF guard)
// ---------------------------------------------------------------------------------------------

function ipv4Bytes(text) {
  const parts = text.split('.');
  if (parts.length !== 4) return undefined;
  const bytes = [];
  for (const part of parts) {
    if (!/^\d{1,3}$/.test(part) || Number(part) > 255) return undefined;
    bytes.push(Number(part));
  }
  return bytes;
}

function ipv6Bytes(text) {
  if (text.includes('%')) return undefined;
  let source = text;
  const tail = /(\d{1,3}(?:\.\d{1,3}){3})$/.exec(source);
  if (tail) {
    const embedded = ipv4Bytes(tail[1]);
    if (!embedded) return undefined;
    source = `${source.slice(0, -tail[1].length)}${((embedded[0] << 8) | embedded[1]).toString(16)}:${((embedded[2] << 8) | embedded[3]).toString(16)}`;
  }
  const halves = source.split('::');
  if (halves.length > 2) return undefined;
  const left = halves[0] === '' ? [] : halves[0].split(':');
  const right = halves.length === 2 && halves[1] !== '' ? halves[1].split(':') : [];
  const missing = 8 - left.length - right.length;
  if (halves.length === 1 ? missing !== 0 : missing < 1) return undefined;
  const groups = [...left, ...new Array(halves.length === 2 ? missing : 0).fill('0'), ...right];
  const bytes = [];
  for (const group of groups) {
    if (!/^[0-9a-f]{1,4}$/i.test(group)) return undefined;
    const value = Number.parseInt(group, 16);
    bytes.push(value >> 8, value & 0xff);
  }
  return bytes.length === 16 ? bytes : undefined;
}

function inPrefix(bytes, prefix, bits) {
  const whole = bits >> 3;
  for (let index = 0; index < whole; index += 1) {
    if (bytes[index] !== prefix[index]) return false;
  }
  const rest = bits & 7;
  if (rest === 0) return true;
  const mask = (0xff << (8 - rest)) & 0xff;
  return (bytes[whole] & mask) === (prefix[whole] & mask);
}

const BLOCKED_IPV4 = [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8], ['169.254.0.0', 16],
  ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24], ['192.88.99.0', 24], ['192.168.0.0', 16],
  ['198.18.0.0', 15], ['198.51.100.0', 24], ['203.0.113.0', 24], ['224.0.0.0', 4], ['240.0.0.0', 4],
].map(([address, bits]) => [ipv4Bytes(address), bits]);
// Only global unicast (2000::/3) is reachable, minus special-purpose blocks.
const GLOBAL_UNICAST_IPV6 = ipv6Bytes('2000::');
const BLOCKED_IPV6 = [['2001::', 23], ['2001:db8::', 32], ['2002::', 16], ['3fff::', 20]]
  .map(([address, bits]) => [ipv6Bytes(address), bits]);
const IPV4_MAPPED = ipv6Bytes('::ffff:0:0');
const NAT64 = ipv6Bytes('64:ff9b::');

function publicIPv4(bytes) {
  return !BLOCKED_IPV4.some(([prefix, bits]) => inPrefix(bytes, prefix, bits));
}

// True only for addresses on the public internet: no private, loopback, link-local, CGNAT,
// multicast, ULA, documentation or otherwise reserved ranges.
export function isPublicAddress(address) {
  if (typeof address !== 'string') return false;
  const family = isIP(address);
  if (family === 4) {
    const bytes = ipv4Bytes(address);
    return Boolean(bytes) && publicIPv4(bytes);
  }
  if (family === 6) {
    const bytes = ipv6Bytes(address);
    if (!bytes) return false;
    if (inPrefix(bytes, IPV4_MAPPED, 96) || inPrefix(bytes, NAT64, 96)) return publicIPv4(bytes.slice(12));
    if (!inPrefix(bytes, GLOBAL_UNICAST_IPV6, 3)) return false;
    return !BLOCKED_IPV6.some(([prefix, bits]) => inPrefix(bytes, prefix, bits));
  }
  return false;
}

function validDnsName(hostname) {
  if (hostname.length > 253) return false;
  const name = hostname.endsWith('.') ? hostname.slice(0, -1) : hostname;
  return name.length > 0 && name.split('.').every((label) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label));
}

// Returns the parsed URL for an acceptable UnifiedPush endpoint, otherwise undefined.
export function parseEndpoint(value, allowPrivate = false) {
  if (typeof value !== 'string' || value.length < 12 || value.length > MAX_ENDPOINT_LENGTH
    || !/^[\x21-\x7e]+$/.test(value)) return undefined;
  const authority = /^https:\/\/([^/?#]*)/i.exec(value)?.[1];
  if (!authority || authority.includes('@')) return undefined;
  let url;
  try {
    url = new URL(value);
  } catch {
    return undefined;
  }
  if (url.protocol !== 'https:' || url.username || url.password || url.port === '0' || !url.hostname) return undefined;
  const hostname = url.hostname.startsWith('[') && url.hostname.endsWith(']') ? url.hostname.slice(1, -1) : url.hostname;
  if (isIP(hostname)) return allowPrivate || isPublicAddress(hostname) ? url : undefined;
  if (!validDnsName(hostname)) return undefined;
  if (!allowPrivate && (!hostname.includes('.') || hostname === 'localhost' || hostname.endsWith('.localhost'))) return undefined;
  return url;
}

// dns.lookup replacement for https.request: the socket connects to exactly the addresses that were
// validated here, so a later DNS answer cannot redirect the request to an internal host.
export function createSafeLookup({ allowPrivate = false, resolve = dns.lookup } = {}) {
  return (hostname, options, callback) => {
    if (typeof options === 'function') {
      callback = options;
      options = {};
    }
    const wantAll = options?.all === true;
    resolve(hostname, { family: options?.family ?? 0, hints: options?.hints, all: true, verbatim: true }, (failure, addresses) => {
      if (failure) return callback(failure);
      if (!Array.isArray(addresses) || addresses.length === 0
        || (!allowPrivate && !addresses.every((entry) => isPublicAddress(entry.address)))) {
        const refusal = new Error('Push endpoint resolves to a forbidden address');
        refusal.code = 'ERR_PUSH_FORBIDDEN_ADDRESS';
        return callback(refusal);
      }
      return wantAll ? callback(null, addresses) : callback(null, addresses[0].address, addresses[0].family);
    });
  };
}

// ---------------------------------------------------------------------------------------------
// Registration validation and the endpoint store
// ---------------------------------------------------------------------------------------------

function decodeBase64Url(value, expectedBytes) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+={0,2}$/.test(value)) return undefined;
  const canonical = value.replace(/=+$/, '');
  const bytes = Buffer.from(canonical, 'base64url');
  return bytes.length === expectedBytes && bytes.toString('base64url') === canonical ? bytes : undefined;
}

function validP256Point(bytes) {
  if (bytes.length !== 65 || bytes[0] !== 4) return false;
  try {
    const ecdh = createECDH('prime256v1');
    ecdh.generateKeys();
    ecdh.computeSecret(bytes);
    return true;
  } catch {
    return false;
  }
}

function parseUnifiedPushRecord(value, allowPrivate) {
  if (!isObject(value) || !parseEndpoint(value.endpoint, allowPrivate)) return undefined;
  const record = { provider: 'unifiedpush', endpoint: value.endpoint };
  if ((value.pubKey === undefined) !== (value.auth === undefined)) return undefined;
  if (value.pubKey !== undefined) {
    const pubKey = decodeBase64Url(value.pubKey, 65);
    const auth = decodeBase64Url(value.auth, 16);
    if (!pubKey || !auth || !validP256Point(pubKey)) return undefined;
    record.pubKey = pubKey.toString('base64url');
    record.auth = auth.toString('base64url');
  }
  return record;
}

function parseApnsRecord(value) {
  if (!isObject(value) || typeof value.token !== 'string' || !/^[0-9a-f]{64,200}$/i.test(value.token)) return undefined;
  if (value.environment !== undefined && !['production', 'sandbox'].includes(value.environment)) return undefined;
  return { provider: 'apns', token: value.token.toLowerCase(), environment: value.environment ?? 'production' };
}

// `message` is a push_register request that carries a `provider`. Returns { record } or { error }.
export function parsePushRegistration(message, { allowPrivate = false, apnsEnabled = false } = {}) {
  if (message.provider === 'unifiedpush') {
    if (!hasOnlyKeys(message, ['type', 'provider', 'endpoint', 'pubKey', 'auth'])) return { error: 'invalid_push_endpoint' };
    const record = parseUnifiedPushRecord(message, allowPrivate);
    return record ? { record } : { error: 'invalid_push_endpoint' };
  }
  if (message.provider === 'apns' && apnsEnabled) {
    if (!hasOnlyKeys(message, ['type', 'provider', 'token', 'environment'])) return { error: 'invalid_push_endpoint' };
    const record = parseApnsRecord(message);
    return record ? { record } : { error: 'invalid_push_endpoint' };
  }
  return { error: 'push_provider_unsupported' };
}

function parseStoredEndpoint(entry) {
  if (!isObject(entry) || !Number.isSafeInteger(entry.registeredAt) || entry.registeredAt < 0) return undefined;
  let record;
  if (entry.provider === 'unifiedpush') {
    if (!hasOnlyKeys(entry, ['provider', 'endpoint', 'pubKey', 'auth', 'registeredAt'])) return undefined;
    record = parseUnifiedPushRecord(entry, true);
    if (record && (record.pubKey !== entry.pubKey || record.auth !== entry.auth)) return undefined;
  } else if (entry.provider === 'apns') {
    if (!hasOnlyKeys(entry, ['provider', 'token', 'environment', 'registeredAt'])) return undefined;
    record = parseApnsRecord(entry);
    if (record && (record.token !== entry.token || record.environment !== entry.environment)) return undefined;
  }
  return record ? { ...record, registeredAt: entry.registeredAt } : undefined;
}

export async function loadPushEndpoints(endpointFile, numberOwners, blockedNumbers) {
  const document = await readJsonFile(endpointFile, 'Push endpoint store');
  if (document === undefined) return new Map();
  if (!isObject(document) || !hasOnlyKeys(document, ['version', 'endpoints']) || document.version !== 1
    || !isObject(document.endpoints)) {
    throw new Error(`Push endpoint store has an unsupported format: ${endpointFile}`);
  }
  const endpoints = new Map();
  for (const [number, entry] of Object.entries(document.endpoints)) {
    const record = NUMBER_PATTERN.test(number) ? parseStoredEndpoint(entry) : undefined;
    if (!record) throw new Error(`Push endpoint store contains an invalid record: ${endpointFile}`);
    if (numberOwners.has(number) && !blockedNumbers.has(number)) endpoints.set(number, record);
  }
  return endpoints;
}

export async function savePushEndpoints(endpointFile, endpoints) {
  await atomicWriteJson(endpointFile, { version: 1, endpoints: Object.fromEntries(endpoints) });
}

export function sameEndpoint(left, right) {
  return Boolean(left) && Boolean(right) && left.provider === right.provider
    && left.endpoint === right.endpoint && left.pubKey === right.pubKey && left.auth === right.auth
    && left.token === right.token && left.environment === right.environment;
}

// ---------------------------------------------------------------------------------------------
// Payloads and RFC 8291 encryption
// ---------------------------------------------------------------------------------------------

export function wirePayload(payload) {
  const wire = { kind: payload.kind, id: payload.id };
  if (payload.kind === 'call_ended' && CALL_ENDED_PUSH_REASONS.includes(payload.reason)) wire.reason = payload.reason;
  return wire;
}

// RFC 8291 message encryption with the aes128gcm content coding of RFC 8188 (single record).
// `salt` and `ephemeral` (an ECDH object holding the application-server key) are injectable for the RFC vector.
export function encryptWebPushPayload(plaintext, { userPublicKey, authSecret, salt = randomBytes(16), ephemeral } = {}) {
  if (plaintext.length > WEB_PUSH_RECORD_SIZE - 17) throw new Error('Push payload is too large');
  const keys = ephemeral ?? createECDH('prime256v1');
  if (!ephemeral) keys.generateKeys();
  const serverPublicKey = keys.getPublicKey();
  const sharedSecret = keys.computeSecret(userPublicKey);
  const keyInfo = Buffer.concat([Buffer.from('WebPush: info\0', 'latin1'), userPublicKey, serverPublicKey]);
  const inputKey = Buffer.from(hkdfSync('sha256', sharedSecret, authSecret, keyInfo, 32));
  const contentKey = Buffer.from(hkdfSync('sha256', inputKey, salt, Buffer.from('Content-Encoding: aes128gcm\0', 'latin1'), 16));
  const nonce = Buffer.from(hkdfSync('sha256', inputKey, salt, Buffer.from('Content-Encoding: nonce\0', 'latin1'), 12));
  const cipher = createCipheriv('aes-128-gcm', contentKey, nonce);
  const record = Buffer.concat([cipher.update(Buffer.concat([plaintext, Buffer.from([2])])), cipher.final(), cipher.getAuthTag()]);
  const header = Buffer.alloc(16 + 4 + 1 + serverPublicKey.length);
  salt.copy(header, 0);
  header.writeUInt32BE(WEB_PUSH_RECORD_SIZE, 16);
  header[20] = serverPublicKey.length;
  serverPublicKey.copy(header, 21);
  return Buffer.concat([header, record]);
}

// ---------------------------------------------------------------------------------------------
// UnifiedPush sender (RFC 8030)
// ---------------------------------------------------------------------------------------------

function postOnce({ url, headers, body, request, lookup, timeoutMs }) {
  return new Promise((resolve, reject) => {
    let settled = false;
    let received = 0;
    let timer;
    const finish = (action, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      action(value);
    };
    const hostname = url.hostname.startsWith('[') ? url.hostname.slice(1, -1) : url.hostname;
    let outgoing;
    try {
      outgoing = request({
        protocol: 'https:', hostname, port: url.port || 443, path: `${url.pathname}${url.search}`, method: 'POST',
        headers: { ...headers, 'content-length': body.length, connection: 'close' }, lookup, agent: false,
      }, (response) => {
        response.on('data', (chunk) => {
          received += chunk.length;
          if (received > RESPONSE_CAP_BYTES) response.destroy();
        });
        response.on('error', () => {});
        const done = () => finish(resolve, response.statusCode ?? 0);
        response.on('end', done);
        response.on('close', done);
      });
    } catch (failure) {
      finish(reject, failure);
      return;
    }
    timer = setTimeout(() => {
      outgoing.destroy(new Error('Push request timed out'));
    }, timeoutMs);
    timer.unref?.();
    outgoing.on('error', (failure) => finish(reject, failure));
    outgoing.end(body);
  });
}

export function createUnifiedPushSender({
  allowPrivate = false, request = https.request, resolve = dns.lookup, retryDelayMs = 1_000, timeoutMs = 5_000,
} = {}) {
  const lookup = createSafeLookup({ allowPrivate, resolve });
  return async function sendUnifiedPush(record, payload) {
    const url = parseEndpoint(record.endpoint, allowPrivate);
    if (!url) throw new Error('Push endpoint is not allowed');
    const plaintext = Buffer.from(JSON.stringify(wirePayload(payload)), 'utf8');
    const encrypted = Boolean(record.pubKey && record.auth);
    const body = encrypted ? encryptWebPushPayload(plaintext, {
      userPublicKey: Buffer.from(record.pubKey, 'base64url'), authSecret: Buffer.from(record.auth, 'base64url'),
    }) : plaintext;
    const headers = {
      ttl: String(Math.max(1, Math.ceil(payload.ttlMs / 1_000))),
      urgency: 'high',
      'content-type': encrypted ? 'application/octet-stream' : 'application/json',
      ...(encrypted ? { 'content-encoding': 'aes128gcm' } : {}),
    };
    const attempt = () => postOnce({ url, headers, body, request, lookup, timeoutMs }).catch((failure) => {
      if (failure?.code === 'ERR_PUSH_FORBIDDEN_ADDRESS') throw failure;
      return 0;
    });
    let status = await attempt();
    if (status === 0 || status >= 500) {
      await sleep(retryDelayMs);
      status = await attempt();
    }
    if (status === 404 || status === 410) throw new PushGoneError();
    if (status < 200 || status >= 300) throw new Error('Push delivery failed');
  };
}

// ---------------------------------------------------------------------------------------------
// APNs sender (HTTP/2, token-based authentication)
// ---------------------------------------------------------------------------------------------

export async function readApnsConfig(env) {
  const names = ['APNS_KEY_FILE', 'APNS_KEY_ID', 'APNS_TEAM_ID', 'APNS_TOPIC'];
  const values = Object.fromEntries(names.map((name) => [name, typeof env[name] === 'string' ? env[name].trim() : '']));
  const missing = names.filter((name) => !values[name]);
  if (missing.length === names.length) return undefined;
  if (missing.length > 0) {
    throw new Error(`APNs needs APNS_KEY_FILE, APNS_KEY_ID, APNS_TEAM_ID and APNS_TOPIC together; missing: ${missing.join(', ')}`);
  }
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(values.APNS_KEY_ID)) throw new Error('APNS_KEY_ID is not valid');
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(values.APNS_TEAM_ID)) throw new Error('APNS_TEAM_ID is not valid');
  if (!/^[A-Za-z0-9.-]{1,155}$/.test(values.APNS_TOPIC)) throw new Error('APNS_TOPIC is not valid');
  let keyPem;
  try {
    keyPem = await readFile(values.APNS_KEY_FILE, 'utf8');
    const key = createPrivateKey(keyPem);
    if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') throw new Error('wrong key type');
  } catch {
    throw new Error('APNS_KEY_FILE must contain a readable P-256 private key in PEM (.p8) format');
  }
  return { keyPem, keyId: values.APNS_KEY_ID, teamId: values.APNS_TEAM_ID, topic: values.APNS_TOPIC };
}

export function createApnsSender({
  keyPem, keyId, teamId, topic, connect = http2.connect, now = Date.now, retryDelayMs = 1_000, timeoutMs = 5_000,
}) {
  const privateKey = createPrivateKey(keyPem);
  const sessions = new Map();
  let cachedToken;
  let cachedAt = 0;

  function providerToken() {
    const current = now();
    if (!cachedToken || current - cachedAt >= APNS_JWT_MAX_AGE_MS) {
      const header = Buffer.from(JSON.stringify({ alg: 'ES256', kid: keyId })).toString('base64url');
      const claims = Buffer.from(JSON.stringify({ iss: teamId, iat: Math.floor(current / 1_000) })).toString('base64url');
      const signature = sign('sha256', Buffer.from(`${header}.${claims}`), { key: privateKey, dsaEncoding: 'ieee-p1363' });
      cachedToken = `${header}.${claims}.${signature.toString('base64url')}`;
      cachedAt = current;
    }
    return cachedToken;
  }

  function sessionFor(authority) {
    const existing = sessions.get(authority);
    if (existing && !existing.closed && !existing.destroyed) return existing;
    const session = connect(authority);
    const forget = () => { if (sessions.get(authority) === session) sessions.delete(authority); };
    session.on('error', forget);
    session.on('close', forget);
    session.on('goaway', forget);
    session.unref?.();
    sessions.set(authority, session);
    return session;
  }

  function requestOnce(authority, headers, body) {
    return new Promise((resolve, reject) => {
      let settled = false;
      let timer;
      let stream;
      const finish = (action, value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        action(value);
      };
      try {
        stream = sessionFor(authority).request(headers);
      } catch (failure) {
        sessions.delete(authority);
        finish(reject, failure);
        return;
      }
      let status = 0;
      let text = '';
      timer = setTimeout(() => {
        stream.close?.();
        finish(reject, new Error('APNs request timed out'));
      }, timeoutMs);
      timer.unref?.();
      stream.setEncoding?.('utf8');
      stream.on('response', (responseHeaders) => { status = Number(responseHeaders[':status']) || 0; });
      stream.on('data', (chunk) => { if (text.length < RESPONSE_CAP_BYTES) text += chunk; });
      stream.on('end', () => {
        let reason;
        try { reason = JSON.parse(text).reason; } catch { reason = undefined; }
        finish(resolve, { status, reason: typeof reason === 'string' ? reason : undefined });
      });
      stream.on('error', (failure) => {
        sessions.delete(authority);
        finish(reject, failure);
      });
      stream.end(body);
    });
  }

  async function sendApns(record, payload) {
    const ttlSeconds = Math.max(1, Math.ceil(payload.ttlMs / 1_000));
    const wire = wirePayload(payload);
    const silent = payload.kind === 'call_ended';
    const body = Buffer.from(JSON.stringify(silent
      ? { aps: { 'content-available': 1 }, ...wire }
      : {
        aps: {
          alert: { title: 'Line', body: payload.kind === 'call' ? 'Входящий звонок' : 'Новое сообщение' },
          sound: 'default', 'mutable-content': 1,
        },
        ...wire,
      }), 'utf8');
    const authority = record.environment === 'sandbox' ? 'https://api.sandbox.push.apple.com' : 'https://api.push.apple.com';
    const headers = {
      ':method': 'POST',
      ':path': `/3/device/${record.token}`,
      authorization: `bearer ${providerToken()}`,
      'apns-topic': topic,
      'apns-push-type': silent ? 'background' : 'alert',
      'apns-priority': silent ? '5' : '10',
      'apns-expiration': String(Math.floor(now() / 1_000) + ttlSeconds),
      'content-type': 'application/json',
      'content-length': body.length,
    };
    let result = await requestOnce(authority, headers, body).catch(() => ({ status: 0 }));
    if (result.status === 0 || result.status >= 500) {
      await sleep(retryDelayMs);
      result = await requestOnce(authority, headers, body).catch(() => ({ status: 0 }));
    }
    if (result.status === 200) return;
    if (result.status === 410 || (result.status === 400 && result.reason === 'BadDeviceToken')) throw new PushGoneError();
    if (result.status === 403 && ['ExpiredProviderToken', 'InvalidProviderToken'].includes(result.reason)) cachedToken = undefined;
    throw new Error('APNs delivery failed');
  }
  sendApns.close = () => {
    for (const session of sessions.values()) {
      try { session.destroy(); } catch { /* already closed */ }
    }
    sessions.clear();
  };
  return sendApns;
}

// ---------------------------------------------------------------------------------------------
// Coalescing and the combined layer
// ---------------------------------------------------------------------------------------------

// At most one dispatch per key per window: the first goes out immediately, anything arriving
// while the window is open is merged into a single trailing dispatch carrying the latest value.
export function createCoalescer({ windowMs, dispatch }) {
  const states = new Map();
  function release(key) {
    const state = states.get(key);
    if (!state) return;
    if (state.pending === undefined) {
      states.delete(key);
      return;
    }
    const pending = state.pending;
    state.pending = undefined;
    state.timer = setTimeout(() => release(key), windowMs);
    state.timer.unref?.();
    dispatch(key, pending);
  }
  return {
    push(key, value) {
      const state = states.get(key);
      if (state) {
        state.pending = value;
        return;
      }
      const created = { pending: undefined, timer: setTimeout(() => release(key), windowMs) };
      created.timer.unref?.();
      states.set(key, created);
      dispatch(key, value);
    },
    forget(key) {
      const state = states.get(key);
      if (state) clearTimeout(state.timer);
      states.delete(key);
    },
    close() {
      for (const state of states.values()) clearTimeout(state.timer);
      states.clear();
    },
  };
}

export async function createPushLayer({ allowPrivate = false, apnsConfig, options = {} } = {}) {
  const apns = apnsConfig ? createApnsSender({ ...apnsConfig, ...options.apns }) : undefined;
  const unifiedPush = createUnifiedPushSender({ allowPrivate, ...options.unifiedPush });
  const providers = ['unifiedpush', ...(apns ? ['apns'] : [])];
  return {
    providers,
    allowPrivate,
    apnsEnabled: Boolean(apns),
    canSend(record) {
      return Boolean(record) && (Boolean(options.pushSender) || providers.includes(record.provider));
    },
    send(record, payload) {
      if (options.pushSender) return options.pushSender(record, payload);
      if (record.provider === 'unifiedpush') return unifiedPush(record, payload);
      if (record.provider === 'apns' && apns) return apns(record, payload);
      return Promise.reject(new Error('Push provider is not configured'));
    },
    close() {
      apns?.close();
    },
  };
}
