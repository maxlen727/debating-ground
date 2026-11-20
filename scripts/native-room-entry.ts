// 安卓手机当局域网房主（#148）时，在 JS 引擎（androidx.javascriptengine）里跑的房间规则入口。
// 房间规则只有一份（worker/src/room.ts 的 RoomCore）：Worker、电脑上的本机服务和手机用的都是它，
// 手机只换掉外壳。JS 引擎里只有语言本身：没有网络、没有定时器、没有 crypto，所以——
// - 要做的事（发消息、断开连接、定闹钟、调用 AI）都记成“效果”返回给 Kotlin，由 Kotlin 去做；
// - AI 调用返回一个等着的 Promise，Kotlin 拿到结果后调 resolve 接上；
// - 时间和随机数每次由 Kotlin 传进来（随机数用 SecureRandom 生成），SHA-256 用下面的纯 JS 实现。
// 改了 worker/src/room.ts 或 shared/ 后运行 npm run bundle:native，tests/native-room.test.ts 会检查打包文件没有过期。
import { RoomCore, type Generate, type SocketLike, type StoredRoom } from '../worker/src/room.ts';
import { ApiError } from '../worker/src/vendor/server/ai.ts';

type Effect =
  | { kind: 'send'; sock: string; data: string }
  | { kind: 'close'; sock: string }
  | { kind: 'alarm'; room: string; at: number }
  | { kind: 'generate'; req: string; purpose: string; payload: unknown }
  | { kind: 'gone'; room: string };

let clock = 0;
let entropy: number[] = [];
let effects: Effect[] = [];

/* ---------- JS 引擎里缺的几样：随机数、SHA-256、TextEncoder、btoa ---------- */
const g = globalThis as Record<string, unknown>;
function utf8(s: string): Uint8Array {
  const out: number[] = [];
  for (const ch of s) {
    const c = ch.codePointAt(0)!;
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    else
      out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
  }
  return new Uint8Array(out);
}
const K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];
/** SHA-256（FIPS 180-4），只用来给邀请码和成员凭据算哈希。 */
export function sha256(data: Uint8Array): Uint8Array {
  const len = data.length;
  const total = Math.ceil((len + 9) / 64) * 64;
  const m = new Uint8Array(total);
  m.set(data);
  m[len] = 0x80;
  const bits = len * 8;
  const view = new DataView(m.buffer);
  view.setUint32(total - 8, Math.floor(bits / 0x100000000));
  view.setUint32(total - 4, bits >>> 0);
  const h = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ];
  const w = new Array<number>(64);
  const rot = (x: number, n: number) => (x >>> n) | (x << (32 - n));
  for (let off = 0; off < total; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rot(w[i - 15], 7) ^ rot(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rot(w[i - 2], 17) ^ rot(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    let [a, b, c, d, e, f, gg, hh] = h;
    for (let i = 0; i < 64; i++) {
      const t1 =
        (hh + (rot(e, 6) ^ rot(e, 11) ^ rot(e, 25)) + ((e & f) ^ (~e & gg)) + K[i] + w[i]) | 0;
      const t2 = ((rot(a, 2) ^ rot(a, 13) ^ rot(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      hh = gg;
      gg = f;
      f = e;
      e = (d + t1) | 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) | 0;
    }
    h[0] = (h[0] + a) | 0;
    h[1] = (h[1] + b) | 0;
    h[2] = (h[2] + c) | 0;
    h[3] = (h[3] + d) | 0;
    h[4] = (h[4] + e) | 0;
    h[5] = (h[5] + f) | 0;
    h[6] = (h[6] + gg) | 0;
    h[7] = (h[7] + hh) | 0;
  }
  const out = new Uint8Array(32);
  const ov = new DataView(out.buffer);
  h.forEach((x, i) => ov.setUint32(i * 4, x >>> 0));
  return out;
}
// 只在缺的时候补上（Node 测试里自带的这些照样能用）
g.crypto = {
  getRandomValues<T extends Uint8Array>(a: T): T {
    if (entropy.length < a.length) throw new Error('随机数不够了。');
    for (let i = 0; i < a.length; i++) a[i] = entropy.shift()!;
    return a;
  },
  subtle: {
    digest: (_alg: string, data: Uint8Array) =>
      Promise.resolve(sha256(new Uint8Array(data)).buffer),
  },
};
if (typeof g.TextEncoder !== 'function')
  g.TextEncoder = class {
    encode(s: string) {
      return utf8(s);
    }
  };
if (typeof g.btoa !== 'function') {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  g.btoa = (s: string) => {
    let out = '';
    for (let i = 0; i < s.length; i += 3) {
      const [a, b, c] = [s.charCodeAt(i), s.charCodeAt(i + 1), s.charCodeAt(i + 2)];
      const n = (a << 16) | ((b || 0) << 8) | (c || 0);
      out +=
        A[(n >> 18) & 63] +
        A[(n >> 12) & 63] +
        (i + 1 < s.length ? A[(n >> 6) & 63] : '=') +
        (i + 2 < s.length ? A[n & 63] : '=');
    }
    return out;
  };
}
// 定时器只用在把 AI 的流式文字攒一攒再发；手机上 AI 不流式返回，这里排到下一次调用时执行
const later: (() => void)[] = [];
g.setTimeout = (fn: () => void) => {
  later.push(fn);
  return later.length;
};
g.clearTimeout = () => {};

/* ---------- 房间 ---------- */
interface Hosted {
  core: RoomCore;
  stored: StoredRoom | undefined;
  socks: Map<string, { member: string | null }>;
}
const rooms = new Map<string, Hosted>();
const pending = new Map<string, { resolve: (v: unknown) => void; reject: (e: unknown) => void }>();
let nextReq = 0;

function socket(room: Hosted, id: string): SocketLike {
  return {
    send: data => effects.push({ kind: 'send', sock: id, data }),
    close: () => effects.push({ kind: 'close', sock: id }),
    memberId: () => room.socks.get(id)?.member ?? null,
    setMember: m => {
      const s = room.socks.get(id);
      if (s) s.member = m;
    },
  };
}

function host(roomId: string): Hosted {
  const room: Hosted = { core: null as unknown as RoomCore, stored: undefined, socks: new Map() };
  room.core = new RoomCore({
    load: () => Promise.resolve(room.stored),
    save: r => {
      room.stored = JSON.parse(JSON.stringify(r)) as StoredRoom;
      return Promise.resolve();
    },
    destroy: () => {
      rooms.delete(roomId);
      effects.push({ kind: 'gone', room: roomId });
      return Promise.resolve();
    },
    sockets: () => [...room.socks.keys()].map(id => socket(room, id)),
    setAlarm: at => {
      effects.push({ kind: 'alarm', room: roomId, at });
      return Promise.resolve();
    },
    // AI 由 Kotlin 用房主的“直接连接”去调，结果回来时 resolve 接上
    generate: (purpose, payload) =>
      new Promise((resolve, reject) => {
        const req = String(++nextReq);
        pending.set(req, { resolve: v => resolve(v as Awaited<ReturnType<Generate>>), reject });
        effects.push({ kind: 'generate', req, purpose, payload });
      }),
    now: () => clock,
  });
  rooms.set(roomId, room);
  return room;
}

// 每次调用：记下时间和随机数，开始做事，让已经能往下走的 Promise 都走完，然后把攒下的效果交出去。
// 还在等 AI 的那部分留在 JS 里，等 resolve 时再接着走。
async function step(now: number, random: number[], work: () => Promise<unknown>) {
  clock = now;
  entropy = random;
  effects = [];
  let result: unknown = null;
  let error = '';
  let done = false;
  work().then(
    r => {
      result = r;
      done = true;
    },
    (e: unknown) => {
      error = e instanceof ApiError ? e.message : '房间出错了。';
      done = true;
    },
  );
  for (let i = 0; i < 200; i++) {
    await Promise.resolve();
    while (later.length) later.shift()!();
  }
  const out = { effects, result: done ? result : null, error };
  effects = [];
  return JSON.stringify(out);
}

const API = {
  /** 房主开房间：返回邀请码和房主凭据（和 Worker 的 /api/rooms 一样）。 */
  create: (roomId: string, setup: unknown, hostName: unknown, now: number, random: number[]) =>
    step(now, random, () => host(roomId).core.init({ roomId, setup, hostName })),
  open: (roomId: string, sock: string, now: number, random: number[]) =>
    step(now, random, async () => {
      const room = rooms.get(roomId);
      if (!room) return effects.push({ kind: 'close', sock });
      room.socks.set(sock, { member: null });
      await room.core.onOpen(socket(room, sock));
    }),
  message: (roomId: string, sock: string, text: string, now: number, random: number[]) =>
    step(now, random, async () => {
      const room = rooms.get(roomId);
      if (room?.socks.has(sock)) await room.core.onMessage(socket(room, sock), text);
    }),
  close: (roomId: string, sock: string, now: number, random: number[]) =>
    step(now, random, async () => {
      const room = rooms.get(roomId);
      if (!room) return;
      const s = socket(room, sock);
      room.socks.delete(sock);
      await room.core.onClose(s);
    }),
  alarm: (roomId: string, now: number, random: number[]) =>
    step(now, random, async () => {
      await rooms.get(roomId)?.core.alarm();
    }),
  /** AI 的结果回来了：ok 时 value 是结果，否则 value 是失败原因。 */
  resolve: (req: string, ok: boolean, value: unknown, now: number, random: number[]) =>
    step(now, random, () => {
      const p = pending.get(req);
      pending.delete(req);
      if (ok) p?.resolve(value);
      else
        p?.reject(new ApiError(typeof value === 'string' && value ? value : 'AI 请求失败。', 502));
      return Promise.resolve(null);
    }),
};

// Kotlin 用字符串调用：sibianRoom.call(name, argsJson)，返回 Promise<string>
g.sibianRoom = {
  call: (name: keyof typeof API, args: string) =>
    (API[name] as (...a: unknown[]) => Promise<string>)(...(JSON.parse(args) as unknown[])),
  // 测试用：和 Node 的 crypto 比对哈希
  sha256Hex: (s: string) =>
    Array.from(sha256(utf8(s)), b => b.toString(16).padStart(2, '0')).join(''),
};
