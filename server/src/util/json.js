'use strict';
/**
 * 轻量 JSON 语义层（对齐原 Java util/Json.java 与 JavaScript 原生 JSON）。
 *
 * Node 自带 JSON 解析/序列化，这里只补齐两处 Java 侧才需要的语义：
 *  1. optObj / optStr / optLong ... 的「安全取值」——键不存在返回默认值。
 *  2. stringify(v, skipNulls) 中 skipNulls 模拟 JS 中 undefined 被省略的行为
 *     （Java 侧用它把 null 字段省略）。
 *
 * 数字语义直接交给原生：整数不带小数点，1.0 也输出为 1，与 Java 实现一致。
 */

class JsonException extends Error {
  constructor(msg) {
    super(msg);
    this.name = 'JsonException';
  }
}

/**
 * 解析 JSON 文本；非法输入抛 JsonException。null/undefined 返回 null。
 *
 * 保精度处理：原生 JSON.parse 把超过 2^53 的整数字面量读成双精度浮点，
 * 末位失真 —— 上游 series_id / vid 均为 19 位（如 7696409867476552729
 * 会变成 7696409867476553000），随后按此 ID 查上游一律空结果，表现为
 * 「该剧暂无剧集」、播放取不到直链。故先把字符串字面量之外的超大整数
 * 加引号，让它们以字符串形态进入下游；全链路对 ID 的消费（optStr /
 * String()）本就按字符串处理，语义不变，小数与科学计数法原样保留。
 */
function parse(text) {
  if (text === null || text === undefined) return null;
  try {
    return JSON.parse(/\d{16,}/.test(text) ? quoteBigInts(text) : text);
  } catch (e) {
    throw new JsonException(e.message);
  }
}

/**
 * 扫描 JSON 文本，把字符串字面量之外的「纯整数字面量且超出
 * Number.MAX_SAFE_INTEGER」加上引号（实现见 parse 注释）。
 * 只做单次线性扫描；调用方已用 \d{16,} 预检，常规响应不走这里。
 */
function quoteBigInts(text) {
  let out = '';
  let i = 0;
  const n = text.length;
  let inStr = false;
  while (i < n) {
    const c = text[i];
    if (inStr) {
      out += c;
      if (c === '\\' && i + 1 < n) {
        // 转义序列连同下一字符一起吞掉，避免 \" 提前结束字符串
        out += text[i + 1];
        i += 2;
        continue;
      }
      if (c === '"') inStr = false;
      i++;
      continue;
    }
    if (c === '"') {
      inStr = true;
      out += c;
      i++;
      continue;
    }
    // 数字 token 起点：数字，或值位置的负号（JSON 里 - 只出现在值位置）。
    // 整数、小数、指数属于同一 token，先整体扫完再判断；只对「纯整数
    // 且超出安全范围」的加引号，小数 / 科学计数法原样，不引入语义变化。
    if (c === '-' || (c >= '0' && c <= '9')) {
      let j = i;
      if (c === '-') j++;
      const intStart = j;
      while (j < n && text.charCodeAt(j) >= 48 && text.charCodeAt(j) <= 57) j++;
      const intEnd = j;
      if (text[j] === '.') {
        j++;
        while (j < n && text.charCodeAt(j) >= 48 && text.charCodeAt(j) <= 57) j++;
      }
      if (text[j] === 'e' || text[j] === 'E') {
        j++;
        if (text[j] === '+' || text[j] === '-') j++;
        while (j < n && text.charCodeAt(j) >= 48 && text.charCodeAt(j) <= 57) j++;
      }
      const isPureInt = intEnd === j;
      if (isPureInt && intEnd > intStart
          && Math.abs(Number(text.slice(intStart, intEnd))) > Number.MAX_SAFE_INTEGER) {
        out += '"' + text.slice(i, j) + '"';
      } else {
        out += text.slice(i, j);
      }
      i = j;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

/** 解析并要求顶层是对象；空串返回空对象（宽容上游空体）。 */
function parseObject(text) {
  if (text === null || text === undefined) return {};
  if (typeof text === 'string' && text.trim() === '') return {};
  const v = parse(text);
  if (v === null || v === undefined) return {};
  if (typeof v !== 'object' || Array.isArray(v)) {
    throw new JsonException('期望 JSON 对象，实际是 ' + typeName(v));
  }
  return v;
}

/** 递归去掉对象里的 null/undefined 字段（数组元素原样保留）。 */
function stripNulls(v) {
  if (Array.isArray(v)) return v.map(stripNulls);
  if (v && typeof v === 'object') {
    const out = {};
    for (const k of Object.keys(v)) {
      if (v[k] === null || v[k] === undefined) continue;
      out[k] = stripNulls(v[k]);
    }
    return out;
  }
  return v;
}

/** 序列化为 JSON 文本；skipNulls 为 true 时省略值为 null 的字段。 */
function stringify(v, skipNulls = false) {
  return JSON.stringify(skipNulls ? stripNulls(v) : v);
}

/** 美化输出（仅用于日志与落盘）。 */
function stringifyPretty(v) {
  return JSON.stringify(v, null, 2);
}

function typeName(v) {
  if (v === null || v === undefined) return 'null';
  if (Array.isArray(v)) return 'array';
  if (typeof v === 'object') return 'object';
  return typeof v;
}

/** 取对象；不存在或非对象返回 null。 */
function optObj(v) {
  return (v && typeof v === 'object' && !Array.isArray(v)) ? v : null;
}

/** 取数组；不存在或非数组返回空数组。 */
function optArr(v) {
  return Array.isArray(v) ? v : [];
}

/** 取字符串；null/undefined 返回 def，数字/布尔按 JS 隐式转换。 */
function optStr(v, def = '') {
  if (v === null || v === undefined) return def;
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return def;
}

/** 取整数；非数字返回 def。 */
function optLong(v, def = 0) {
  if (typeof v === 'number') return Math.trunc(v);
  if (typeof v === 'string') {
    const n = parseInt(v.trim(), 10);
    return Number.isNaN(n) ? def : n;
  }
  if (typeof v === 'boolean') return v ? 1 : 0;
  return def;
}

function optInt(v, def = 0) {
  return optLong(v, def);
}

/** 取浮点；整型值也会正确转换。 */
function optDouble(v, def = 0) {
  if (typeof v === 'number') return v;
  if (typeof v === 'string') {
    const n = parseFloat(v.trim());
    return Number.isNaN(n) ? def : n;
  }
  return def;
}

/** 取布尔：true/1/yes/on 为真，false/0/no/off 为假。 */
function optBool(v, def = false) {
  if (typeof v === 'boolean') return v;
  if (typeof v === 'number') return v !== 0;
  if (typeof v === 'string') {
    const s = v.trim().toLowerCase();
    if (s === 'true' || s === '1') return true;
    if (s === 'false' || s === '0') return false;
  }
  return def;
}

function obj() { return {}; }
function arr() { return []; }

module.exports = {
  JsonException,
  parse, parseObject, stringify, stringifyPretty, typeName,
  optObj, optArr, optStr, optLong, optInt, optDouble, optBool,
  obj, arr,
};
