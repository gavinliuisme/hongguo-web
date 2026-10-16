'use strict';
/**
 * 统一日志输出与环境变量读取（对应原 Java util/Log.java）。
 * 输出格式保持与原实现一致，便于用户对照排查：yyyy-MM-dd HH:mm:ss [INFO|WARN] msg
 */

function two(n) { return String(n).padStart(2, '0'); }

function ts() {
  const d = new Date();
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())} `
    + `${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`;
}

function write(stream, msg, isWarn) {
  stream.write(`${ts()} [${isWarn ? 'WARN' : 'INFO'}] ${msg}\n`);
}

function info(msg) { write(process.stdout, msg, false); }
function warn(msg) { write(process.stdout, msg, true); }
function error(msg, t) {
  write(process.stderr, msg, true);
  if (t) {
    if (t.stack) process.stderr.write(t.stack + '\n');
    else process.stderr.write(String(t) + '\n');
  }
}

/**
 * 读取配置项：环境变量优先，空字符串视为未设置（对齐 JS 的 process.env.X || default）。
 *
 * 原 Java 版还额外查系统属性；Node 侧统一走环境变量，语义等价
 * （自检里原本用 System.setProperty 传递的值，Node 版改用 process.env）。
 */
function env(name, def = null) {
  const v = process.env[name];
  if (v === undefined || v === null) return def;
  const t = String(v).trim();
  return t === '' ? def : t;
}

function envInt(name, def = 0) {
  const v = env(name, null);
  if (v === null) return def;
  const n = parseInt(v, 10);
  return Number.isNaN(n) ? def : n;
}

/** 兼容原 Java 接口：Node 下控制台统一 UTF-8，无需处理代码页。 */
function initEncoding() { /* no-op：Node 输出统一 UTF-8 */ }

module.exports = { env, envInt, info, warn, error, initEncoding };
