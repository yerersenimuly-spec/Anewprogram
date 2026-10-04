import { atomicWriteJson, hasOnlyKeys, isObject, NUMBER_PATTERN, readJsonFile, UUID_PATTERN } from './common.js';

export const MISSED_CALL_TTL_MS = 7 * 24 * 60 * 60 * 1_000;
export const MAX_MISSED_CALLS_PER_RECIPIENT = 20;
export const MISSED_CALL_REASONS = Object.freeze(['timeout', 'declined', 'left', 'disconnected']);

// Drops expired records and keeps the newest MAX_MISSED_CALLS_PER_RECIPIENT per recipient.
export function pruneMissedCalls(calls, now) {
  const counts = new Map();
  const kept = [];
  for (let index = calls.length - 1; index >= 0; index -= 1) {
    const call = calls[index];
    if (call.at <= now - MISSED_CALL_TTL_MS) continue;
    const count = counts.get(call.to) ?? 0;
    if (count >= MAX_MISSED_CALLS_PER_RECIPIENT) continue;
    counts.set(call.to, count + 1);
    kept.push(call);
  }
  return kept.reverse();
}

function validMissedCall(call) {
  return isObject(call) && hasOnlyKeys(call, ['to', 'from', 'callId', 'at', 'reason'])
    && NUMBER_PATTERN.test(call.to) && NUMBER_PATTERN.test(call.from)
    && typeof call.callId === 'string' && UUID_PATTERN.test(call.callId)
    && Number.isSafeInteger(call.at) && call.at >= 0 && MISSED_CALL_REASONS.includes(call.reason);
}

export async function loadMissedCalls(missedFile, numberOwners, now) {
  const document = await readJsonFile(missedFile, 'Missed-call store');
  if (document === undefined) return [];
  if (!isObject(document) || !hasOnlyKeys(document, ['version', 'calls']) || document.version !== 1
    || !Array.isArray(document.calls)) {
    throw new Error(`Missed-call store has an unsupported format: ${missedFile}`);
  }
  const seen = new Set();
  const calls = [];
  for (const call of document.calls) {
    if (!validMissedCall(call)) throw new Error(`Missed-call store contains an invalid record: ${missedFile}`);
    const key = `${call.to}:${call.callId}`;
    if (seen.has(key)) throw new Error(`Missed-call store contains duplicate records: ${missedFile}`);
    seen.add(key);
    if (numberOwners.has(call.to) && numberOwners.has(call.from)) calls.push({ ...call });
  }
  return pruneMissedCalls(calls, now);
}

export async function saveMissedCalls(missedFile, calls) {
  await atomicWriteJson(missedFile, { version: 1, calls });
}
