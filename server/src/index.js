import { createHash, randomInt, randomUUID, scrypt, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, open, readFile, rename, unlink } from 'node:fs/promises';
import { isIP } from 'node:net';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { AccessToken, RoomServiceClient, TrackSource } from 'livekit-server-sdk';
import { WebSocket, WebSocketServer } from 'ws';
import { createBlobStore } from './blobs.js';
import {
  consumeRateLimit, createRateLimiter, hasOnlyKeys, isObject, NUMBER_PATTERN, readBooleanSetting, readIntegerSetting,
  UUID_PATTERN, atomicWriteJson,
} from './common.js';
import { MISSED_CALL_TTL_MS, loadMissedCalls, pruneMissedCalls, saveMissedCalls } from './missed.js';
import { loadProfiles, normalizeProfileName, saveProfiles } from './profiles.js';
import {
  CALL_ENDED_PUSH_REASONS, PUSH_ENDPOINT_GONE, createCoalescer, createPushLayer, loadPushEndpoints, parsePushRegistration,
  readApnsConfig, sameEndpoint, savePushEndpoints,
} from './push.js';

const TOKEN_PATTERN = /^[a-f0-9]{64}$/;
const MAX_PAYLOAD = 64 * 1024;
const MAX_ENVELOPE_BYTES = 24 * 1024;
const MAX_BUFFERED_BYTES = 512 * 1024;
const MAX_PRE_KEYS = 1_000;
const DEFAULT_MAILBOX_TTL_MS = 7 * 24 * 60 * 60 * 1_000;
const DEFAULT_MAX_MAILBOX_ENTRIES = 1_000;
const DEFAULT_MAX_MAILBOX_PER_USER = 100;
const DEFAULT_MAX_RECEIPTS = 5_000;
const DEFAULT_MAX_INBOX_SYNCS_PER_WINDOW = 10;
const DEFAULT_INBOX_SYNC_WINDOW_MS = 60_000;
const MIN_PUSH_TOKEN_BYTES = 25;
const MAX_PUSH_TOKEN_BYTES = 4_096;
const MESSAGE_PUSH_TTL_MS = 24 * 60 * 60 * 1_000;
const CALL_PUSH_TTL_MS = 45_000;
const PROTOCOL_VERSION = 8;
const FEATURES = Object.freeze(['profile', 'push', 'receipts', 'missed_calls', 'attachments', 'call_resume']);
const DEFAULT_CALL_RESUME_GRACE_MS = 15_000;
const MAX_CALL_RESUME_GRACE_MS = 60_000;
const DEFAULT_PUSH_COALESCE_MS = 1_500;
const MEBIBYTE = 1024 * 1024;
const REQUEST_ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
const BLOB_REQUEST_SHAPES = Object.freeze({
  blob_create: ['type', 'requestId', 'id', 'to', 'size'],
  blob_get: ['type', 'requestId', 'id'],
});
const DEFAULT_ADMIN_SETTINGS = Object.freeze({
  callsEnabled: true,
  chatEnabled: true,
  registrationEnabled: true,
  maxParticipants: 8,
});
const ADMIN_EVENT_LIMIT = 100;

function validBase64(value, maxBytes) {
  if (typeof value !== 'string' || value.length === 0 || value.length > Math.ceil(maxBytes / 3) * 4) return false;
  const bytes = Buffer.from(value, 'base64');
  return bytes.length > 0 && bytes.length <= maxBytes && bytes.toString('base64') === value;
}

function validKeyId(value) {
  return Number.isSafeInteger(value) && value >= 0 && value <= 0x7fffffff;
}

function validateBundle(bundle) {
  if (!isObject(bundle) || !hasOnlyKeys(bundle, ['identityKey', 'registrationId', 'signedPreKey', 'kyberPreKey', 'preKeys'])) return undefined;
  if (!validBase64(bundle.identityKey, 256)) return undefined;
  if (!Number.isSafeInteger(bundle.registrationId) || bundle.registrationId < 0 || bundle.registrationId > 0x7fffffff) return undefined;

  for (const keyName of ['signedPreKey', 'kyberPreKey']) {
    const key = bundle[keyName];
    if (!isObject(key) || !hasOnlyKeys(key, ['id', 'publicKey', 'signature'])
      || !validKeyId(key.id) || !validBase64(key.publicKey, 4_096) || !validBase64(key.signature, 256)) return undefined;
  }

  if (!Array.isArray(bundle.preKeys) || bundle.preKeys.length > MAX_PRE_KEYS) return undefined;
  const ids = new Set();
  const preKeys = [];
  for (const key of bundle.preKeys) {
    if (!isObject(key) || !hasOnlyKeys(key, ['id', 'publicKey']) || !validKeyId(key.id)
      || !validBase64(key.publicKey, 4_096) || ids.has(key.id)) return undefined;
    ids.add(key.id);
    preKeys.push({ id: key.id, publicKey: key.publicKey });
  }

  return {
    identityKey: bundle.identityKey,
    registrationId: bundle.registrationId,
    signedPreKey: { ...bundle.signedPreKey },
    kyberPreKey: { ...bundle.kyberPreKey },
    preKeys: preKeys.sort((a, b) => a.id - b.id),
  };
}

function cloneEntry(entry) {
  return { number: entry.number, bundle: entry.bundle ? structuredClone(entry.bundle) : null, preKeyFloor: entry.preKeyFloor ?? -1 };
}

export async function loadIdentities(dataFile, maxIdentities) {
  let text;
  try {
    text = await readFile(dataFile, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return new Map();
    throw error;
  }

  let document;
  try {
    document = JSON.parse(text);
  } catch {
    throw new Error(`Identity store is not valid JSON: ${dataFile}`);
  }
  if (![1, 2].includes(document?.version) || !isObject(document.identities)) {
    throw new Error(`Identity store has an unsupported format: ${dataFile}`);
  }

  const identities = new Map();
  const numbers = new Set();
  for (const [hash, stored] of Object.entries(document.identities)) {
    const legacy = document.version === 1;
    const number = legacy ? stored : stored?.number;
    const bundle = legacy ? null : stored?.bundle;
    if (!/^[a-f0-9]{64}$/.test(hash) || !NUMBER_PATTERN.test(number) || numbers.has(number)) {
      throw new Error(`Identity store contains invalid or duplicate identities: ${dataFile}`);
    }
    const publicBundle = bundle === null ? null : validateBundle(bundle);
    if (!legacy && bundle !== null && !publicBundle) {
      throw new Error(`Identity store contains an invalid public key bundle: ${dataFile}`);
    }
    const preKeyFloor = legacy ? -1 : (stored.preKeyFloor ?? -1);
    if (!Number.isSafeInteger(preKeyFloor) || preKeyFloor < -1) throw new Error('Invalid prekey counter');
    identities.set(hash, { number, bundle: publicBundle, preKeyFloor });
    numbers.add(number);
  }
  if (identities.size > maxIdentities) throw new Error('Identity store exceeds MAX_IDENTITIES');
  return identities;
}

async function saveIdentities(dataFile, identities) {
  await mkdir(dirname(dataFile), { recursive: true });
  const temporaryFile = `${dataFile}.${process.pid}.${randomUUID()}.tmp`;
  const data = `${JSON.stringify({
    version: 2,
    identities: Object.fromEntries([...identities].map(([hash, entry]) => [hash, cloneEntry(entry)])),
  }, null, 2)}\n`;

  try {
    const file = await open(temporaryFile, 'wx', 0o600);
    try {
      await file.writeFile(data, 'utf8');
      await file.sync();
    } finally {
      await file.close();
    }
    await rename(temporaryFile, dataFile);
    try {
      const directory = await open(dirname(dataFile), 'r');
      try { await directory.sync(); } finally { await directory.close(); }
    } catch {
      // Some filesystems do not allow syncing directory handles.
    }
  } catch (error) {
    await unlink(temporaryFile).catch(() => {});
    throw error;
  }
}

function mailboxKey(from, id) {
  return `${from}:${id}`;
}

function envelopeDigest(cipherType, body) {
  return createHash('sha256').update(`${cipherType}:${body}`, 'utf8').digest('hex');
}

function validateMailboxEnvelope(envelope, numberOwners, now, ttlMs) {
  if (!isObject(envelope) || !hasOnlyKeys(envelope, ['from', 'to', 'id', 'cipherType', 'body', 'createdAt', 'expiresAt'])
    || !NUMBER_PATTERN.test(envelope.from) || !NUMBER_PATTERN.test(envelope.to)
    || !numberOwners.has(envelope.from) || !numberOwners.has(envelope.to)
    || !UUID_PATTERN.test(envelope.id) || ![2, 3].includes(envelope.cipherType)
    || !validBase64(envelope.body, MAX_ENVELOPE_BYTES)
    || !Number.isSafeInteger(envelope.createdAt) || !Number.isSafeInteger(envelope.expiresAt)
    || envelope.expiresAt <= now || envelope.expiresAt <= envelope.createdAt
    || envelope.expiresAt - envelope.createdAt > ttlMs) return undefined;
  return { ...envelope };
}

export async function loadMailbox(mailboxFile, numberOwners, blockedNumbers, now, ttlMs, maxEntries, maxPerUser, maxReceipts) {
  let text;
  try {
    text = await readFile(mailboxFile, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return { envelopes: new Map(), receipts: new Map(), dirty: false };
    throw error;
  }
  let document;
  try {
    document = JSON.parse(text);
  } catch {
    throw new Error(`Mailbox store is not valid JSON: ${mailboxFile}`);
  }
  if (document?.version !== 1 || !Array.isArray(document.envelopes) || !Array.isArray(document.receipts)) {
    throw new Error(`Mailbox store has an unsupported format: ${mailboxFile}`);
  }
  const envelopes = new Map();
  const receipts = new Map();
  const recipientCounts = new Map();
  let dirty = false;
  for (const raw of document.envelopes) {
    const envelope = validateMailboxEnvelope(raw, numberOwners, now, ttlMs);
    if (!envelope) {
      if (isObject(raw) && Number.isSafeInteger(raw.expiresAt) && raw.expiresAt <= now) {
        dirty = true;
        continue;
      }
      throw new Error(`Mailbox store contains an invalid message: ${mailboxFile}`);
    }
    if (blockedNumbers.has(envelope.from) || blockedNumbers.has(envelope.to)) {
      dirty = true;
      continue;
    }
    const key = mailboxKey(envelope.from, envelope.id);
    if (envelopes.has(key)) throw new Error(`Mailbox store contains duplicate message IDs: ${mailboxFile}`);
    recipientCounts.set(envelope.to, (recipientCounts.get(envelope.to) ?? 0) + 1);
    envelopes.set(key, envelope);
  }
  const seenReceipts = new Set();
  for (const receipt of document.receipts) {
    const valid = isObject(receipt) && hasOnlyKeys(receipt, ['from', 'to', 'id', 'cipherType', 'cipherHash', 'createdAt', 'expiresAt'])
      && NUMBER_PATTERN.test(receipt.from) && NUMBER_PATTERN.test(receipt.to)
      && numberOwners.has(receipt.from) && numberOwners.has(receipt.to)
      && UUID_PATTERN.test(receipt.id) && [2, 3].includes(receipt.cipherType)
      && /^[a-f0-9]{64}$/.test(receipt.cipherHash) && Number.isSafeInteger(receipt.createdAt)
      && Number.isSafeInteger(receipt.expiresAt) && receipt.expiresAt > now
      && receipt.expiresAt > receipt.createdAt && receipt.expiresAt - receipt.createdAt <= ttlMs;
    if (!valid) {
      if (isObject(receipt) && Number.isSafeInteger(receipt.expiresAt) && receipt.expiresAt <= now) {
        dirty = true;
        continue;
      }
      throw new Error(`Mailbox store contains an invalid delivery receipt: ${mailboxFile}`);
    }
    if (blockedNumbers.has(receipt.from) || blockedNumbers.has(receipt.to)) {
      dirty = true;
      continue;
    }
    const key = mailboxKey(receipt.from, receipt.id);
    if (seenReceipts.has(key)) throw new Error(`Mailbox store contains duplicate delivery receipts: ${mailboxFile}`);
    seenReceipts.add(key);
    receipts.set(key, { ...receipt });
  }
  if (envelopes.size > maxEntries || receipts.size > maxReceipts
    || [...recipientCounts.values()].some((count) => count > maxPerUser)) {
    throw new Error(`Mailbox store exceeds configured limits: ${mailboxFile}`);
  }
  return { envelopes, receipts, dirty };
}

// Legacy FCM token store: read by check-data-compat only. This version never writes or rewrites it.
export async function loadPushTokens(pushFile, numberOwners, blockedNumbers) {
  let text;
  try {
    text = await readFile(pushFile, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return { tokens: new Map(), dirty: false };
    throw error;
  }
  let document;
  try {
    document = JSON.parse(text);
  } catch {
    throw new Error(`Push token store is not valid JSON: ${pushFile}`);
  }
  if (document?.version !== 1 || !isObject(document.tokens)) {
    throw new Error(`Push token store has an unsupported format: ${pushFile}`);
  }
  const tokens = new Map();
  let dirty = false;
  for (const [number, token] of Object.entries(document.tokens)) {
    if (!NUMBER_PATTERN.test(number) || !numberOwners.has(number)
      || typeof token !== 'string' || Buffer.byteLength(token, 'utf8') < MIN_PUSH_TOKEN_BYTES
      || Buffer.byteLength(token, 'utf8') > MAX_PUSH_TOKEN_BYTES || /[\u0000-\u0020\u007f]/.test(token)) {
      throw new Error(`Push token store contains an invalid or unknown account: ${pushFile}`);
    }
    if (blockedNumbers.has(number)) {
      dirty = true;
      continue;
    }
    tokens.set(number, token);
  }
  return { tokens, dirty };
}

function canonicalBase64(value) {
  if (typeof value !== 'string' || value.length === 0) return undefined;
  const decoded = Buffer.from(value, 'base64');
  return decoded.toString('base64') === value ? decoded : undefined;
}

function parseAdminPasswordHash(value) {
  if (typeof value !== 'string' || value.length > 256) return undefined;
  const match = /^scrypt\$([A-Za-z0-9+/]+={0,2})\$([A-Za-z0-9+/]+={0,2})$/.exec(value);
  if (!match) return undefined;
  const salt = canonicalBase64(match[1]);
  const digest = canonicalBase64(match[2]);
  if (!salt || salt.length < 16 || salt.length > 64 || !digest || digest.length !== 64) return undefined;
  return { salt, digest };
}

function deriveAdminDigest(code, salt) {
  return new Promise((resolve, reject) => {
    scrypt(code, salt, 64, { maxmem: 64 * 1024 * 1024 }, (error, digest) => {
      if (error) reject(error);
      else resolve(digest);
    });
  });
}

function validateAdminSettings(settings) {
  return isObject(settings) && hasOnlyKeys(settings, Object.keys(DEFAULT_ADMIN_SETTINGS))
    && typeof settings.callsEnabled === 'boolean'
    && typeof settings.chatEnabled === 'boolean'
    && typeof settings.registrationEnabled === 'boolean'
    && Number.isSafeInteger(settings.maxParticipants) && settings.maxParticipants >= 2 && settings.maxParticipants <= 8;
}

export async function loadAdminState(dataFile) {
  let text;
  try {
    text = await readFile(dataFile, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return { settings: { ...DEFAULT_ADMIN_SETTINGS }, blockedNumbers: [] };
    throw error;
  }

  let document;
  try {
    document = JSON.parse(text);
  } catch {
    throw new Error(`Admin store is not valid JSON: ${dataFile}`);
  }
  if (!isObject(document) || !hasOnlyKeys(document, ['version', 'settings', 'blockedNumbers'])
    || document.version !== 1 || !validateAdminSettings(document.settings)
    || !Array.isArray(document.blockedNumbers)
    || document.blockedNumbers.some((number) => typeof number !== 'string' || !NUMBER_PATTERN.test(number))
    || new Set(document.blockedNumbers).size !== document.blockedNumbers.length) {
    throw new Error(`Admin store has an invalid format: ${dataFile}`);
  }
  return { settings: { ...document.settings }, blockedNumbers: [...document.blockedNumbers] };
}

async function saveAdminState(dataFile, state) {
  await mkdir(dirname(dataFile), { recursive: true });
  const temporaryFile = `${dataFile}.${process.pid}.${randomUUID()}.tmp`;
  const content = `${JSON.stringify({ version: 1, ...state }, null, 2)}\n`;
  try {
    const file = await open(temporaryFile, 'wx', 0o600);
    try {
      await file.writeFile(content, 'utf8');
      await file.sync();
    } finally {
      await file.close();
    }
    await rename(temporaryFile, dataFile);
    try {
      const directory = await open(dirname(dataFile), 'r');
      try { await directory.sync(); } finally { await directory.close(); }
    } catch {
      // Some filesystems do not allow syncing directory handles.
    }
  } catch (error) {
    await unlink(temporaryFile).catch(() => {});
    throw error;
  }
}

function validMediaHostname(hostname) {
  const ipAddress = hostname.startsWith('[') && hostname.endsWith(']')
    ? hostname.slice(1, -1)
    : hostname;
  if (isIP(ipAddress)) return true;
  if (hostname.length > 253) return false;
  return hostname.split('.').every((label) => label.length <= 63
    && /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label));
}

function buildMediaConfig(env) {
  const value = env.LIVEKIT_URL?.trim();
  const apiKey = env.LIVEKIT_API_KEY?.trim();
  const apiSecret = env.LIVEKIT_API_SECRET?.trim();
  if (!value || !apiKey || !apiSecret) return undefined;

  try {
    const url = new URL(value);
    const authority = /^wss:\/\/([^/?#]*)/i.exec(value)?.[1];
    if (url.protocol !== 'wss:' || !authority || authority.includes('@') || !validMediaHostname(url.hostname)
      || url.port === '0' || url.pathname !== '/' || value.includes('?') || value.includes('#')) return undefined;
    const mediaUrl = url.origin;
    const apiUrl = mediaUrl.replace(/^wss:/i, 'https:');
    return { mediaUrl, apiUrl, apiKey, apiSecret };
  } catch {
    return undefined;
  }
}

function send(socket, message) {
  if (socket.readyState !== WebSocket.OPEN) return false;
  const data = JSON.stringify(message);
  if (socket.bufferedAmount + Buffer.byteLength(data) > MAX_BUFFERED_BYTES) {
    socket.close(4004, 'outgoing queue full');
    return false;
  }
  try {
    socket.send(data);
    return true;
  } catch {
    return false;
  }
}

export async function createSignalingServer(options = {}) {
  const env = options.env ?? process.env;
  const defaultDataFile = fileURLToPath(new URL('../data/identities.json', import.meta.url));
  const dataFile = resolve(options.dataFile ?? env.DATA_FILE ?? defaultDataFile);
  const adminDataFile = resolve(options.adminDataFile ?? env.ADMIN_DATA_FILE ?? `${dataFile}.admin.json`);
  const mailboxFile = resolve(options.mailboxFile ?? env.MAILBOX_FILE ?? `${dataFile}.mailbox.json`);
  const pushTokenFile = resolve(options.pushTokenFile ?? env.PUSH_TOKEN_FILE ?? `${dataFile}.push.json`);
  const profileFile = resolve(options.profileFile ?? env.PROFILE_FILE ?? `${dataFile}.profiles.json`);
  const pushEndpointFile = resolve(options.pushEndpointFile ?? env.PUSH_ENDPOINT_FILE ?? `${dataFile}.push-endpoints.json`);
  const missedFile = resolve(options.missedFile ?? env.MISSED_FILE ?? `${dataFile}.missed.json`);
  const blobDirectory = resolve(options.blobDir ?? env.BLOB_DIR ?? `${dataFile}.blobs`);
  if (new Set([dataFile, adminDataFile, mailboxFile, pushTokenFile, profileFile, pushEndpointFile, missedFile, blobDirectory]).size !== 8) {
    throw new Error('Identity, admin, mailbox, legacy push-token, profile, push-endpoint, missed-call, and blob stores must use separate paths');
  }
  const adminPasswordHash = parseAdminPasswordHash(env.ADMIN_PASSWORD_HASH);
  const adminSessionMs = options.adminSessionMs ?? 5 * 60_000;
  const adminRateWindowMs = options.adminRateWindowMs ?? 60_000;
  const adminLockMs = options.adminLockMs ?? 60_000;
  const adminMaxFailures = options.adminMaxFailures ?? 5;
  const maxConcurrentAdminLogins = options.maxConcurrentAdminLogins ?? 8;
  const deriveAdminPassword = options.deriveAdminPassword ?? deriveAdminDigest;
  const maxIdentities = options.maxIdentities ?? 100_000;
  const maxConnections = options.maxConnections ?? 1_000;
  const maxRegistrationsPerIp = options.maxRegistrationsPerIp ?? 30;
  const registrationWindowMs = options.registrationWindowMs ?? 60_000;
  const maxMessagesPerSecond = options.maxMessagesPerSecond ?? 40;
  const maxRateLimitEntries = options.maxRateLimitEntries ?? 10_000;
  const maxPendingStoreOperations = options.maxPendingStoreOperations ?? 1_000;
  const mailboxTtlMs = options.mailboxTtlMs ?? DEFAULT_MAILBOX_TTL_MS;
  const maxMailboxEntries = options.maxMailboxEntries ?? DEFAULT_MAX_MAILBOX_ENTRIES;
  const maxMailboxPerUser = options.maxMailboxPerUser ?? DEFAULT_MAX_MAILBOX_PER_USER;
  const maxReceiptEntries = options.maxReceiptEntries ?? DEFAULT_MAX_RECEIPTS;
  const maxInboxSyncsPerWindow = options.maxInboxSyncsPerWindow ?? DEFAULT_MAX_INBOX_SYNCS_PER_WINDOW;
  const inboxSyncWindowMs = options.inboxSyncWindowMs ?? DEFAULT_INBOX_SYNC_WINDOW_MS;
  if (!Number.isSafeInteger(mailboxTtlMs) || mailboxTtlMs < 1 || mailboxTtlMs > DEFAULT_MAILBOX_TTL_MS
    || !Number.isSafeInteger(maxMailboxEntries) || maxMailboxEntries < 1 || maxMailboxEntries > DEFAULT_MAX_MAILBOX_ENTRIES
    || !Number.isSafeInteger(maxMailboxPerUser) || maxMailboxPerUser < 1 || maxMailboxPerUser > DEFAULT_MAX_MAILBOX_PER_USER
    || !Number.isSafeInteger(maxReceiptEntries) || maxReceiptEntries < 1 || maxReceiptEntries > DEFAULT_MAX_RECEIPTS
    || !Number.isSafeInteger(maxInboxSyncsPerWindow) || maxInboxSyncsPerWindow < 1 || maxInboxSyncsPerWindow > 100
    || !Number.isSafeInteger(inboxSyncWindowMs) || inboxSyncWindowMs < 1_000 || inboxSyncWindowMs > 60_000) {
    throw new Error('Invalid mailbox limits');
  }
  const callResumeGraceMs = readIntegerSetting('CALL_RESUME_GRACE_MS', options.callResumeGraceMs ?? env.CALL_RESUME_GRACE_MS,
    DEFAULT_CALL_RESUME_GRACE_MS, 0, MAX_CALL_RESUME_GRACE_MS);
  const pushCoalesceMs = readIntegerSetting('pushCoalesceMs', options.pushCoalesceMs, DEFAULT_PUSH_COALESCE_MS, 0, 60_000);
  const blobMaxBytes = readIntegerSetting('BLOB_MAX_BYTES', options.blobMaxBytes ?? env.BLOB_MAX_BYTES, 26 * MEBIBYTE, 1, 64 * MEBIBYTE);
  const blobTtlMs = readIntegerSetting('BLOB_TTL_MS', options.blobTtlMs ?? env.BLOB_TTL_MS,
    7 * 24 * 60 * 60_000, 1, 14 * 24 * 60 * 60_000);
  const blobQuotaBytes = readIntegerSetting('BLOB_QUOTA_BYTES', options.blobQuotaBytes ?? env.BLOB_QUOTA_BYTES,
    1024 * MEBIBYTE, 1, 1024 * 1024 * MEBIBYTE);
  const blobSenderPendingBytes = readIntegerSetting('BLOB_SENDER_PENDING_BYTES',
    options.blobSenderPendingBytes ?? env.BLOB_SENDER_PENDING_BYTES, 200 * MEBIBYTE, 1, 1024 * 1024 * MEBIBYTE);
  const blobCleanupIntervalMs = readIntegerSetting('blobCleanupIntervalMs', options.blobCleanupIntervalMs, 60_000, 10, 3_600_000);
  const registrationTimeoutMs = options.registrationTimeoutMs ?? 10_000;
  const ringingTimeoutMs = options.ringingTimeoutMs ?? 45_000;
  const heartbeatIntervalMs = options.heartbeatIntervalMs ?? 10_000;
  const identities = await loadIdentities(dataFile, maxIdentities);
  const storedAdminState = await loadAdminState(adminDataFile);
  const numberOwners = new Map([...identities].map(([hash, entry]) => [entry.number, hash]));
  if (storedAdminState.blockedNumbers.length > maxIdentities
    || storedAdminState.blockedNumbers.some((number) => !numberOwners.has(number))) {
    throw new Error(`Admin store references invalid or unknown accounts: ${adminDataFile}`);
  }
  const sessions = new Map();
  const memberships = new Map();
  const calls = new Map();
  const registrationRates = new Map();
  const inboxSyncRates = new Map();
  const adminLoginRates = new Map();
  const adminEvents = [];
  let pendingAdminLogins = 0;
  let adminSettings = storedAdminState.settings;
  let blockedNumbers = new Set(storedAdminState.blockedNumbers);
  const startedAt = Date.now();
  let mailbox = await loadMailbox(mailboxFile, numberOwners, blockedNumbers, Date.now(), mailboxTtlMs,
    maxMailboxEntries, maxMailboxPerUser, maxReceiptEntries);
  if (mailbox.dirty) {
    await atomicWriteJson(mailboxFile, {
      version: 1,
      envelopes: [...mailbox.envelopes.values()],
      receipts: [...mailbox.receipts.values()],
    });
  }
  // Profiles, push endpoints and missed calls live in files that are only ever created by a write.
  let profiles = await loadProfiles(profileFile, numberOwners);
  let pushEndpoints = await loadPushEndpoints(pushEndpointFile, numberOwners, blockedNumbers);
  let missedCalls = await loadMissedCalls(missedFile, numberOwners, Date.now());
  const push = await createPushLayer({
    allowPrivate: readBooleanSetting(env.PUSH_ALLOW_PRIVATE_ENDPOINTS),
    apnsConfig: await readApnsConfig(env),
    options,
  });
  const blobs = createBlobStore({
    directory: blobDirectory,
    maxBytes: blobMaxBytes,
    ttlMs: blobTtlMs,
    quotaBytes: blobQuotaBytes,
    senderPendingBytes: blobSenderPendingBytes,
    ...options.blob,
    isBlocked: (number) => blockedNumbers.has(number),
    chatEnabled: () => adminSettings.chatEnabled,
  });
  await blobs.load();
  const mediaConfig = buildMediaConfig(env);
  const tokenIssuer = options.tokenIssuer ?? (async ({ identity, room, apiKey, apiSecret }) => {
    const token = new AccessToken(apiKey, apiSecret, { identity, ttl: 120 });
    token.addGrant({
      roomJoin: true,
      room,
      canPublishSources: [TrackSource.MICROPHONE],
      canSubscribe: true,
      canPublishData: false,
    });
    return token.toJwt();
  });
  let roomService;
  if (mediaConfig && (!options.createRoom || !options.deleteRoom)) {
    roomService = new RoomServiceClient(mediaConfig.apiUrl, mediaConfig.apiKey, mediaConfig.apiSecret);
  }
  const createRoom = options.createRoom ?? (roomService ? (roomOptions) => roomService.createRoom(roomOptions) : undefined);
  const deleteRoom = options.deleteRoom ?? (roomService ? (room) => roomService.deleteRoom(room) : undefined);

  let storeQueue = Promise.resolve();
  let pendingStoreOperations = 0;
  const timers = new WeakMap();
  let closing = false;
  const profileSetLimiter = createRateLimiter({ limit: 10, windowMs: 60_000, maxEntries: maxRateLimitEntries });
  const blobCreateLimiter = createRateLimiter({ limit: 20, windowMs: 60_000, maxEntries: maxRateLimitEntries });

  const httpServer = createServer((request, response) => {
    if (request.method === 'GET' && (request.url === '/health' || request.url === '/')) {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
      response.end(JSON.stringify({ status: 'ok' }));
      return;
    }
    if (blobs.handleRequest(request, response)) return;
    response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    response.end('Not found');
  });
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_PAYLOAD, perMessageDeflate: false });

  function error(socket, code, extra = {}) {
    send(socket, { type: 'error', code, ...extra });
  }

  function appendAdminEvent(event, outcome = 'success') {
    adminEvents.push({ at: new Date().toISOString(), event, outcome });
    if (adminEvents.length > ADMIN_EVENT_LIMIT) adminEvents.splice(0, adminEvents.length - ADMIN_EVENT_LIMIT);
  }

  function capabilities() {
    return {
      type: 'capabilities',
      ...adminSettings,
      mediaReady: Boolean(mediaConfig),
    };
  }

  function adminResult(session, requestId, ok, values = {}) {
    send(session.socket, { type: 'admin_result', requestId, ok, ...values });
  }

  function takeAdminLoginSlot(ip) {
    const now = Date.now();
    let bucket = adminLoginRates.get(ip);
    if (!bucket && adminLoginRates.size >= maxRateLimitEntries) {
      for (const [key, candidate] of adminLoginRates) {
        if (candidate.pending === 0 && candidate.lockUntil <= now
          && now - candidate.windowStart >= adminRateWindowMs) adminLoginRates.delete(key);
        if (adminLoginRates.size < maxRateLimitEntries) break;
      }
      if (adminLoginRates.size >= maxRateLimitEntries) return undefined;
    }
    if (!bucket) {
      bucket = { windowStart: now, failures: 0, pending: 0, lockUntil: 0 };
      adminLoginRates.set(ip, bucket);
    }
    if (bucket.lockUntil > now) return undefined;
    if (now - bucket.windowStart >= adminRateWindowMs && bucket.pending === 0) {
      bucket.windowStart = now;
      bucket.failures = 0;
      bucket.lockUntil = 0;
    }
    if (bucket.failures + bucket.pending >= adminMaxFailures) return undefined;
    bucket.pending += 1;
    return bucket;
  }

  async function loginAdmin(session, requestId, code) {
    if (!session.number) {
      adminResult(session, requestId, false, { error: 'registration_required' });
      return;
    }
    if (!adminPasswordHash) {
      adminResult(session, requestId, false, { error: 'admin_disabled' });
      return;
    }
    if (session.adminLoginPending) {
      adminResult(session, requestId, false, { error: 'rate_limited' });
      return;
    }
    if (pendingAdminLogins >= maxConcurrentAdminLogins) {
      adminResult(session, requestId, false, { error: 'rate_limited' });
      return;
    }
    const bucket = takeAdminLoginSlot(session.ip);
    if (!bucket) {
      adminResult(session, requestId, false, { error: 'rate_limited' });
      return;
    }
    session.adminLoginPending = true;
    const authGeneration = session.adminAuthGeneration ?? 0;
    pendingAdminLogins += 1;
    let valid = false;
    try {
      if (typeof code === 'string' && code.length <= 256 && [...code].length >= 12 && [...code].length <= 128
        && Buffer.byteLength(code, 'utf8') <= 512) {
        const digest = await deriveAdminPassword(code, adminPasswordHash.salt);
        valid = timingSafeEqual(digest, adminPasswordHash.digest);
      }
    } catch {
      valid = false;
    } finally {
      session.adminLoginPending = false;
      pendingAdminLogins -= 1;
    }
    bucket.pending -= 1;
    if (!valid) {
      bucket.failures += 1;
      if (bucket.failures >= adminMaxFailures) bucket.lockUntil = Date.now() + adminLockMs;
      if (!session.closed && session.socket.readyState === WebSocket.OPEN) {
        adminResult(session, requestId, false, { error: 'invalid_credentials' });
      }
      return;
    }
    if ((session.adminAuthGeneration ?? 0) !== authGeneration) {
      if (!session.closed && session.socket.readyState === WebSocket.OPEN) adminResult(session, requestId, false, { error: 'login_cancelled' });
      return;
    }
    bucket.failures = 0;
    bucket.lockUntil = 0;
    if (bucket.pending === 0) adminLoginRates.delete(session.ip);
    if (session.closed || session.socket.readyState !== WebSocket.OPEN) return;
    session.adminExpiresAt = Date.now() + adminSessionMs;
    appendAdminEvent('admin_login');
    adminResult(session, requestId, true, { expiresAt: session.adminExpiresAt });
  }

  function adminStatus() {
    return {
      settings: { ...adminSettings },
      metrics: {
        online: sessions.size,
        registered: identities.size,
        activeCalls: calls.size,
        mediaConfigured: Boolean(mediaConfig),
        uptimeSeconds: Math.floor((Date.now() - startedAt) / 1_000),
      },
      blockedNumbers: [...blockedNumbers].sort(),
      calls: [...calls.values()].map((call) => ({ id: call.id, participantCount: call.members.length })),
      events: adminEvents.map((entry) => ({ ...entry })),
    };
  }

  async function executeAdminAction(session, message) {
    const { action, requestId } = message;
    if (action === 'logout') {
      session.adminExpiresAt = undefined;
      appendAdminEvent('admin_logout');
      return { loggedOut: true };
    }
    if (action === 'status') return adminStatus();
    if (action === 'update_settings') {
      const nextSettings = { ...adminSettings, ...message.settings };
      if (!validateAdminSettings(nextSettings)) return { error: 'invalid_settings' };
      const nextState = { settings: nextSettings, blockedNumbers: [...blockedNumbers] };
      await saveAdminState(adminDataFile, nextState);
      adminSettings = nextSettings;
      appendAdminEvent('settings_updated');
      if (!adminSettings.callsEnabled) {
        for (const call of [...calls.values()]) endCall(call, 'admin_disabled');
      }
      for (const socket of wss.clients) {
        if (socket.readyState === WebSocket.OPEN) send(socket, capabilities());
      }
      return { settings: { ...adminSettings } };
    }
    if (action === 'block' || action === 'unblock') {
      const number = message.number;
      if (!numberOwners.has(number)) return { error: 'not_found' };
      if (action === 'block' && session.number === number) return { error: 'self_block_denied' };
      const nextBlocked = new Set(blockedNumbers);
      if (action === 'block') nextBlocked.add(number);
      else nextBlocked.delete(number);
      await saveAdminState(adminDataFile, { settings: { ...adminSettings }, blockedNumbers: [...nextBlocked] });
      blockedNumbers = nextBlocked;
      appendAdminEvent(action === 'block' ? 'number_blocked' : 'number_unblocked');
      if (action === 'block') {
        await removeNumberData(number);
        endCallForNumber(number, 'blocked');
        const target = sessions.get(number);
        if (target) {
          error(target.socket, 'blocked');
          target.socket.close(4003, 'blocked');
          cleanupSession(target);
        }
      }
      return { number, blocked: blockedNumbers.has(number) };
    }
    if (action === 'end_call') {
      const call = calls.get(message.callId);
      if (!call) return { error: 'not_found' };
      endCall(call, 'admin_ended');
      appendAdminEvent('call_ended_by_admin');
      return { callId: message.callId, ended: true };
    }
    if (action === 'clear_events') {
      adminEvents.length = 0;
      appendAdminEvent('events_cleared');
      return { cleared: true };
    }
    return { error: 'unknown_action' };
  }

  function queueAdminOperation(session, message) {
    const { requestId } = message;
    if (pendingStoreOperations >= maxPendingStoreOperations) {
      adminResult(session, requestId, false, { error: 'rate_limited' });
      return;
    }
    pendingStoreOperations += 1;
    const task = storeQueue.then(async () => {
      if (session.closed || session.socket.readyState !== WebSocket.OPEN) return;
      if (!session.adminExpiresAt) {
        adminResult(session, requestId, false, { error: 'unauthorized' });
        return;
      }
      if (session.adminExpiresAt <= Date.now()) {
        session.adminExpiresAt = undefined;
        adminResult(session, requestId, false, { error: 'expired' });
        return;
      }
      try {
        const result = await executeAdminAction(session, message);
        if (session.closed || session.socket.readyState !== WebSocket.OPEN) return;
        if (result.error) adminResult(session, requestId, false, { error: result.error });
        else adminResult(session, requestId, true, { result });
      } catch {
        adminResult(session, requestId, false, { error: 'storage_unavailable' });
      }
    });
    storeQueue = task.finally(() => { pendingStoreOperations -= 1; });
  }

  function handleAdminMessage(session, message) {
    if (typeof message.requestId !== 'string' || !UUID_PATTERN.test(message.requestId)) {
      return error(session.socket, 'invalid_message');
    }
    if (message.type === 'admin_login') {
      if (!hasOnlyKeys(message, ['type', 'requestId', 'code']) || typeof message.code !== 'string') {
        return adminResult(session, message.requestId, false, { error: 'invalid_message' });
      }
      return void loginAdmin(session, message.requestId, message.code);
    }
    if (!hasOnlyKeys(message, ['type', 'requestId', 'action', 'settings', 'number', 'callId'])
      || typeof message.action !== 'string') return adminResult(session, message.requestId, false, { error: 'invalid_message' });
    if (message.action === 'status' || message.action === 'clear_events' || message.action === 'logout') {
      if (Object.keys(message).some((key) => !['type', 'requestId', 'action'].includes(key))) {
        return adminResult(session, message.requestId, false, { error: 'invalid_message' });
      }
    } else if (message.action === 'update_settings') {
      if (!isObject(message.settings) || Object.keys(message.settings).length === 0
        || !hasOnlyKeys(message.settings, Object.keys(DEFAULT_ADMIN_SETTINGS))) {
        return adminResult(session, message.requestId, false, { error: 'invalid_message' });
      }
      for (const [key, value] of Object.entries(message.settings)) {
        if (key === 'maxParticipants' ? !Number.isSafeInteger(value) || value < 2 || value > 8 : typeof value !== 'boolean') {
          return adminResult(session, message.requestId, false, { error: 'invalid_settings' });
        }
      }
      if (Object.keys(message).some((key) => !['type', 'requestId', 'action', 'settings'].includes(key))) {
        return adminResult(session, message.requestId, false, { error: 'invalid_message' });
      }
    } else if (message.action === 'block' || message.action === 'unblock') {
      if (typeof message.number !== 'string' || !NUMBER_PATTERN.test(message.number)
        || Object.keys(message).some((key) => !['type', 'requestId', 'action', 'number'].includes(key))) {
        return adminResult(session, message.requestId, false, { error: 'invalid_message' });
      }
    } else if (message.action === 'end_call') {
      if (typeof message.callId !== 'string' || !UUID_PATTERN.test(message.callId)
        || Object.keys(message).some((key) => !['type', 'requestId', 'action', 'callId'].includes(key))) {
        return adminResult(session, message.requestId, false, { error: 'invalid_message' });
      }
    }
    if (message.action === 'logout') {
      if (!session.number) return adminResult(session, message.requestId, false, { error: 'registration_required' });
      if (session.adminExpiresAt || session.adminLoginPending) appendAdminEvent('admin_logout');
      session.adminAuthGeneration = (session.adminAuthGeneration ?? 0) + 1;
      session.adminExpiresAt = undefined;
      return adminResult(session, message.requestId, true, { result: { loggedOut: true } });
    }
    if (!session.adminExpiresAt) return adminResult(session, message.requestId, false, { error: 'unauthorized' });
    if (session.adminExpiresAt <= Date.now()) {
      session.adminExpiresAt = undefined;
      return adminResult(session, message.requestId, false, { error: 'expired' });
    }
    queueAdminOperation(session, message);
  }

  function queueStoreOperation(session, operation, onFailure) {
    if (pendingStoreOperations >= maxPendingStoreOperations) {
      onFailure('rate_limited');
      return;
    }
    pendingStoreOperations += 1;
    const task = storeQueue.then(async () => {
      if (session.closed || session.socket.readyState !== WebSocket.OPEN) return;
      try {
        await operation();
      } catch {
        onFailure('storage_unavailable');
      }
    });
    storeQueue = task.finally(() => { pendingStoreOperations -= 1; });
  }

  async function saveMailboxState(envelopes, receipts) {
    await options.beforeMailboxWrite?.();
    const now = Date.now();
    const nextEnvelopes = new Map([...envelopes].filter(([, envelope]) => envelope.expiresAt > now));
    const nextReceipts = new Map([...receipts].filter(([, receipt]) => receipt.expiresAt > now));
    while (nextReceipts.size > maxReceiptEntries) {
      nextReceipts.delete(nextReceipts.keys().next().value);
    }
    await atomicWriteJson(mailboxFile, {
      version: 1,
      envelopes: [...nextEnvelopes.values()],
      receipts: [...nextReceipts.values()],
    });
    mailbox = { envelopes: nextEnvelopes, receipts: nextReceipts, dirty: false };
  }

  async function savePushEndpointState(endpoints) {
    await savePushEndpoints(pushEndpointFile, endpoints);
    pushEndpoints = endpoints;
  }

  function enqueueStoreTask(task) {
    const result = storeQueue.then(task);
    storeQueue = result.catch(() => {});
  }

  // A stale endpoint stays in memory if the write fails; the next rejected push retries the removal.
  function removeEndpoint(number, record) {
    enqueueStoreTask(async () => {
      if (pushEndpoints.get(number) !== record) return;
      const next = new Map(pushEndpoints);
      next.delete(number);
      await savePushEndpointState(next);
    });
  }

  function isOnline(number) {
    const session = sessions.get(number);
    return Boolean(session && !session.closed && session.socket.readyState === WebSocket.OPEN);
  }

  const hasUsableEndpoint = (number) => push.canSend(pushEndpoints.get(number));
  const profileName = (number) => profiles.get(number)?.name || undefined;

  function dispatchPush(number, payload, trailing) {
    const record = pushEndpoints.get(number);
    if (closing || !record || blockedNumbers.has(number) || !push.canSend(record)) return;
    if (trailing && isOnline(number)) return;
    Promise.resolve().then(() => push.send(record, payload)).catch((failure) => {
      if (failure?.code === PUSH_ENDPOINT_GONE) removeEndpoint(number, record);
    });
  }

  const messageCoalescer = createCoalescer({ windowMs: pushCoalesceMs, dispatch: dispatchPush });

  function sendPush(number, kind, id, ttlMs, reason) {
    const payload = { kind, id, ttlMs };
    if (kind === 'call_ended' && CALL_ENDED_PUSH_REASONS.includes(reason)) payload.reason = reason;
    if (kind === 'message' && pushCoalesceMs > 0) messageCoalescer.push(number, payload);
    else dispatchPush(number, payload, false);
  }

  function envelopeEvent(recipient, envelope) {
    const event = {
      type: 'envelope', from: envelope.from, id: envelope.id,
      cipherType: envelope.cipherType, body: envelope.body,
    };
    if (recipient.protocolVersion >= 8) {
      const name = profileName(envelope.from);
      if (name) event.fromName = name;
      event.sentAt = envelope.createdAt;
    }
    return event;
  }

  function incomingEvent(recipient, call) {
    const event = { type: 'incoming', callId: call.id, room: call.room, members: call.members, owner: call.owner };
    if (recipient.protocolVersion >= 8) {
      const name = profileName(call.owner);
      if (name) event.ownerName = name;
    }
    return event;
  }

  async function removeNumberData(number) {
    const envelopes = new Map([...mailbox.envelopes].filter(([, envelope]) => envelope.from !== number && envelope.to !== number));
    const receipts = new Map([...mailbox.receipts].filter(([, receipt]) => receipt.from !== number && receipt.to !== number));
    await saveMailboxState(envelopes, receipts);
    if (pushEndpoints.has(number)) {
      const endpoints = new Map(pushEndpoints);
      endpoints.delete(number);
      await savePushEndpointState(endpoints);
    }
    if (profiles.has(number)) {
      const remaining = new Map(profiles);
      remaining.delete(number);
      await saveProfiles(profileFile, remaining);
      profiles = remaining;
    }
    const remainingMissed = missedCalls.filter((call) => call.to !== number && call.from !== number);
    if (remainingMissed.length !== missedCalls.length) {
      await saveMissedCalls(missedFile, remainingMissed);
      missedCalls = remainingMissed;
    }
    messageCoalescer.forget(number);
    await blobs.removeNumber(number).catch(() => {});
  }

  function sendMailbox(session) {
    if (!session.number || !adminSettings.chatEnabled || blockedNumbers.has(session.number)) return;
    const now = Date.now();
    for (const envelope of mailbox.envelopes.values()) {
      if (envelope.to !== session.number || envelope.expiresAt <= now) continue;
      const forwarded = send(session.socket, envelopeEvent(session, envelope));
      if (!forwarded) {
        sendPush(session.number, 'message', envelope.id, MESSAGE_PUSH_TTL_MS);
        return;
      }
    }
  }

  function sendDeliveryReceipts(session) {
    if (session.protocolVersion < 7) return;
    const now = Date.now();
    for (const receipt of mailbox.receipts.values()) {
      if (receipt.from === session.number && receipt.expiresAt > now) {
        send(session.socket, { type: 'delivered', id: receipt.id });
      }
    }
  }

  function deliverPending(session) {
    sendPendingCalls(session);
    sendDeliveryReceipts(session);
    sendMailbox(session);
  }

  function sendInboxComplete(session) {
    if (session.protocolVersion >= 7) send(session.socket, { type: 'inbox_complete' });
  }

  function sendPendingCalls(session) {
    if (!adminSettings.callsEnabled || blockedNumbers.has(session.number)) return;
    const callId = memberships.get(session.number);
    const call = callId && calls.get(callId);
    if (call && call.owner !== session.number && !call.joined.has(session.number)) {
      if (!send(session.socket, incomingEvent(session, call))) {
        sendPush(session.number, 'call', call.id, CALL_PUSH_TTL_MS);
      }
    }
  }

  function resumeSession(session) {
    deliverPending(session);
    sendInboxComplete(session);
  }

  function takeInboxSyncSlot(number) {
    const now = Date.now();
    let bucket = inboxSyncRates.get(number);
    if (!bucket && inboxSyncRates.size >= maxRateLimitEntries) {
      for (const [key, candidate] of inboxSyncRates) {
        if (now - candidate.start >= inboxSyncWindowMs) inboxSyncRates.delete(key);
        if (inboxSyncRates.size < maxRateLimitEntries) break;
      }
      if (inboxSyncRates.size >= maxRateLimitEntries) return false;
    }
    return consumeRateLimit(inboxSyncRates, number, {
      limit: maxInboxSyncsPerWindow,
      windowMs: inboxSyncWindowMs,
      now,
    });
  }

  function syncInbox(session) {
    if (session.protocolVersion < 7) return error(session.socket, 'invalid_message');
    if (!takeInboxSyncSlot(session.number)) return error(session.socket, 'rate_limited');
    deliverPending(session);
    sendInboxComplete(session);
  }

  async function registerPushEndpoint(session, message) {
    const parsed = parsePushRegistration(message, { allowPrivate: push.allowPrivate, apnsEnabled: push.apnsEnabled });
    if (parsed.error) return error(session.socket, parsed.error);
    if (!sameEndpoint(pushEndpoints.get(session.number), parsed.record)) {
      const endpoints = new Map(pushEndpoints);
      endpoints.set(session.number, { ...parsed.record, registeredAt: Date.now() });
      await savePushEndpointState(endpoints);
    }
    send(session.socket, { type: 'push_registered', pushEnabled: true, provider: parsed.record.provider });
  }

  async function unregisterPushEndpoint(session) {
    if (pushEndpoints.has(session.number)) {
      const endpoints = new Map(pushEndpoints);
      endpoints.delete(session.number);
      await savePushEndpointState(endpoints);
    }
    send(session.socket, { type: 'push_unregistered' });
  }

  async function setProfile(session, name) {
    if (blockedNumbers.has(session.number)) return error(session.socket, 'blocked');
    if (!adminSettings.chatEnabled) return error(session.socket, 'chat_disabled');
    const current = profiles.get(session.number);
    if (name === (current?.name ?? '')) {
      return send(session.socket, { type: 'profile_updated', name, updatedAt: name === '' ? 0 : current.updatedAt });
    }
    const updatedAt = Math.max(Date.now(), (current?.updatedAt ?? 0) + 1);
    const next = new Map(profiles);
    next.set(session.number, { name, updatedAt, proto: Math.max(current?.proto ?? 0, session.protocolVersion) });
    await saveProfiles(profileFile, next);
    profiles = next;
    return send(session.socket, { type: 'profile_updated', name, updatedAt: name === '' ? 0 : updatedAt });
  }

  // Best effort: a failed write is retried at the account's next v8 registration.
  async function recordProtocolVersion(session) {
    const current = profiles.get(session.number);
    const proto = Math.max(current?.proto ?? 0, session.protocolVersion);
    if (current?.proto === proto) return;
    const next = new Map(profiles);
    next.set(session.number, { name: current?.name ?? '', updatedAt: current?.updatedAt ?? 0, proto });
    try {
      await saveProfiles(profileFile, next);
      profiles = next;
    } catch {
      // Nothing to report: the version is recorded again next time.
    }
  }

  function listProfiles(session, message) {
    const listed = [];
    for (const number of message.numbers) {
      const profile = profiles.get(number);
      if (profile && numberOwners.has(number) && !blockedNumbers.has(number)) {
        listed.push({ number, name: profile.name, updatedAt: profile.updatedAt, proto: profile.proto });
      }
    }
    send(session.socket, { type: 'profiles', requestId: message.requestId, profiles: listed });
  }

  async function acknowledgeMissedCall(session, callId) {
    const remaining = missedCalls.filter((call) => !(call.to === session.number && call.callId === callId));
    if (remaining.length === missedCalls.length) return;
    await saveMissedCalls(missedFile, remaining);
    missedCalls = remaining;
  }

  function sendMissedCalls(session) {
    const now = Date.now();
    for (const call of missedCalls) {
      if (call.to !== session.number || call.at <= now - MISSED_CALL_TTL_MS || blockedNumbers.has(call.from)) continue;
      const event = { type: 'missed_call', callId: call.callId, from: call.from, at: call.at };
      const name = profileName(call.from);
      if (name) event.fromName = name;
      send(session.socket, event);
    }
  }

  // Members who never joined a call that went unanswered are recorded as missed (not the decliner).
  function recordMissedCalls(call, reason) {
    if (closing || !call.announced || !['timeout', 'disconnected', 'left', 'declined'].includes(reason)) return;
    const at = Date.now();
    const records = call.members
      .filter((number) => number !== call.owner && !call.joined.has(number) && number !== call.declinedBy
        && !blockedNumbers.has(number))
      .map((number) => ({ to: number, from: call.owner, callId: call.id, at, reason }));
    if (records.length === 0) return;
    enqueueStoreTask(async () => {
      const next = pruneMissedCalls([...missedCalls, ...records], Date.now());
      await saveMissedCalls(missedFile, next);
      missedCalls = next;
    });
  }

  function startCallGrace(call, number) {
    if (call.grace.has(number)) return;
    const timer = setTimeout(() => {
      call.grace.delete(number);
      endCall(call, 'disconnected');
    }, callResumeGraceMs);
    timer.unref?.();
    call.grace.set(number, timer);
  }

  // A member of an active call is back within the grace period: keep the call, or end it as before
  // when the new session cannot resume.
  function takeCallResume(number, protocolVersion) {
    const callId = memberships.get(number);
    const call = callId && calls.get(callId);
    if (!call || !call.grace.has(number)) return undefined;
    clearTimeout(call.grace.get(number));
    call.grace.delete(number);
    if (protocolVersion < 8) {
      endCall(call, 'disconnected');
      return undefined;
    }
    return call;
  }

  function endCall(call, reason) {
    if (calls.get(call.id) !== call) return;
    calls.delete(call.id);
    clearTimeout(call.timer);
    for (const timer of call.grace.values()) clearTimeout(timer);
    call.grace.clear();
    appendAdminEvent('call_ended');
    recordMissedCalls(call, reason);
    for (const number of call.members) {
      if (memberships.get(number) === call.id) memberships.delete(number);
      const member = sessions.get(number);
      const notified = call.announced && member
        ? send(member.socket, { type: 'ended', callId: call.id, reason }) : false;
      if (call.announced && !notified && number !== call.owner) {
        sendPush(number, 'call_ended', call.id, CALL_PUSH_TTL_MS, reason);
      }
    }
    if (call.roomProvisioned) deleteRoomBestEffort(call.room);
  }

  function deleteRoomBestEffort(room) {
    if (deleteRoom) Promise.resolve().then(() => deleteRoom(room)).catch(() => {});
  }

  function endCallForNumber(number, reason) {
    const callId = memberships.get(number);
    const call = callId && calls.get(callId);
    if (call) endCall(call, reason);
  }

  function cleanupSession(session) {
    if (!session || session.closed) return;
    session.closed = true;
    if (sessions.get(session.number) === session) sessions.delete(session.number);
    const callId = memberships.get(session.number);
    const call = callId && calls.get(callId);
    if (call && (call.owner === session.number || call.joined.has(session.number))) {
      if (session.protocolVersion >= 8 && callResumeGraceMs > 0 && call.announced && !closing) startCallGrace(call, session.number);
      else endCall(call, 'disconnected');
    }
  }

  function takeRegistrationSlot(ip) {
    if (!registrationRates.has(ip) && registrationRates.size >= maxRateLimitEntries) {
      const now = Date.now();
      for (const [key, bucket] of registrationRates) {
        if (now - bucket.start >= registrationWindowMs) registrationRates.delete(key);
        if (registrationRates.size < maxRateLimitEntries) break;
      }
      if (registrationRates.size >= maxRateLimitEntries) return false;
    }
    return consumeRateLimit(registrationRates, ip, { limit: maxRegistrationsPerIp, windowMs: registrationWindowMs });
  }

  function replaceIdentities(next) {
    identities.clear();
    numberOwners.clear();
    for (const [hash, entry] of next) {
      identities.set(hash, entry);
      numberOwners.set(entry.number, hash);
    }
  }

  async function register(session, tokenValue, bundle, protocolVersion = 6) {
    if (session.number) return error(session.socket, 'already_registered');
    if (!takeRegistrationSlot(session.ip)) return error(session.socket, 'rate_limited');
    if (typeof tokenValue !== 'string' || !TOKEN_PATTERN.test(tokenValue)) return error(session.socket, 'invalid_token');
    const publicBundle = validateBundle(bundle);
    if (!publicBundle) return error(session.socket, 'invalid_bundle');

    const hash = createHash('sha256').update(tokenValue, 'utf8').digest('hex');
    const existing = identities.get(hash);
    if (existing && blockedNumbers.has(existing.number)) {
      error(session.socket, 'blocked');
      session.socket.close(4003, 'blocked');
      return;
    }
    if (!existing && !adminSettings.registrationEnabled) return error(session.socket, 'registration_disabled');
    if (existing?.bundle && existing.bundle.identityKey !== publicBundle.identityKey) {
      return error(session.socket, 'identity_mismatch');
    }
    if (!existing && identities.size >= maxIdentities) return error(session.socket, 'capacity');

    let number = existing?.number;
    if (!number) {
      const usedNumbers = new Set(numberOwners.keys());
      for (let attempt = 0; attempt < 1_000; attempt += 1) {
        const candidate = String(randomInt(100_000_000)).padStart(8, '0');
        if (!usedNumbers.has(candidate)) {
          number = candidate;
          break;
        }
      }
      if (!number) return error(session.socket, 'capacity');
    }

    const next = new Map(identities);
    const preKeyFloor = existing?.preKeyFloor ?? -1;
    publicBundle.preKeys = publicBundle.preKeys.filter((key) => key.id > preKeyFloor);
    next.set(hash, { number, bundle: publicBundle, preKeyFloor });
    await saveIdentities(dataFile, next);
    replaceIdentities(next);
    if (session.closed || session.socket.readyState !== WebSocket.OPEN) return;

    const previous = sessions.get(number);
    if (previous) {
      error(previous.socket, 'replaced');
      previous.socket.close(4001, 'replaced');
      cleanupSession(previous);
    }
    session.number = number;
    session.protocolVersion = Math.min(protocolVersion, PROTOCOL_VERSION);
    sessions.set(number, session);
    clearTimeout(timers.get(session));
    appendAdminEvent('registration');
    const resumedCall = takeCallResume(number, session.protocolVersion);
    const registered = {
      type: 'registered', number, mediaReady: Boolean(mediaConfig),
      callsEnabled: adminSettings.callsEnabled,
      chatEnabled: adminSettings.chatEnabled,
      registrationEnabled: adminSettings.registrationEnabled,
      maxParticipants: adminSettings.maxParticipants,
    };
    if (session.protocolVersion >= 7) registered.pushEnabled = hasUsableEndpoint(number);
    if (session.protocolVersion >= 8) {
      registered.protocol = PROTOCOL_VERSION;
      registered.serverTime = Date.now();
      registered.features = [...FEATURES];
      registered.pushProviders = [...push.providers];
      const name = profileName(number);
      if (name) registered.name = name;
    }
    send(session.socket, registered);
    if (session.protocolVersion >= 8) {
      sendMissedCalls(session);
      if (resumedCall) {
        send(session.socket, {
          type: 'call_resume', callId: resumedCall.id, room: resumedCall.room, members: resumedCall.members,
          owner: resumedCall.owner, joined: [...resumedCall.joined],
        });
      }
    }
    resumeSession(session);
    if (session.protocolVersion >= 8) await recordProtocolVersion(session);
  }

  async function updateKeys(session, bundle) {
    if (!adminSettings.chatEnabled) return error(session.socket, 'chat_disabled');
    const publicBundle = validateBundle(bundle);
    if (!publicBundle) return error(session.socket, 'invalid_bundle');
    const hash = numberOwners.get(session.number);
    const current = hash && identities.get(hash);
    if (!current || current.bundle?.identityKey !== publicBundle.identityKey) return error(session.socket, 'identity_mismatch');

    const next = new Map(identities);
    const preKeyFloor = current.preKeyFloor ?? -1;
    publicBundle.preKeys = publicBundle.preKeys.filter((key) => key.id > preKeyFloor);
    next.set(hash, { number: current.number, bundle: publicBundle, preKeyFloor });
    await saveIdentities(dataFile, next);
    replaceIdentities(next);
    send(session.socket, { type: 'keys_updated' });
  }

  async function lookupBundle(session, message) {
    if (!adminSettings.chatEnabled) return error(session.socket, 'chat_disabled', { requestId: message.requestId });
    if (blockedNumbers.has(message.to)) return error(session.socket, 'not_found', { requestId: message.requestId });
    const hash = numberOwners.get(message.to);
    const target = hash && identities.get(hash);
    if (!target?.bundle) return error(session.socket, 'not_found', { requestId: message.requestId });
    const reply = (bundle) => {
      const bundleMessage = { type: 'bundle', peer: message.to, requestId: message.requestId, bundle };
      const profile = session.protocolVersion >= 8 ? profiles.get(message.to) : undefined;
      if (profile) bundleMessage.profile = { name: profile.name, updatedAt: profile.updatedAt, proto: profile.proto };
      return send(session.socket, bundleMessage);
    };
    if (message.consumePreKey === false) {
      return reply({ ...structuredClone(target.bundle), preKeys: [] });
    }
    if (target.bundle.preKeys.length === 0) return error(session.socket, 'prekeys_exhausted', { requestId: message.requestId });

    const [preKey, ...remaining] = target.bundle.preKeys;
    const bundle = { ...structuredClone(target.bundle), preKeys: [preKey], preKey };
    const next = new Map(identities);
    next.set(hash, { number: target.number, bundle: { ...structuredClone(target.bundle), preKeys: remaining }, preKeyFloor: preKey.id });
    await saveIdentities(dataFile, next);
    replaceIdentities(next);
    reply(bundle);
  }

  async function relayEnvelope(session, message) {
    if (!adminSettings.chatEnabled) return error(session.socket, 'chat_disabled', { id: message.id });
    if (blockedNumbers.has(message.to)) return error(session.socket, 'blocked', { id: message.id });
    if (!numberOwners.has(message.to)) return error(session.socket, 'not_found', { id: message.id });
    const key = `${session.number}:${message.id}`;
    const now = Date.now();
    const receipt = mailbox.receipts.get(key);
    if (receipt?.expiresAt > now) {
      if (receipt.to !== message.to || receipt.cipherType !== message.cipherType
        || receipt.cipherHash !== envelopeDigest(message.cipherType, message.body)) {
        return error(session.socket, 'id_conflict', { id: message.id });
      }
      return send(session.socket, { type: session.protocolVersion >= 7 ? 'delivered' : 'sent', id: message.id });
    }
    const pending = mailbox.envelopes.get(key);
    if (pending?.expiresAt > now) {
      if (pending.to !== message.to || pending.cipherType !== message.cipherType || pending.body !== message.body) {
        return error(session.socket, 'id_conflict', { id: message.id });
      }
      return send(session.socket, { type: session.protocolVersion >= 7 ? 'queued' : 'sent', id: message.id });
    }
    if ([...mailbox.envelopes.values(), ...mailbox.receipts.values()]
      .some((item) => item.expiresAt > now && item.id === message.id)) {
      return error(session.socket, 'id_conflict', { id: message.id });
    }

    const envelopes = new Map([...mailbox.envelopes].filter(([, item]) => item.expiresAt > now));
    const receipts = new Map([...mailbox.receipts].filter(([, item]) => item.expiresAt > now));
    if (envelopes.size >= maxMailboxEntries) return error(session.socket, 'mailbox_full', { id: message.id });
    const recipientCount = [...envelopes.values()].filter((item) => item.to === message.to).length;
    if (recipientCount >= maxMailboxPerUser) return error(session.socket, 'mailbox_full', { id: message.id });
    const envelope = {
      from: session.number, to: message.to, id: message.id,
      cipherType: message.cipherType, body: message.body,
      createdAt: now, expiresAt: now + mailboxTtlMs,
    };
    envelopes.set(key, envelope);
    const recipient = sessions.get(message.to);
    const silent = message.silent === true;
    // Forward before the disk write; the sender's acknowledgement below still means "durably queued".
    // A failed write is reported to the sender, and the recipient deduplicates a retry by message ID.
    if (recipient && !recipient.closed && recipient.socket.readyState === WebSocket.OPEN) {
      const forwarded = send(recipient.socket, envelopeEvent(recipient, envelope));
      if (!forwarded && !silent) sendPush(message.to, 'message', message.id, MESSAGE_PUSH_TTL_MS);
    } else if (!silent) {
      sendPush(message.to, 'message', message.id, MESSAGE_PUSH_TTL_MS);
    }
    await saveMailboxState(envelopes, receipts);
    send(session.socket, { type: session.protocolVersion >= 7 ? 'queued' : 'sent', id: message.id });
  }

  async function acknowledgeDelivery(session, id) {
    const entry = [...mailbox.envelopes].find(([, item]) => item.id === id && item.to === session.number);
    if (!entry) return error(session.socket, 'unauthorized', { id });
    const [key, envelope] = entry;
    const now = Date.now();
    const envelopes = new Map(mailbox.envelopes);
    const receipts = new Map([...mailbox.receipts].filter(([, receipt]) => receipt.expiresAt > now));
    envelopes.delete(key);
    if (envelope.expiresAt <= now) {
      await saveMailboxState(envelopes, receipts);
      return error(session.socket, 'expired', { id });
    }
    receipts.set(key, {
      from: envelope.from, to: envelope.to, id: envelope.id,
      cipherType: envelope.cipherType, cipherHash: envelopeDigest(envelope.cipherType, envelope.body),
      createdAt: now, expiresAt: now + mailboxTtlMs,
    });
    await saveMailboxState(envelopes, receipts);
    const sender = sessions.get(envelope.from);
    if (sender?.protocolVersion >= 7 && !sender.closed) {
      send(sender.socket, { type: 'delivered', id: envelope.id });
    }
  }

  async function createCall(session, members) {
    if (!adminSettings.callsEnabled) return error(session.socket, 'calls_disabled');
    if (!Array.isArray(members) || members.length < 1 || members.length > adminSettings.maxParticipants - 1
      || members.some((member) => typeof member !== 'string' || !NUMBER_PATTERN.test(member) || member === session.number)
      || new Set(members).size !== members.length) return error(session.socket, 'invalid_message');
    if (memberships.has(session.number)) return error(session.socket, 'busy');

    for (const number of members) {
      if (!numberOwners.has(number)) return error(session.socket, 'not_found', { to: number });
      if (blockedNumbers.has(number)) return error(session.socket, 'blocked', { to: number });
      const target = sessions.get(number);
      if (memberships.has(number)) return error(session.socket, 'busy', { to: number });
      if ((!target || target.closed || target.socket.readyState !== WebSocket.OPEN)
        && !hasUsableEndpoint(number)) return error(session.socket, 'offline', { to: number });
    }
    if (!mediaConfig) return error(session.socket, 'media_not_configured');

    const callId = randomUUID();
    const room = `line-${callId}`;
    const roster = [session.number, ...members];
    const call = {
      id: callId, room, owner: session.number, members: roster, joined: new Set(), timer: undefined,
      announced: false, roomProvisioned: false, grace: new Map(), declinedBy: undefined,
    };
    calls.set(callId, call);
    for (const number of roster) memberships.set(number, callId);
    call.timer = setTimeout(() => endCall(call, 'timeout'), ringingTimeoutMs);
    call.timer.unref?.();

    if (mediaConfig && createRoom) {
      try {
        await createRoom({ name: room, maxParticipants: roster.length });
        call.roomProvisioned = true;
      } catch {
        deleteRoomBestEffort(room);
        if (calls.get(callId) === call) {
          endCall(call, 'media_unavailable');
          error(session.socket, 'media_unavailable');
        }
        return;
      }
    }

    const stillActive = calls.get(callId) === call && adminSettings.callsEnabled
      && call.members.every((number) => {
        if (blockedNumbers.has(number) || memberships.get(number) !== callId) return false;
        const member = sessions.get(number);
        if (member && !member.closed && member.socket.readyState === WebSocket.OPEN) return true;
        return number !== call.owner && hasUsableEndpoint(number);
      });
    if (!stillActive) {
      if (calls.get(callId) === call) endCall(call, 'disconnected');
      else if (call.roomProvisioned) deleteRoomBestEffort(room);
      return;
    }

    call.announced = true;
    const invitation = { callId, room, members: roster, owner: call.owner };
    send(session.socket, { type: 'call_created', ...invitation });
    for (const number of members) {
      const target = sessions.get(number);
      if (target && !target.closed && target.socket.readyState === WebSocket.OPEN) {
        if (!send(target.socket, incomingEvent(target, call))) {
          sendPush(number, 'call', call.id, CALL_PUSH_TTL_MS);
        }
      } else {
        sendPush(number, 'call', call.id, CALL_PUSH_TTL_MS);
      }
    }
    appendAdminEvent('call_created');
  }

  async function joinCall(session, callId) {
    if (!adminSettings.callsEnabled) return error(session.socket, 'calls_disabled', { callId });
    const call = calls.get(callId);
    if (!call || !call.members.includes(session.number) || memberships.get(session.number) !== callId) {
      return error(session.socket, 'unauthorized', { callId });
    }
    if (!call.announced) return error(session.socket, 'call_not_ready', { callId });
    if (!mediaConfig) return error(session.socket, 'media_not_configured', { callId });

    try {
      const token = await tokenIssuer({
        identity: session.number,
        room: call.room,
        apiKey: mediaConfig.apiKey,
        apiSecret: mediaConfig.apiSecret,
        ttlSeconds: 120,
      });
      if (calls.get(callId) !== call || session.closed || session.socket.readyState !== WebSocket.OPEN) return;
      if (typeof token !== 'string' || token.length === 0) throw new Error('Invalid token issuer response');
      call.joined.add(session.number);
      if (call.joined.size === call.members.length) clearTimeout(call.timer);
      send(session.socket, {
        type: 'room_grant',
        callId,
        room: call.room,
        members: call.members,
        owner: call.owner,
        url: mediaConfig.mediaUrl,
        token,
      });
    } catch {
      error(session.socket, 'media_unavailable', { callId });
    }
  }

  const validRequestId = (value) => typeof value === 'string' && REQUEST_ID_PATTERN.test(value);

  async function createBlob(session, message) {
    const reply = (code) => error(session.socket, code, { requestId: message.requestId });
    if (!adminSettings.chatEnabled) return reply('chat_disabled');
    if (blockedNumbers.has(session.number)) return reply('blocked');
    if (!blobCreateLimiter.take(session.number)) return reply('rate_limited');
    if (message.size > blobMaxBytes) return reply('blob_too_large');
    if (blockedNumbers.has(message.to)) return reply('blocked');
    if (!numberOwners.has(message.to)) return reply('not_found');
    const id = message.id.toLowerCase();
    const result = await blobs.create({ owner: session.number, to: message.to, id, size: message.size });
    if (result.error) return reply(result.error);
    send(session.socket, {
      type: 'blob_ticket', requestId: message.requestId, id: message.id, method: 'PUT', path: `/blob/${id}`,
      token: result.token, expiresAt: result.expiresAt, offset: result.offset,
    });
  }

  function getBlob(session, message) {
    const reply = (code) => error(session.socket, code, { requestId: message.requestId });
    if (!adminSettings.chatEnabled) return reply('chat_disabled');
    const id = message.id.toLowerCase();
    const result = blobs.download({ number: session.number, id });
    if (result.error) return reply(result.error);
    send(session.socket, {
      type: 'blob_ticket', requestId: message.requestId, id: message.id, method: 'GET', path: `/blob/${id}`,
      token: result.token, expiresAt: result.expiresAt, size: result.size, from: result.from,
    });
  }

  function acknowledgeBlob(session, message) {
    blobs.acknowledge({ number: session.number, id: message.id.toLowerCase() }).then((result) => {
      if (result.error) error(session.socket, result.error, { id: message.id });
    }, () => error(session.socket, 'storage_unavailable', { id: message.id }));
  }

  function handleMessage(session, message) {
    if (message.type === 'admin_login' || message.type === 'admin') return handleAdminMessage(session, message);
    if (!session.number) {
      if (message.type !== 'register' || !hasOnlyKeys(message, ['type', 'token', 'bundle', 'protocolVersion'])
        || (message.protocolVersion !== undefined
          && (!Number.isSafeInteger(message.protocolVersion) || message.protocolVersion < 1 || message.protocolVersion > 255))) {
        return error(session.socket, 'registration_required');
      }
      return queueStoreOperation(
        session,
        () => register(session, message.token, message.bundle, message.protocolVersion ?? 6),
        (code) => error(session.socket, code),
      );
    }

    switch (message.type) {
      case 'register':
        return error(session.socket, 'already_registered');
      case 'keys':
        if (!hasOnlyKeys(message, ['type', 'bundle'])) return error(session.socket, 'invalid_message');
        return queueStoreOperation(session, () => updateKeys(session, message.bundle), (code) => error(session.socket, code));
      case 'lookup':
        if (!hasOnlyKeys(message, ['type', 'to', 'requestId', 'consumePreKey'])
          || (message.consumePreKey !== undefined && typeof message.consumePreKey !== 'boolean') || typeof message.to !== 'string'
          || !NUMBER_PATTERN.test(message.to) || typeof message.requestId !== 'string' || !UUID_PATTERN.test(message.requestId)) {
          return error(session.socket, 'invalid_message');
        }
        return queueStoreOperation(
          session,
          () => lookupBundle(session, message),
          (code) => error(session.socket, code, { requestId: message.requestId }),
        );
      case 'envelope':
        if (!hasOnlyKeys(message, session.protocolVersion >= 8
          ? ['type', 'to', 'id', 'cipherType', 'body', 'silent'] : ['type', 'to', 'id', 'cipherType', 'body'])
          || (message.silent !== undefined && typeof message.silent !== 'boolean') || typeof message.to !== 'string'
          || !NUMBER_PATTERN.test(message.to) || typeof message.id !== 'string' || !UUID_PATTERN.test(message.id)
          || ![2, 3].includes(message.cipherType) || !validBase64(message.body, MAX_ENVELOPE_BYTES)) {
          return error(session.socket, 'invalid_message', typeof message.id === 'string' ? { id: message.id } : {});
        }
        return queueStoreOperation(session, () => relayEnvelope(session, message),
          (code) => error(session.socket, code, { id: message.id }));
      case 'delivery_ack':
        if (session.protocolVersion < 7 || !hasOnlyKeys(message, ['type', 'id'])
          || typeof message.id !== 'string' || !UUID_PATTERN.test(message.id)) {
          return error(session.socket, 'invalid_message', typeof message.id === 'string' ? { id: message.id } : {});
        }
        return queueStoreOperation(session, () => acknowledgeDelivery(session, message.id),
          (code) => error(session.socket, code, { id: message.id }));
      case 'push_register':
        if (session.protocolVersion < 7) return error(session.socket, 'invalid_message');
        if (session.protocolVersion >= 8 && message.provider !== undefined) {
          if (typeof message.provider !== 'string') return error(session.socket, 'invalid_message');
          return queueStoreOperation(session, () => registerPushEndpoint(session, message), (code) => error(session.socket, code));
        }
        // Firebase tokens are no longer accepted: report that push is not available for this registration.
        if (!hasOnlyKeys(message, ['type', 'token'])) return error(session.socket, 'invalid_message');
        return void send(session.socket, { type: 'push_registered', pushEnabled: false });
      case 'push_unregister':
        if (session.protocolVersion < 8 || !hasOnlyKeys(message, ['type'])) return error(session.socket, 'invalid_message');
        return queueStoreOperation(session, () => unregisterPushEndpoint(session), (code) => error(session.socket, code));
      case 'profile_set': {
        if (session.protocolVersion < 8 || !hasOnlyKeys(message, ['type', 'name']) || typeof message.name !== 'string') {
          return error(session.socket, 'invalid_message');
        }
        if (!profileSetLimiter.take(session.number)) return error(session.socket, 'rate_limited');
        const name = normalizeProfileName(message.name);
        if (name === undefined) return error(session.socket, 'invalid_profile');
        return queueStoreOperation(session, () => setProfile(session, name), (code) => error(session.socket, code));
      }
      case 'profile_get': {
        if (session.protocolVersion < 8 || !hasOnlyKeys(message, ['type', 'requestId', 'numbers'])
          || typeof message.requestId !== 'string' || !UUID_PATTERN.test(message.requestId)
          || !Array.isArray(message.numbers) || message.numbers.length > 32
          || message.numbers.some((number) => typeof number !== 'string' || !NUMBER_PATTERN.test(number))
          || new Set(message.numbers).size !== message.numbers.length) {
          return error(session.socket, 'invalid_message');
        }
        session.profileGetRates ??= new Map();
        if (!consumeRateLimit(session.profileGetRates, 'profile_get', { limit: 30, windowMs: 60_000 })) {
          return error(session.socket, 'rate_limited', { requestId: message.requestId });
        }
        if (!adminSettings.chatEnabled) return error(session.socket, 'chat_disabled', { requestId: message.requestId });
        return listProfiles(session, message);
      }
      case 'missed_ack':
        if (session.protocolVersion < 8 || !hasOnlyKeys(message, ['type', 'callId'])
          || typeof message.callId !== 'string' || !UUID_PATTERN.test(message.callId)) {
          return error(session.socket, 'invalid_message');
        }
        return queueStoreOperation(session, () => acknowledgeMissedCall(session, message.callId.toLowerCase()),
          (code) => error(session.socket, code));
      case 'blob_create':
        if (session.protocolVersion < 8 || !hasOnlyKeys(message, BLOB_REQUEST_SHAPES.blob_create) || !validRequestId(message.requestId)
          || typeof message.id !== 'string' || !UUID_PATTERN.test(message.id)
          || typeof message.to !== 'string' || !NUMBER_PATTERN.test(message.to)
          || !Number.isSafeInteger(message.size) || message.size < 1) {
          return error(session.socket, 'invalid_message', validRequestId(message.requestId) ? { requestId: message.requestId } : {});
        }
        return void createBlob(session, message).catch(() => error(session.socket, 'storage_unavailable', { requestId: message.requestId }));
      case 'blob_get':
        if (session.protocolVersion < 8 || !hasOnlyKeys(message, BLOB_REQUEST_SHAPES.blob_get) || !validRequestId(message.requestId)
          || typeof message.id !== 'string' || !UUID_PATTERN.test(message.id)) {
          return error(session.socket, 'invalid_message', validRequestId(message.requestId) ? { requestId: message.requestId } : {});
        }
        return getBlob(session, message);
      case 'blob_ack':
        if (session.protocolVersion < 8 || !hasOnlyKeys(message, ['type', 'id'])
          || typeof message.id !== 'string' || !UUID_PATTERN.test(message.id)) {
          return error(session.socket, 'invalid_message');
        }
        return acknowledgeBlob(session, message);
      case 'inbox_sync':
        if (session.protocolVersion < 7 || !hasOnlyKeys(message, ['type'])) return error(session.socket, 'invalid_message');
        return queueStoreOperation(session, () => syncInbox(session),
          (code) => error(session.socket, code));
      case 'create_call':
        if (!hasOnlyKeys(message, ['type', 'members'])) return error(session.socket, 'invalid_message');
        return createCall(session, message.members);
      case 'join_call':
        if (!hasOnlyKeys(message, ['type', 'callId']) || typeof message.callId !== 'string' || !UUID_PATTERN.test(message.callId)) {
          return error(session.socket, 'invalid_message');
        }
        return joinCall(session, message.callId);
      case 'leave_call':
      case 'decline_call': {
        if (!hasOnlyKeys(message, ['type', 'callId']) || typeof message.callId !== 'string' || !UUID_PATTERN.test(message.callId)) {
          return error(session.socket, 'invalid_message');
        }
        const call = calls.get(message.callId);
        if (!call || !call.members.includes(session.number)) return error(session.socket, 'unauthorized', { callId: message.callId });
        if (message.type === 'decline_call') call.declinedBy = session.number;
        endCall(call, message.type === 'leave_call' ? 'left' : 'declined');
        return;
      }
      default:
        return error(session.socket, 'invalid_message');
    }
  }

  httpServer.on('upgrade', (request, socket, head) => {
    if (request.url !== '/signal') {
      socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    if (wss.clients.size >= maxConnections) {
      socket.write('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(request, socket, head, (webSocket) => wss.emit('connection', webSocket, request));
  });

  wss.on('connection', (socket, request) => {
    const session = {
      socket,
      ip: request.socket.remoteAddress ?? 'unknown',
      number: undefined,
      adminExpiresAt: undefined,
      adminLoginPending: false,
      closed: false,
      rateLimited: false,
      alive: true,
      messageWindow: 0,
      messageCount: 0,
    };
    socket.session = session;
    const registrationTimer = setTimeout(() => {
      if (!session.number) {
        error(socket, 'registration_timeout');
        socket.close(4002, 'registration timeout');
      }
    }, registrationTimeoutMs);
    registrationTimer.unref?.();
    timers.set(session, registrationTimer);

    socket.on('pong', () => { session.alive = true; });
    socket.on('message', (data, isBinary) => {
      if (session.rateLimited) return;
      if (isBinary) return error(socket, 'invalid_message');
      const now = Date.now();
      if (now - session.messageWindow >= 1_000) {
        session.messageWindow = now;
        session.messageCount = 0;
      }
      session.messageCount += 1;
      if (session.messageCount > maxMessagesPerSecond) {
        session.rateLimited = true;
        error(socket, 'rate_limited');
        socket.close(4003, 'rate limited');
        return;
      }

      let message;
      try {
        message = JSON.parse(data.toString());
      } catch {
        return error(socket, 'malformed_json');
      }
      if (!isObject(message) || typeof message.type !== 'string') return error(socket, 'invalid_message');
      handleMessage(session, message);
    });
    socket.on('close', () => {
      clearTimeout(timers.get(session));
      session.adminExpiresAt = undefined;
      cleanupSession(session);
    });
    socket.on('error', () => {});
  });

  const heartbeat = setInterval(() => {
    for (const socket of wss.clients) {
      const session = socket.session;
      if (session?.alive === false) {
        socket.terminate();
        continue;
      }
      if (session) session.alive = false;
      socket.ping();
    }
  }, heartbeatIntervalMs);
  heartbeat.unref?.();

  const mailboxCleanup = setInterval(() => {
    const task = storeQueue.then(async () => {
      const now = Date.now();
      const envelopes = new Map([...mailbox.envelopes].filter(([, item]) => item.expiresAt > now));
      const receipts = new Map([...mailbox.receipts].filter(([, item]) => item.expiresAt > now));
      if (envelopes.size !== mailbox.envelopes.size || receipts.size !== mailbox.receipts.size) {
        try { await saveMailboxState(envelopes, receipts); } catch {}
      }
    });
    storeQueue = task.catch(() => {});
  }, Math.min(mailboxTtlMs, 60_000));
  mailboxCleanup.unref?.();

  const blobCleanup = setInterval(() => { blobs.sweep().catch(() => {}); }, blobCleanupIntervalMs);
  blobCleanup.unref?.();

  return {
    httpServer,
    wss,
    async listen(port = options.port ?? 3000, host = options.host ?? '0.0.0.0') {
      await new Promise((resolveListen, reject) => {
        httpServer.once('error', reject);
        httpServer.listen(port, host, () => {
          httpServer.off('error', reject);
          resolveListen();
        });
      });
      return httpServer.address();
    },
    async close() {
      closing = true;
      clearInterval(heartbeat);
      clearInterval(mailboxCleanup);
      clearInterval(blobCleanup);
      messageCoalescer.close();
      for (const call of [...calls.values()]) endCall(call, 'disconnected');
      push.close();
      for (const socket of wss.clients) socket.terminate();
      await new Promise((resolveClose) => {
        if (!httpServer.listening) return resolveClose();
        httpServer.close(() => resolveClose());
        httpServer.closeAllConnections?.();
      });
      await new Promise((resolveClose) => wss.close(() => resolveClose()));
    },
  };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = await createSignalingServer();
  await server.listen(Number(process.env.PORT ?? 3000), process.env.HOST ?? '0.0.0.0');
}
