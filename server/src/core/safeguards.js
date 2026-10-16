'use strict';
/**
 * 风控与稳定性辅助：内存缓存、节流、风控识别、设备身份池。
 * 对应原 Java core/Safeguards.java（其本身移植自 server/src/safeguards.js）。
 *
 * 缓存要点：访问序 LRU + 条数与字节双上限 + 定时清扫过期条目 ——
 * 让 6 小时 TTL 的 episodes/vmtracks 缓存真正能被回收。
 */
const crypto = require('crypto');
const Json = require('../util/json');
const Config = require('../util/config');
const Log = require('../util/log');

// ==================== 缓存 ====================

/** key -> { val, expire, weight }；Map 本身按插入序，get 时重插实现 LRU。 */
const MEM = new Map();

let memBytes = 0;

/** 条数上限：HG_MEM_CACHE_MAX，默认 4096。 */
const MAX_ENTRIES = Config.num('runtime.cache.mem_max', 4096);

/** 字节上限：HG_MEM_CACHE_MAX_MB，默认 128MB。 */
const MAX_BYTES = Config.num('runtime.cache.mem_max_mb', 128) * 1024 * 1024;

/** 清扫周期：HG_SWEEP_INTERVAL_MS，默认 60s。 */
const SWEEP_INTERVAL_MS = Config.num('runtime.sweep_interval_ms', 60) * 1000;

/** 清扫所有过期条目。 */
function sweep() {
  const now = Date.now();
  for (const [key, e] of MEM) {
    if (e.expire !== 0 && e.expire < now) {
      MEM.delete(key);
      memBytes -= e.weight;
    }
  }
}

/** 裁剪到上限内（按 LRU 顺序从头淘汰）。 */
function trim() {
  while ((MEM.size > MAX_ENTRIES || memBytes > MAX_BYTES) && MEM.size > 0) {
    const first = MEM.keys().next().value;
    const e = MEM.get(first);
    MEM.delete(first);
    memBytes -= e.weight;
  }
}

/** 拼接缓存键（对齐 JS 的 parts.map(String).join(':')）。 */
function cacheKey(...parts) {
  return parts.map(stringifyPart).join(':');
}

function stringifyPart(p) {
  if (p === null || p === undefined) return 'null';
  if (typeof p === 'number') {
    if (Number.isInteger(p) && Math.abs(p) < 9.007199254740992e15) return String(p);
    return String(p);
  }
  return String(p);
}

function cacheGet(key) {
  const hit = MEM.get(key);
  if (hit === undefined) return null;
  if (hit.expire !== 0 && hit.expire < Date.now()) {
    MEM.delete(key);
    memBytes -= hit.weight;
    return null;
  }
  // 访问序 LRU：删除后重插到末尾
  MEM.delete(key);
  MEM.set(key, hit);
  return hit.val;
}

/** 缓存写入。ttl 单位为秒，<=0 表示不缓存。 */
function cacheSet(key, val, ttlSec) {
  if (ttlSec <= 0) return;
  const expire = Date.now() + ttlSec * 1000;
  const weight = weigh(val);
  const old = MEM.get(key);
  if (old !== undefined) {
    MEM.delete(key);
    memBytes -= old.weight;
  }
  MEM.set(key, { val, expire, weight });
  memBytes += weight;
  trim();
}

/**
 * 估算缓存值的近似字节数（量级估算即可）。带深度上限，防止自引用结构栈溢出。
 */
function weigh(v, depth = 0) {
  if (v === null || v === undefined) return depth === 0 ? 0 : 4;
  try {
    if (depth > 4) return 64;
    if (typeof v === 'string') return 40 + v.length * 2;
    if (Buffer.isBuffer(v) || v instanceof Uint8Array) return 16 + v.length;
    if (typeof v === 'number' || typeof v === 'boolean') return 16;
    if (Array.isArray(v)) {
      let sum = 48;
      for (const o of v) {
        sum += 16 + weigh(o, depth + 1);
        if (sum > (1 << 20)) return sum;
      }
      return sum;
    }
    if (typeof v === 'object') {
      let sum = 48;
      for (const k of Object.keys(v)) {
        sum += 48 + weigh(k, depth + 1) + weigh(v[k], depth + 1);
        if (sum > (1 << 20)) return sum;
      }
      return sum;
    }
    return 64;
  } catch (e) {
    return 256;
  }
}

/** 后台清扫：Node 用 unref 的定时器，不阻止进程退出。 */
const sweeperTimer = setInterval(() => {
  try { sweep(); } catch (e) { /* 清扫失败不应影响服务 */ }
}, SWEEP_INTERVAL_MS);
if (sweeperTimer && typeof sweeperTimer.unref === 'function') sweeperTimer.unref();

/** 清扫器是否已启用（自检用）。 */
function sweeperActive() { return sweeperTimer !== null; }

// ==================== 睡眠 ====================

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// ==================== 节流 ====================

/**
 * 串行化节流：保证任意两次外部 API 调用间隔 >= minIntervalMs。
 * 用 Promise 链串行化，对齐原 JS 实现。
 */
class Throttle {
  constructor() {
    this.minIntervalMs = Config.num('runtime.throttle_ms', 260);
    this.last = 0;
    this.chain = Promise.resolve();
  }

  wait() {
    const run = async () => {
      const now = Date.now();
      const gap = now - this.last;
      if (gap < this.minIntervalMs) await sleep(this.minIntervalMs - gap);
      this.last = Date.now();
    };
    this.chain = this.chain.then(run, run);
    return this.chain;
  }
}

const THROTTLE = new Throttle();

function throttle() { return THROTTLE; }

// ==================== 风控识别 ====================

const AUTH_CODES = new Set([401, 403, 8, 1001]);
const AUTH_KEYWORDS = ['token', '登录', 'login', '未登录', 'not login', 'unauthor'];
const RISK_CODES = new Set([429, 110001, 110002, 110003]);
const RISK_KEYWORDS = ['verify', 'captcha', 'risk', '频繁', '稍后', '验证', 'rate limit', 'too many'];

/** 风控 / 登录态失效异常。 */
class ApiError extends Error {
  constructor(msg) {
    super(msg);
    this.name = 'ApiError';
    this.safeResponse = null;
    this.diagnostic = null;
    this.modelShape = null;
  }
}

class RiskControlError extends ApiError {
  constructor(msg) { super(msg); this.name = 'RiskControlError'; }
}

class AuthExpiredError extends ApiError {
  constructor(msg) { super(msg); this.name = 'AuthExpiredError'; }
}

class UpstreamResponseError extends ApiError {
  constructor(msg) { super(msg); this.name = 'UpstreamResponseError'; }
}

/** 检查上游业务响应，命中风控/登录态失效则抛对应错误（附安全诊断信息）。 */
function checkResponse(j) {
  if (!j || typeof j !== 'object') return;
  const code = (typeof j.code === 'number') ? j.code : null;
  const raw = (j.message !== undefined && j.message !== null) ? j.message : j.msg;
  const msg = Json.optStr(raw, '').toLowerCase();

  const knownRisk = code !== null && RISK_CODES.has(code);
  let riskKeyword = false;
  for (const w of RISK_KEYWORDS) if (msg.includes(w)) { riskKeyword = true; break; }
  if (knownRisk || (code !== null && code !== 0 && riskKeyword)) {
    const err = new RiskControlError(`风控/限流 (code=${code})`);
    err.safeResponse = { code, known_risk_code: knownRisk, risk_keyword: riskKeyword };
    throw err;
  }

  const knownAuth = code !== null && AUTH_CODES.has(code);
  let authKeyword = false;
  for (const w of AUTH_KEYWORDS) if (msg.includes(w)) { authKeyword = true; break; }
  if (knownAuth || (code !== null && code !== 0 && authKeyword)) {
    const err = new AuthExpiredError(`登录态失效 (code=${code})`);
    err.safeResponse = { code, known_auth_code: knownAuth, auth_keyword: authKeyword };
    throw err;
  }
}

// ==================== 设备身份池 ====================

const UA_TEMPLATES = [
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36',
];

/** 池中的一个设备身份。 */
class PoolDevice {
  constructor(query, userAgent) {
    this.query = query;
    this.userAgent = userAgent;
    this.user_agent = userAgent; // 兼容旧字段名
  }
}

/** 设备身份池。 */
class DevicePool {
  constructor(devices) {
    this.devices = devices;
    this.idx = 0;
  }

  current() { return this.devices[this.idx % this.devices.length]; }
}

/** [0, n) 的随机长整数（对应 randomBytes(8).readBigUInt64BE() % n）。 */
function rnd(n) {
  return crypto.randomBytes(8).readBigUInt64BE() % BigInt(n);
}

/**
 * 设备身份池：分散 device_id / cdid，降低风控。
 * 支持两种调用方式：
 *   loadDevicePool(baseQuery, size, managed, baseDeviceId)
 *   loadDevicePool(baseQuery, { size, managed, baseDeviceId })
 */
function loadDevicePool(baseQuery, a, b, c) {
  let size;
  let managed;
  let baseDeviceId;
  if (a !== null && typeof a === 'object') {
    size = a.size;
    managed = a.managed;
    baseDeviceId = a.baseDeviceId;
  } else {
    size = a;
    managed = b;
    baseDeviceId = c;
  }
  if (managed || !size || size <= 0) return null;
  const devices = [];
  for (let i = 0; i < size; i++) {
    const q = {};
    q.device_id = String(rnd(9000000000000000n) + 1000000000000000n);
    q.cdid = String(rnd(9000000000000000n) + 1000000000000000n);
    q.device_type = 'audit-windows';
    devices.push(new PoolDevice(q, UA_TEMPLATES[i % UA_TEMPLATES.length]));
  }
  if (size === 1 && baseDeviceId) devices[0].query.device_id = baseDeviceId;
  return new DevicePool(devices);
}

module.exports = {
  cacheKey, cacheGet, cacheSet, weigh, sweep, sweeperActive,
  sleep, Throttle, throttle,
  ApiError, RiskControlError, AuthExpiredError, UpstreamResponseError, checkResponse,
  RISK_CODES, AUTH_CODES, PoolDevice, DevicePool, loadDevicePool,
};
