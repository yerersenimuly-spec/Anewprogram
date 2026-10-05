import { randomUUID } from 'node:crypto';
import { mkdir, open, readFile, rename, unlink } from 'node:fs/promises';
import { dirname } from 'node:path';

export const NUMBER_PATTERN = /^\d{8}$/;
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function hasOnlyKeys(value, keys) {
  return Object.keys(value).every((key) => keys.includes(key));
}

export async function atomicWriteJson(dataFile, document) {
  await mkdir(dirname(dataFile), { recursive: true });
  const temporaryFile = `${dataFile}.${process.pid}.${randomUUID()}.tmp`;
  try {
    const file = await open(temporaryFile, 'wx', 0o600);
    try {
      await file.writeFile(`${JSON.stringify(document, null, 2)}\n`, 'utf8');
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

// Returns undefined when the file does not exist; any other problem fails closed.
export async function readJsonFile(file, label) {
  let text;
  try {
    text = await readFile(file, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return undefined;
    throw error;
  }
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${label} is not valid JSON: ${file}`);
  }
}

export function consumeRateLimit(map, key, { limit, windowMs, now = Date.now() }) {
  let bucket = map.get(key);
  if (!bucket || now - bucket.start >= windowMs) {
    bucket = { start: now, count: 0 };
    map.set(key, bucket);
  }
  bucket.count += 1;
  return bucket.count <= limit;
}

// Bounded fixed-window limiter; refuses new keys instead of growing without limit.
export function createRateLimiter({ limit, windowMs, maxEntries = 10_000 }) {
  const buckets = new Map();
  return {
    take(key, now = Date.now()) {
      if (!buckets.has(key) && buckets.size >= maxEntries) {
        for (const [candidate, bucket] of buckets) {
          if (now - bucket.start >= windowMs) buckets.delete(candidate);
          if (buckets.size < maxEntries) break;
        }
        if (buckets.size >= maxEntries) return false;
      }
      return consumeRateLimit(buckets, key, { limit, windowMs, now });
    },
    delete(key) { buckets.delete(key); },
  };
}

// Integer option: undefined/'' uses the fallback, malformed values throw, out-of-range values clamp.
export function readIntegerSetting(name, value, fallback, min, max) {
  if (value === undefined || value === null || value === '') return fallback;
  const parsed = typeof value === 'number' ? value : (/^\s*\d+\s*$/.test(String(value)) ? Number(value) : Number.NaN);
  if (!Number.isSafeInteger(parsed) || parsed < 0) throw new Error(`Invalid ${name}`);
  return Math.min(max, Math.max(min, parsed));
}

export function readBooleanSetting(value) {
  return typeof value === 'string' ? ['1', 'true', 'yes'].includes(value.trim().toLowerCase()) : value === true;
}
