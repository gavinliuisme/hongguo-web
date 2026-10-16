'use strict';
/**
 * SM3（GB/T 32905）杂凑算法，纯 JS 实现。
 *
 * 移植自原 Java core/Sm3.java（其本身移植自 jackotom/hongguo-drama 的
 * src/native/sm3.js）。Node 自带 crypto 通常不含 SM3，故直接实现，
 * 避免引入第三方依赖。仅实现签名链路用到的单次杂凑。
 *
 * 对应 JS 侧 sm3(buffer) -> 32 字节 Buffer。
 */

function rotl(x, n) {
  const k = n & 31;
  return ((x << k) | (x >>> ((32 - k) & 31))) | 0;
}

/** T_j 常量：前 16 轮 0x79cc4519，之后 0x7a879d8a。 */
function tj(j) { return j < 16 ? 0x79cc4519 : 0x7a879d8a; }

function ff(a, b, c, j) {
  return j < 16 ? (a ^ b ^ c) : ((a & b) | (a & c) | (b & c));
}

function gg(e, f, g, j) {
  return j < 16 ? (e ^ f ^ g) : ((e & f) | ((~e) & g));
}

/** P0 = x ^ rotl(x,15) ^ rotl(x,23)。 */
function p0(x) { return (x ^ rotl(x, 15) ^ rotl(x, 23)) | 0; }

/** P1 = x ^ rotl(x,9) ^ rotl(x,17)。 */
function p1(x) { return (x ^ rotl(x, 9) ^ rotl(x, 17)) | 0; }

/** 计算 SM3 摘要，返回 32 字节 Buffer。 */
function hash(input) {
  const buf = Buffer.isBuffer(input)
    ? input
    : (input instanceof Uint8Array ? Buffer.from(input) : Buffer.from(String(input), 'utf8'));
  const len = buf.length;
  const padded = Math.floor((len + 9 + 63) / 64) * 64;
  const data = Buffer.alloc(padded);
  buf.copy(data, 0);
  data[len] = 0x80;
  const bits = BigInt(len) * 8n;
  for (let i = 0; i < 8; i++) {
    data[padded - 1 - i] = Number((bits >> BigInt(8 * i)) & 0xffn);
  }

  const state = [
    0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
    0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e,
  ];
  const w = new Array(68);

  for (let offset = 0; offset < padded; offset += 64) {
    for (let i = 0; i < 16; i++) w[i] = data.readInt32BE(offset + i * 4);
    for (let i = 16; i < 68; i++) {
      const x = (w[i - 16] ^ w[i - 9] ^ rotl(w[i - 3], 15)) | 0;
      w[i] = (p0(x) ^ rotl(w[i - 13], 7) ^ w[i - 6]) | 0;
    }
    let a = state[0], b = state[1], c = state[2], d = state[3];
    let e = state[4], f = state[5], g = state[6], h = state[7];
    for (let j = 0; j < 64; j++) {
      const a12 = rotl(a, 12);
      const s1 = rotl((a12 + e + rotl(tj(j), j)) | 0, 7);
      const s2 = (s1 ^ a12) | 0;
      const t1 = (ff(a, b, c, j) + d + s2 + (w[j] ^ w[j + 4])) | 0;
      const t2 = (gg(e, f, g, j) + h + s1 + w[j]) | 0;
      d = c;
      c = rotl(b, 9);
      b = a;
      a = t1;
      h = g;
      g = rotl(f, 19);
      f = e;
      e = p1(t2);
    }
    state[0] = (state[0] ^ a) | 0;
    state[1] = (state[1] ^ b) | 0;
    state[2] = (state[2] ^ c) | 0;
    state[3] = (state[3] ^ d) | 0;
    state[4] = (state[4] ^ e) | 0;
    state[5] = (state[5] ^ f) | 0;
    state[6] = (state[6] ^ g) | 0;
    state[7] = (state[7] ^ h) | 0;
  }

  const out = Buffer.alloc(32);
  for (let i = 0; i < 8; i++) out.writeInt32BE(state[i], i * 4);
  return out;
}

/** SM3("abc") 的标准摘要值（GB/T 32905 附录 A）。 */
const ABC_DIGEST = '66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0';

/** 自检：对 "abc" 算一次，比对标准值。 */
function selfTest() {
  const got = hash('abc').toString('hex');
  const ok = ABC_DIGEST === got;
  if (!ok) require('../util/log').warn(`SM3 自检失败：期望 ${ABC_DIGEST} 实际 ${got}`);
  return ok;
}

module.exports = { hash, selfTest, ABC_DIGEST };
