// 邀请链接的二维码（#151）：扫一下用浏览器打开就能加入。自己实现，不加依赖。
// 只做用得到的部分：字节模式、纠错等级 M（能扛住屏幕反光和一小块遮挡）、版本 1–40 自动挑最小的、8 种掩码按标准罚分挑最好的。
// 原生版 room/QrCode.kt 是同一套算法的移植，用 scripts/native-fixtures.ts 生成的标准答案逐格比对。

// 纠错等级 M：每块纠错码字数、块数（下标是版本号）
const ECC_PER_BLOCK = [
  -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28,
  28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
];
const BLOCKS = [
  -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25,
  26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
];
// 纠错等级 M 的格式信息编号
const ECL_BITS = 0;

/** 二维码：size × size，true 是深色格。 */
export type QrCode = { size: number; version: number; mask: number; modules: boolean[][] };

// 去掉定位、校正、时序、格式和版本信息以后，能放数据的格子数
function rawModules(ver: number) {
  let n = (16 * ver + 128) * ver + 64;
  if (ver >= 2) {
    const align = Math.floor(ver / 7) + 2;
    n -= (25 * align - 10) * align - 55;
    if (ver >= 7) n -= 36;
  }
  return n;
}
const dataCodewords = (ver: number) =>
  Math.floor(rawModules(ver) / 8) - ECC_PER_BLOCK[ver] * BLOCKS[ver];

/* ---------- Reed–Solomon（GF(256)，本原多项式 0x11D） ---------- */
function gfMul(x: number, y: number) {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}
function rsDivisor(degree: number) {
  const r = new Array<number>(degree).fill(0);
  r[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < r.length; j++) {
      r[j] = gfMul(r[j], root);
      if (j + 1 < r.length) r[j] ^= r[j + 1];
    }
    root = gfMul(root, 0x02);
  }
  return r;
}
function rsRemainder(data: number[], divisor: number[]) {
  const r = divisor.map(() => 0);
  for (const b of data) {
    const factor = b ^ (r.shift() as number);
    r.push(0);
    divisor.forEach((coef, i) => (r[i] ^= gfMul(coef, factor)));
  }
  return r;
}

/* ---------- 数据码字：模式 + 长度 + 内容 + 结束符 + 填充，再分块加纠错、交错 ---------- */
function codewords(bytes: Uint8Array, ver: number) {
  const bits: number[] = [];
  const put = (val: number, len: number) => {
    for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1);
  };
  put(0b0100, 4);
  put(bytes.length, ver <= 9 ? 8 : 16);
  bytes.forEach(b => put(b, 8));
  const cap = dataCodewords(ver) * 8;
  put(0, Math.min(4, cap - bits.length));
  put(0, (8 - (bits.length % 8)) % 8);
  for (let pad = 0xec; bits.length < cap; pad ^= 0xec ^ 0x11) put(pad, 8);
  const data: number[] = [];
  for (let i = 0; i < bits.length; i += 8)
    data.push(bits.slice(i, i + 8).reduce((a, b) => (a << 1) | b, 0));

  const blocks = BLOCKS[ver];
  const ecc = ECC_PER_BLOCK[ver];
  const total = Math.floor(rawModules(ver) / 8);
  const shortBlocks = blocks - (total % blocks);
  const shortLen = Math.floor(total / blocks);
  const div = rsDivisor(ecc);
  const all: number[][] = [];
  for (let i = 0, k = 0; i < blocks; i++) {
    const dat = data.slice(k, k + shortLen - ecc + (i < shortBlocks ? 0 : 1));
    k += dat.length;
    const block = [...dat, ...rsRemainder(dat, div)];
    // 短块在数据末尾补一个空位，交错时跳过
    if (i < shortBlocks) block.splice(dat.length, 0, -1);
    all.push(block);
  }
  const out: number[] = [];
  for (let i = 0; i < all[0].length; i++) for (const b of all) if (b[i] !== -1) out.push(b[i]);
  return out;
}

/* ---------- 画格子 ---------- */
function alignmentPositions(ver: number) {
  if (ver === 1) return [];
  const n = Math.floor(ver / 7) + 2;
  const step = ver === 32 ? 26 : Math.ceil((ver * 4 + 4) / (n * 2 - 2)) * 2;
  const r = [6];
  for (let pos = ver * 4 + 10; r.length < n; pos -= step) r.splice(1, 0, pos);
  return r;
}

function bchFormat(mask: number) {
  const data = (ECL_BITS << 3) | mask;
  let rem = data;
  for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
  return ((data << 10) | rem) ^ 0x5412;
}
function bchVersion(ver: number) {
  let rem = ver;
  for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
  return (ver << 12) | rem;
}

const MASKS: ((x: number, y: number) => boolean)[] = [
  (x, y) => (x + y) % 2 === 0,
  (_x, y) => y % 2 === 0,
  x => x % 3 === 0,
  (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
  (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
  (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
];

class Grid {
  ver: number;
  size: number;
  m: boolean[][];
  fixed: boolean[][];
  constructor(ver: number) {
    this.ver = ver;
    this.size = ver * 4 + 17;
    this.m = Array.from({ length: this.size }, () => new Array<boolean>(this.size).fill(false));
    this.fixed = Array.from({ length: this.size }, () => new Array<boolean>(this.size).fill(false));
  }
  set(x: number, y: number, dark: boolean) {
    this.m[y][x] = dark;
    this.fixed[y][x] = true;
  }
  functionPatterns() {
    const { size } = this;
    for (let i = 0; i < size; i++) {
      this.set(6, i, i % 2 === 0);
      this.set(i, 6, i % 2 === 0);
    }
    const finder = (cx: number, cy: number) => {
      for (let dy = -4; dy <= 4; dy++)
        for (let dx = -4; dx <= 4; dx++) {
          const d = Math.max(Math.abs(dx), Math.abs(dy));
          const x = cx + dx;
          const y = cy + dy;
          if (x >= 0 && x < size && y >= 0 && y < size) this.set(x, y, d !== 2 && d !== 4);
        }
    };
    finder(3, 3);
    finder(size - 4, 3);
    finder(3, size - 4);
    const pos = alignmentPositions(this.ver);
    const last = pos.length - 1;
    pos.forEach((a, i) =>
      pos.forEach((b, j) => {
        if ((i === 0 && j === 0) || (i === 0 && j === last) || (i === last && j === 0)) return;
        for (let dy = -2; dy <= 2; dy++)
          for (let dx = -2; dx <= 2; dx++)
            this.set(a + dx, b + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
      }),
    );
    this.format(0);
    if (this.ver >= 7) {
      const bits = bchVersion(this.ver);
      for (let i = 0; i < 18; i++) {
        const dark = ((bits >>> i) & 1) !== 0;
        const a = size - 11 + (i % 3);
        const b = Math.floor(i / 3);
        this.set(a, b, dark);
        this.set(b, a, dark);
      }
    }
  }
  format(mask: number) {
    const bits = bchFormat(mask);
    const bit = (i: number) => ((bits >>> i) & 1) !== 0;
    const { size } = this;
    for (let i = 0; i <= 5; i++) this.set(8, i, bit(i));
    this.set(8, 7, bit(6));
    this.set(8, 8, bit(7));
    this.set(7, 8, bit(8));
    for (let i = 9; i < 15; i++) this.set(14 - i, 8, bit(i));
    for (let i = 0; i < 8; i++) this.set(size - 1 - i, 8, bit(i));
    for (let i = 8; i < 15; i++) this.set(8, size - 15 + i, bit(i));
    this.set(8, size - 8, true);
  }
  // 从右下角开始，两列一组之字形往上往下
  place(data: number[]) {
    const { size } = this;
    let i = 0;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let v = 0; v < size; v++)
        for (let j = 0; j < 2; j++) {
          const x = right - j;
          const up = ((right + 1) & 2) === 0;
          const y = up ? size - 1 - v : v;
          if (!this.fixed[y][x] && i < data.length * 8) {
            this.m[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) !== 0;
            i++;
          }
        }
    }
  }
  applyMask(mask: number) {
    const f = MASKS[mask];
    for (let y = 0; y < this.size; y++)
      for (let x = 0; x < this.size; x++)
        if (!this.fixed[y][x] && f(x, y)) this.m[y][x] = !this.m[y][x];
  }
  penalty() {
    const { size, m } = this;
    let score = 0;
    const line = (get: (i: number) => boolean) => {
      // 规则 1：同色连续 5 格以上；规则 3：1:1:3:1:1 的类定位图形，两边各留 4 格浅色（越界算浅色）
      let run = 1;
      for (let i = 1; i <= size; i++) {
        if (i < size && get(i) === get(i - 1)) run++;
        else {
          if (run >= 5) score += run - 2;
          run = 1;
        }
      }
      const at = (i: number) => i >= 0 && i < size && get(i);
      const pat = [true, false, true, true, true, false, true];
      for (let i = 0; i + 7 <= size; i++) {
        if (!pat.every((p, k) => at(i + k) === p)) continue;
        const before = [1, 2, 3, 4].every(k => !at(i - k));
        const after = [7, 8, 9, 10].every(k => !at(i + k));
        if (before) score += 40;
        if (after) score += 40;
      }
    };
    for (let y = 0; y < size; y++) line(x => m[y][x]);
    for (let x = 0; x < size; x++) line(y => m[y][x]);
    // 规则 2：2×2 同色块
    for (let y = 0; y < size - 1; y++)
      for (let x = 0; x < size - 1; x++) {
        const c = m[y][x];
        if (c === m[y][x + 1] && c === m[y + 1][x] && c === m[y + 1][x + 1]) score += 3;
      }
    // 规则 4：深色比例偏离 50%
    let dark = 0;
    for (const row of m) for (const c of row) if (c) dark++;
    const total = size * size;
    const k = Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1;
    score += k * 10;
    return score;
  }
}

/** 把文字编成二维码。[mask] 只给测试用（固定掩码，和别的实现逐格比对）。太长放不下时返回 null。 */
export function encodeQr(text: string, mask?: number): QrCode | null {
  const bytes = new TextEncoder().encode(text);
  let ver = 1;
  while (ver <= 40 && 4 + (ver <= 9 ? 8 : 16) + bytes.length * 8 > dataCodewords(ver) * 8) ver++;
  if (ver > 40) return null;
  const data = codewords(bytes, ver);
  const build = (k: number) => {
    const g = new Grid(ver);
    g.functionPatterns();
    g.place(data);
    g.applyMask(k);
    g.format(k);
    return g;
  };
  let best: Grid | null = null;
  let bestMask = 0;
  if (mask !== undefined) {
    best = build(mask);
    bestMask = mask;
  } else {
    let min = Infinity;
    for (let k = 0; k < 8; k++) {
      const g = build(k);
      const p = g.penalty();
      if (p < min) {
        min = p;
        best = g;
        bestMask = k;
      }
    }
  }
  const g = best as Grid;
  return { size: g.size, version: ver, mask: bestMask, modules: g.m };
}

/** 深色格的 SVG 路径（一行里连着的格子合成一段），四周留 [quiet] 格静区。 */
export function qrPath(q: QrCode, quiet = 4) {
  let d = '';
  q.modules.forEach((row, y) => {
    for (let x = 0; x < q.size; x++) {
      if (!row[x]) continue;
      let w = 1;
      while (x + w < q.size && row[x + w]) w++;
      d += `M${x + quiet} ${y + quiet}h${w}v1h-${w}z`;
      x += w - 1;
    }
  });
  return d;
}
