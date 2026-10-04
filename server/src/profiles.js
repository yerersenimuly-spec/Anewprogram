import { atomicWriteJson, hasOnlyKeys, isObject, NUMBER_PATTERN, readJsonFile } from './common.js';

export const PROFILE_NAME_MAX_CODE_POINTS = 32;
const FORBIDDEN_CHARACTERS = /[\p{C}\u202A-\u202E\u2066-\u2069]/u;

// NFC, trimmed, single-spaced, 1..32 code points without control, format or bidi override characters.
// Returns '' for "no name" and undefined when the value is not acceptable.
export function normalizeProfileName(value) {
  if (typeof value !== 'string' || value.length > 512) return undefined;
  const name = value.normalize('NFC').trim().replace(/\s+/gu, ' ');
  if ([...name].length > PROFILE_NAME_MAX_CODE_POINTS || FORBIDDEN_CHARACTERS.test(name)) return undefined;
  return name;
}

function validProfile(entry) {
  return isObject(entry) && hasOnlyKeys(entry, ['name', 'updatedAt', 'proto'])
    && typeof entry.name === 'string' && normalizeProfileName(entry.name) === entry.name
    && Number.isSafeInteger(entry.updatedAt) && entry.updatedAt >= 0
    && Number.isSafeInteger(entry.proto) && entry.proto >= 1 && entry.proto <= 255;
}

export async function loadProfiles(profileFile, numberOwners) {
  const document = await readJsonFile(profileFile, 'Profile store');
  if (document === undefined) return new Map();
  if (!isObject(document) || !hasOnlyKeys(document, ['version', 'profiles']) || document.version !== 1
    || !isObject(document.profiles)) {
    throw new Error(`Profile store has an unsupported format: ${profileFile}`);
  }
  const profiles = new Map();
  for (const [number, entry] of Object.entries(document.profiles)) {
    if (!NUMBER_PATTERN.test(number) || !validProfile(entry)) {
      throw new Error(`Profile store contains an invalid profile: ${profileFile}`);
    }
    if (!numberOwners.has(number)) continue;
    profiles.set(number, { name: entry.name, updatedAt: entry.updatedAt, proto: entry.proto });
  }
  return profiles;
}

export async function saveProfiles(profileFile, profiles) {
  await atomicWriteJson(profileFile, {
    version: 1,
    profiles: Object.fromEntries([...profiles].sort(([a], [b]) => (a < b ? -1 : 1))
      .map(([number, entry]) => [number, { name: entry.name, updatedAt: entry.updatedAt, proto: entry.proto }])),
  });
}
