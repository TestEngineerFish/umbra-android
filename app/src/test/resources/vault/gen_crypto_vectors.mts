// 用 PC 端 crypto.ts 原件生成保险箱跨端向量（Android JUnit 用）。
import * as c from "/Users/sam/Documents/SourceTree/Geek/UmbraPC/electron/core/vault/crypto.ts";
import * as nc from "node:crypto";
const salt = Buffer.from("000102030405060708090a0b0c0d0e0f", "hex");
const out: any = { saltB64: salt.toString("base64") };
const cases = [
  { pw: "correct horse 电池 staple", sk: "U1-ABCDE-FGHIJ-KLMNO-PQRST-UVWXY-Z" },
  { pw: "hunter2!", sk: "u1-7q2zk-mn3ab-cd4ef-gh5ij-kl6mn-o" },
  { pw: "Café́", sk: "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" },
];
out.cases = cases.map(({ pw, sk }) => {
  const auk = c.deriveAUK(pw, sk, salt, "pbkdf2");
  const ah = c.authHash(auk, salt);
  return { pw, sk, skDecoded: c.decodeSecretKey(sk).toString("hex"), auk: auk.toString("hex"), authHash: ah, verifier: c.verifierOf(ah) };
});
// 一份完整 record：PC 加密快照 + VaultKey
const auk0 = Buffer.from(out.cases[0].auk, "hex");
const vk = Buffer.from("aa".repeat(32), "hex");
const snap = { v: 1, vaults: [{ id: "v1", name: "我自己", owner: "self", icon: "🧑", order: 0, keyWrapped: c.wrapKey(auk0, vk) }],
  data: { v1: { types: [], items: [{ id: "i1", typeId: "", title: "GitHub", blocks: [], attachments: [], createdAt: 1700000000000, updatedAt: 1700000000001, revision: 3,
    fmt: 2, nodes: [{ id: "n1", kind: "acct", x: 40, y: 40, w: 280, h: 240, title: "账号", data: { user: "alice", pwd: "p@ss", seed: "", url: "https://github.com", note: "" }, set: {}, zz: 1 }], edges: [] }], attachments: {} } } };
const enc = c.aesEncrypt(auk0, Buffer.from(JSON.stringify(snap), "utf8"));
out.record = JSON.stringify({ v: 1, kdf: "pbkdf2", salt: salt.toString("base64"), verifier: out.cases[0].verifier, enc });
out.snapshotJson = JSON.stringify(snap);
out.vkHex = vk.toString("hex");
out.attBlobB64 = c.aesEncryptBytes(vk, Buffer.from("hello 附件", "utf8")).toString("base64");
// 固定 iv 的加密（证明 Kotlin 拼出来的 v1:iv:tag:ct 与 node 一致）
const iv = Buffer.from("0102030405060708090a0b0c", "hex");
const ci = nc.createCipheriv("aes-256-gcm", auk0, iv);
const ct = Buffer.concat([ci.update(Buffer.from("固定 iv 明文", "utf8")), ci.final()]);
out.fixedIv = { ivHex: iv.toString("hex"), plain: "固定 iv 明文", blob: `v1:${iv.toString("base64")}:${ci.getAuthTag().toString("base64")}:${ct.toString("base64")}` };
// Kotlin 加密后 PC 能解：留一个检查入口（读 argv）
if (process.argv[2]) { const [k, b] = process.argv.slice(2); console.log(c.aesDecrypt(Buffer.from(k, "hex"), b).toString("utf8")); process.exit(0); }
console.log(JSON.stringify(out, null, 1));
