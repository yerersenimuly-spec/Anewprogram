import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { readFile, readdir, stat } from 'node:fs/promises';
import http from 'node:http';
import { join } from 'node:path';
import { afterEach, test } from 'node:test';
import {
  ADMIN_CODE, account, adminAction, adminEnv, adminLogin, cleanup, closeSocket, openSocket, register, restart, sendRequest, setup, sleep,
  token, until, uuid, bundle,
} from '../test-support/harness.js';

afterEach(cleanup);

const blobDirectory = (resource) => join(resource.directory, 'identities.json.blobs');
let requestCounter = 0;
const nextRequestId = () => uuid(80_000 + requestCounter++);
const answered = (requestId) => (message) => message.requestId === requestId;

function httpRequest(resource, method, path, { headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const request = http.request({ host: '127.0.0.1', port: resource.port, method, path, headers, agent: false }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks) }));
    });
    request.on('error', reject);
    request.end(body);
  });
}

const bearer = (ticket) => ({ authorization: `Bearer ${ticket.token}` });
const createTicket = (who, fields) => {
  const requestId = fields.requestId ?? nextRequestId();
  return sendRequest(who.socket, { type: 'blob_create', requestId, ...fields }, answered(requestId));
};
const getTicket = (who, id) => {
  const requestId = nextRequestId();
  return sendRequest(who.socket, { type: 'blob_get', requestId, id }, answered(requestId));
};
const upload = (resource, ticket, data, extra = {}) => httpRequest(resource, 'PUT', ticket.path, { headers: bearer(ticket), body: data, ...extra });

async function uploadBlob(resource, from, to, data, id) {
  const ticket = await createTicket(from, { id, to: to.number, size: data.length });
  assert.equal(ticket.type, 'blob_ticket');
  const result = await upload(resource, ticket, data);
  assert.equal(result.status, 201);
  return { id, ticket };
}

// Opens a PUT that sends `sent` bytes of `data` and then stalls; resolves once the server has stored them.
async function stalledUpload(resource, ticket, data, sent) {
  const request = http.request({
    host: '127.0.0.1', port: resource.port, method: 'PUT', path: ticket.path, agent: false,
    headers: { ...bearer(ticket), 'content-length': data.length },
  });
  request.on('error', () => {});
  request.write(data.subarray(0, sent));
  const part = join(blobDirectory(resource), `${ticket.id}.part`);
  await until(async () => (await stat(part).then((info) => info.size, () => -1)) === sent);
  return request;
}

test('a blob ticket, upload, resume and download follow the contract the Android BlobTransfer expects', async () => {
  const resource = await setup();
  await assert.rejects(stat(blobDirectory(resource)), (failure) => failure.code === 'ENOENT');
  const alice = await account(resource, '1', 801, 8);
  const bob = await account(resource, '2', 802, 8);
  const carol = await account(resource, '3', 803, 8);
  const data = randomBytes(1_000);
  const id = uuid(1);

  const requestId = nextRequestId();
  const ticket = await sendRequest(alice.socket, { type: 'blob_create', requestId, id, to: bob.number, size: data.length }, answered(requestId));
  assert.deepEqual(Object.keys(ticket).sort(), ['expiresAt', 'id', 'method', 'offset', 'path', 'requestId', 'size', 'token', 'type']);
  assert.deepEqual({ ...ticket, token: undefined, expiresAt: undefined },
    { type: 'blob_ticket', requestId, id, method: 'PUT', path: `/blob/${id}`, offset: 0, size: 1_000, token: undefined, expiresAt: undefined });
  assert.match(ticket.token, /^[A-Za-z0-9_-]{43}$/);
  assert.ok(ticket.expiresAt > Date.now() && ticket.expiresAt <= Date.now() + 10 * 60_000);
  assert.equal((await stat(blobDirectory(resource))).mode & 0o777, 0o700);

  assert.equal((await httpRequest(resource, 'PUT', ticket.path, { body: data })).status, 401);
  assert.equal((await upload(resource, { ...ticket, token: 'x'.repeat(43) }, data)).status, 401);
  assert.equal((await httpRequest(resource, 'PUT', ticket.path, { headers: { authorization: `Basic ${ticket.token}` }, body: data })).status, 401);

  const stalled = await stalledUpload(resource, ticket, data, 400);
  const busy = await upload(resource, ticket, data.subarray(400), { headers: { ...bearer(ticket), 'content-range': 'bytes 400-999/1000' } });
  assert.equal(busy.status, 409);
  assert.equal(busy.headers['upload-offset'], '400');
  stalled.destroy();

  const resumed = await createTicket(alice, { id, to: bob.number, size: data.length });
  assert.equal(resumed.offset, 400);
  assert.equal(resumed.size, 1_000);
  const put = (headers, body) => httpRequest(resource, 'PUT', resumed.path, { headers: { ...bearer(resumed), ...headers }, body });
  // 400 is only returned once the aborted upload has been released by the server.
  await until(async () => (await put({ 'content-range': 'bytes 400-999/999' }, data.subarray(400))).status === 400);
  const wrong = await put({ 'content-range': 'bytes 100-999/1000' }, data.subarray(100));
  assert.equal(wrong.status, 409);
  assert.equal(wrong.headers['upload-offset'], '400');
  assert.equal((await put({ 'content-range': 'bytes 400-1000/1000' }, data.subarray(400))).status, 400);
  assert.equal((await put({ 'content-range': 'bytes=400-999/1000' }, data.subarray(400))).status, 400);
  assert.equal((await put({ 'content-range': 'bytes 400-999/1000', 'content-length': '10' }, data.subarray(0, 10))).status, 400);

  const completed = await put({ 'content-range': 'bytes 400-999/1000' }, data.subarray(400));
  assert.equal(completed.status, 201);
  assert.match(completed.headers['content-type'], /^application\/json/);
  assert.deepEqual(JSON.parse(completed.body.toString('utf8')), { id, size: 1_000 });
  const again = await put({ 'content-range': 'bytes 400-999/1000' }, data.subarray(400));
  assert.equal(again.status, 409);
  assert.equal(again.headers['upload-offset'], '1000');
  const reissued = await createTicket(alice, { id, to: bob.number, size: data.length });
  assert.equal(reissued.offset, 1_000);

  const download = await getTicket(bob, id);
  assert.deepEqual(Object.keys(download).sort(), ['expiresAt', 'from', 'id', 'method', 'offset', 'path', 'requestId', 'size', 'token', 'type']);
  assert.equal(download.method, 'GET');
  assert.equal(download.path, `/blob/${id}`);
  assert.equal(download.size, 1_000);
  assert.equal(download.offset, 0);
  assert.equal(download.from, alice.number);
  const get = (headers = {}, ticketToUse = download) => httpRequest(resource, 'GET', ticketToUse.path, { headers: { ...bearer(ticketToUse), ...headers } });
  const whole = await get({ 'accept-encoding': 'identity' });
  assert.equal(whole.status, 200);
  assert.equal(whole.headers['content-length'], '1000');
  assert.equal(whole.headers['accept-ranges'], 'bytes');
  assert.equal(whole.headers['cache-control'], 'no-store');
  assert.deepEqual(whole.body, data);
  const tail = await get({ range: 'bytes=400-' });
  assert.equal(tail.status, 206);
  assert.equal(tail.headers['content-range'], 'bytes 400-999/1000');
  assert.equal(tail.headers['content-length'], '600');
  assert.deepEqual(tail.body, data.subarray(400));
  const head = await get({ range: 'bytes=0-99' });
  assert.equal(head.status, 206);
  assert.equal(head.headers['content-range'], 'bytes 0-99/1000');
  assert.deepEqual(head.body, data.subarray(0, 100));
  assert.deepEqual((await get({ range: 'bytes=900-5000' })).body, data.subarray(900));
  const beyond = await get({ range: 'bytes=1000-' });
  assert.equal(beyond.status, 416);
  assert.equal(beyond.headers['content-range'], 'bytes */1000');

  assert.equal((await httpRequest(resource, 'GET', download.path)).status, 401);
  assert.equal((await get({}, reissued)).status, 403);
  assert.equal((await upload(resource, download, data)).status, 403);
  assert.equal((await httpRequest(resource, 'GET', `/blob/${uuid(2)}`, { headers: bearer(download) })).status, 401);
  const denied = await getTicket(carol, id);
  assert.deepEqual([denied.type, denied.code], ['error', 'unauthorized']);
  assert.equal((await getTicket(alice, id)).method, 'GET');
  assert.equal((await getTicket(bob, uuid(3))).code, 'not_found');

  const foreign = await sendRequest(carol.socket, { type: 'blob_ack', id }, (item) => item.type === 'error');
  assert.deepEqual(foreign, { type: 'error', code: 'unauthorized', id });
  const sender = await sendRequest(alice.socket, { type: 'blob_ack', id }, (item) => item.type === 'error');
  assert.deepEqual(sender, { type: 'error', code: 'unauthorized', id });
  bob.socket.send(JSON.stringify({ type: 'blob_ack', id }));
  await until(async () => (await getTicket(bob, id)).code === 'not_found', 2_000, 60);
  assert.equal((await get()).status, 401);
  await until(async () => (await readdir(blobDirectory(resource))).filter((name) => name.startsWith(id)).length === 0);
  bob.socket.send(JSON.stringify({ type: 'blob_ack', id: uuid(4) }));
  assert.equal((await createTicket(alice, { id, to: bob.number, size: 10 })).offset, 0);
});

test('size limits, quotas, id conflicts and malformed requests are reported with the request id', async () => {
  const resource = await setup({ blobMaxBytes: 1_500, blobQuotaBytes: 3_000, blobSenderPendingBytes: 2_500 });
  const alice = await account(resource, '1', 811, 8);
  const bob = await account(resource, '2', 812, 8);
  const error = (reply, code, requestId) => assert.deepEqual(reply, { type: 'error', code, requestId });
  const ask = async (who, fields) => {
    const requestId = nextRequestId();
    return { requestId, reply: await createTicket(who, { requestId, ...fields }) };
  };

  let attempt = await ask(alice, { id: uuid(10), to: bob.number, size: 1_501 });
  error(attempt.reply, 'blob_too_large', attempt.requestId);
  attempt = await ask(alice, { id: uuid(11), to: '00000000', size: 10 });
  error(attempt.reply, 'not_found', attempt.requestId);
  attempt = await ask(alice, { id: uuid(12), to: bob.number, size: 1_000 });
  assert.equal(attempt.reply.type, 'blob_ticket');
  const conflicting = await ask(alice, { id: uuid(12), to: bob.number, size: 999 });
  error(conflicting.reply, 'id_conflict', conflicting.requestId);
  const otherRecipient = await ask(alice, { id: uuid(12), to: alice.number, size: 1_000 });
  error(otherRecipient.reply, 'id_conflict', otherRecipient.requestId);
  const stranger = await ask(bob, { id: uuid(12), to: alice.number, size: 1_000 });
  error(stranger.reply, 'id_conflict', stranger.requestId);
  const same = await ask(alice, { id: uuid(12), to: bob.number, size: 1_000 });
  assert.equal(same.reply.offset, 0);
  assert.equal((await ask(alice, { id: uuid(13), to: bob.number, size: 1_000 })).reply.type, 'blob_ticket');
  attempt = await ask(alice, { id: uuid(14), to: bob.number, size: 1_000 });
  error(attempt.reply, 'blob_quota', attempt.requestId);
  assert.equal((await ask(bob, { id: uuid(15), to: alice.number, size: 1_000 })).reply.type, 'blob_ticket');
  attempt = await ask(bob, { id: uuid(16), to: alice.number, size: 1 });
  error(attempt.reply, 'blob_quota', attempt.requestId);

  bob.socket.send(JSON.stringify({ type: 'blob_ack', id: uuid(12) }));
  await until(async () => (await ask(bob, { id: uuid(16), to: alice.number, size: 1 })).reply.type === 'blob_ticket', 2_000, 60);

  const invalid = [
    { id: uuid(20), to: bob.number, size: 0 }, { id: uuid(20), to: bob.number, size: 1.5 }, { id: uuid(20), to: bob.number, size: '10' },
    { id: uuid(20), to: bob.number }, { id: 'not-a-uuid', to: bob.number, size: 10 }, { id: uuid(20), to: '123', size: 10 },
    { id: uuid(20), to: bob.number, size: 10, extra: true }, { id: uuid(20), size: 10 },
  ];
  for (const fields of invalid) {
    const reply = await ask(alice, fields);
    error(reply.reply, 'invalid_message', reply.requestId);
  }
  for (const requestId of ['bad id!', 'x'.repeat(65), 5]) {
    assert.deepEqual(await sendRequest(alice.socket, { type: 'blob_create', requestId, id: uuid(21), to: bob.number, size: 10 }, (item) => item.type === 'error'),
      { type: 'error', code: 'invalid_message' });
  }
  assert.deepEqual(await sendRequest(alice.socket, { type: 'blob_get', requestId: 'r1', id: 'zzz' }, (item) => item.type === 'error'),
    { type: 'error', code: 'invalid_message', requestId: 'r1' });
  assert.deepEqual(await sendRequest(alice.socket, { type: 'blob_ack', id: 'zzz' }, (item) => item.type === 'error'), { type: 'error', code: 'invalid_message' });

  const limited = await setup();
  const spammer = await account(limited, '3', 813, 8);
  for (let index = 0; index < 20; index += 1) {
    assert.equal((await createTicket(spammer, { id: uuid(100 + index), to: spammer.number, size: 1 })).type, 'blob_ticket');
  }
  assert.equal((await createTicket(spammer, { id: uuid(130), to: spammer.number, size: 1 })).code, 'rate_limited');
});

test('oversized and malformed uploads are refused; a chunked upload continues with Content-Range', async () => {
  const resource = await setup();
  const alice = await account(resource, '1', 821, 8);
  const bob = await account(resource, '2', 822, 8);
  const data = randomBytes(300);
  const ticket = await createTicket(alice, { id: uuid(30), to: bob.number, size: 300 });
  assert.equal((await upload(resource, ticket, Buffer.alloc(301))).status, 413);
  assert.equal((await upload(resource, ticket, Buffer.alloc(0))).status, 400);
  assert.equal((await httpRequest(resource, 'PUT', ticket.path, { headers: { ...bearer(ticket), 'content-length': 'abc' } })).status, 400);

  const first = await upload(resource, ticket, data.subarray(0, 100));
  assert.equal(first.status, 202);
  assert.equal(first.headers['upload-offset'], '100');
  const last = await upload(resource, ticket, data.subarray(100), { headers: { ...bearer(ticket), 'content-range': 'bytes 100-299/300' } });
  assert.equal(last.status, 201);
  assert.deepEqual(JSON.parse(last.body.toString('utf8')), { id: uuid(30), size: 300 });
  const download = await getTicket(bob, uuid(30));
  assert.deepEqual((await httpRequest(resource, 'GET', download.path, { headers: bearer(download) })).body, data);

  assert.equal((await httpRequest(resource, 'PUT', '/blob/not-a-uuid', { headers: bearer(ticket), body: data })).status, 404);
  const post = await httpRequest(resource, 'POST', ticket.path, { headers: bearer(ticket), body: data });
  assert.equal(post.status, 405);
  assert.equal(post.headers.allow, 'GET, PUT');
});

test('tickets and blobs expire on schedule; the sweep removes expired files', async () => {
  let clock = Date.now();
  const resource = await setup({ blobTtlMs: 60_000, blobCleanupIntervalMs: 20, blob: { now: () => clock, ticketTtlMs: 5_000, incompleteTtlMs: 120_000 } });
  const alice = await account(resource, '1', 831, 8);
  const bob = await account(resource, '2', 832, 8);
  const data = randomBytes(64);
  const first = await createTicket(alice, { id: uuid(40), to: bob.number, size: data.length });
  assert.equal(first.expiresAt, clock + 5_000);
  clock += 6_000;
  assert.equal((await upload(resource, first, data)).status, 401);
  const renewed = await createTicket(alice, { id: uuid(40), to: bob.number, size: data.length });
  assert.equal((await upload(resource, renewed, data)).status, 201);

  const download = await getTicket(bob, uuid(40));
  assert.equal(download.expiresAt, clock + 5_000);
  clock += 6_000;
  assert.equal((await httpRequest(resource, 'GET', download.path, { headers: bearer(download) })).status, 401);
  const fresh = await getTicket(bob, uuid(40));
  assert.equal((await httpRequest(resource, 'GET', fresh.path, { headers: bearer(fresh) })).status, 200);
  clock += 60_000;
  await until(async () => (await getTicket(bob, uuid(40))).code === 'not_found', 2_000, 60);
  await until(async () => (await readdir(blobDirectory(resource))).filter((name) => name.startsWith(uuid(40))).length === 0);

  const pending = await createTicket(alice, { id: uuid(41), to: bob.number, size: data.length });
  assert.equal(pending.offset, 0);
  clock += 121_000;
  await until(async () => (await readdir(blobDirectory(resource))).filter((name) => name.startsWith(uuid(41))).length === 0);
  assert.equal((await createTicket(alice, { id: uuid(41), to: bob.number, size: data.length + 1 })).type, 'blob_ticket');
});

test('blobs survive a restart, including partial uploads, and only ever live in the blob directory with owner-only modes', async () => {
  const resource = await setup();
  const alice = await account(resource, '1', 841, 8);
  const bob = await account(resource, '2', 842, 8);
  const done = randomBytes(2_000);
  const half = randomBytes(1_000);
  const { id } = await uploadBlob(resource, alice, bob, done, uuid(50));
  const halfTicket = await createTicket(alice, { id: uuid(51), to: bob.number, size: half.length });
  const stalled = await stalledUpload(resource, halfTicket, half, 300);
  stalled.destroy();
  await closeSocket(alice.socket);
  await closeSocket(bob.socket);

  const names = await readdir(blobDirectory(resource));
  assert.ok(names.includes(`${id}.bin`) && names.includes(`${id}.json`));
  assert.equal((await stat(join(blobDirectory(resource), `${id}.bin`))).mode & 0o777, 0o600);
  assert.equal((await stat(join(blobDirectory(resource), `${id}.json`))).mode & 0o777, 0o600);
  const meta = JSON.parse(await readFile(join(blobDirectory(resource), `${id}.json`), 'utf8'));
  assert.deepEqual(Object.keys(meta).sort(), ['completedAt', 'createdAt', 'owner', 'size', 'to', 'version']);
  assert.deepEqual([meta.owner, meta.to, meta.size], [alice.number, bob.number, 2_000]);
  assert.deepEqual((await readdir(resource.directory)).sort(), ['identities.json', 'identities.json.blobs', 'identities.json.profiles.json']);

  await restart(resource);
  const aliceAgain = { socket: await openSocket(resource) };
  await register(aliceAgain.socket, token('1'), bundle(841, 0), 8);
  const bobAgain = { socket: await openSocket(resource) };
  await register(bobAgain.socket, token('2'), bundle(842, 0), 8);
  const download = await getTicket(bobAgain, id);
  assert.equal(download.size, 2_000);
  assert.deepEqual((await httpRequest(resource, 'GET', download.path, { headers: bearer(download) })).body, done);
  const resumed = await createTicket(aliceAgain, { id: uuid(51), to: bob.number, size: half.length });
  assert.equal(resumed.offset, 300);
  const finished = await httpRequest(resource, 'PUT', resumed.path, {
    headers: { ...bearer(resumed), 'content-range': 'bytes 300-999/1000' }, body: half.subarray(300),
  });
  assert.equal(finished.status, 201);
  const second = await getTicket(bobAgain, uuid(51));
  assert.deepEqual((await httpRequest(resource, 'GET', second.path, { headers: bearer(second) })).body, half);
});

test('the health endpoint and unknown paths are unchanged and reveal nothing about blobs', async () => {
  const resource = await setup();
  for (const path of ['/health', '/']) {
    const reply = await httpRequest(resource, 'GET', path);
    assert.equal(reply.status, 200);
    assert.equal(reply.body.toString('utf8'), '{"status":"ok"}');
  }
  assert.equal((await httpRequest(resource, 'GET', '/blob/')).status, 404);
  assert.equal((await httpRequest(resource, 'GET', '/blob/nope')).status, 404);
  assert.equal((await httpRequest(resource, 'GET', `/blob/${uuid(60)}`)).status, 401);
  assert.equal((await httpRequest(resource, 'GET', `/blob/${uuid(60)}`, { headers: { authorization: `Bearer ${'a'.repeat(43)}` } })).status, 401);
  const missing = await httpRequest(resource, 'GET', '/other');
  assert.equal(missing.status, 404);
  assert.equal(missing.body.toString('utf8'), 'Not found');
  await assert.rejects(stat(blobDirectory(resource)), (failure) => failure.code === 'ENOENT');
});

test('blocking an account removes its blobs and tickets; disabling chat refuses new tickets and transfers', async () => {
  const resource = await setup({ env: adminEnv() });
  const admin = await account(resource, '1', 851, 8);
  const alice = await account(resource, '2', 852, 8);
  const bob = await account(resource, '3', 853, 8);
  assert.equal((await adminLogin(admin.socket, ADMIN_CODE)).ok, true);
  const data = randomBytes(128);
  const { id } = await uploadBlob(resource, alice, bob, data, uuid(70));
  const download = await getTicket(bob, id);
  assert.equal((await httpRequest(resource, 'GET', download.path, { headers: bearer(download) })).status, 200);

  assert.equal((await adminAction(admin.socket, 'update_settings', { settings: { chatEnabled: false } })).ok, true);
  assert.equal((await createTicket(bob, { id: uuid(71), to: alice.number, size: 10 })).code, 'chat_disabled');
  assert.equal((await getTicket(bob, id)).code, 'chat_disabled');
  assert.equal((await httpRequest(resource, 'GET', download.path, { headers: bearer(download) })).status, 403);
  assert.equal((await adminAction(admin.socket, 'update_settings', { settings: { chatEnabled: true } })).ok, true);

  assert.equal((await adminAction(admin.socket, 'block', { number: alice.number })).ok, true);
  await until(async () => (await getTicket(bob, id)).code === 'not_found', 2_000, 60);
  assert.equal((await httpRequest(resource, 'GET', download.path, { headers: bearer(download) })).status, 401);
  await until(async () => (await readdir(blobDirectory(resource))).filter((name) => name.startsWith(id)).length === 0);
  assert.equal((await createTicket(bob, { id: uuid(72), to: alice.number, size: 10 })).code, 'blocked');
  await sleep(10);
});
