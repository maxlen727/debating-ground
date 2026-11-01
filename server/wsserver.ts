// 本机服务用的最小 WebSocket 服务端（RFC 6455）。只用在语音输入：文本帧、关闭、ping。
// 本机服务不加运行时依赖，所以不用 ws 这类库。
import crypto from 'node:crypto';
import type http from 'node:http';
import type { Duplex } from 'node:stream';

export interface ServerSocket { send(text: string): void; close(): void; onMessage(fn: (text: string) => void): void; onClose(fn: () => void): void }

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const MAX_MESSAGE = 64 * 1024;   // 语音帧的 base64 很小；超过就断开

export function rejectUpgrade(socket: Duplex, status: number, reason: string) {
  socket.end(`HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
}

export function acceptWebSocket(req: http.IncomingMessage, socket: Duplex): ServerSocket | null {
  const key = req.headers['sec-websocket-key'];
  if (typeof key !== 'string' || req.headers.upgrade?.toLowerCase() !== 'websocket') { rejectUpgrade(socket, 400, 'Bad Request'); return null; }
  const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);

  let buf = Buffer.alloc(0), fragments: Buffer[] = [], closed = false;
  const onMessage: ((t: string) => void)[] = [], onClose: (() => void)[] = [];
  const frame = (opcode: number, payload: Buffer) => {
    const len = payload.length;
    const head = len < 126 ? Buffer.from([0x80 | opcode, len])
      : len < 65536 ? Buffer.from([0x80 | opcode, 126, len >> 8, len & 255])
        : Buffer.concat([Buffer.from([0x80 | opcode, 127]), (() => { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(len)); return b; })()]);
    if (!closed) socket.write(Buffer.concat([head, payload]));
  };
  const finish = (graceful = false) => { if (closed) return; closed = true; if (graceful) socket.end(); else socket.destroy(); onClose.forEach(f => f()); };

  socket.on('data', (chunk: Buffer) => {
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= 2) {
      const fin = (buf[0] & 0x80) !== 0, opcode = buf[0] & 0x0f, masked = (buf[1] & 0x80) !== 0;
      let len = buf[1] & 0x7f, off = 2;
      if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (buf.length < 10) return; const big = buf.readBigUInt64BE(2); if (big > BigInt(MAX_MESSAGE)) return finish(); len = Number(big); off = 10; }
      // 浏览器发来的帧必须带掩码
      if (!masked || len > MAX_MESSAGE) return finish();
      if (buf.length < off + 4 + len) return;
      const mask = buf.subarray(off, off + 4);
      const payload = Buffer.from(buf.subarray(off + 4, off + 4 + len));
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      buf = buf.subarray(off + 4 + len);
      if (opcode === 0x8) { frame(0x8, Buffer.alloc(0)); return finish(true); }
      if (opcode === 0x9) { frame(0xA, payload); continue; }
      if (opcode === 0xA) continue;
      if (opcode !== 0x1 && opcode !== 0x0) return finish();   // 只收文本
      fragments.push(payload);
      if (fragments.reduce((n, f) => n + f.length, 0) > MAX_MESSAGE) return finish();
      if (fin) { const text = Buffer.concat(fragments).toString('utf8'); fragments = []; onMessage.forEach(f => f(text)); }
    }
  });
  socket.on('close', () => finish());
  socket.on('error', () => finish());

  return {
    send: text => frame(0x1, Buffer.from(text, 'utf8')),
    close: () => { frame(0x8, Buffer.from([0x03, 0xe8])); finish(true); },
    onMessage: fn => { onMessage.push(fn); },
    onClose: fn => { onClose.push(fn); }
  };
}
