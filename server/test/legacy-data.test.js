import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { copyFile, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, test } from 'node:test';
import { loadAdminState, loadIdentities, loadMailbox } from '../src/index.js';
import {
  ADMIN_CODE, adminAction, adminEnv, adminLogin, bundle, cleanup, openSocket, record, register, sendRequest, setup, sleep, token, until,
  waitFor,
} from '../test-support/harness.js';

const fixtures = new URL('./fixtures/legacy-0.7.1/', import.meta.url);
const checker = fileURLToPath(new URL('../scripts/check-data-compat.mjs', import.meta.url));
const fixtureText = (name) => readFile(new URL(name, fixtures), 'utf8');
const accountsPromise = fixtureText('accounts.json').then(JSON.parse);
const temporaryDirectories = [];
afterEach(async () => {
  await cleanup();
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

async function legacyDirectory(identities = 'identities.v2.json', { push = true, mailbox = true, admin = true } = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'signal-legacy-'));
  temporaryDirectories.push(directory);
  await copyFile(new URL(identities, fixtures), join(directory, 'identities.json'));
  if (admin) await copyFile(new URL('admin.v1.json', fixtures), join(directory, 'identities.json.admin.json'));
  if (mailbox) await copyFile(new URL('mailbox.v1.json', fixtures), join(directory, 'identities.json.mailbox.json'));
  if (push) await copyFile(new URL('push.json', fixtures), join(directory, 'identities.json.push.json'));
  return directory;
}

async function snapshot(directory) {
  const result = {};
  for (const name of (await readdir(directory)).sort()) {
    const info = await stat(join(directory, name));
    result[name] = {
      sha256: info.isFile() ? createHash('sha256').update(await readFile(join(directory, name))).digest('hex') : 'directory',
      inode: info.ino, mtimeMs: info.mtimeMs, mode: info.mode & 0o777,
    };
  }
  return result;
}
const contents = (shot) => Object.fromEntries(Object.entries(shot).map(([name, entry]) => [name, entry.sha256]));
const names = (shot) => Object.keys(shot);

async function legacySession(resource, who, version = 7) {
  const accounts = await accountsPromise;
  const account = accounts[who];
  const socket = await openSocket(resource);
  const events = record(socket);
  const registered = await register(socket, account.token, bundle(account.seed, 2), version);
  if (registered.type === 'registered' && version >= 7) await until(() => events.some((item) => item.type === 'inbox_complete'));
  return { socket, events, registered, account };
}

function runChecker(dataFile, env = {}) {
  return new Promise((resolve) => {
    execFile(process.execPath, dataFile === undefined ? [checker] : [checker, dataFile], {
      encoding: 'utf8', env: { PATH: process.env.PATH, ...env },
    }, (failure, stdout, stderr) => resolve({ status: failure ? failure.code : 0, output: `${stdout}${stderr}` }));
  });
}

test('starting, health checks and idle connections never write to legacy files', async () => {
  for (const variant of ['identities.v1.json', 'identities.v2.json']) {
    const directory = await legacyDirectory(variant);
    const before = await snapshot(directory);
    const resource = await setup({ directory });
    const health = await fetch(`${resource.http}/health`);
    assert.equal(await health.text(), '{"status":"ok"}');
    const idle = await openSocket(resource);
    idle.send(JSON.stringify({ type: 'lookup' }));
    await waitFor(idle, (item) => item.type === 'error');
    await sleep(30);
    await resource.server.close();
    assert.deepEqual(await snapshot(directory), before, variant);
  }
});

test('a v2 store, admin v1, mailbox v1 and legacy push.json are served as 0.7.1 served them', async () => {
  const accounts = await accountsPromise;
  const directory = await legacyDirectory();
  const before = await snapshot(directory);
  const resource = await setup({ directory });

  const bob = await legacySession(resource, 'b');
  assert.equal(bob.registered.number, accounts.b.number);
  assert.equal(bob.registered.maxParticipants, 6);
  assert.equal(bob.registered.pushEnabled, false);
  assert.deepEqual(bob.events.map((item) => item.type), ['registered', 'envelope', 'inbox_complete']);
  const envelope = bob.events[1];
  assert.deepEqual(Object.keys(envelope).sort(), ['body', 'cipherType', 'from', 'id', 'type']);
  assert.equal(envelope.from, accounts.a.number);
  assert.equal(envelope.id, accounts.pendingEnvelopeId);

  const alice = await legacySession(resource, 'a');
  assert.equal(alice.registered.number, accounts.a.number);
  assert.deepEqual(alice.events.filter((item) => item.type === 'delivered'), [{ type: 'delivered', id: accounts.receiptId }]);

  const carol = await legacySession(resource, 'c');
  assert.deepEqual(carol.registered, { type: 'error', code: 'blocked' });

  const after = await snapshot(directory);
  assert.deepEqual(contents(after), contents(before));
  assert.equal(after['identities.json.push.json'].inode, before['identities.json.push.json'].inode);
  assert.equal(after['identities.json.mailbox.json'].inode, before['identities.json.mailbox.json'].inode);
  assert.deepEqual(names(after), names(before));

  const stranger = await openSocket(resource);
  const fresh = await register(stranger, token('d'), bundle(14, 0), 7);
  assert.equal(fresh.type, 'registered');
  assert.ok(![accounts.a.number, accounts.b.number, accounts.c.number].includes(fresh.number));
  const store = JSON.parse(await readFile(join(directory, 'identities.json'), 'utf8'));
  const original = JSON.parse(await fixtureText('identities.v2.json'));
  assert.equal(store.version, 2);
  for (const [hash, entry] of Object.entries(original.identities)) assert.deepEqual(store.identities[hash], entry);
  assert.equal(Object.keys(store.identities).length, 4);
  const final = await snapshot(directory);
  for (const name of ['identities.json.admin.json', 'identities.json.mailbox.json', 'identities.json.push.json']) {
    assert.equal(final[name].sha256, before[name].sha256, name);
  }
});

test('a version-1 identity store keeps its numbers and becomes version 2 only through a registration, as before', async () => {
  const accounts = await accountsPromise;
  const directory = await legacyDirectory('identities.v1.json');
  const before = await snapshot(directory);
  const resource = await setup({ directory });
  const bob = await legacySession(resource, 'b');
  assert.equal(bob.registered.number, accounts.b.number);
  assert.equal(bob.events.find((item) => item.type === 'envelope').id, accounts.pendingEnvelopeId);

  const store = JSON.parse(await readFile(join(directory, 'identities.json'), 'utf8'));
  assert.equal(store.version, 2);
  const numbers = Object.values(store.identities).map((entry) => entry.number).sort();
  assert.deepEqual(numbers, [accounts.a.number, accounts.b.number, accounts.c.number].sort());
  assert.equal(Object.values(store.identities).filter((entry) => entry.bundle).length, 1);
  const after = await snapshot(directory);
  for (const name of ['identities.json.admin.json', 'identities.json.mailbox.json', 'identities.json.push.json']) {
    assert.equal(after[name].sha256, before[name].sha256, name);
  }
  assert.equal((await loadIdentities(join(directory, 'identities.json'), 100_000)).size, 3);
});

test('v8 features add only new files; existing formats are preserved and push.json is never rewritten', async () => {
  const accounts = await accountsPromise;
  const directory = await legacyDirectory();
  const before = await snapshot(directory);
  const resource = await setup({ directory, env: adminEnv() });

  const bob = await legacySession(resource, 'b', 8);
  assert.equal(bob.registered.protocol, 8);
  const delivered = bob.events.find((item) => item.type === 'envelope');
  assert.equal(delivered.id, accounts.pendingEnvelopeId);
  assert.ok(delivered.sentAt > 4_000_000_000_000);
  const alice = await legacySession(resource, 'a', 8);
  assert.equal((await adminLogin(alice.socket, ADMIN_CODE)).ok, true);
  await sendRequest(bob.socket, { type: 'push_register', provider: 'unifiedpush', endpoint: 'https://push.example.test/up/legacy' },
    (item) => item.type === 'push_registered');
  await sendRequest(bob.socket, { type: 'profile_set', name: 'Бек' }, (item) => item.type === 'profile_updated');
  const acknowledged = waitFor(alice.socket, (item) => item.type === 'delivered' && item.id === accounts.pendingEnvelopeId);
  bob.socket.send(JSON.stringify({ type: 'delivery_ack', id: accounts.pendingEnvelopeId }));
  await acknowledged;

  const mailboxFile = join(directory, 'identities.json.mailbox.json');
  const mailbox = JSON.parse(await readFile(mailboxFile, 'utf8'));
  assert.deepEqual(Object.keys(mailbox), ['version', 'envelopes', 'receipts']);
  assert.equal(mailbox.version, 1);
  assert.deepEqual(mailbox.envelopes, []);
  assert.deepEqual(mailbox.receipts.map((item) => item.id), [accounts.receiptId, accounts.pendingEnvelopeId]);
  for (const receipt of mailbox.receipts) {
    assert.deepEqual(Object.keys(receipt), ['from', 'to', 'id', 'cipherType', 'cipherHash', 'createdAt', 'expiresAt']);
  }
  const owners = new Map([[accounts.a.number, 'a'], [accounts.b.number, 'b'], [accounts.c.number, 'c']]);
  const reloaded = await loadMailbox(mailboxFile, owners, new Set(), Date.now(), 7 * 24 * 3_600_000, 1_000, 100, 5_000);
  assert.equal(reloaded.receipts.size, 2);

  assert.equal((await adminAction(alice.socket, 'block', { number: accounts.b.number })).ok, true);
  const adminFile = join(directory, 'identities.json.admin.json');
  assert.deepEqual(Object.keys(JSON.parse(await readFile(adminFile, 'utf8'))), ['version', 'settings', 'blockedNumbers']);
  const admin = await loadAdminState(adminFile);
  assert.deepEqual(admin.blockedNumbers, [accounts.c.number, accounts.b.number]);
  assert.equal(admin.settings.maxParticipants, 6);

  const after = await snapshot(directory);
  assert.equal(after['identities.json.push.json'].sha256, before['identities.json.push.json'].sha256);
  assert.equal(after['identities.json.push.json'].inode, before['identities.json.push.json'].inode);
  assert.equal(after['identities.json.push.json'].mtimeMs, before['identities.json.push.json'].mtimeMs);
  assert.deepEqual(names(after).filter((name) => !(name in before)).sort(), ['identities.json.profiles.json', 'identities.json.push-endpoints.json']);
  for (const name of ['identities.json.profiles.json', 'identities.json.push-endpoints.json']) assert.equal(after[name].mode, 0o600);
  assert.equal(after['identities.json'].sha256, before['identities.json'].sha256);

  await resource.server.close();
  const verdict = await runChecker(join(directory, 'identities.json'));
  assert.equal(verdict.status, 0, verdict.output);
  assert.match(verdict.output, /RESULT: compatible/);
  assert.match(verdict.output, /OK\s+profiles\s+\S+ \(0 names\)/);
  assert.match(verdict.output, /OK\s+push endpoints\s+\S+ \(0 endpoints\)/);
});

test('an unreadable legacy push.json is ignored without being touched, and expired mailbox entries are pruned at start as in 0.7.1', async () => {
  const accounts = await accountsPromise;
  const directory = await legacyDirectory();
  await writeFile(join(directory, 'identities.json.push.json'), '{ not json');
  const mailbox = JSON.parse(await fixtureText('mailbox.v1.json'));
  const expired = { ...mailbox.envelopes[0], id: '6b0f4a3e-5c2d-4e8f-9a1b-000000000042', createdAt: 1_000, expiresAt: 2_000 };
  mailbox.envelopes.push(expired);
  await writeFile(join(directory, 'identities.json.mailbox.json'), `${JSON.stringify(mailbox, null, 2)}\n`);
  const pushBefore = await readFile(join(directory, 'identities.json.push.json'));
  const resource = await setup({ directory });

  const stored = JSON.parse(await readFile(join(directory, 'identities.json.mailbox.json'), 'utf8'));
  assert.deepEqual(stored.envelopes.map((item) => item.id), [accounts.pendingEnvelopeId]);
  const bob = await legacySession(resource, 'b', 8);
  assert.deepEqual(bob.events.filter((item) => item.type === 'envelope').map((item) => item.id), [accounts.pendingEnvelopeId]);
  assert.deepEqual(await sendRequest(bob.socket, { type: 'push_register', token: 'fcm-token-0123456789abcdefghij' },
    (item) => item.type === 'push_registered'), { type: 'push_registered', pushEnabled: false });
  assert.deepEqual(await readFile(join(directory, 'identities.json.push.json')), pushBefore);
  assert.deepEqual((await readdir(directory)).filter((name) => name.endsWith('.tmp')), []);
});

test('check:data accepts the 0.7.1 fixtures, reports their contents and changes nothing', async () => {
  for (const variant of ['identities.v1.json', 'identities.v2.json']) {
    const directory = await legacyDirectory(variant);
    const before = await snapshot(directory);
    const verdict = await runChecker(join(directory, 'identities.json'));
    assert.equal(verdict.status, 0, verdict.output);
    assert.match(verdict.output, /OK\s+identities\s+\S+ \(3 accounts, \d with a public bundle\)/);
    assert.match(verdict.output, /OK\s+admin\s+\S+ \(1 blocked, maxParticipants 6\)/);
    assert.match(verdict.output, /OK\s+mailbox\s+\S+ \(1 pending, 1 receipts\)/);
    assert.match(verdict.output, /OK\s+push \(legacy\)\s+\S+ \(2 FCM tokens; ignored by this version and never modified\)/);
    assert.match(verdict.output, /SKIP\s+profiles/);
    assert.match(verdict.output, /RESULT: compatible/);
    assert.deepEqual(await snapshot(directory), before, variant);
  }
});

test('check:data fails with a non-zero status on every kind of unusable store and never writes', async () => {
  const accounts = await accountsPromise;
  const scenarios = {
    'missing identities': async (directory) => rm(join(directory, 'identities.json')),
    'identities not JSON': (directory) => writeFile(join(directory, 'identities.json'), '{'),
    'duplicate number': async (directory) => {
      const document = JSON.parse(await fixtureText('identities.v2.json'));
      const [first, second] = Object.keys(document.identities);
      document.identities[second].number = document.identities[first].number;
      await writeFile(join(directory, 'identities.json'), JSON.stringify(document));
    },
    'admin blocks an unknown number': (directory) => writeFile(join(directory, 'identities.json.admin.json'), JSON.stringify({
      version: 1, settings: { callsEnabled: true, chatEnabled: true, registrationEnabled: true, maxParticipants: 6 }, blockedNumbers: ['00000001'],
    })),
    'admin invalid settings': (directory) => writeFile(join(directory, 'identities.json.admin.json'), JSON.stringify({
      version: 1, settings: { maxParticipants: 99 }, blockedNumbers: [],
    })),
    'mailbox not JSON': (directory) => writeFile(join(directory, 'identities.json.mailbox.json'), 'nope'),
    'mailbox unknown sender': async (directory) => {
      const document = JSON.parse(await fixtureText('mailbox.v1.json'));
      document.envelopes[0].from = '00000002';
      await writeFile(join(directory, 'identities.json.mailbox.json'), JSON.stringify(document));
    },
    'profile store invalid': (directory) => writeFile(join(directory, 'identities.json.profiles.json'),
      JSON.stringify({ version: 1, profiles: { [accounts.a.number]: { name: 'x' } } })),
    'push endpoint store invalid': (directory) => writeFile(join(directory, 'identities.json.push-endpoints.json'),
      JSON.stringify({ version: 1, endpoints: { [accounts.a.number]: { provider: 'unifiedpush', endpoint: 'http://x.test/', registeredAt: 1 } } })),
    'missed-call store invalid': (directory) => writeFile(join(directory, 'identities.json.missed.json'), JSON.stringify({ version: 1, calls: [1] })),
  };
  await Promise.all(Object.entries(scenarios).map(async ([name, corrupt]) => {
    const directory = await legacyDirectory();
    await corrupt(directory);
    const before = await snapshot(directory);
    const verdict = await runChecker(join(directory, 'identities.json'));
    assert.equal(verdict.status, 1, `${name}\n${verdict.output}`);
    assert.match(verdict.output, /FAIL/, name);
    assert.match(verdict.output, /RESULT: INCOMPATIBLE/, name);
    assert.deepEqual(await snapshot(directory), before, name);
  }));

  const directory = await legacyDirectory();
  await writeFile(join(directory, 'identities.json.push.json'), 'garbage');
  const advisory = await runChecker(join(directory, 'identities.json'));
  assert.equal(advisory.status, 0, advisory.output);
  assert.match(advisory.output, /WARN\s+push \(legacy\)/);

  assert.equal((await runChecker(undefined)).status, 2);
  assert.equal((await runChecker('--help')).status, 2);
});

test('check:data follows the same path overrides as the server', async () => {
  const directory = await legacyDirectory('identities.v2.json', { admin: false });
  const elsewhere = await mkdtemp(join(tmpdir(), 'signal-legacy-admin-'));
  temporaryDirectories.push(elsewhere);
  await copyFile(new URL('admin.v1.json', fixtures), join(elsewhere, 'admin.json'));
  const verdict = await runChecker(join(directory, 'identities.json'), { ADMIN_DATA_FILE: join(elsewhere, 'admin.json') });
  assert.equal(verdict.status, 0, verdict.output);
  assert.match(verdict.output, /admin\s+\S*admin\.json \(1 blocked/);
});
