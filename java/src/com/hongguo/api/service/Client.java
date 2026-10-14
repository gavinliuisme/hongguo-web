package com.hongguo.api.service;

import com.hongguo.api.core.Device;
import com.hongguo.api.core.PureSign;
import com.hongguo.api.core.Safeguards;
import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Http;
import com.hongguo.api.util.Json;
import com.hongguo.api.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 红果短剧 API 客户端。
 *
 * 移植自 server/src/client.js。保留原有语义：设备身份池、_rticket 时间戳、
 * x-ss-stub MD5、列表类接口免签、剧集详情缓存、风控/登录态退避重试。
 *
 * 与 Node 版的两处架构变化：
 *  - 签名不再走外部 unidbg 服务，而是本进程内 PureSign 纯代码计算
 *    （branch==1 时抖动 _rticket 规避，实测服务端返回 code=0）；
 *  - 搜索与分集详情在 App 接口之外增加官网数据源（WebSource）：
 *    搜索官网优先（App 搜索需登录态，未登录恒空），分集详情 App 优先、
 *    失败回落官网。
 */
public final class Client {

    private Client() {}

    // ==================== 配置装载 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfig() {
        List<String> candidates = new ArrayList<>();
        String env = Log.env("HONGGUO_CONTENT_CONFIG", null);
        if (env != null) candidates.add(env);
        candidates.add(Paths.get("server", "config", "content-config.json").toString());

        for (String c : candidates) {
            try {
                Path p = Paths.get(c);
                if (!Files.exists(p)) continue;
                String raw = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                if (!raw.isEmpty() && raw.charAt(0) == '﻿') raw = raw.substring(1);
                return Json.parseObject(raw);
            } catch (Exception e) {
                // 尝试下一个
            }
        }
        throw new RuntimeException("未找到内容配置（content-config.json）");
    }

    private static final Map<String, Object> CFG = loadConfig();

    public static final String HOST = Log.env("HONGGUO_HOST", Json.optStr(CFG.get("api_host"), ""));

    @SuppressWarnings("unchecked")
    public static Map<String, Object> baseQuery() {
        Map<String, Object> q = Json.optObj(CFG.get("base_query"));
        return q != null ? q : Json.obj();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> sessionHeaders() {
        Map<String, Object> h = Json.optObj(CFG.get("session_headers"));
        Map<String, String> out = new LinkedHashMap<>();
        if (h != null) {
            for (Map.Entry<String, Object> e : h.entrySet()) {
                if (e.getValue() != null) out.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    // search/tab/v 等接口校验 query 里的 device_id，缺失一律返回 code=100103
    // PARAM_INVALID。这里补齐一个本机稳定且持久化的值。
    private static final Map<String, Object> BASE_QUERY = baseQuery();
    private static final Device.Identity IDENTITY = Device.ensureDeviceId(BASE_QUERY);

    /** 桌面托管模式标志：存在则禁止刷新登录态、禁止降级容错。 */
    public static final boolean MANAGED =
            Log.env("HONGGUO_SESSION_API_KEY", null) != null;

    /** 仅 landpage/cell 系列列表接口免签（原 SIGN_LIST 语义）。 */
    public static final boolean SIGN_LIST = Arrays.asList("1", "true", "yes", "on")
            .contains(Log.env("HG_SIGN_LIST", "").toLowerCase());

    private static final String USER_AGENT = Log.env("HONGGUO_UA",
            sessionHeaders().getOrDefault("user-agent", "hongguo-web/1.0"));

    /** 设备身份池（DEVICE_POOL_SIZE > 0 时启用；托管模式固定单设备）。 */
    private static final Safeguards.DevicePool POOL = Safeguards.loadDevicePool(
            BASE_QUERY,
            Log.envInt("DEVICE_POOL_SIZE", 0),
            MANAGED,
            IDENTITY.deviceId);

    private static Safeguards.PoolDevice currentDevice() {
        return POOL == null ? null : POOL.current();
    }

    public static Safeguards.PoolDevice rotateDevice() {
        return POOL == null ? null : POOL.rotate();
    }

    public static Device.Identity identity() { return IDENTITY; }

    /**
     * vid -> series_id 反查表：播放链路据此判断某集能否走官网明文直链
     * （/stream?vid= 只带 vid，不带剧 id，而官网直链页需要两者）。
     */
    private static final Map<String, String> SID_OF_VID = new ConcurrentHashMap<>();

    /** 记录 vid 所属剧集。 */
    static void rememberVid(String vid, String sid) {
        if (vid == null || vid.isEmpty() || sid == null || sid.isEmpty()) return;
        // 有界：看过的剧多了之后整体清空重建，避免长跑进程无限增长
        if (SID_OF_VID.size() > 8192) SID_OF_VID.clear();
        SID_OF_VID.put(vid, sid);
    }

    /** 反查 vid 所属剧集；未知返回 null。 */
    public static String seriesIdOfVid(String vid) {
        return vid == null ? null : SID_OF_VID.get(vid);
    }

    // ==================== 榜单 / 体裁常量 ====================

    public static final Map<String, String> RANK_BOARDS = new LinkedHashMap<>();
    public static final Map<String, String> RANK_NAMES = new LinkedHashMap<>();
    static {
        RANK_BOARDS.put("recommend", "comic_series_hot_rank");
        RANK_BOARDS.put("hot", "comic_series_hot_play");
        RANK_BOARDS.put("new", "comic_series_new_rank");
        RANK_NAMES.put("recommend", "漫剧推荐榜");
        RANK_NAMES.put("hot", "漫剧热播榜");
        RANK_NAMES.put("new", "漫剧新剧榜");
    }

    public static final String COMIC_RANK_CELL = "7470092475068071998";

    /** genre -> [req_scene, genre] */
    public static final Map<String, String[]> GENRES = new LinkedHashMap<>();
    public static final Map<String, String> GENRE_NAMES = new LinkedHashMap<>();
    static {
        GENRES.put("short_play", new String[]{"default", "short_play"});
        GENRES.put("comic_series", new String[]{"comic_series", "comic_series"});
        GENRES.put("ai_series", new String[]{"ai_series", "ai_series"});
        GENRE_NAMES.put("short_play", "短剧");
        GENRE_NAMES.put("comic_series", "漫剧");
        GENRE_NAMES.put("ai_series", "AI短剧");
    }

    // 主题 / 设定 / 背景 共用 cate_ 命名空间
    private static final Map<String, String> FILTER_CATE = new LinkedHashMap<>();
    private static final Map<String, String> FILTER_SORT = new LinkedHashMap<>();
    private static final Map<String, String> FILTER_GENDER = new LinkedHashMap<>();
    private static final Map<String, String> FILTER_DAYS = new LinkedHashMap<>();
    private static final Map<String, String> FILTER_STATUS = new LinkedHashMap<>();
    static {
        String[][] cate = {
            {"脑洞", "cate_755"}, {"奇幻", "cate_6"}, {"剧情", "cate_316"}, {"玄幻", "cate_7"},
            {"末世", "cate_68"}, {"豪门", "cate_936"}, {"科幻", "cate_1092"}, {"冒险", "cate_1182"},
            {"重生", "cate_36"}, {"穿越", "cate_37"}, {"逆袭", "cate_739"}, {"异能", "cate_598"},
            {"系统", "cate_19"}, {"反转", "cate_756"}, {"娱乐圈", "cate_43"}, {"总裁", "cate_29"},
            {"架空", "cate_452"}, {"都市", "cate_1"}, {"古代", "cate_758"}, {"异界", "cate_599"},
            {"校园", "cate_4"}, {"职场", "cate_127"}, {"年代", "cate_79"}, {"乡村", "cate_11"},
            {"民国", "cate_390"},
        };
        for (String[] c : cate) FILTER_CATE.put(c[0], c[1]);

        FILTER_SORT.put("最新上架", "online_time");
        FILTER_SORT.put("最新", "online_time");
        FILTER_SORT.put("最高热度", "hot_score");
        FILTER_SORT.put("热度", "hot_score");
        FILTER_SORT.put("hot", "hot_score");
        FILTER_SORT.put("最高收藏", "hot_collect");
        FILTER_SORT.put("收藏", "hot_collect");

        FILTER_GENDER.put("男频", "1");
        FILTER_GENDER.put("男", "1");
        FILTER_GENDER.put("女频", "0");
        FILTER_GENDER.put("女", "0");

        FILTER_DAYS.put("7", "days_7");
        FILTER_DAYS.put("14", "days_14");
        FILTER_DAYS.put("30", "days_30");
        FILTER_DAYS.put("90", "days_90");
        FILTER_DAYS.put("7天内上新", "days_7");
        FILTER_DAYS.put("14天内上新", "days_14");
        FILTER_DAYS.put("30天内上新", "days_30");
        FILTER_DAYS.put("90天内上新", "days_90");

        FILTER_STATUS.put("已完结", "creation_status_0");
        FILTER_STATUS.put("完结", "creation_status_0");
        FILTER_STATUS.put("连载中", "creation_status_1");
        FILTER_STATUS.put("连载", "creation_status_1");
    }

    // ==================== 请求组装 ====================

    /** 组装完整 URL（含 base_query、设备身份、_rticket）。 */
    public static String buildUrl(String pathname, Map<String, Object> extra) {
        return buildUrl(pathname, extra, System.currentTimeMillis());
    }

    /**
     * 组装完整 URL，_rticket 由调用方指定。
     *
     * 签名分支 branch==1 时服务端不认，需要抖动 _rticket 后重签重发——
     * 此时 URL 必须用同一个 pathname/extra 重新组装，只有 _rticket 不同。
     */
    public static String buildUrl(String pathname, Map<String, Object> extra, long rticket) {
        Map<String, Object> q = new LinkedHashMap<>(BASE_QUERY);
        Safeguards.PoolDevice dev = currentDevice();
        if (dev != null) q.putAll(dev.query);
        if (extra != null) q.putAll(extra);
        q.put("_rticket", String.valueOf(rticket));

        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, Object> e : q.entrySet()) {
            if (qs.length() > 0) qs.append('&');
            qs.append(e.getKey()).append('=')
              .append(Http.urlencode(String.valueOf(e.getValue())));
        }
        return "https://" + HOST + pathname + "?" + qs;
    }

    /** 构造基础请求头与请求体。 */
    private static Object[] buildHeaders(Object body) {
        Map<String, String> headers = new LinkedHashMap<>(sessionHeaders());
        Safeguards.PoolDevice dev = currentDevice();
        if (dev != null) {
            if (dev.userAgent != null) headers.put("user-agent", dev.userAgent);
            headers.remove("x-tt-token");
            headers.remove("cookie");
        }
        if (!headers.containsKey("user-agent")) headers.put("user-agent", USER_AGENT);
        headers.put("content-type", "application/json; charset=utf-8");

        byte[] data = null;
        if (body != null) {
            data = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
            headers.put("x-ss-stub", Crypto.md5HexUpper(data));
            headers.put("Content-Length", String.valueOf(data.length));
        } else {
            headers.remove("Content-Length");
        }
        return new Object[]{headers, data};
    }

    /** 单次 API 调用（不含重试）。 */
    private static Map<String, Object> apiOnce(String method, String pathname, Object body,
                                               Map<String, Object> extraQuery, boolean signed)
            throws Exception {
        String url = buildUrl(pathname, extraQuery);
        Object[] built = buildHeaders(body);
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) built[0];
        byte[] data = (byte[]) built[1];

        if (signed) {
            // 本地纯代码签名（PureSign，移植自 jackotom/hongguo-drama）。
            // 注意 branch==1 的签名变体服务端不认（实测 110001），
            // 需抖动 _rticket 换一个 query 重签，最多 32 次。
            long khronos = System.currentTimeMillis() / 1000L;
            long rticket = System.currentTimeMillis();
            int guard = 0;
            while (PureSign.branchOf(url, data, khronos) == 1 && guard++ < 32) {
                rticket += 1;
                url = buildUrl(pathname, extraQuery, rticket);
            }
            if (guard >= 32) {
                Log.warn("签名分支抖动 32 次未避开 branch==1，按当前 URL 签名（可能被服务端拒绝）");
            }
            headers.putAll(PureSign.sign(url, data, khronos));
        }
        headers.remove("accept-encoding");
        Safeguards.throttle().wait_();

        Http.Req req = new Http.Req(url).method(method).timeout(30000);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            req.header(e.getKey(), e.getValue());
        }
        if (data != null) req.body(data);

        Http.Resp res = Http.send(req);
        String text = res.text();
        Map<String, Object> j;
        try {
            j = Json.parseObject(text);
        } catch (Exception e) {
            Safeguards.UpstreamResponseError err =
                    new Safeguards.UpstreamResponseError("上游未返回 JSON");
            Map<String, Object> diag = Json.obj();
            diag.put("http_status", res.status);
            diag.put("body_bytes", text.getBytes(StandardCharsets.UTF_8).length);
            err.diagnostic = diag;
            throw err;
        }
        Safeguards.checkResponse(j);
        return j;
    }

    /** 带重试退避 + 登录态刷新的 API 调用。 */
    public static Map<String, Object> api(String method, String pathname, Object body,
                                          Map<String, Object> extraQuery, int maxRetries,
                                          boolean signed) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return apiOnce(method, pathname, body, extraQuery, signed);
            } catch (Safeguards.AuthExpiredError | Safeguards.RiskControlError e) {
                if (MANAGED) throw e; // 托管模式不刷新被抓包的登录态
                last = e;
                if (e instanceof Safeguards.AuthExpiredError) {
                    Log.info("登录态失效，刷新后重试 (" + pathname + ")");
                    refreshSession();
                    Safeguards.sleep(1000);
                } else {
                    int wait = (int) Math.pow(2, attempt) + 1;
                    Log.info("风控/异常，退避 " + wait + "s 重试 (" + pathname + "): " + e.getMessage());
                    Safeguards.sleep(wait * 1000L);
                }
            } catch (Safeguards.UpstreamResponseError e) {
                if (MANAGED) throw e;
                last = e;
                Safeguards.sleep(1500L * (attempt + 1));
            }
        }
        if (last != null) throw last;
        throw new IOException("api 失败");
    }

    private static long lastRefresh = 0;

    /**
     * 刷新设备身份与登录态。
     *
     * 本地纯代码签名模式下本方法为空操作：设备身份由
     * {@link Device#ensureDeviceId} 本机生成并持久化，token 来自
     * content-config.json 的 session_headers，不存在需要从外部
     * 签名服务抓取的状态。保留方法签名是为了让 api() 的重试流程
     * 无需为此分支。
     */
    public static synchronized void refreshSession() {
        if (System.currentTimeMillis() - lastRefresh < 20000) return; // 防抖
        lastRefresh = System.currentTimeMillis();
    }

    // ==================== 数据解析 ====================

    /** 从 category_schema 字符串中解析分类名（源数据为 JSON 字符串）。 */
    public static List<String> parseCategories(Object schema) {
        List<String> out = new ArrayList<>();
        if (schema == null) return out;
        String s = String.valueOf(schema);
        java.util.regex.Matcher rx = java.util.regex.Pattern
                .compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(s);
        while (rx.find()) {
            String m = rx.group(1);
            if (!m.isEmpty() && !out.contains(m)) out.add(m);
        }
        return out;
    }

    /** 提取封面角标文本（爆剧/新剧/独播…）。 */
    public static List<String> coverTags(Object d) {
        List<String> out = new ArrayList<>();
        Map<String, Object> m = Json.optObj(d);
        if (m == null) return out;
        for (Object t : Json.optArr(m.get("cover_tag_info_list"))) {
            Map<String, Object> tm = Json.optObj(t);
            if (tm != null) {
                Object v = firstNonNull(tm, "text", "content", "name", "tag_text", "title");
                if (v != null) out.add(String.valueOf(v).replaceAll("<[^>]+>", ""));
            } else if (t instanceof String && !((String) t).isEmpty()) {
                out.add((String) t);
            }
        }
        return out;
    }

    private static Object firstNonNull(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isEmpty()) return v;
        }
        return null;
    }

    private static String str(Map<String, Object> m, String k) {
        return m == null ? "" : Json.optStr(m.get(k), "");
    }

    private static long num(Map<String, Object> m, String k) {
        return m == null ? 0 : Json.optLong(m.get(k), 0);
    }

    /** 剥 HTML 标签并截断。 */
    private static String strip(String s, int max) {
        String t = s == null ? "" : s.replaceAll("<[^>]+>", "");
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 从搜索 cell 解析短剧条目；非短剧（无集数）或无 id 返回 null。 */
    public static Map<String, Object> parseSearchCell(Object cellObj) {
        Map<String, Object> cell = Json.optObj(cellObj);
        if (cell == null) return null;
        Object sid = cell.get("book_id");
        if (sid == null) sid = cell.get("search_result_id");
        if (sid == null) return null;
        Map<String, Object> vd = Json.optObj(cell.get("video_detail"));
        if (vd == null) vd = Json.obj();

        Object vdataObj = cell.get("video_data");
        if (vdataObj instanceof List && !((List<?>) vdataObj).isEmpty()) {
            vdataObj = ((List<?>) vdataObj).get(0);
        }
        Map<String, Object> vdata = Json.optObj(vdataObj);
        if (vdata == null) vdata = Json.obj();
        Map<String, Object> inner = Json.optObj(vdata.get("video_detail"));
        if (inner == null) inner = Json.obj();

        long ep = num(vd, "episode_cnt");
        if (ep == 0) ep = num(vdata, "episode_cnt");
        if (ep == 0) ep = num(inner, "episode_cnt");
        if (ep == 0) return null; // 只要短剧（有集数的）

        Map<String, Object> hl = Json.optObj(cell.get("search_high_light"));
        String hlText = "";
        if (hl != null) {
            Map<String, Object> t = Json.optObj(hl.get("title"));
            if (t != null) hlText = str(t, "text");
        }
        String title = hlText;
        if (title.isEmpty()) title = str(vd, "series_title");
        if (title.isEmpty()) title = str(vdata, "title");
        if (title.isEmpty()) title = str(inner, "series_title");

        Map<String, Object> out = Json.obj();
        out.put("series_id", String.valueOf(sid));
        out.put("title", strip(title, 9999));
        out.put("episode_cnt", ep);
        out.put("score", vdata.containsKey("score") ? vdata.get("score")
                : (inner.containsKey("score") ? inner.get("score") : ""));
        out.put("play_cnt", vdata.containsKey("play_cnt") ? vdata.get("play_cnt")
                : Json.optLong(inner.get("series_play_cnt"), 0));
        out.put("hot", str(vdata, "rec_text"));
        out.put("copyright", str(vdata, "copyright"));
        String cover = str(vdata, "cover");
        if (cover.isEmpty()) cover = str(inner, "series_cover");
        out.put("cover", cover);
        out.put("tags", coverTags(vdata));
        String intro = !str(inner, "series_intro").isEmpty() ? str(inner, "series_intro")
                : str(vd, "series_intro");
        out.put("abstract", strip(intro, 60));
        out.put("vid", str(vdata, "vid"));
        out.put("duration", num(vdata, "duration"));
        out.put("horiz_cover", str(vdata, "horiz_cover"));
        return out;
    }

    /** 官网搜索结果转为与 {@link #parseSearchCell} 相同的条目结构（未知字段留空）。 */
    private static List<Object> webSearchItems(List<Map<String, Object>> web) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> w : web) {
            Map<String, Object> row = Json.obj();
            row.put("series_id", Json.optStr(w.get("series_id"), ""));
            row.put("title", Json.optStr(w.get("series_title"), ""));
            row.put("episode_cnt", Json.optLong(w.get("episode_cnt"), 0));
            row.put("score", "");
            row.put("play_cnt", 0);
            row.put("hot", "");
            row.put("copyright", "");
            row.put("cover", Json.optStr(w.get("cover"), ""));
            row.put("tags", new ArrayList<>());
            row.put("abstract", strip(Json.optStr(w.get("series_intro"), ""), 60));
            row.put("vid", "");
            row.put("duration", 0);
            row.put("horiz_cover", "");
            out.add(row);
        }
        return out;
    }

    /** 官网分集结果转为与 {@link #parseEpisodeDetail} 相同的结构（官网无简介/演员等字段）。 */
    private static Map<String, Object> webEpisodesToDetail(Map<String, Object> web) {
        String sid = Json.optStr(web.get("series_id"), "");
        List<Object> eps = new ArrayList<>();
        for (Object eObj : Json.optArr(web.get("episodes"))) {
            Map<String, Object> e = Json.optObj(eObj);
            if (e == null) continue;
            String vid = Json.optStr(e.get("vid"), "");
            rememberVid(vid, sid);
            Map<String, Object> row = Json.obj();
            row.put("index", Json.optLong(e.get("vid_index"), 0));
            row.put("vid", vid);
            row.put("title", Json.optStr(e.get("title"), ""));
            row.put("duration", 0);
            row.put("cover", Json.optStr(e.get("cover"), ""));
            row.put("comment_count", 0);
            row.put("digged_count", 0);
            // 官网侧标注：该集是否有网页直链（限免集）。播放链路据此跳过无谓的官网探测
            row.put("web_available", e.get("web_available"));
            eps.add(row);
        }
        Map<String, Object> meta = Json.obj();
        meta.put("series_id", sid);
        String title = Json.optStr(web.get("series_title"), "");
        meta.put("title", title.isEmpty() ? sid : title);
        meta.put("abstract", "");
        meta.put("episode_cnt", Json.optLong(web.get("total"), eps.size()));
        meta.put("status", "");
        meta.put("play_cnt", 0);
        meta.put("followed_cnt", 0);
        meta.put("create_time", 0);
        meta.put("cover", Json.optStr(web.get("cover"), ""));
        meta.put("category", new ArrayList<>());
        meta.put("tags", new ArrayList<>());
        meta.put("celebrities", new ArrayList<>());
        Map<String, Object> out = Json.obj();
        out.put("meta", meta);
        out.put("episodes", eps);
        return out;
    }

    /** 剧集详情请求体（image_shrink 为抓包中的固定值）。 */
    private static final String IMAGE_SHRINK = "W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjkwMCwic2hyaW5rX3R5cGUiOjN9LHsiaW1hZ2VfdHlwZSI6NCwiaW1hZ2Vfd2lkdGgiOjU0LCJzaHJpbmtfdHlwZSI6NH1d";

    private static Map<String, Object> episodesBody(String seriesId) {
        Map<String, Object> biz = Json.obj();
        biz.put("detail_page_version", 0);
        biz.put("disable_digg_stat", false);
        biz.put("image_shrink_datas_str", IMAGE_SHRINK);
        biz.put("need_all_video_definition", false);
        biz.put("need_mp4_align", false);
        biz.put("screen_width_px", "900");
        biz.put("source", 7);
        biz.put("use_os_player", false);
        biz.put("use_server_dns", false);

        Map<String, Object> out = Json.obj();
        out.put("biz_param", biz);
        out.put("dr_scene", "preload");
        out.put("series_id", String.valueOf(seriesId));
        return out;
    }

    /**
     * 解析剧集详情。
     *
     * 注意 title 字段：上游 video_list[].title 里装的其实是「本剧简介」，
     * 每一集都重复同一段文字。这里与 series_intro 比对，命中则视为无效标题。
     */
    public static Map<String, Object> parseEpisodeDetail(String sid, Map<String, Object> vd) {
        if (vd == null) vd = Json.obj();
        String intro = str(vd, "series_intro").trim();
        List<Object> eps = new ArrayList<>();

        for (Object eObj : Json.optArr(vd.get("video_list"))) {
            Map<String, Object> e = Json.optObj(eObj);
            if (e == null) continue;
            String title = str(e, "title").trim();
            if (!title.isEmpty() && !intro.isEmpty()
                    && (title.equals(intro) || intro.contains(title))) {
                title = "";
            }
            Map<String, Object> row = Json.obj();
            row.put("index", num(e, "vid_index"));
            Object v = e.get("vid");
            row.put("vid", v == null ? "" : String.valueOf(v));
            rememberVid(v == null ? "" : String.valueOf(v), sid);
            row.put("title", title.length() > 30 ? title.substring(0, 30) : title);
            row.put("duration", num(e, "duration"));
            String cover = str(e, "episode_cover");
            if (cover.isEmpty()) cover = str(e, "cover");
            row.put("cover", cover);
            row.put("comment_count", num(e, "comment_count"));
            row.put("digged_count", num(e, "digged_count"));
            eps.add(row);
        }

        eps.sort((a, b) -> Long.compare(
                Json.optLong(Json.optObj(a).get("index"), 0),
                Json.optLong(Json.optObj(b).get("index"), 0)));

        String firstEpCover = "";
        for (Object o : eps) {
            Map<String, Object> m = Json.optObj(o);
            if (m != null && !str(m, "cover").isEmpty()) {
                firstEpCover = str(m, "cover");
                break;
            }
        }

        List<Object> celebs = new ArrayList<>();
        for (Object cObj : Json.optArr(vd.get("celebrities"))) {
            Map<String, Object> c = Json.optObj(cObj);
            if (c == null) continue;
            Map<String, Object> cm = Json.obj();
            cm.put("演员", str(c, "nickname"));
            cm.put("角色", str(c, "role_name"));
            cm.put("头像", str(c, "avatar"));
            cm.put("简介", strip(str(c, "intro"), 80));
            celebs.add(cm);
        }

        Map<String, Object> meta = Json.obj();
        meta.put("series_id", String.valueOf(sid));
        String title = str(vd, "series_title");
        meta.put("title", title.isEmpty() ? String.valueOf(sid) : title);
        meta.put("abstract", str(vd, "series_intro"));
        long epCnt = num(vd, "episode_cnt");
        meta.put("episode_cnt", epCnt != 0 ? epCnt : eps.size());
        meta.put("status", Json.optLong(vd.get("series_status"), 0) == 1 ? "完结" : "连载中");
        meta.put("play_cnt", num(vd, "series_play_cnt"));
        meta.put("followed_cnt", num(vd, "followed_cnt"));
        meta.put("create_time", num(vd, "create_time"));
        String cover = str(vd, "series_cover");
        meta.put("cover", cover.isEmpty() ? firstEpCover : cover);
        meta.put("category", parseCategories(vd.get("category_schema")));
        meta.put("tags", coverTags(vd));
        meta.put("celebrities", celebs);

        Map<String, Object> out = Json.obj();
        out.put("meta", meta);
        out.put("episodes", eps);
        return out;
    }

    // ==================== 业务接口 ====================

    /**
     * 搜索短剧。按综合 tab 分页循环翻页，
     * 直到 has_more=false 或达到 max_items / 12 页安全上限。
     */
    public static List<Object> search(String query, int maxItemsArg) throws Exception {
        return searchPage(query, maxItemsArg, 0).items;
    }

    /**
     * 搜索的一页（供「加载更多」续页）。
     *
     * 数据源优先级：官网搜索页（_ROUTER_DATA.searchList，服务端渲染、免签）
     * -> App 签名搜索接口。App 搜索需要登录态，未登录时恒返回空，
     * 因此官网命中即直接采用；官网无结果（含网络失败）才回落 API。
     *
     * search 接口的 passback/search_id/offset 是上游游标，跨页必须原样回传，
     * 否则每次都从第 1 页重查。
     *
     * skip 的语义是"调用方已展示的条数"：上游不支持直接跳到第 N 条，
     * 只能从头抓够 skip + limit 条再丢掉前 skip 条。因此续页代价随页码增长，
     * 但结果正确 —— 早先的实现完全忽略 skip、每次都返回同一批前 limit 条，
     * 前端按 id 去重后得到空列表，于是误报"没有更多了"。
     */
    public static Page searchPage(String query, int maxItemsArg, int skip) throws Exception {
        int limit = Math.max(1, Math.min(
                maxItemsArg > 0 ? maxItemsArg : Log.envInt("HG_SEARCH_MAX_ITEMS", 20), 40));
        int skipN = Math.max(0, skip);

        // ---- 官网搜索优先 ----
        String ckWeb = Safeguards.cacheKey("websearch", query);
        List<Object> webItems = null;
        Object cachedWeb = Safeguards.cacheGet(ckWeb);
        if (cachedWeb instanceof List) webItems = castList(cachedWeb);
        if (webItems == null) {
            List<Map<String, Object>> web = WebSource.search(query);
            if (!web.isEmpty()) {
                webItems = webSearchItems(web);
                Safeguards.cacheSet(ckWeb, webItems, 600);
            }
        }
        if (webItems != null && !webItems.isEmpty()) {
            // 官网一次返回全部命中（约 10 条），没有后续页
            return Page.slice(webItems, skipN, limit, false);
        }

        // ---- 回落 App 签名搜索 ----
        // 游标状态可复用：同一关键词的续页从头重抓上游，靠这里的缓存兜住重复请求
        String ck = Safeguards.cacheKey("searchp", query, limit, skipN);
        Object cached = Safeguards.cacheGet(ck);
        if (cached instanceof Object[]) {
            Object[] arr = (Object[]) cached;
            if (arr.length == 3 && arr[0] instanceof List) {
                return new Page(castList(arr[0]), Boolean.TRUE.equals(arr[1]),
                        Json.optInt(arr[2], skipN));
            }
        }

        List<Object> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int offset = 0;
        String passback = "";
        String searchId = "";
        boolean hasMore = false;

        // 续页要跳过前 skipN 条，只能从头多抓。为免每点一次「加载更多」就
        // 把上游从头翻一遍，这里把已累积的结果与上游游标按关键词缓存起来，
        // 后续续页从上次位置接着抓。缓存仅 10 分钟，避免结果集明显变化后仍给旧数据。
        String ckAll = Safeguards.cacheKey("searchacc", query);
        Object cachedAcc = Safeguards.cacheGet(ckAll);
        if (cachedAcc instanceof Object[]) {
            Object[] acc = (Object[]) cachedAcc;
            if (acc.length == 6) {
                results.addAll(castList(acc[0]));
                for (Object o : castList(acc[1])) seen.add(String.valueOf(o));
                offset = Json.optInt(acc[2], 0);
                passback = Json.optStr(acc[3], "");
                searchId = Json.optStr(acc[4], "");
                hasMore = Boolean.TRUE.equals(acc[5]);
            }
        }

        // 连续翻上游直到攒够要交付的那一页（skip 之后的 limit 条）
        int want = skipN + limit;
        // 上游每页 count=10 且要过滤掉无集数的条目，多留些轮次以免深翻页时凑不满
        for (int page = 0; page < 60; page++) {
            // 注意：hasMore 初值为 false，不能在首轮就用它判断退出，
            // 否则第一页都抓不到（搜索恒返回 0 条）。首次无条件抓一轮。
            if (results.size() >= want) break;
            if (page > 0 && !hasMore) break;
            Map<String, Object> q = Json.obj();
            q.put("query", query);
            q.put("tab_name", "feed");
            q.put("search_source", "1");
            q.put("offset", String.valueOf(offset));
            q.put("count", "10");
            q.put("use_correct", "true");
            if (!passback.isEmpty()) q.put("passback", passback);
            if (!searchId.isEmpty()) q.put("search_id", searchId);

            Map<String, Object> j;
            try {
                j = api("GET", "/reading/bookapi/search/tab/v", null, q, 1, true);
            } catch (Exception e) {
                // 翻页中途失败：保留已累积的部分，交由调用方判断 has_more，
                // 不能把整个请求判死，否则续页永远无结果
                if (results.isEmpty()) throw e;
                break;
            }
            List<Object> tabs = Json.optArr(j.get("search_tabs"));
            if (tabs.isEmpty()) { hasMore = false; break; }
            Map<String, Object> tab = Json.optObj(tabs.get(0));
            if (tab == null) { hasMore = false; break; }
            List<Object> data = Json.optArr(tab.get("data"));
            for (Object cell : data) {
                Map<String, Object> item = parseSearchCell(cell);
                if (item == null) continue;
                String isid = str(item, "series_id");
                if (seen.contains(isid)) continue;
                seen.add(isid);
                results.add(item);
            }
            // 游标先落定，供下一页使用
            int adv = tab.get("next_offset") != null
                    ? Json.optInt(tab.get("next_offset"), offset + data.size())
                    : offset + data.size();
            String pb = str(tab, "passback");
            if (!pb.isEmpty()) passback = pb;
            String sid = str(tab, "search_id");
            if (!sid.isEmpty()) searchId = sid;
            hasMore = Json.optBool(tab.get("has_more"), false) && data.size() > 0;
            if (adv <= offset) hasMore = false;   // 游标原地不动 => 无后续
            offset = adv;
        }

        Safeguards.cacheSet(ckAll, new Object[]{
                new ArrayList<>(results), new ArrayList<>(seen), offset, passback, searchId, hasMore}, 600);

        // 丢掉前 skipN 条，只交付之后的那一页（切片逻辑见 Page.slice）
        Page page = Page.slice(results, skipN, limit, hasMore);
        Safeguards.cacheSet(ck, new Object[]{page.items, page.hasMore, page.nextSkip}, 600);
        return page;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object o) {
        return (List<Object>) o;
    }

    /** 取单剧元信息 + 剧集列表（缓存 6 小时）。App 详情失败时回落官网分集页。 */
    public static Map<String, Object> getEpisodes(String seriesId) throws Exception {
        String sid = String.valueOf(seriesId);
        String ck = Safeguards.cacheKey("episodes", sid);
        Object cached = Safeguards.cacheGet(ck);
        if (cached != null) return Json.optObj(cached);

        try {
            Map<String, Object> j = api("POST", "/novel/player/multi_video_detail/preload/v1",
                    episodesBody(sid), null, 3, true);
            Map<String, Object> data = Json.optObj(j.get("data"));
            Map<String, Object> vd = data == null ? null
                    : Json.optObj(Json.optObj(data.get(sid)).get("video_data"));
            Map<String, Object> result = parseEpisodeDetail(sid, vd);
            Safeguards.cacheSet(ck, result, 21600);
            return result;
        } catch (Exception e) {
            // App 详情失败（风控 / 网络 / 签名被拒）时回落官网分集页：
            // 官网只有 vid 列表，没有简介、演员等富信息，但保证分集可见
            Log.info("App 剧集详情失败，回落官网分集 (" + sid + ")：" + e.getMessage());
            Map<String, Object> web = WebSource.episodes(sid);
            if (web != null) {
                Map<String, Object> result = webEpisodesToDetail(web);
                Safeguards.cacheSet(ck, result, 21600);
                return result;
            }
            throw e;
        }
    }

    /** 批量取剧集元数据。 */
    public static Object[] getEpisodesBatch(List<String> seriesIds, int batchSizeArg) throws Exception {
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String s : seriesIds) {
            String sid = s == null ? "" : s.trim();
            if (!sid.isEmpty() && seen.add(sid)) ids.add(sid);
        }
        int bs = Math.max(1, Math.min(batchSizeArg > 0 ? batchSizeArg : 20, 20));
        Map<String, Object> out = Json.obj();
        List<Object> failed = new ArrayList<>();
        List<String> todo = new ArrayList<>();

        for (String sid : ids) {
            Object c = Safeguards.cacheGet(Safeguards.cacheKey("episodes", sid));
            if (c != null) {
                out.put(sid, Json.optObj(c).get("meta"));
            } else {
                todo.add(sid);
            }
        }

        for (int i = 0; i < todo.size(); i += bs) {
            List<String> batch = todo.subList(i, Math.min(i + bs, todo.size()));
            try {
                Map<String, Object> j = api("POST", "/novel/player/multi_video_detail/preload/v1",
                        episodesBody(String.join(",", batch)), null, 3, true);
                Map<String, Object> data = Json.optObj(j.get("data"));
                for (String sid : batch) {
                    Map<String, Object> vd = data == null ? null
                            : Json.optObj(Json.optObj(data.get(sid)).get("video_data"));
                    if (vd == null || vd.isEmpty()) {
                        Map<String, Object> f = Json.obj();
                        f.put("series_id", sid);
                        f.put("error", "empty detail");
                        failed.add(f);
                        continue;
                    }
                    Map<String, Object> parsed = parseEpisodeDetail(sid, vd);
                    Safeguards.cacheSet(Safeguards.cacheKey("episodes", sid), parsed, 21600);
                    out.put(sid, parsed.get("meta"));
                }
            } catch (Exception e) {
                for (String sid : batch) {
                    Map<String, Object> f = Json.obj();
                    f.put("series_id", sid);
                    f.put("error", String.valueOf(e.getMessage()));
                    failed.add(f);
                }
            }
        }
        return new Object[]{out, failed};
    }

    /** 取漫剧榜单。board: recommend / hot / new。 */
    public static List<Object> rank(String board, int limit) throws Exception {
        RankPage p = rankPage(board, limit, 0);
        return p.items;
    }

    /**
     * 榜单分页结果（供前端无限滚动）。
     *
     * @param items 本页条目
     * @param hasMore 上游是否还有后续页
     * @param nextOffset 下一页应使用的偏移量
     */
    public static final class RankPage {
        public final List<Object> items;
        public final boolean hasMore;
        public final int nextOffset;

        RankPage(List<Object> items, boolean hasMore, int nextOffset) {
            this.items = items;
            this.hasMore = hasMore;
            this.nextOffset = nextOffset;
        }
    }

    /**
     * 取漫剧榜单的一页。
     *
     * offset 为 0 时行为与从前一致（从头累积抓够 limit 条）；offset>0 时只抓
     * 本页，不会从头重复遍历 —— 无限滚动必须走这条路径，否则每次触底都会把
     * 前面的页重跑一遍。
     */
    public static RankPage rankPage(String board, int limit, int offsetArg) throws Exception {
        int limit1 = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 100));
        int offset = Math.max(0, offsetArg);
        // 分页视图按offset 缓存，避免相邻页互相覆盖
        String ck = Safeguards.cacheKey("rankp", board, limit1, offset);
        Object cached = Safeguards.cacheGet(ck);
        if (cached instanceof Object[]) {
            Object[] arr = (Object[]) cached;
            if (arr.length == 3 && arr[0] instanceof List) {
                return new RankPage(castList(arr[0]), Boolean.TRUE.equals(arr[1]),
                        Json.optInt(arr[2], offset));
            }
        }

        String sub = RANK_BOARDS.getOrDefault(board, board);
        List<Object> results = new ArrayList<>();
        int cur = offset;
        String sessionUuid = UUID.randomUUID().toString();
        boolean hasMore = false;

        while (results.size() < limit1) {
            Map<String, Object> q = Json.obj();
            q.put("cell_id", COMIC_RANK_CELL);
            q.put("tab_type", "26");
            q.put("client_req_type", "2");
            q.put("client_template", "2");
            q.put("screen_width_px", "1350");
            q.put("selected_items", "comic_series_rank");
            q.put("sub_selected_items", sub);
            q.put("session_uuid", sessionUuid);
            if (cur != 0) q.put("offset", String.valueOf(cur));

            Map<String, Object> j = api("GET", "/reading/bookapi/bookmall/cell/change/v",
                    null, q, 3, SIGN_LIST);
            Map<String, Object> data = Json.optObj(j.get("data"));
            Map<String, Object> cv = data == null ? null : Json.optObj(data.get("cell_view"));
            List<Object> cells = cv == null ? new ArrayList<>() : Json.optArr(cv.get("cell_data"));
            if (cells.isEmpty()) break;

            for (Object itemObj : cells) {
                Map<String, Object> item = Json.optObj(itemObj);
                if (item == null) continue;
                Object vo = item.get("video_data");
                if (vo instanceof List) {
                    List<?> l = (List<?>) vo;
                    vo = l.isEmpty() ? Json.obj() : l.get(0);
                }
                Map<String, Object> v = Json.optObj(vo);
                if (v == null) v = Json.obj();
                Object sid = v.get("series_id");
                if (sid == null) sid = v.get("book_id");
                if (sid == null) continue;

                Map<String, Object> row = Json.obj();
                // 分页时 rank 接着上一页排，避免每页都从 1 开始
                row.put("rank", offset + results.size() + 1);
                row.put("series_id", String.valueOf(sid));
                row.put("title", str(v, "title"));
                row.put("episode_cnt", num(v, "episode_cnt"));
                row.put("score", v.getOrDefault("score", ""));
                row.put("play_cnt", num(v, "play_cnt"));
                row.put("hot", str(v, "rec_text"));
                row.put("copyright", str(v, "copyright"));
                row.put("cover", str(v, "cover"));
                row.put("tags", coverTags(v));
                row.put("abstract", strip(str(v, "video_desc"), 50));
                results.add(row);
            }
            hasMore = cv != null && Json.optBool(cv.get("has_more"), false);
            int adv = cv != null && cv.get("next_offset") != null
                    ? Json.optInt(cv.get("next_offset"), cur + cells.size())
                    : cur + cells.size();
            // 游标没前进说明上游在原地打转，再翻下去会无限重复同一页
            if (adv <= cur) { hasMore = false; break; }
            cur = adv;
            if (!hasMore) break;
        }

        List<Object> out = new ArrayList<>(results.subList(0, Math.min(limit1, results.size())));
        // 结果放满说明可能还有后续页；否则上游已明确没有
        boolean more = hasMore && out.size() >= limit1;
        Safeguards.cacheSet(ck, new Object[]{out, more, cur}, 1800);
        return new RankPage(out, more, cur);
    }

    /**
     * 最新上架。
     * - 短剧：官方有"今日上新"标签，only_today=true 精确返回今日上新；
     * - 漫剧/AI：官方最细 7 天粒度，统一返回"7天内上新·最新上架"。
     */
    public static List<Object> latest(String genre, boolean onlyToday, int maxItems, boolean refresh)
            throws Exception {
        return latestPage(genre, onlyToday, maxItems, refresh, null, 0).items;
    }

    /** 分页结果（通用）。 */
    public static final class Page {
        public final List<Object> items;
        public final boolean hasMore;
        public final int nextSkip;

        Page(List<Object> items, boolean hasMore, int nextSkip) {
            this.items = items;
            this.hasMore = hasMore;
            this.nextSkip = nextSkip;
        }

        /**
         * 从「已抓到的累积结果」里切出 [skip, skip+limit) 这一页。
         *
         * 上游不支持直接跳到第 N 条，续页只能从头多抓再丢掉前面的，
         * 所以切片集中放在这里，便于自检直接验证（search 需真实签名服务）。
         *
         * has_more 的判据是"这一页确实凑满了 limit 条、且上游还有后续"。
         * 攒不满即已到末尾，必须报 false —— 否则前端会一直显示「加载更多」，
         * 点了却永远拿不到内容。
         */
        public static Page slice(List<Object> all, int skip, int limit, boolean upstreamHasMore) {
            int skipN = Math.max(0, skip);
            int lim = Math.max(1, limit);
            int size = all == null ? 0 : all.size();
            int from = Math.min(skipN, size);
            int to = Math.min(skipN + lim, size);
            List<Object> out = new ArrayList<>(all == null
                    ? java.util.Collections.emptyList() : all.subList(from, to));
            boolean more = out.size() >= lim && upstreamHasMore;
            return new Page(out, more, skipN + out.size());
        }
    }

    /**
     * 最新上架的一页（供无限滚动）。
     *
     * @param seenIds 已经展示过的 series_id。landpage 接口靠 filter_ids 去重，
     *                把已看过的 id 原样回传即可拿到后续内容且不与前面重复。
     */
    public static Page latestPage(String genre, boolean onlyToday, int maxItems, boolean refresh,
                                  List<String> seenIds, int skip) throws Exception {
        String[] gv = GENRES.get(genre);
        if (gv == null) throw new IllegalArgumentException("genre 必须是 "
                + String.join("|", GENRES.keySet()));
        String scene = gv[0];
        String g = gv[1];
        boolean tagToday = genre.equals("short_play");
        List<Object> onlineTime = new ArrayList<>();
        if (!tagToday) onlineTime.add("days_7");
        boolean wantToday = tagToday && onlyToday;

        String ck = Safeguards.cacheKey("latest", genre, String.valueOf(onlyToday),
                String.valueOf(maxItems));
        // 分页请求（skip>0 或带了已看列表）不进整页缓存：整页缓存是给
        // 首屏用的，带去重状态的中间页缓存起来既易失效又会占内存
        boolean paged = skip > 0 || (seenIds != null && !seenIds.isEmpty());
        if (!refresh && !paged) {
            Object c = Safeguards.cacheGet(ck);
            if (c != null) return new Page(castList(c), true, 0);
        }

        List<Object> out = new ArrayList<>();
        // 已看过的 id 沿用上游去重参数，续页才不会重复返回同一批
        List<String> shown = new ArrayList<>(seenIds != null ? seenIds : new ArrayList<>());
        // 关键：offset 必须从已展示条数起步，否则上游每次都回第一页，
        // 前端表现为「续页内容与首页完全相同」
        int offset = Math.max(0, skip);
        boolean hasMore = false;

        for (int pages = 0; pages < 20 && out.size() < maxItems; pages++) {
            Map<String, Object> body = landpageBody(shown, scene, offset, 18, onlineTime, g);
            Map<String, Object> j = api("POST", "/reading/distribution/category/landpage/v",
                    body, null, 3, SIGN_LIST);
            Map<String, Object> data = Json.optObj(j.get("data"));
            List<Object> items = data == null ? new ArrayList<>() : Json.optArr(data.get("video_data"));
            if (items.isEmpty()) break;

            int pageToday = 0;
            for (Object itObj : items) {
                Map<String, Object> it = Json.optObj(itObj);
                if (it == null) continue;
                String sid = String.valueOf(it.get("series_id"));
                shown.add(sid);

                List<String> subs = new ArrayList<>();
                for (Object sObj : Json.optArr(it.get("sub_title_list"))) {
                    Map<String, Object> s = Json.optObj(sObj);
                    if (s != null) subs.add(str(s, "content"));
                }
                boolean isToday = subs.contains("今日上新");
                if (isToday) pageToday++;
                if (wantToday && !isToday) continue;

                List<String> cats = parseCategories(it.get("category_schema"));
                if (cats.isEmpty()) {
                    for (String s : subs) {
                        if (s.isEmpty() || s.equals("今日上新") || s.matches("^\\d+(\\.\\d+)?万")
                                || s.contains("播放") || s.matches("^\\d+集$")) continue;
                        if (!cats.contains(s)) cats.add(s);
                    }
                }

                Map<String, Object> row = Json.obj();
                row.put("series_id", sid);
                row.put("title", str(it, "title"));
                row.put("episode_cnt", num(it, "episode_cnt"));
                row.put("score", it.getOrDefault("score", ""));
                row.put("play_cnt", num(it, "play_cnt"));
                row.put("cover", str(it, "cover"));
                row.put("category", String.join(" / ", cats));
                row.put("today", isToday);
                row.put("copyright", str(it, "copyright"));
                List<Object> tags = new ArrayList<>();
                String tagText = str(Json.optObj(it.get("tag_info")), "text");
                if (!tagText.isEmpty()) tags.add(tagText);
                row.put("tags", tags);
                row.put("abstract", strip(str(it, "video_desc"), 50));
                row.put("vid", str(it, "vid"));
                out.add(row);
                if (out.size() >= maxItems) break;
            }
            if (wantToday && pageToday == 0) break; // 整页无今日 => 已过今日簇
            hasMore = data != null && !Boolean.FALSE.equals(data.get("has_more"));
            if (!hasMore) break;
            offset += items.size();
        }
        if (out.size() >= maxItems && out.size() > skip) {
            Safeguards.cacheSet(ck, out, 600);
        }
        return new Page(out, hasMore, skip + out.size());
    }

    /** landpage 接口请求体。 */
    private static Map<String, Object> landpageBody(List<String> shown, String scene, int offset,
                                                    int limit, List<Object> onlineTime, String g) {
        Map<String, Object> select = Json.obj();
        select.put("category_dim_epoch", new ArrayList<>());
        select.put("online_time", onlineTime);
        select.put("gender", new ArrayList<>());
        select.put("category_dim_role", new ArrayList<>());
        select.put("genre", new ArrayList<>(Arrays.asList(g)));
        select.put("sort", new ArrayList<>(Arrays.asList("online_time")));
        select.put("category_dim_theme", new ArrayList<>());

        Map<String, Object> body = Json.obj();
        body.put("filter_ids", String.join(",", shown));
        body.put("req_scene", scene);
        body.put("offset", offset);
        body.put("need_selector_panel", false);
        body.put("limit", limit);
        body.put("select_items", select);
        body.put("session_id", "");
        body.put("req_type", "only_content");
        body.put("client_req_type", 3);
        return body;
    }

    /** 取某体裁的实时筛选面板。 */
    public static List<Object> filters(String genre) throws Exception {
        String[] gv = GENRES.get(genre);
        if (gv == null) gv = GENRES.get("short_play");

        Map<String, Object> select = Json.obj();
        select.put("category_dim_epoch", new ArrayList<>());
        select.put("online_time", new ArrayList<>());
        select.put("gender", new ArrayList<>());
        select.put("category_dim_role", new ArrayList<>());
        select.put("genre", new ArrayList<>(Arrays.asList(gv[1])));
        select.put("sort", new ArrayList<>());
        select.put("category_dim_theme", new ArrayList<>());

        Map<String, Object> body = Json.obj();
        body.put("filter_ids", "");
        body.put("req_scene", gv[0]);
        body.put("offset", 0);
        body.put("limit", 1);
        body.put("need_selector_panel", true);
        body.put("req_type", "default");
        body.put("client_req_type", 3);
        body.put("select_items", select);
        body.put("session_id", "");

        Map<String, Object> j = api("POST", "/reading/distribution/category/landpage/v",
                body, null, 3, SIGN_LIST);
        Map<String, Object> data = Json.optObj(j.get("data"));
        List<Object> rows = data == null ? new ArrayList<>() : Json.optArr(data.get("selector_rows"));
        List<Object> out = new ArrayList<>();
        for (Object rObj : rows) {
            Map<String, Object> r = Json.optObj(rObj);
            if (r == null) continue;
            Map<String, Object> row = Json.obj();
            row.put("type", str(r, "type"));
            row.put("row_name", str(r, "row_name"));
            row.put("selection_type", str(r, "selection_type"));
            List<Object> items = new ArrayList<>();
            for (Object itObj : Json.optArr(r.get("items"))) {
                Map<String, Object> it = Json.optObj(itObj);
                if (it == null) continue;
                Map<String, Object> im = Json.obj();
                im.put("id", it.get("selector_item_id"));
                im.put("name", it.get("show_name"));
                items.add(im);
            }
            row.put("items", items);
            out.add(row);
        }
        return out;
    }

    /** 单值或数组 -> id 数组；名称按 mapping 映射，已是 id/未知则原样透传。 */
    private static List<Object> toIds(String csv, Map<String, String> mapping) {
        List<Object> out = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return out;
        for (String v : Http.splitCsv(csv)) {
            String mapped = mapping.get(v);
            out.add(mapped != null ? mapped : v);
        }
        return out;
    }

    /** 按筛选条件浏览。 */
    public static List<Object> browse(String genre, Map<String, String> opts) throws Exception {
        return browsePage(genre, opts, null, 0).items;
    }

    /**
     * 筛选浏览的一页（供无限滚动）。
     *
     * @param seenIds 已展示过的 series_id，经 filter_ids 回传给上游做去重。
     */
    public static Page browsePage(String genre, Map<String, String> opts,
                                  List<String> seenIds, int skip) throws Exception {
        String[] gv = GENRES.get(genre);
        if (gv == null) throw new IllegalArgumentException("genre 必须是 "
                + String.join("|", GENRES.keySet()));
        int maxItems = Math.min(Json.optInt(opts.get("limit"), 60), 120);

        Map<String, Object> select = Json.obj();
        select.put("genre", new ArrayList<>(Arrays.asList(gv[1])));
        select.put("category_dim_theme", toIds(opts.get("theme"), FILTER_CATE));
        select.put("category_dim_role", toIds(opts.get("setting"), FILTER_CATE));
        select.put("category_dim_epoch", toIds(opts.get("background"), FILTER_CATE));
        List<Object> sortIds = toIds(opts.get("sort"), FILTER_SORT);
        select.put("sort", sortIds.isEmpty()
                ? new ArrayList<>(Arrays.asList("online_time")) : sortIds);
        select.put("gender", toIds(opts.get("gender"), FILTER_GENDER));
        select.put("creation_status", toIds(opts.get("status"), FILTER_STATUS));
        select.put("online_time", toIds(opts.get("days"), FILTER_DAYS));

        List<Object> out = new ArrayList<>();
        List<String> shown = new ArrayList<>(seenIds != null ? seenIds : new ArrayList<>());
        // 与 latestPage 同理：offset 必须从已展示条数起步
        int offset = Math.max(0, skip);
        boolean hasMore = false;

        for (int pages = 0; pages < 20 && out.size() < maxItems; pages++) {
            Map<String, Object> body = Json.obj();
            body.put("filter_ids", String.join(",", shown));
            body.put("req_scene", gv[0]);
            body.put("offset", offset);
            body.put("need_selector_panel", false);
            body.put("limit", 18);
            body.put("select_items", select);
            body.put("session_id", "");
            body.put("req_type", "only_content");
            body.put("client_req_type", 3);

            Map<String, Object> j = api("POST", "/reading/distribution/category/landpage/v",
                    body, null, 3, SIGN_LIST);
            Map<String, Object> data = Json.optObj(j.get("data"));
            List<Object> items = data == null ? new ArrayList<>() : Json.optArr(data.get("video_data"));
            if (items.isEmpty()) break;

            for (Object itObj : items) {
                Map<String, Object> it = Json.optObj(itObj);
                if (it == null) continue;
                String sid = String.valueOf(it.get("series_id"));
                shown.add(sid);

                Map<String, Object> row = Json.obj();
                row.put("series_id", sid);
                row.put("title", str(it, "title"));
                row.put("episode_cnt", num(it, "episode_cnt"));
                row.put("score", it.getOrDefault("score", ""));
                row.put("play_cnt", num(it, "play_cnt"));
                row.put("cover", str(it, "cover"));
                row.put("copyright", str(it, "copyright"));
                row.put("category", String.join(" / ", parseCategories(it.get("category_schema"))));
                row.put("tags", coverTags(it));
                row.put("abstract", strip(str(it, "video_desc"), 50));
                row.put("vid", str(it, "vid"));
                row.put("comment_count", num(it, "comment_count"));
                row.put("duration", num(it, "duration"));
                row.put("horiz_cover", str(it, "horiz_cover"));
                out.add(row);
                if (out.size() >= maxItems) break;
            }
            // 上方 items.isEmpty() 已拦截 data == null，此处 data 必非空。
            // 上游只在下页无内容时才给出 has_more=false，故按其值决定是否继续。
            if (Boolean.FALSE.equals(data.get("has_more"))) break;
            hasMore = true;
            offset += items.size();
        }
        return new Page(out, hasMore, skip + out.size());
    }

    // ==================== 工具 ====================

    /** 文件名安全化（保留 60 字上限）。 */
    public static String sanitize(String name) {
        String t = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return t.length() > 60 ? t.substring(0, 60) : t;
    }
}
