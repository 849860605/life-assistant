/**
 * 提交密码前在浏览器里加密，请求里看不到明文：RSA + OAEP 填充（SHA-256，MGF1 也用 SHA-256），和后端 PasswordCipher 对应（和用户端 life-web/src/utils/rsa.ts 相同）。
 * 加密的内容是"服务器时间\n密码"，后端核对时间、同一段密文只认一次，截获了也不能重放。
 * 不用 crypto.subtle（只在 https 下能用），自己用 BigInt 算。
 */

/** 后端给的公钥：n、e 是十六进制，time 是服务器毫秒数 */
export interface LoginKey {
  n: string;
  e: string;
  time: number;
}

/** 把密码加密成 base64。 */
export function sealPassword(password: string, key: LoginKey): string {
  const n = BigInt("0x" + key.n);
  const k = Math.ceil(key.n.replace(/^0+/, "").length / 2);
  const em = oaepEncode(new TextEncoder().encode(`${key.time}\n${password}`), k);
  const c = modPow(bytesToBig(em), BigInt("0x" + key.e), n);
  return base64(bigToBytes(c, k));
}

/** RFC 8017 的 EME-OAEP 编码（label 为空）。 */
function oaepEncode(message: Uint8Array, k: number): Uint8Array {
  const hLen = 32;
  if (message.length > k - 2 * hLen - 2) throw new Error("密码太长");
  const db = new Uint8Array(k - hLen - 1);
  db.set(sha256(new Uint8Array(0)));
  db[db.length - message.length - 1] = 0x01;
  db.set(message, db.length - message.length);
  const seed = crypto.getRandomValues(new Uint8Array(hLen));
  const maskedDb = xor(db, mgf1(seed, db.length));
  const maskedSeed = xor(seed, mgf1(maskedDb, hLen));
  const em = new Uint8Array(k);
  em.set(maskedSeed, 1);
  em.set(maskedDb, 1 + hLen);
  return em;
}

/** MGF1：SHA-256(seed + 4 字节计数器) 接起来截到 length。 */
function mgf1(seed: Uint8Array, length: number): Uint8Array {
  const out = new Uint8Array(length);
  const buf = new Uint8Array(seed.length + 4);
  buf.set(seed);
  for (let counter = 0, off = 0; off < length; counter++, off += 32) {
    new DataView(buf.buffer).setUint32(seed.length, counter);
    out.set(sha256(buf).subarray(0, Math.min(32, length - off)), off);
  }
  return out;
}

function xor(a: Uint8Array, b: Uint8Array): Uint8Array {
  return a.map((v, i) => v ^ b[i]);
}

function modPow(base: bigint, exp: bigint, mod: bigint): bigint {
  const zero = BigInt(0), one = BigInt(1), two = BigInt(2);
  let result = one;
  base %= mod;
  while (exp > zero) {
    if (exp % two === one) result = (result * base) % mod;
    base = (base * base) % mod;
    exp /= two;
  }
  return result;
}

function bytesToBig(bytes: Uint8Array): bigint {
  let hex = "";
  for (const b of bytes) hex += b.toString(16).padStart(2, "0");
  return BigInt("0x" + (hex || "0"));
}

/** 大数转成定长 k 字节（前面补 0）。 */
function bigToBytes(value: bigint, k: number): Uint8Array {
  const hex = value.toString(16).padStart(k * 2, "0");
  const out = new Uint8Array(k);
  for (let i = 0; i < k; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

function base64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

/** SHA-256（OAEP 填充要用；crypto.subtle 只在 https 下能用，所以自己算）。 */
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

const W = new Uint32Array(64);

/** 一组字节的 SHA-256，返回 32 字节。 */
function sha256(data: Uint8Array): Uint8Array {
  const bitLength = data.length * 8;
  const padded = new Uint8Array(((data.length + 9 + 63) >> 6) << 6);
  padded.set(data);
  padded[data.length] = 0x80;
  const view = new DataView(padded.buffer);
  // 长度写在最后 8 字节（高 32 位对这里的短消息总是 0）
  view.setUint32(padded.length - 8, Math.floor(bitLength / 0x100000000));
  view.setUint32(padded.length - 4, bitLength >>> 0);

  let h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
  let h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) W[i] = view.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const a = W[i - 15], b = W[i - 2];
      const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
      const s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10);
      W[i] = (W[i - 16] + s0 + W[i - 7] + s1) | 0;
    }
    let a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
    for (let i = 0; i < 64; i++) {
      const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const ch = (e & f) ^ (~e & g);
      const t1 = (h + S1 + ch + K[i] + W[i]) | 0;
      const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0;
      d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0;
    h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + h) | 0;
  }
  const out = new Uint8Array(32);
  const ov = new DataView(out.buffer);
  [h0, h1, h2, h3, h4, h5, h6, h7].forEach((v, i) => ov.setUint32(i * 4, v >>> 0));
  return out;
}
