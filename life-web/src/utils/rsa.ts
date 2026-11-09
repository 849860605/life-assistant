import { sha256, utf8 } from "./pow";

/**
 * 提交密码前在浏览器里加密，请求里看不到明文：RSA + OAEP 填充（SHA-256，MGF1 也用 SHA-256），和后端 PasswordCipher 对应。
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
  const em = oaepEncode(utf8(`${key.time}\n${password}`), k);
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
