import { createServer } from 'node:https';
import { connect } from 'node:net';
import { randomBytes, scryptSync } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createSignalingServer } from '../server/src/index.js';

const salt = randomBytes(16);
const password = 'Local-test-admin-2026';
const hash = `scrypt$${salt.toString('base64')}$${scryptSync(password, salt, 64).toString('base64')}`;
const api = await createSignalingServer({
  dataFile: '.tools/admin-device/identities.json',
  env: { ADMIN_PASSWORD_HASH: hash },
});
const address = await api.listen(0, '127.0.0.1');
const tls = createServer({ key: readFileSync('.tools/admin-device/key.pem'), cert: readFileSync('.tools/admin-device/cert.pem') },
  (_, response) => { response.writeHead(200); response.end('Local admin device test'); });
tls.on('upgrade', (request, socket, head) => {
  const upstream = connect(address.port, '127.0.0.1', () => {
    upstream.write(`${request.method} ${request.url} HTTP/${request.httpVersion}\r\n`);
    for (let index = 0; index < request.rawHeaders.length; index += 2) upstream.write(`${request.rawHeaders[index]}: ${request.rawHeaders[index + 1]}\r\n`);
    upstream.write('\r\n'); if (head.length) upstream.write(head);
    socket.pipe(upstream).pipe(socket);
  });
  upstream.on('error', () => socket.destroy()); socket.on('error', () => upstream.destroy()); socket.on('close', () => upstream.destroy());
});
tls.listen(8443, '127.0.0.1', () => console.log('Local TLS admin test ready'));
async function close() { tls.close(); await api.close(); process.exit(0); }
process.once('SIGTERM', close); process.once('SIGINT', close);
