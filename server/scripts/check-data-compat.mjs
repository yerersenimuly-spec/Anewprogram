#!/usr/bin/env node
// Read-only compatibility check of a server data directory against this version's loaders.
// Usage: node scripts/check-data-compat.mjs <DATA_FILE>
// The other stores are located like the server does: ADMIN_DATA_FILE, MAILBOX_FILE, PUSH_TOKEN_FILE, PROFILE_FILE,
// PUSH_ENDPOINT_FILE, MISSED_FILE and BLOB_DIR from the environment, otherwise `<DATA_FILE>.<suffix>`.
// Nothing is created, rewritten or deleted. Exit status: 0 compatible, 1 incompatible, 2 usage error.
import { readdir, stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { STORE_LIMITS, loadAdminState, loadIdentities, loadMailbox, loadPushTokens } from '../src/index.js';
import { loadProfiles } from '../src/profiles.js';
import { loadMissedCalls } from '../src/missed.js';
import { loadPushEndpoints } from '../src/push.js';

const dataArgument = process.argv[2];
if (!dataArgument || dataArgument.startsWith('-') || process.argv.length > 3) {
  console.error('Usage: node scripts/check-data-compat.mjs <DATA_FILE>');
  console.error('Optional path overrides (same names as the server): ADMIN_DATA_FILE, MAILBOX_FILE, PUSH_TOKEN_FILE,');
  console.error('PROFILE_FILE, PUSH_ENDPOINT_FILE, MISSED_FILE, BLOB_DIR.');
  process.exit(2);
}

const env = process.env;
const dataFile = resolve(dataArgument);
const paths = {
  identities: dataFile,
  admin: resolve(env.ADMIN_DATA_FILE || `${dataFile}.admin.json`),
  mailbox: resolve(env.MAILBOX_FILE || `${dataFile}.mailbox.json`),
  legacyPush: resolve(env.PUSH_TOKEN_FILE || `${dataFile}.push.json`),
  profiles: resolve(env.PROFILE_FILE || `${dataFile}.profiles.json`),
  pushEndpoints: resolve(env.PUSH_ENDPOINT_FILE || `${dataFile}.push-endpoints.json`),
  missed: resolve(env.MISSED_FILE || `${dataFile}.missed.json`),
  blobs: resolve(env.BLOB_DIR || `${dataFile}.blobs`),
};

let failures = 0;
const report = (status, name, detail) => {
  if (status === 'FAIL') failures += 1;
  console.log(`${status.padEnd(5)} ${name.padEnd(16)} ${detail}`);
};
const exists = (file) => stat(file).then(() => true, (failure) => (failure.code === 'ENOENT' ? false : Promise.reject(failure)));
const message = (failure) => String(failure?.message ?? failure).replaceAll(/\s+/g, ' ');

async function check(name, file, task, { optional = false, advisory = false, absent = 'absent; created on first use' } = {}) {
  try {
    if (optional && !(await exists(file))) {
      report('SKIP', name, `${file} (${absent})`);
      return undefined;
    }
    const outcome = await task();
    report('OK', name, `${file} ${outcome.summary}`.trim());
    return outcome.value;
  } catch (failure) {
    report(advisory ? 'WARN' : 'FAIL', name, `${file}: ${message(failure)}`);
    return undefined;
  }
}

const now = Date.now();
console.log('Line data compatibility check (read-only)');
console.log(`Node ${process.version}, server code ${resolve(fileURLToPath(new URL('..', import.meta.url)))}`);

const identities = await check('identities', paths.identities, async () => {
  if (!(await exists(paths.identities))) throw new Error('file not found; pass the real DATA_FILE');
  const loaded = await loadIdentities(paths.identities, STORE_LIMITS.maxIdentities);
  const withBundle = [...loaded.values()].filter((entry) => entry.bundle).length;
  return { value: loaded, summary: `(${loaded.size} accounts, ${withBundle} with a public bundle)` };
});

if (identities) {
  const numberOwners = new Map([...identities].map(([hash, entry]) => [entry.number, hash]));
  const admin = await check('admin', paths.admin, async () => {
    const state = await loadAdminState(paths.admin);
    const unknown = state.blockedNumbers.filter((number) => !numberOwners.has(number));
    if (unknown.length > 0) throw new Error(`${unknown.length} blocked number(s) are not registered accounts`);
    return { value: state, summary: `(${state.blockedNumbers.length} blocked, maxParticipants ${state.settings.maxParticipants})` };
  });
  const blocked = new Set(admin?.blockedNumbers ?? []);

  await check('mailbox', paths.mailbox, async () => {
    const loaded = await loadMailbox(paths.mailbox, numberOwners, blocked, now, STORE_LIMITS.mailboxTtlMs,
      STORE_LIMITS.maxMailboxEntries, STORE_LIMITS.maxMailboxPerUser, STORE_LIMITS.maxReceiptEntries);
    const note = loaded.dirty ? '; expired or blocked entries are pruned in place at the first start, as in 0.7.1' : '';
    return { summary: `(${loaded.envelopes.size} pending, ${loaded.receipts.size} receipts${note})` };
  }, { optional: true });

  await check('push (legacy)', paths.legacyPush, async () => {
    const loaded = await loadPushTokens(paths.legacyPush, numberOwners, new Set());
    return { summary: `(${loaded.tokens.size} FCM tokens; ignored by this version and never modified)` };
  }, { optional: true, advisory: true, absent: 'absent; this version does not use it' });

  await check('profiles', paths.profiles, async () => {
    const loaded = await loadProfiles(paths.profiles, numberOwners);
    return { summary: `(${[...loaded.values()].filter((entry) => entry.name).length} names)` };
  }, { optional: true });

  await check('push endpoints', paths.pushEndpoints, async () => {
    const loaded = await loadPushEndpoints(paths.pushEndpoints, numberOwners, blocked);
    return { summary: `(${loaded.size} endpoints)` };
  }, { optional: true });

  await check('missed calls', paths.missed, async () => {
    const loaded = await loadMissedCalls(paths.missed, numberOwners, now);
    return { summary: `(${loaded.length} undelivered)` };
  }, { optional: true });

  await check('blobs', paths.blobs, async () => {
    const names = await readdir(paths.blobs);
    return { summary: `(${names.filter((name) => name.endsWith('.bin')).length} complete, ${names.filter((name) => name.endsWith('.part')).length} partial)` };
  }, { optional: true });
}

console.log(failures === 0 ? 'RESULT: compatible' : `RESULT: INCOMPATIBLE (${failures} problem${failures === 1 ? '' : 's'})`);
process.exit(failures === 0 ? 0 : 1);
