import { randomBytes, scryptSync } from 'node:crypto';

function readSecret() {
  if (!process.stdin.isTTY) {
    return new Promise((resolve, reject) => {
      let input = '';
      process.stdin.setEncoding('utf8');
      process.stdin.on('data', (chunk) => { input += chunk; });
      process.stdin.on('end', () => resolve(input.replace(/\r?\n$/, '')));
      process.stdin.on('error', reject);
    });
  }

  return new Promise((resolve, reject) => {
    let secret = '';
    process.stderr.write('Admin passphrase (input hidden): ');
    process.stdin.setRawMode(true);
    process.stdin.setEncoding('utf8');
    const finish = (error) => {
      process.stdin.setRawMode(false);
      process.stdin.pause();
      process.stdin.off('data', onData);
      process.stderr.write('\n');
      if (error) reject(error);
      else resolve(secret);
    };
    const onData = (chunk) => {
      for (const character of chunk) {
        if (character === '\u0003') return finish(new Error('Input cancelled'));
        if (character === '\r' || character === '\n') return finish();
        if (character === '\u007f' || character === '\b') secret = [...secret].slice(0, -1).join('');
        else secret += character;
      }
    };
    process.stdin.on('data', onData);
    process.stdin.resume();
  });
}

try {
  const password = await readSecret();
  const length = [...password].length;
  if (length < 12 || length > 128 || Buffer.byteLength(password, 'utf8') > 512) {
    throw new Error('Passphrase must be 12 to 128 characters.');
  }
  const salt = randomBytes(16);
  const digest = scryptSync(password, salt, 64, { maxmem: 64 * 1024 * 1024 });
  process.stdout.write(`scrypt$${salt.toString('base64')}$${digest.toString('base64')}\n`);
} catch (error) {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
}
