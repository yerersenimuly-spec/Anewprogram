import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { afterEach, test } from 'node:test';
import {
  account, base64, cleanup, closeSocket, openSocket, record, register, restart, sendRequest, setup, sleep, token, until, uuid, waitFor, bundle,
} from '../test-support/harness.js';

afterEach(cleanup);

const mailboxFile = (resource) => join(resource.directory, 'identities.json.mailbox.json');
const queued = (id) => (message) => (message.type === 'queued' && message.id === id) || message.type === 'error';
const registerEndpoint = (socket, path) => sendRequest(socket,
  { type: 'push_register', provider: 'unifiedpush', endpoint: `https://push.example.test/up/${path}` },
  (message) => message.type === 'push_registered');

test('silent envelopes are delivered but never push, and the stored mailbox schema is unchanged', async () => {
  const pushes = [];
  const resource = await setup({ pushSender: async (record, payload) => { pushes.push(payload); }, pushCoalesceMs: 0 });
  const sender = await account(resource, '1', 401, 8);
  const recipient = await account(resource, '2', 402, 8);
  await registerEndpoint(recipient.socket, 'silent');

  const live = waitFor(recipient.socket, (message) => message.type === 'envelope');
  const first = { type: 'envelope', to: recipient.number, id: uuid(1), cipherType: 3, body: base64('receipt-live'), silent: true };
  assert.equal((await sendRequest(sender.socket, first, queued(first.id))).type, 'queued');
  const forwarded = await live;
  assert.equal(forwarded.id, first.id);
  assert.equal('silent' in forwarded, false);
  await sleep(30);
  assert.equal(pushes.length, 0);
  const acknowledged = waitFor(sender.socket, (message) => message.type === 'delivered' && message.id === first.id);
  recipient.socket.send(JSON.stringify({ type: 'delivery_ack', id: first.id }));
  await acknowledged;

  await closeSocket(recipient.socket);
  const silent = { type: 'envelope', to: recipient.number, id: uuid(2), cipherType: 3, body: base64('receipt-offline'), silent: true };
  assert.equal((await sendRequest(sender.socket, silent, queued(silent.id))).type, 'queued');
  await sleep(50);
  assert.equal(pushes.length, 0);
  const stored = JSON.parse(await readFile(mailboxFile(resource), 'utf8'));
  const entry = stored.envelopes.find((item) => item.id === silent.id);
  assert.deepEqual(Object.keys(entry).sort(), ['body', 'cipherType', 'createdAt', 'expiresAt', 'from', 'id', 'to']);
  assert.equal(stored.version, 1);

  const loud = { ...silent, id: uuid(3), body: base64('message'), silent: false };
  assert.equal((await sendRequest(sender.socket, loud, queued(loud.id))).type, 'queued');
  await until(() => pushes.length === 1);
  assert.deepEqual(pushes[0].kind, 'message');
  assert.equal(pushes[0].id, loud.id);
  const plain = { to: recipient.number, id: uuid(4), cipherType: 3, body: base64('plain') };
  assert.equal((await sendRequest(sender.socket, { type: 'envelope', ...plain }, queued(plain.id))).type, 'queued');
  await until(() => pushes.length === 2);

  const returned = await openSocket(resource);
  const events = record(returned);
  await register(returned, token('2'), bundle(402, 0), 8);
  await until(() => events.some((message) => message.type === 'inbox_complete'));
  const delivered = events.filter((message) => message.type === 'envelope');
  assert.deepEqual(delivered.map((message) => message.id), [silent.id, loud.id, plain.id]);
  assert.ok(delivered.every((message) => !('silent' in message)));

  const invalid = await sendRequest(sender.socket, { ...plain, type: 'envelope', id: uuid(5), silent: 'yes' }, (message) => message.type === 'error');
  assert.deepEqual(invalid, { type: 'error', code: 'invalid_message', id: uuid(5) });
});

test('the recipient receives an envelope before the mailbox write completes; the sender acknowledgement waits for it', async () => {
  let hold = false;
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const resource = await setup({ beforeMailboxWrite: async () => { if (hold) await gate; } });
  const sender = await account(resource, '1', 421, 8);
  const recipient = await account(resource, '2', 422, 8);
  const senderEvents = record(sender.socket);
  const recipientEvents = record(recipient.socket);

  hold = true;
  const message = { type: 'envelope', to: recipient.number, id: uuid(20), cipherType: 3, body: base64('ordering') };
  sender.socket.send(JSON.stringify(message));
  await until(() => recipientEvents.some((item) => item.type === 'envelope' && item.id === message.id));
  await sleep(40);
  assert.deepEqual(senderEvents.filter((item) => item.id === message.id), []);
  await assert.rejects(readFile(mailboxFile(resource), 'utf8'), (failure) => failure.code === 'ENOENT');

  release();
  await until(() => senderEvents.some((item) => item.type === 'queued' && item.id === message.id));
  const stored = JSON.parse(await readFile(mailboxFile(resource), 'utf8'));
  assert.deepEqual(stored.envelopes.map((item) => item.id), [message.id]);
  assert.equal(recipientEvents.filter((item) => item.type === 'envelope').length, 1);

  hold = false;
  recipient.socket.send(JSON.stringify({ type: 'delivery_ack', id: message.id }));
  await until(() => senderEvents.some((item) => item.type === 'delivered' && item.id === message.id));
});

test('a failed write is reported to the sender; the retry is accepted and the recipient can deduplicate by id', async () => {
  let failures = 1;
  const resource = await setup({ beforeMailboxWrite: async () => { if (failures > 0) { failures -= 1; throw new Error('disk full'); } } });
  const sender = await account(resource, '1', 431, 8);
  const recipient = await account(resource, '2', 432, 8);
  const events = record(recipient.socket);
  const message = { type: 'envelope', to: recipient.number, id: uuid(30), cipherType: 3, body: base64('retry') };

  const failed = await sendRequest(sender.socket, message, (item) => item.type === 'error' || item.type === 'queued');
  assert.deepEqual(failed, { type: 'error', code: 'storage_unavailable', id: message.id });
  await assert.rejects(readFile(mailboxFile(resource), 'utf8'), (failure) => failure.code === 'ENOENT');
  const retried = await sendRequest(sender.socket, message, (item) => item.type === 'error' || item.type === 'queued');
  assert.deepEqual(retried, { type: 'queued', id: message.id });
  // The forwarded copy can arrive on the recipient socket slightly after the sender's `queued` reply.
  await until(() => events.filter((item) => item.type === 'envelope' && item.id === message.id).length === 2);
  assert.deepEqual(events.filter((item) => item.type === 'envelope').map((item) => item.id), [message.id, message.id]);
  assert.deepEqual(JSON.parse(await readFile(mailboxFile(resource), 'utf8')).envelopes.map((item) => item.id), [message.id]);

  const again = await sendRequest(sender.socket, message, (item) => item.type === 'error' || item.type === 'queued');
  assert.deepEqual(again, { type: 'queued', id: message.id });
  await restart(resource);
  const survivor = await openSocket(resource);
  const replay = record(survivor);
  await register(survivor, token('2'), bundle(432, 0), 8);
  await until(() => replay.some((item) => item.type === 'inbox_complete'));
  assert.deepEqual(replay.filter((item) => item.type === 'envelope').map((item) => item.id), [message.id]);
});
