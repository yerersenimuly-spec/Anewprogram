// Encrypted attachment storage. The server only ever holds ciphertext chosen by the client; keys and
// plaintext never reach it. Everything is created lazily on the first upload: the directory holds
// `<id>.part` (upload in progress), `<id>.bin` (complete ciphertext) and `<id>.json` (metadata).
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdir, open, readdir, readFile, rename, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { pipeline } from 'node:stream';
import { atomicWriteJson, hasOnlyKeys, isObject, NUMBER_PATTERN, UUID_PATTERN } from './common.js';

const MAX_SCANNED_FILES = 50_000;
const FILE_PATTERN = /^([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\.(part|bin|json)(?:\..+\.tmp)?$/;
const sha256 = (value) => createHash('sha256').update(value, 'utf8').digest();

function validMeta(meta) {
  return isObject(meta) && hasOnlyKeys(meta, ['version', 'owner', 'to', 'size', 'createdAt', 'completedAt'])
    && meta.version === 1 && NUMBER_PATTERN.test(meta.owner) && NUMBER_PATTERN.test(meta.to)
    && Number.isSafeInteger(meta.size) && meta.size >= 1
    && Number.isSafeInteger(meta.createdAt) && meta.createdAt >= 0
    && (meta.completedAt === undefined || (Number.isSafeInteger(meta.completedAt) && meta.completedAt >= meta.createdAt));
}

export function createBlobStore({
  directory, maxBytes, ttlMs, quotaBytes, senderPendingBytes,
  incompleteTtlMs = 60 * 60_000, ticketTtlMs = 10 * 60_000, idleTimeoutMs = 30_000,
  maxEntries = 10_000, maxEntriesPerSender = 1_000, maxUploads = 16, maxDownloads = 64,
  now = Date.now, isBlocked = () => false, chatEnabled = () => true,
}) {
  const entries = new Map();
  const tickets = new Map();
  const inflight = new Set();
  let chain = Promise.resolve();
  let directoryReady = false;
  let activeUploads = 0;
  let activeDownloads = 0;

  const paths = (id) => ({ part: join(directory, `${id}.part`), bin: join(directory, `${id}.bin`), meta: join(directory, `${id}.json`) });

  function serial(task) {
    const result = chain.then(task);
    chain = result.catch(() => {});
    return result;
  }

  async function ensureDirectory() {
    if (directoryReady) return;
    await mkdir(directory, { recursive: true, mode: 0o700 });
    directoryReady = true;
  }

  function writeMeta(entry) {
    return atomicWriteJson(paths(entry.id).meta, {
      version: 1, owner: entry.owner, to: entry.to, size: entry.size, createdAt: entry.createdAt,
      ...(entry.completedAt === undefined ? {} : { completedAt: entry.completedAt }),
    });
  }

  function removeFiles(id) {
    const { part, bin, meta } = paths(id);
    return Promise.all([part, bin, meta].map((file) => rm(file, { force: true }).catch(() => {})));
  }

  async function removeEntry(entry) {
    if (entries.get(entry.id) === entry) entries.delete(entry.id);
    entry.deleted = true;
    tickets.delete(entry.id);
    if (!entry.uploading) await removeFiles(entry.id);
  }

  function issueTicket(id, number, method) {
    const token = randomBytes(32).toString('base64url');
    const expiresAt = now() + ticketTtlMs;
    let byBlob = tickets.get(id);
    if (!byBlob) {
      byBlob = new Map();
      tickets.set(id, byBlob);
    }
    byBlob.set(`${method}:${number}`, { number, method, digest: sha256(token), expiresAt });
    return { token, expiresAt };
  }

  // 401: no valid live token for this blob; 403: a valid token that is not meant for this method.
  function authorize(id, method, token) {
    const presented = sha256(typeof token === 'string' ? token : '');
    let matched;
    for (const ticket of tickets.get(id)?.values() ?? []) {
      if (timingSafeEqual(presented, ticket.digest)) matched = ticket;
    }
    if (!token || !matched || matched.expiresAt <= now()) return { status: 401 };
    return matched.method === method ? { ticket: matched } : { status: 403 };
  }

  async function load() {
    let names;
    try {
      names = await readdir(directory);
    } catch {
      return;
    }
    const byId = new Map();
    for (const name of names.slice(0, MAX_SCANNED_FILES)) {
      const match = FILE_PATTERN.exec(name);
      if (!match) continue;
      const files = byId.get(match[1]) ?? [];
      files.push(name);
      byId.set(match[1], files);
    }
    const current = now();
    for (const [id, files] of byId) {
      const { part, bin, meta } = paths(id);
      let entry;
      try {
        const document = JSON.parse(await readFile(meta, 'utf8'));
        if (validMeta(document)) {
          entry = {
            id, owner: document.owner, to: document.to, size: document.size, createdAt: document.createdAt,
            completedAt: document.completedAt, stored: 0, uploading: false, deleted: false,
          };
        }
      } catch {
        entry = undefined;
      }
      if (entry) {
        const binSize = await stat(bin).then((info) => info.size, () => -1);
        const partSize = await stat(part).then((info) => info.size, () => -1);
        if (binSize === entry.size) {
          entry.completedAt ??= Math.max(entry.createdAt, Math.floor((await stat(bin)).mtimeMs));
          entry.stored = entry.size;
        } else if (!entry.completedAt && partSize === entry.size) {
          await rename(part, bin).catch(() => {});
          entry.completedAt = Math.max(entry.createdAt, current);
          entry.stored = entry.size;
        } else if (!entry.completedAt && partSize >= 0 && partSize < entry.size) {
          entry.stored = partSize;
        } else if (!entry.completedAt && partSize < 0) {
          entry.stored = 0;
        } else {
          entry = undefined;
        }
      }
      const expired = entry && (entry.completedAt ? current >= entry.completedAt + ttlMs : current >= entry.createdAt + incompleteTtlMs);
      if (!entry || expired || isBlocked(entry.owner) || isBlocked(entry.to)) {
        await removeFiles(id);
        for (const file of files) await rm(join(directory, file), { force: true }).catch(() => {});
        continue;
      }
      entries.set(id, entry);
    }
  }

  function usage(owner) {
    let total = 0;
    let sender = 0;
    let senderCount = 0;
    for (const entry of entries.values()) {
      total += entry.size;
      if (entry.owner === owner) {
        sender += entry.size;
        senderCount += 1;
      }
    }
    return { total, sender, senderCount };
  }

  function create({ owner, to, id, size }) {
    return serial(async () => {
      const existing = entries.get(id);
      if (existing) {
        if (existing.deleted || existing.owner !== owner || existing.to !== to || existing.size !== size) return { error: 'id_conflict' };
        if (!existing.completedAt && !existing.uploading) {
          existing.stored = await stat(paths(id).part).then((info) => Math.min(info.size, existing.size), () => 0);
        }
        return { offset: existing.completedAt ? existing.size : existing.stored, ...issueTicket(id, owner, 'PUT') };
      }
      const used = usage(owner);
      if (entries.size >= maxEntries || used.senderCount >= maxEntriesPerSender
        || used.total + size > quotaBytes || used.sender + size > senderPendingBytes) return { error: 'blob_quota' };
      await ensureDirectory();
      const entry = { id, owner, to, size, createdAt: now(), completedAt: undefined, stored: 0, uploading: false, deleted: false };
      await rm(paths(id).part, { force: true });
      await writeMeta(entry);
      entries.set(id, entry);
      return { offset: 0, ...issueTicket(id, owner, 'PUT') };
    });
  }

  function download({ number, id }) {
    const entry = entries.get(id);
    if (!entry || entry.deleted || !entry.completedAt) return { error: 'not_found' };
    if (number !== entry.to && number !== entry.owner) return { error: 'unauthorized' };
    if (now() >= entry.completedAt + ttlMs) return { error: 'expired' };
    return { size: entry.size, from: entry.owner, ...issueTicket(id, number, 'GET') };
  }

  function acknowledge({ number, id }) {
    return serial(async () => {
      const entry = entries.get(id);
      if (!entry) return {};
      if (entry.to !== number) return { error: 'unauthorized' };
      await removeEntry(entry);
      return {};
    });
  }

  function removeNumber(number) {
    return serial(async () => {
      for (const entry of [...entries.values()]) {
        if (entry.owner === number || entry.to === number) await removeEntry(entry);
      }
    });
  }

  function sweep() {
    return serial(async () => {
      const current = now();
      for (const [id, byBlob] of tickets) {
        for (const [key, ticket] of byBlob) if (ticket.expiresAt <= current) byBlob.delete(key);
        if (byBlob.size === 0) tickets.delete(id);
      }
      for (const entry of [...entries.values()]) {
        const expired = entry.completedAt
          ? current >= entry.completedAt + ttlMs
          : current >= entry.createdAt + incompleteTtlMs && !entry.uploading;
        if (expired || isBlocked(entry.owner) || isBlocked(entry.to)) await removeEntry(entry);
      }
    });
  }

  // ---- HTTP -------------------------------------------------------------------------------------

  function respond(response, status, { headers = {}, json, close = false } = {}) {
    const body = json === undefined ? '' : JSON.stringify(json);
    response.writeHead(status, {
      'cache-control': 'no-store', 'x-content-type-options': 'nosniff',
      ...(json === undefined ? {} : { 'content-type': 'application/json; charset=utf-8' }),
      'content-length': Buffer.byteLength(body),
      ...(close ? { connection: 'close' } : {}),
      ...headers,
    });
    response.end(body);
  }

  function bearerToken(request) {
    const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.authorization ?? '');
    return match?.[1];
  }

  function parseContentLength(request) {
    const value = request.headers['content-length'];
    if (value === undefined) return { value: undefined };
    return /^\d{1,15}$/.test(value) ? { value: Number(value) } : { invalid: true };
  }

  async function handleUpload(request, response, id) {
    const auth = authorize(id, 'PUT', bearerToken(request));
    if (auth.status) return respond(response, auth.status, { close: true });
    const number = auth.ticket.number;
    if (!chatEnabled() || isBlocked(number)) return respond(response, 403, { close: true });
    const entry = entries.get(id);
    if (!entry || entry.deleted || entry.owner !== number) return respond(response, 404, { close: true });
    if (entry.completedAt || entry.uploading) {
      return respond(response, 409, { headers: { 'upload-offset': String(entry.completedAt ? entry.size : entry.stored) }, close: true });
    }
    if (activeUploads >= maxUploads) return respond(response, 429, { headers: { 'retry-after': '5' }, close: true });

    const length = parseContentLength(request);
    if (length.invalid) return respond(response, 400, { close: true });
    let start = 0;
    let expected = length.value;
    const rangeHeader = request.headers['content-range'];
    if (rangeHeader !== undefined) {
      const match = /^bytes (\d{1,15})-(\d{1,15})\/(\d{1,15})$/.exec(rangeHeader);
      if (!match) return respond(response, 400, { close: true });
      const [first, last, total] = [Number(match[1]), Number(match[2]), Number(match[3])];
      if (total !== entry.size || last < first || last >= total) return respond(response, 400, { close: true });
      if (first !== entry.stored) return respond(response, 409, { headers: { 'upload-offset': String(entry.stored) }, close: true });
      if (length.value !== undefined && length.value !== last - first + 1) return respond(response, 400, { close: true });
      start = first;
      expected = last - first + 1;
    }
    if (expected === 0) return respond(response, 400, { close: true });
    const allowed = entry.size - start;
    if (expected !== undefined && expected > allowed) return respond(response, 413, { close: true });

    entry.uploading = true;
    activeUploads += 1;
    let handle;
    let idleTimer;
    let written = 0;
    let failure;
    let overflow = false;
    try {
      handle = await open(paths(id).part, start === 0 ? 'w' : 'a', 0o600);
      if (start === 0) entry.stored = 0;
      const size = (await handle.stat()).size;
      if (size !== start) {
        entry.stored = size;
        return respond(response, 409, { headers: { 'upload-offset': String(size) }, close: true });
      }
      idleTimer = setTimeout(() => request.destroy(new Error('upload idle')), idleTimeoutMs);
      idleTimer.unref?.();
      const chunks = request[Symbol.asyncIterator]();
      try {
        for (;;) {
          const { value: chunk, done } = await chunks.next();
          if (done) break;
          if (entry.deleted) throw new Error('blob deleted');
          if (written + chunk.length > allowed || (expected !== undefined && written + chunk.length > expected)) {
            overflow = true;
            break;
          }
          let offset = 0;
          while (offset < chunk.length) {
            offset += (await handle.write(chunk, offset, chunk.length - offset)).bytesWritten;
          }
          written += chunk.length;
          entry.stored = start + written;
          idleTimer.refresh();
        }
      } catch (error) {
        failure = error;
      }
      if (overflow) {
        await handle.truncate(start);
        entry.stored = start;
      } else {
        await handle.sync();
      }
      await handle.close();
      handle = undefined;
      if (overflow) return respond(response, 413, { close: true });
      if (failure) {
        if (!response.writableEnded && !response.destroyed && !request.destroyed) respond(response, entry.deleted ? 404 : 400, { close: true });
        return undefined;
      }
      if (entry.deleted) return respond(response, 404, { close: true });
      if (expected !== undefined && written !== expected) return respond(response, 400, { close: true });
      if (entry.stored < entry.size) {
        return respond(response, 202, { headers: { 'upload-offset': String(entry.stored) } });
      }
      await rename(paths(id).part, paths(id).bin);
      entry.completedAt = now();
      await writeMeta(entry).catch(() => {});
      return respond(response, 201, { json: { id, size: entry.size } });
    } finally {
      clearTimeout(idleTimer);
      if (handle) await handle.close().catch(() => {});
      entry.uploading = false;
      activeUploads -= 1;
      if (entry.deleted) await removeFiles(id);
    }
  }

  async function handleDownload(request, response, id) {
    const auth = authorize(id, 'GET', bearerToken(request));
    if (auth.status) return respond(response, auth.status, { close: true });
    const number = auth.ticket.number;
    if (!chatEnabled() || isBlocked(number)) return respond(response, 403, { close: true });
    const entry = entries.get(id);
    if (!entry || entry.deleted || !entry.completedAt || now() >= entry.completedAt + ttlMs) return respond(response, 404);
    if (number !== entry.to && number !== entry.owner) return respond(response, 403);
    if (activeDownloads >= maxDownloads) return respond(response, 429, { headers: { 'retry-after': '5' } });

    let start = 0;
    let end = entry.size - 1;
    let partial = false;
    const range = /^bytes=(\d{1,15})-(\d{0,15})$/.exec(request.headers.range ?? '');
    if (range) {
      start = Number(range[1]);
      end = range[2] === '' ? entry.size - 1 : Math.min(Number(range[2]), entry.size - 1);
      if (start >= entry.size || end < start) {
        return respond(response, 416, { headers: { 'content-range': `bytes */${entry.size}` } });
      }
      partial = start > 0 || end < entry.size - 1;
    }
    let handle;
    try {
      handle = await open(paths(id).bin, 'r');
    } catch {
      return respond(response, 404);
    }
    activeDownloads += 1;
    response.writeHead(partial ? 206 : 200, {
      'content-type': 'application/octet-stream', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff',
      'accept-ranges': 'bytes', 'content-length': end - start + 1,
      ...(partial ? { 'content-range': `bytes ${start}-${end}/${entry.size}` } : {}),
    });
    await new Promise((resolve) => {
      pipeline(handle.createReadStream({ start, end, autoClose: true }), response, () => resolve());
    });
    activeDownloads -= 1;
  }

  // Returns false when the request does not belong to the blob endpoints.
  function handleRequest(request, response) {
    const pathname = (request.url ?? '').split('?')[0];
    if (!pathname.startsWith('/blob/')) return false;
    const id = pathname.slice('/blob/'.length);
    if (!UUID_PATTERN.test(id)) {
      respond(response, 404);
      return true;
    }
    const handler = request.method === 'PUT' ? handleUpload : request.method === 'GET' ? handleDownload : undefined;
    if (!handler) {
      respond(response, 405, { headers: { allow: 'GET, PUT' } });
      return true;
    }
    const task = handler(request, response, id.toLowerCase()).catch(() => {
      if (!response.headersSent) respond(response, 503, { headers: { 'retry-after': '5' }, close: true });
      else response.destroy();
    });
    inflight.add(task);
    task.finally(() => inflight.delete(task));
    return true;
  }

  // Resolves once queued store work and in-flight transfers have finished (used on shutdown).
  async function drain() {
    await Promise.allSettled([chain, ...inflight]);
  }

  return { load, create, download, acknowledge, removeNumber, sweep, handleRequest, drain };
}
