package com.hongguo.api.core;

import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Json;
import com.hongguo.api.util.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地链路密钥管理。
 *
 * 设计：数据接口强制要求有效密钥（x-api-key 头或 ?api_key= 查询参数），
 * 管理接口另用 ADMIN_TOKEN 口令校验，两者分离。
 */
public final class KeyStore {

    private static final SecureRandom RND = new SecureRandom();

    /**
     * 进程内单例：密钥与设备身份都持久化在这一个文件里，且每次保存都是
     * 全量重写。若各处各自 new KeyStore，后保存的一方会拿自己的缓存覆盖
     * 另一方刚写进去的字段（典型如 Device 写 device_id、Server 随后
     * 保存 admin_token，把 device_id 又抹掉）。因此统一走单例。
     */
    private static KeyStore INSTANCE;

    /** 取得（必要时懒加载）进程内唯一的 KeyStore。 */
    public static synchronized KeyStore store() {
        if (INSTANCE == null) INSTANCE = new KeyStore();
        return INSTANCE;
    }

    /** 持久化文件路径（自检与诊断用）。 */
    public Path storeFile() {
        return file;
    }

    private final Path file;
    /** key -> 记录。 */
    private final Map<String, Rec> keys = new LinkedHashMap<>();
    /** 管理口令（/admin/* 与 /stats 用）；空表示尚未生成。 */
    private String adminToken = "";
    /** 本次启动是否由 ensureAdminToken() 新生成了口令（供控制台决定是否提示）。 */
    private boolean adminTokenFresh = false;
    /** 设备身份（device_id）；空表示文件里还没有，见 {@link #setDeviceId}。 */
    private String deviceId = "";

    /** 密钥记录。 */
    public static final class Rec {
        public String key = "";
        public String note = "";
        public boolean enabled = true;
        public String createdAt = "";

        Map<String, Object> toMap() {
            Map<String, Object> m = Json.obj();
            m.put("key", key);
            m.put("note", note);
            m.put("enabled", enabled);
            m.put("createdAt", createdAt);
            return m;
        }
    }

    public KeyStore() {
        // 固定位于 server/config（与 config.json 同目录）：密钥与设备身份属于
        // 长期状态，不随 runtime.data_dir（只影响视频缓存）迁移。
        this.file = Paths.get("server", "config", "apikeys.json");
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        try {
            if (!Files.exists(file)) return;
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Object v = Json.parse(raw);
            Map<String, Object> root = Json.optObj(v);
            if (root == null) return;
            this.adminToken = Json.optStr(root.get("admin_token"), "").trim();
            this.deviceId = Json.optStr(root.get("device_id"), "").trim();
            for (Object rowObj : Json.optArr(root.get("keys"))) {
                Map<String, Object> row = Json.optObj(rowObj);
                if (row == null) continue;
                String k = Json.optStr(row.get("key"), "");
                if (k.isEmpty()) continue;
                Rec r = new Rec();
                r.key = k;
                r.note = Json.optStr(row.get("note"), "");
                r.enabled = Json.optBool(row.get("enabled"), true);
                r.createdAt = Json.optStr(row.get("createdAt"), "");
                keys.put(k, r);
            }
        } catch (Exception e) {
            Log.warn("读取密钥文件失败，使用空集合: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            List<Object> rows = new ArrayList<>();
            for (Rec r : keys.values()) rows.add(r.toMap());
            Map<String, Object> out = Json.obj();
            out.put("keys", rows);
            // 只在已生成/已读取到管理口令时落盘：没解析过口令就写字段，
            // 会在用户尚未用到管理接口时往文件里塞一个从未示人的值。
            if (!adminToken.isEmpty()) out.put("admin_token", adminToken);
            // device_id 同理：只有显式设置过才写，避免无谓字段
            if (!deviceId.isEmpty()) out.put("device_id", deviceId);
            Files.write(file, Json.stringifyPretty(out).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.warn("写入密钥文件失败（仅内存生效）: " + e.getMessage());
        }
    }

    /** 首次启动时若无任何密钥，自动签发一把本地密钥，避免用户手动配置。 */
    public String ensureBootstrap() {
        if (!keys.isEmpty()) return null;
        return generate("bootstrap");
    }

    /**
     * 取管理口令：文件里已有则原样返回，没有则生成一个并写回 apikeys.json。
     *
     * <p>与密钥的 bootstrap 同思路——早期版本每次启动随机生成、只打在控制台，
     * 重启即失效，脚本化的管理调用（列密钥、吊销）拿不到稳定口令。
     * 现在以文件为准：生成一次，之后各次启动都读同一个值。
     * 环境变量 {@code ADMIN_TOKEN} 优先级更高（Server 侧先问环境变量），
     * 设置时不会改动文件里的值。
     *
     * <p>新生成时 {@link #adminTokenFresh()} 返回 true，控制台据此决定
     * 是否提示口令；从文件读到时返回 false，不再重复打印。
     */
    public String ensureAdminToken() {
        if (!adminToken.isEmpty()) return adminToken;
        byte[] raw = new byte[32];
        RND.nextBytes(raw);
        adminToken = Crypto.hex(raw);
        adminTokenFresh = true;
        save();
        return adminToken;
    }

    /** 本次启动的管理口令是否为新生成（文件里原本没有）。 */
    public boolean adminTokenFresh() {
        return adminTokenFresh;
    }

    /**
     * 文件里持久化的设备身份；没有则空串。
     *
     * <p>device_id 与密钥同住本文件：两者都是「本机长期状态」，且都必须
     * 留在 git/发行包之外（身份进 git 会让所有 clone 者共用同一设备指纹）。
     * 早期 device_id 单独放在 {@code device.json}，与 config.json 的
     * {@code runtime.device_id} 配置项形成双入口，1.0.7 起并入此处。
     */
    public String deviceId() {
        return deviceId;
    }

    /**
     * 写入设备身份并立即落盘（供 {@code Device} 首次生成时调用）。
     * 空值忽略——擦除身份没有正当用途，只会让下次启动重新生成。
     */
    public void setDeviceId(String id) {
        if (id == null || id.isEmpty()) return;
        deviceId = id.trim();
        save();
    }

    public String generate(String note) {
        byte[] raw = new byte[24];
        RND.nextBytes(raw);
        String key = "hg_" + Crypto.hex(raw);
        Rec r = new Rec();
        r.key = key;
        r.note = note == null ? "" : note;
        r.enabled = true;
        r.createdAt = Instant.now().toString();
        keys.put(key, r);
        save();
        return key;
    }

    public boolean isValid(String key) {
        if (key == null || key.isEmpty()) return false;
        Rec r = keys.get(key);
        return r != null && r.enabled;
    }

    public List<Object> list() {
        List<Object> out = new ArrayList<>();
        for (Rec r : keys.values()) out.add(r.toMap());
        return out;
    }

    /** 找出第一把启用的密钥（自动签发接口用）。 */
    public Rec firstEnabled() {
        for (Rec r : keys.values()) if (r.enabled) return r;
        return null;
    }

    public Rec get(String key) {
        return key == null ? null : keys.get(key);
    }

    public int countEnabled() {
        int n = 0;
        for (Rec r : keys.values()) if (r.enabled) n++;
        return n;
    }

    /** 撤销或启用一把密钥。 */
    public boolean revoke(String key, boolean enable) {
        Rec r = key == null ? null : keys.get(key);
        if (r == null) return false;
        r.enabled = enable;
        save();
        return true;
    }

    public boolean delete(String key) {
        if (key == null) return false;
        boolean ok = keys.remove(key) != null;
        if (ok) save();
        return ok;
    }
}
