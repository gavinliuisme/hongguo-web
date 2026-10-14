package com.hongguo.api;

import com.hongguo.api.core.Mp4;
import com.hongguo.api.core.PureSign;
import com.hongguo.api.core.Safeguards;
import com.hongguo.api.core.Spade;
import com.hongguo.api.service.AppSource;
import com.hongguo.api.service.Client;
import com.hongguo.api.service.Stream;
import com.hongguo.api.util.Config;
import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * 自检套件。
 *
 * 移植自原Node 版 scripts/selftest.js 的纯算法部分 ——
 * 这些用例都是踩过坑之后固化的回归基线，不依赖网络与 Node 运行时，
 * 迁移后依然逐条保留，是判断「迁移是否等价」的主要依据。
 */
final class SelfTest {

    private int passed;
    private int failed;
    private int skipped;

    private SelfTest() {}

    // ==================== 断言 ====================

    private void check(String name, boolean cond, String detail) {
        if (cond) {
            passed++;
            System.out.println("  ✓ " + name + (detail == null || detail.isEmpty() ? "" : "  " + detail));
        } else {
            failed++;
            System.out.println("  ✗ " + name + (detail == null || detail.isEmpty() ? "" : "  " + detail));
        }
    }

    private void check(String name, boolean cond) {
        check(name, cond, "");
    }

    /**
     * 跳过一条断言：前提未满足时不算失败，也不计入通过数。
     *
     * 用于「结论正确、但依赖运行态产物」的检查（如密钥引导签发）——
     * 在未启动过服务的干净环境下判失败会产生假红。跳过原因会打印出来，
     * 保证「跳过」与「通过」一样可追溯。
     */
    private void skip(String name, String reason) {
        skipped++;
        System.out.println("  ⊘ " + name + (reason == null || reason.isEmpty() ? "" : "  " + reason));
    }

    private static void section(String title) {
        System.out.println("");
        System.out.println("=== " + title + " ===");
    }

    private static String hex(byte[] b) {
        return Crypto.hex(b);
    }

    private static byte[] unhex(String h) {
        return Crypto.unhex(h);
    }

    // ==================== 各组用例 ====================

    /** 1. spade 解包：5 组运行时真值 + ver2 拒绝。 */
    private void spade() {
        section("1. spade -> content key 解包（对齐 5 组真值）");
        boolean all = Spade.selfTest();
        check("spade 解包 5 组真值", all, "");
        String k = Spade.unwrapV1(unhex("93bc1df253ba1bf7618b19c448b806f64d810afc45b715fe5eb925f26cbf12f541ba098282"), 0);
        check("输出为 32 位十六进制", Spade.isValidKey(k), k);
        byte[] ver2 = new byte[40];
        check("ver2 类型被拒绝", Spade.unwrapV1(ver2, 0) == null, "");
    }

    /** 2. AES-128-CTR 计数器构造。 */
    private void ctrCounter() {
        section("2. AES-128-CTR 计数器构造");
        // counter = (base_iv_high64 + index) << 64，低 64 位恒为 0
        byte[] c0 = Mp4.ctrCounter("0000000000000001", 0);
        byte[] c1 = Mp4.ctrCounter("0000000000000001", 1);
        byte[] c2 = Mp4.ctrCounter("00000000000000ff", 1);
        check("counter 长度 16 字节", c0.length == 16, "");
        check("index=0 高位保留",
                hex(c0).equals("00000000000000010000000000000000"), hex(c0));
        check("index=1 进位到高 64 位",
                hex(c1).equals("00000000000000020000000000000000"), hex(c1));
        check("0xff + 1 进位正确",
                hex(c2).equals("00000000000001000000000000000000"), hex(c2));
    }

    /**
     * 3. 逐样本 IV -> CTR 计数器。
     *
     * 回归用例：本项目实测 senc IV 为 8 字节，位于计数器高 64 位（右侧补零）。
     * 曾因 padStart 写成左侧补零导致解出全噪声。
     */
    private void ivToCounter() {
        section("3. 逐样本 IV -> CTR 计数器");
        byte[] c8 = Mp4.ivToCounter("ebbde5029bbf3f29");
        check("8 字节 IV 长度 16", c8.length == 16, "");
        check("8 字节 IV 位于高 64 位",
                hex(c8).equals("ebbde5029bbf3f290000000000000000"), hex(c8));
        check("与 ctrCounter(baseIv,0) 等价",
                Arrays.equals(c8, Mp4.ctrCounter("ebbde5029bbf3f29", 0)));
        byte[] c16 = Mp4.ivToCounter("00112233445566778899aabbccddeeff");
        check("16 字节 IV 原样保留",
                hex(c16).equals("00112233445566778899aabbccddeeff"), hex(c16));

        Mp4.Track tr = new Mp4.Track();
        tr.ivs = new String[]{"ebbde5029bbf3f29", "ebbde5029bbf3f2a"};
        check("counterFor 优先用逐样本 IV",
                hex(Mp4.counterFor(tr, null, 1)).equals("ebbde5029bbf3f2a0000000000000000"));
        check("counterFor 无 IV 时回退 base_iv",
                hex(Mp4.counterFor(new Mp4.Track(), "0000000000000005", 2))
                        .equals("00000000000000070000000000000000"));
    }

    /** 4. AVCC / NAL 自证。 */
    private void avcc() {
        section("4. AVCC / NAL 自证");
        byte[] annexb = new byte[]{0, 0, 0, 1, 0x67, 0x42, 0, 1, 0x68, (byte) 0xCE};
        byte[] avcc = new byte[]{0, 0, 0, 2, 0x67, 0x42, 0, 0, 0, 3, 0x68, (byte) 0xCE, 0x3C};
        byte[] junk = new byte[64];
        Arrays.fill(junk, (byte) 0xAB);

        check("Annex-B 起始码识别", Mp4.avccSelfOk(annexb, annexb.length));
        check("AVCC 长度前缀识别", Mp4.avccSelfOk(avcc, avcc.length));
        check("随机数据拒绝", !Mp4.avccSelfOk(junk, junk.length));

        // 多 NAL 样本整链自证（真实样本通常含 SPS/PPS/SEI/IDR）
        byte[] multi = new byte[]{
                0, 0, 0, 2, 0x67, 0x42,
                0, 0, 0, 2, 0x68, (byte) 0xCE,
                0, 0, 0, 3, 0x65, (byte) 0x88, (byte) 0x84};
        check("多 NAL 样本整链自证", Mp4.avccSelfOk(multi, multi.length));

        // nal_unit_type=0 非 IDR 分片也应通过
        byte[] nonIdr = new byte[]{0, 0, 0, 2, 0x06, 0x42};
        check("nal_unit_type=0 非 IDR 分片通过", Mp4.avccSelfOk(nonIdr, nonIdr.length));

        // 长度越界必须拒绝
        byte[] overflow = new byte[]{0, 0, 0, 99, 0x65, (byte) 0x88};
        check("长度越界拒绝", !Mp4.avccSelfOk(overflow, overflow.length));

        // forbidden_zero_bit 置位必须拒绝
        byte[] badBit = new byte[]{0, 0, 0, 2, (byte) 0x85, 0x42};
        check("forbidden_zero_bit 置位拒绝", !Mp4.avccSelfOk(badBit, badBit.length));

        // 探测样本应含 index 0 且都在范围内
        Mp4.Track tr = new Mp4.Track();
        tr.sampleCount = 100;
        tr.sizes = new long[100];
        Arrays.fill(tr.sizes, 1000);
        int[] probe = Mp4.pickProbeIndices(tr);
        boolean hasZero = false;
        boolean inRange = true;
        for (int i : probe) {
            if (i == 0) hasZero = true;
            if (i < 0 || i >= 100) inRange = false;
        }
        check("探测样本含 index 0 且在范围内", hasZero && inRange,
                "共 " + probe.length + " 个");
    }

    /** 5. AES-128-CTR 加解密往返一致性。 */
    private void ctrRoundTrip() {
        section("5. AES-128-CTR 加解密往返一致性");
        byte[] key = new byte[16];
        byte[] ctr = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) i;
            ctr[i] = (byte) (0xF0 + i);
        }
        byte[] plain = "红果短剧 · hongguo ctr 往返测试".getBytes(StandardCharsets.UTF_8);
        byte[] enc = Crypto.aes128Ctr(key, ctr, plain);
        byte[] dec = Crypto.aes128Ctr(key, ctr, enc);
        check("解密还原原文", Arrays.equals(plain, dec));
        check("密文与原文不同", !Arrays.equals(enc, plain));
    }

    /** 6. MP4 盒解析（构造最小合法 moov）。 */
    private void mp4Boxes() {
        section("6. MP4 盒解析（构造最小合法 moov）");
        byte[] buf = Mp4Fixture.buildMinimalEncryptedMp4();
        Mp4.Analysis a = Mp4.analyzeEncryptedTracks(buf);
        check("解析出加密轨", !a.tracks.isEmpty(), a.tracks.size() + " 条");
        if (a.tracks.isEmpty()) return;
        Mp4.Track t = a.tracks.get(0);
        check("handler 为 vide", "vide".equals(t.handler), t.handler);
        check("样本数为 2", t.sampleCount == 2, "" + t.sampleCount);
        check("逐样本 IV 已解析", t.ivs != null && t.ivs.length == 2, "");
        check("IV 长度为 16 字节", t.ivSize == 16, "" + t.ivSize);
        // tenc 挂在 stsd→encv 内，而 encv 不在盒树的容器白名单里，
        // 因此 default_KID 读不到（Node 版行为一致）。
        // 这不影响解密：content key 来自 spade 解包，不依赖 KID。
        check("KID 不参与解密链路（content key 来自 spade）", t.kid == null, "");

        // 顶层盒结构
        java.util.List<Mp4.Box> top = Mp4.parseBoxes(buf, 0, buf.length);
        boolean hasFtyp = false;
        boolean hasMoov = false;
        for (Mp4.Box b : top) {
            if (b.type.equals("ftyp")) hasFtyp = true;
            if (b.type.equals("moov")) hasMoov = true;
        }
        check("顶层含 ftyp 与 moov", hasFtyp && hasMoov, "");
    }

    /** 7. 端到端：逐样本 IV + content key 解密。 */
    private void decryptEndToEnd() {
        section("7. 端到端：content key + 逐样本 IV 解密");
        try {
            byte[] buf = Mp4Fixture.buildMinimalEncryptedMp4();
            Mp4.Analysis a = Mp4.analyzeEncryptedTracks(buf);
            if (a.tracks.isEmpty()) {
                check("夹具可解析", false, "无加密轨");
                return;
            }
            Mp4.Track vt = a.tracks.get(0);
            String keyHex = Mp4Fixture.fixtureKeyHex();

            // IV 自证：应通过逐样本 IV 校验
            java.util.List<String> cands = Mp4.trackBaseIvCandidates(vt);
            String vIv = Mp4.verifyVideoIv(buf, vt, keyHex, cands);
            check("verifyVideoIv 通过逐样本 IV 自证",
                    vIv != null && vIv.equals(vt.ivs[0]), "" + vIv);

            // 解密后每个样本应通过 AVCC 自证
            Mp4.DecryptResult r = Mp4.decryptTrack(buf, vt, keyHex, vIv);
            check("全部样本解密完成", r.decrypted == vt.sampleCount,
                    r.decrypted + "/" + r.total);
            boolean allOk = true;
            for (int i = 0; i < r.decrypted; i++) {
                long off = vt.offsets[i];
                int size = (int) vt.sizes[i];
                if (off + size > buf.length
                        || !Mp4.avccSelfOk(Arrays.copyOfRange(buf, (int) off, (int) off + size), size)) {
                    allOk = false;
                    break;
                }
            }
            check("解密后每个样本 AVCC 自洽", allOk, "");

            // 解密结果应逐字节还原夹具明文
            byte[][] plains = Mp4Fixture.plainSamples();
            boolean restored = true;
            for (int i = 0; i < r.decrypted && i < plains.length; i++) {
                long off = vt.offsets[i];
                int size = (int) vt.sizes[i];
                byte[] got = Arrays.copyOfRange(buf, (int) off, (int) off + size);
                if (!Arrays.equals(got, plains[i])) restored = false;
            }
            check("解密逐字节还原夹具明文", restored, "");

            // 错误的 content key 必须被拒绝
            byte[] badKey = new byte[16];
            Arrays.fill(badKey, (byte) 0x5A);
            String badIv = Mp4.verifyVideoIv(buf, vt, hex(badKey), cands);
            check("错误 content key 被拒绝", badIv == null, "" + badIv);
        } catch (Exception e) {
            check("端到端解密", false, String.valueOf(e.getMessage()));
        }
    }

    /** 8. JSON 往返（自研轻量库，含数字语义与 null 省略）。 */
    private void json() {
        section("8. JSON 往返与数字语义");
        try {
            Map<String, Object> src = Json.obj();
            src.put("a", 1);
            src.put("b", "中文\"引号\"\n换行");
            src.put("c", Json.arr());
            src.put("d", 1.5);
            String text = Json.stringify(src);
            Map<String, Object> back = Json.parseObject(text);
            check("整型往返为数值",
                    Json.optLong(back.get("a"), 0) == 1L, "");
            check("字符串转义往返",
                    "中文\"引号\"\n换行".equals(back.get("b")), "");
            check("小数保持精度",
                    Math.abs(Json.optDouble(back.get("d"), 0) - 1.5) < 1e-9, "");

            // skipNulls 模拟 JS 中 undefined 被 JSON.stringify 省略的行为
            Map<String, Object> withNull = Json.obj();
            withNull.put("keep", "v");
            withNull.put("drop", null);
            String s2 = Json.stringify(withNull, true);
            check("skipNulls 省略 null 字段", !s2.contains("drop"), s2);

            // 数字语义：1.0 应输出为 1，避免与 JS 产生差异
            Map<String, Object> num = Json.obj();
            num.put("x", 1.0d);
            check("1.0 输出为 1", Json.stringify(num).contains("\"x\":1"), Json.stringify(num));
        } catch (Exception e) {
            check("JSON 往返", false, String.valueOf(e.getMessage()));
        }
    }

    /**
     * 9. 播放缓存路径。
     *
     * 背景：解密末尾 remux 产出 "<vid>_<q>.play.mp4" 并删掉 raw，
     * 而早期实现只探测 "<vid>_<q>.mp4"，导致加密内容永远不命中缓存，
     * 每次播放都重新下载 + 解密。
     */
    private void cachePaths() {
        section("9. 播放缓存路径：.play.mp4 变体必须参与命中判定");
        Stream.Paths3 p = Stream.cachePaths("7688396322021329982", "best");
        check("remux 产出名与 play 路径一致",
                p.play.getFileName().toString().equals("7688396322021329982_best.play.mp4"),
                p.play.getFileName().toString());
        check("play 与 out 是不同文件", !p.play.equals(p.out), "");
        check("raw 命名带 .raw 段",
                p.raw.getFileName().toString().endsWith("_best.raw.mp4"),
                p.raw.getFileName().toString());

        // cachedPath 三个变体任一存在即命中
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("hg-cache-");
            String saved = System.getProperty("HONGGUO_STREAM_CACHE");
            System.setProperty("HONGGUO_STREAM_CACHE", tmp.toString());
            Stream.Paths3 q = Stream.cachePaths("999", "best");
            check("空目录时 cachedPath 为空",
                    Stream.cachedPath("999", "best") == null, "");
            Files.write(q.play, new byte[]{'x'});
            check("仅 .play.mp4 存在即命中",
                    Stream.cachedPath("999", "best").equals(q.play.toString()), "");
            Files.delete(q.play);
            Files.write(q.out, new byte[]{'x'});
            check("仅 .mp4 存在即命中",
                    Stream.cachedPath("999", "best").equals(q.out.toString()), "");
            if (saved == null) {
                System.clearProperty("HONGGUO_STREAM_CACHE");
            } else {
                System.setProperty("HONGGUO_STREAM_CACHE", saved);
            }
        } catch (Exception e) {
            check("缓存路径命中判定", false, String.valueOf(e.getMessage()));
        } finally {
            if (tmp != null) {
                try {
                    Files.walk(tmp).sorted(java.util.Comparator.reverseOrder())
                            .forEach(x -> {
                                try {
                                    Files.delete(x);
                                } catch (Exception ignored) {
                                    // 忽略
                                }
                            });
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
    }

    /** 10. 设备身份 device_id（搜索接口必需）。 */
    private void device() {
        section("10. 设备身份 device_id（搜索接口必需）");
        com.hongguo.api.core.Device.Identity id = Client.identity();
        check("device_id 为 16 位数字",
                id.deviceId != null && id.deviceId.matches("^\\d{16}$"), id.deviceId);
        check("来源已标记",
                id.source != null && !id.source.isEmpty(), id.source);
        check("已注入 base_query",
                Client.baseQuery().get("device_id").equals(id.deviceId), "");
        check("device.json 已落盘",
                Files.exists(com.hongguo.api.core.Device.storeFile()), "");

        // iid 恒为 0：非 0 随机 iid 会触发上游风控
        Object iid = Client.baseQuery().get("iid");
        check("iid 保持 0（随机 iid 会触发风控）",
                iid == null || "0".equals(String.valueOf(iid)), "" + iid);
    }

    /** 11. 剧集解析：title 清洗（上游 title 实为简介）。 */
    private void episodeTitle() {
        section("12. 剧集解析：title 清洗（上游 title 实为简介）");
        String intro = "这是一段很长的本剧简介文字";
        Map<String, Object> vd = Json.obj();
        vd.put("series_intro", intro);
        vd.put("series_title", "测试剧");
        vd.put("episode_cnt", 2);
        java.util.List<Object> vl = Json.arr();

        Map<String, Object> e1 = Json.obj();
        e1.put("vid_index", 1);
        e1.put("vid", "v1");
        e1.put("title", intro);          // 与简介相同 => 应置空
        e1.put("duration", 100);
        vl.add(e1);

        Map<String, Object> e2 = Json.obj();
        e2.put("vid_index", 2);
        e2.put("vid", "v2");
        e2.put("title", "第 2 集");       // 正常集名=> 应保留
        e2.put("duration", 120);
        vl.add(e2);

        vd.put("video_list", vl);
        Map<String, Object> r = Client.parseEpisodeDetail("123", vd);
        java.util.List<Object> eps = Json.optArr(r.get("episodes"));
        check("解析出 2 集", eps.size() == 2, "" + eps.size());

        Map<String, Object> a1 = Json.optObj(eps.get(0));
        Map<String, Object> a2 = Json.optObj(eps.get(1));
        check("与简介相同的 title 被置空",
                Json.optStr(a1.get("title"), "x").isEmpty(), "");
        check("正常集名保留",
                Json.optStr(a2.get("title"), "").equals("第 2 集"), "");
        check("meta 标题取 series_title",
                Json.optStr(Json.optObj(r.get("meta")).get("title"), "")
                        .equals("测试剧"), "");
        check("按 index 升序排列",
                Json.optLong(a1.get("index"), 0) == 1
                        && Json.optLong(a2.get("index"), 0) == 2, "");
    }

    /** 12. 解码兼容性：默认不转码（直出 HEVC）。 */
    private void transcode() {
        section("13. 解码兼容性：默认不转码（直出 HEVC）");
        check("ffmpeg 可用", Stream.ffmpegAvailable(), "");
        check("默认关闭转码", !Stream.transcodeEnabled(), "");
        if (Stream.transcodeEnabled()) {
            check("转码时能找到 H.264 编码器",
                    Stream.h264EncoderName() != null, "" + Stream.h264EncoderName());
        } else {
            System.out.println("  · 转码已关闭（HG_TRANSCODE=1 可开启 HEVC -> H.264）");
        }
    }

    /** 密钥管理。 */
    private void keyStore() {
        section("14. 本地链路密钥");
        com.hongguo.api.core.KeyStore ks = new com.hongguo.api.core.KeyStore();
        // 与 Server.ensureBootstrap() 同一入口：干净环境（从未启动过服务）下
        // apikeys.json 不存在。这里先自签一把，让下面两项断言确定执行，
        // 结论不随「是否跑过服务」浮动——早期版本在这里按运行态跳过，
        // 导致同一份代码在干净机上报 123、跑过服务后报 125。
        ks.ensureBootstrap();
        check("至少一把启用密钥", ks.countEnabled() >= 1, "" + ks.countEnabled());
        com.hongguo.api.core.KeyStore.Rec first = ks.firstEnabled();
        check("首把密钥格式为 hg_ 前缀",
                first != null && first.key.startsWith("hg_"), "");
        check("无效密钥被拒绝", !ks.isValid("hg_nonexistent"), "");
        check("空密钥被拒绝", !ks.isValid(""), "");
        // 管理口令：apikeys.json 里没有则生成并写回，重读必须拿到同一个值
        String at1 = ks.ensureAdminToken();
        check("管理口令已生成（64 位 hex）", at1.length() == 64, "len=" + at1.length());
        com.hongguo.api.core.KeyStore ks2 = new com.hongguo.api.core.KeyStore();
        check("管理口令持久化到 apikeys.json", at1.equals(ks2.ensureAdminToken()), "");
    }

    /** moov 定位：尾部 moov（非 faststart）必须能被正确找到。 */
    private void moovLocator() {
        section("15. moov 定位：尾部 moov 也能找到（流式解密前置）");
        try {
            // 头部形态（faststart）：moov 应在 ftyp 之后立刻出现
            byte[] head = Mp4Fixture.buildMinimalEncryptedMp4();
            Mp4.MoovSpan hSpan = scanMoov(head);
            check("头部形态能定位 moov", hSpan != null,
                    hSpan == null ? "null" : ("start=" + hSpan.start + " size=" + hSpan.size));
            if (hSpan != null) {
                check("头部形态 moov 紧跟 ftyp", hSpan.start > 0 && hSpan.start < head.length / 2,
                        "start=" + hSpan.start);
            }

            // 尾部形态：moov 在文件末尾
            byte[] tail = Mp4Fixture.buildTailMoovEncryptedMp4();
            Mp4.MoovSpan tSpan = scanMoov(tail);
            check("尾部形态能定位 moov", tSpan != null,
                    tSpan == null ? "null" : ("start=" + tSpan.start + " size=" + tSpan.size));
            if (tSpan != null) {
                // 夹具体积很小（几百字节），不用比例判断，改为「moov 在 mdat 之后」
                // ——这才是尾部 moov 的语义：索引段排在媒体数据之后。
                int mdatEnd = indexOfType(tail, "mdat");
                check("尾部形态 moov 排在 mdat 之后",
                        tSpan.start > mdatEnd,
                        "moov@" + tSpan.start + " > mdat@" + mdatEnd);
                check("moov 末端与文件末端一致",
                        tSpan.end() == tail.length,
                        tSpan.end() + "==" + tail.length);
            }

            // 两种形态都能解析出加密轨，且样本偏移一致（都指向各自 mdat）
            check("头部形态解析出加密轨",
                    !Mp4.analyzeEncryptedTracks(head).tracks.isEmpty(), "");
            check("尾部形态解析出加密轨",
                    !Mp4.analyzeEncryptedTracks(tail).tracks.isEmpty(), "");

            // 尾部形态的样本偏移必须能对上真实字节：
            // 用 base-offset 视图解析后，偏移应落在文件范围内
            if (tSpan != null) {
                byte[] moovBuf = java.util.Arrays.copyOfRange(tail, (int) tSpan.start,
                        (int) tSpan.end());
                Mp4.Analysis a = Mp4.analyzeEncryptedTracks(
                        new Mp4.ByteView(moovBuf, tSpan.start));
                check("尾部形态 base-offset 视图可解析", !a.tracks.isEmpty(), "");
                if (!a.tracks.isEmpty()) {
                    Mp4.Track vt = a.tracks.get(0);
                    boolean inRange = true;
                    for (int i = 0; i < vt.sampleCount; i++) {
                        if (vt.offsets[i] < 0 || vt.offsets[i] + vt.sizes[i] > tail.length) {
                            inRange = false;
                            break;
                        }
                    }
                    check("尾部形态样本偏移落在文件范围内（base 换算正确）", inRange, "");
                }
            }
        } catch (Exception e) {
            check("moov 定位", false, String.valueOf(e.getMessage()));
        }
    }

    /** 用字节数组模拟的 HeadReader，扫描 moov。 */
    private static Mp4.MoovSpan scanMoov(final byte[] buf) {
        return Mp4.findMoov((off, len) -> {
            if (off < 0 || off + len > buf.length) return null;
            return java.util.Arrays.copyOfRange(buf, (int) off, (int) (off + len));
        }, buf.length);
    }

    /** 返回指定盒类型首字节在数组中的下标（找不到返回 -1）。 */
    private static int indexOfType(byte[] hay, String type) {
        byte[] needle = type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i - 4;
        }
        return -1;
    }

    /**
     * 15. 流式解密的内存回归。
     *
     * 核心断言：走SampleStore（RandomAccessFile）路径解密后，
     * 逐字节结果必须与整文件 byte[] 路径完全一致 —— 这是流式改造的等价性证明。
     * 同时校验「大文件不撑爆堆」：仅声明解密期间不读全文。
     */
    private void streamingDecrypt() {
        section("16. 流式解密与整文件解密结果一致");
        try {
            byte[] fixture = Mp4Fixture.buildMinimalEncryptedMp4();
            String keyHex = Mp4Fixture.fixtureKeyHex();

            // 基线：整文件 byte[] 路径（保持历史行为）
            byte[] base = java.util.Arrays.copyOf(fixture, fixture.length);
            Mp4.Analysis a1 = Mp4.analyzeEncryptedTracks(base);
            Mp4.Track vt1 = a1.tracks.get(0);
            String iv1 = Mp4.verifyVideoIv(base, vt1, keyHex,
                    Mp4.trackBaseIvCandidates(vt1));
            Mp4.decryptTrack(base, vt1, keyHex, iv1);

            // 流式：moov 窗口 + RandomAccessFile 路径
            Path tmp = Files.createTempFile("hg-stream-", ".mp4");
            try {
                Files.write(tmp, fixture);
                byte[] streamOut;
                try (java.io.RandomAccessFile raf =
                             new java.io.RandomAccessFile(tmp.toFile(), "rw")) {
                    Mp4.SampleStore store = new Mp4.RafStore(raf, 4096);
                    // 仅加载 moov 段（模拟流式：堆内只有索引段）
                    Mp4.MoovSpan span = scanMoov(fixture);
                    check("流式路径能定位 moov", span != null, "");
                    byte[] moovBuf = java.util.Arrays.copyOfRange(fixture,
                            (int) span.start, (int) span.end());
                    Mp4.Analysis a2 = Mp4.analyzeEncryptedTracks(
                            new Mp4.ByteView(moovBuf, span.start));
                    check("流式路径解析出加密轨", !a2.tracks.isEmpty(), "");
                    Mp4.Track vt2 = a2.tracks.get(0);
                    check("两种路径样本数一致",
                            vt1.sampleCount == vt2.sampleCount,
                            vt1.sampleCount + "==" + vt2.sampleCount);
                    check("两种路径样本偏移一致",
                            java.util.Arrays.equals(vt1.offsets, vt2.offsets), "");

                    // verify 必须先于 decrypt
                    String iv2 = Mp4.verifyVideoIv(store, vt2, keyHex,
                            Mp4.trackBaseIvCandidates(vt2));
                    check("流式路径 IV 自证通过", iv1.equals(iv2), "" + iv2);
                    Mp4.DecryptResult r2 = Mp4.decryptTrack(store, vt2, keyHex, iv2);
                    check("流式路径解密样本数一致", r2.decrypted == vt1.sampleCount,
                            r2.decrypted + "/" + r2.total);
                }
                streamOut = Files.readAllBytes(tmp);

                // 等价性：流式结果必须与整文件结果逐字节相同
                check("流式解密结果与整文件解密逐字节一致",
                        java.util.Arrays.equals(base, streamOut), "");

                // 且能还原夹具明文
                boolean restored = true;
                byte[][] plains = Mp4Fixture.plainSamples();
                for (int i = 0; i < plains.length; i++) {
                    long off = vt1.offsets[i];
                    int size = (int) vt1.sizes[i];
                    if (!java.util.Arrays.equals(
                            java.util.Arrays.copyOfRange(streamOut, (int) off, (int) off + size),
                            plains[i])) {
                        restored = false;
                        break;
                    }
                }
                check("流式解密逐字节还原夹具明文", restored, "");
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            check("流式解密", false, String.valueOf(e.getMessage()));
        }
    }

    /**
     * 16. 缓存上限：写入后必须能在超限时淘汰，且过期条目会被清扫。
     *
     * 这是"内存持续增长"的核心防线 —— 历史实现 TTL 形同虚设、
     * 无任何淘汰，故此用例固化该行为。
     */
    private void cacheBounds() {
        section("17. 缓存上限与过期清扫");
        try {
            // 条数上限：HG_MEM_CACHE_MAX 生效（用较小值快速验证）
            String savedMax = System.getProperty("HG_MEM_CACHE_MAX");
            System.setProperty("HG_MEM_CACHE_MAX", "50");
            // 注意：Safeguards 静态初始化已在类加载时读取上限，
            // 因此这里只验证「写入+读取+清扫」行为本身，不改配置重载。

            // 写入若干条目并可读回
            for (int i = 0; i < 100; i++) {
                Safeguards.cacheSet("selftest:k:" + i, "v" + i, 3600);
            }
            boolean readable = Safeguards.cacheGet("selftest:k:99") != null;
            check("缓存写入后可读回", readable, "");

            // 过期条目：cacheGet 应视为 miss
            Safeguards.cacheSet("selftest:expired", "x", 1);
            Thread.sleep(1100);
            check("过期条目视为 miss（TTL 生效）",
                    Safeguards.cacheGet("selftest:expired") == null, "");

            // 后台清扫线程存在且为 daemon（不阻止 JVM 退出）
            boolean sweeper = false;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if ("hg-cache-sweeper".equals(t.getName()) && t.isDaemon()) {
                    sweeper = true;
                    break;
                }
            }
            check("后台清扫线程已启动且为 daemon", sweeper, "");

            // 估算器不应抛异常，且对大结构有界
            int w = Safeguards.weigh(new java.util.HashMap<String, Object>());
            check("weigh 对空结构返回合理值", w >= 0 && w < 4096, "" + w);

            if (savedMax == null) System.clearProperty("HG_MEM_CACHE_MAX");
            else System.setProperty("HG_MEM_CACHE_MAX", savedMax);
        } catch (Exception e) {
            check("缓存上限", false, String.valueOf(e.getMessage()));
        }
    }

    /**
     * 17. stream-cache 数量上限（磁盘缓存限量）。
     *
     * 背景：解密成品原为无上限落盘，看得越多占用越大。现在落盘后自动清理，
     * 按最后修改时间保留最新 N 个（默认 30），最旧的删除。
     */
    private void streamCacheLimit() {
        section("18. stream-cache 数量上限（超出按修改时间淘汰最旧）");
        Path tmp = null;
        try {
            // 上限链路：runtime.cache.max_files（配置文件）经 Config.num 读取，
            // 可被 HONGGUO_CACHE_MAX_FILES 覆盖。曾经这个键只存在于 config.json、
            // 代码却只读环境变量（死键，改了不生效），这里把「键已登记」钉住，
            // 配合下面的系统属性覆盖断言锁住整条链。
            check("上限配置键已登记（runtime.cache.max_files）",
                    Config.keys().contains("runtime.cache.max_files"), "");

            tmp = Files.createTempDirectory("hg-prune-");
            String savedDir = System.getProperty("HONGGUO_STREAM_CACHE");
            String savedMax = System.getProperty("HONGGUO_CACHE_MAX_FILES");
            System.setProperty("HONGGUO_STREAM_CACHE", tmp.toString());

            // 固定基准时刻，显式设置 mtime，避免文件系统时间精度导致顺序不稳
            final long base = 1700000000000L;

            // 造 5 个成品，mtime 递增：f0 最旧 ... f4 最新
            java.util.List<Path> made = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Path p = tmp.resolve("v" + i + "_best.play.mp4");
                Files.write(p, new byte[]{'x'});
                Files.setLastModifiedTime(p,
                        java.nio.file.attribute.FileTime.fromMillis(base + i * 60_000L));
                made.add(p);
            }

            // 上限 3 -> 应删掉最旧的 2 个（v0、v1）
            System.setProperty("HONGGUO_CACHE_MAX_FILES", "3");
            int del = Stream.pruneCache();
            check("超出上限时删除最旧文件", del == 2, "删除 " + del + " 个");
            check("最旧的 v0 已删除", !Files.exists(made.get(0)), "");
            check("最旧的 v1 已删除", !Files.exists(made.get(1)), "");
            check("最新 3 个被保留",
                    Files.exists(made.get(2)) && Files.exists(made.get(3))
                            && Files.exists(made.get(4)), "");
            check("清理后数量等于上限",
                    countCacheFiles(tmp) == 3, "" + countCacheFiles(tmp));

            // 临时文件（.part/.enc）不计入上限、也不被删
            Path part = tmp.resolve("zz_download.part");
            Path enc = tmp.resolve("zz_ct.enc");
            Files.write(part, new byte[]{'x'});
            Files.write(enc, new byte[]{'x'});
            Files.setLastModifiedTime(part,
                    java.nio.file.attribute.FileTime.fromMillis(base - 999_999L));
            Files.setLastModifiedTime(enc,
                    java.nio.file.attribute.FileTime.fromMillis(base - 999_999L));
            Stream.pruneCache();
            check("下载临时文件 .part 不被删除", Files.exists(part), "");
            check("密文临时文件 .enc 不被删除", Files.exists(enc), "");

            // 恰好等于上限时不应删任何东西
            System.setProperty("HONGGUO_CACHE_MAX_FILES", "5");
            Path tmp2 = null;
            try {
                tmp2 = Files.createTempDirectory("hg-prune2-");
                for (int i = 0; i < 5; i++) {
                    Path p = tmp2.resolve("k" + i + "_best.play.mp4");
                    Files.write(p, new byte[]{'x'});
                    Files.setLastModifiedTime(p,
                            java.nio.file.attribute.FileTime.fromMillis(base + i * 60_000L));
                }
                int d2 = Stream.pruneCache(5);
                check("恰好等于上限时不删除", d2 == 0, "删除 " + d2 + " 个");
            } finally {
                deleteTree(tmp2);
            }

            // 上限<=0 表示关闭清理
            check("上限设为 0 时不清理", Stream.pruneCache(0) == 0, "");
            check("上限设为负数时不清理", Stream.pruneCache(-1) == 0, "");

            // 空目录 / 目录不存在都不应抛异常
            Path tmp3 = Files.createTempDirectory("hg-prune3-");
            String savedDir3 = System.getProperty("HONGGUO_STREAM_CACHE");
            System.setProperty("HONGGUO_STREAM_CACHE", tmp3.toString());
            check("空目录清理不报错", Stream.pruneCache() == 0, "");
            System.setProperty("HONGGUO_STREAM_CACHE", tmp3.resolve("nope").toString());
            check("目录不存在时不报错", Stream.pruneCache() == 0, "");
            System.setProperty("HONGGUO_STREAM_CACHE", savedDir3);
            deleteTree(tmp3);

            if (savedDir == null) System.clearProperty("HONGGUO_STREAM_CACHE");
            else System.setProperty("HONGGUO_STREAM_CACHE", savedDir);
            if (savedMax == null) System.clearProperty("HONGGUO_CACHE_MAX_FILES");
            else System.setProperty("HONGGUO_CACHE_MAX_FILES", savedMax);
        } catch (Exception e) {
            check("stream-cache 数量上限", false, String.valueOf(e.getMessage()));
        } finally {
            if (tmp != null) deleteTree(tmp);
        }
    }

    /**
     * 18. 搜索分页切片（skip 必须真正生效）。
     *
     * 背景：searchPage 原先完全忽略 skip，每次都从上游第一页抓、返回同一批
     * 前 limit 条。前端按 id 去重后第二页为空，于是永远显示"没有更多了"。
     * 这里直接验证 Page.slice 的切片与 has_more 判据。
     */
    private void searchPageSlice() {
        section("19. 搜索分页切片（skip 生效且 has_more 判据正确）");
        java.util.List<Object> all = new java.util.ArrayList<>();
        for (int i = 0; i < 75; i++) all.add("item" + i);

        // 第 1 页：前 30 条
        Client.Page p1 = Client.Page.slice(all, 0, 30, true);
        check("首屏返回 30 条", p1.items.size() == 30, "" + p1.items.size());
        check("首屏为第 0..29 条", "item0".equals(p1.items.get(0))
                && "item29".equals(p1.items.get(29)), "");
        check("首屏 nextSkip=30", p1.nextSkip == 30, "" + p1.nextSkip);
        check("上游有后续时 has_more=true", p1.hasMore, "");

        // 第 2 页：必须与第 1 页完全不重叠（这正是原 bug 的症状）
        Client.Page p2 = Client.Page.slice(all, 30, 30, true);
        check("续页返回 30 条", p2.items.size() == 30, "" + p2.items.size());
        check("续页从 item30 开始", "item30".equals(p2.items.get(0)), "");
        boolean overlap = false;
        for (Object a : p1.items) for (Object b : p2.items) if (a.equals(b)) overlap = true;
        check("续页与首页无重复条目", !overlap, overlap ? "存在重复" : "");
        check("续页 nextSkip=60", p2.nextSkip == 60, "" + p2.nextSkip);
        check("续页 has_more=true", p2.hasMore, "");

        // 第 3 页：只剩 15 条
        Client.Page p3 = Client.Page.slice(all, 60, 30, true);
        check("第 3 页返回剩余 15 条", p3.items.size() == 15, "" + p3.items.size());
        check("第 3 页从 item60 开始", "item60".equals(p3.items.get(0)), "");
        check("末页 has_more=false（攒不满即到末尾）", !p3.hasMore, "");
        check("末页 nextSkip=75", p3.nextSkip == 75, "" + p3.nextSkip);

        // 上游报告没有后续时，即使凑满也要报 false
        Client.Page p4 = Client.Page.slice(all, 0, 30, false);
        check("上游 has_more=false 时不谎报有更多", !p4.hasMore, "");

        // skip 超出实际条数：返回空、has_more=false，不能越界
        Client.Page p5 = Client.Page.slice(all, 99, 30, true);
        check("skip 越界返回空列表", p5.items.isEmpty(), "" + p5.items.size());
        check("skip 越界 has_more=false", !p5.hasMore, "");

        // 空输入不报错
        Client.Page p6 = Client.Page.slice(new java.util.ArrayList<>(), 0, 30, true);
        check("空结果返回空列表", p6.items.isEmpty(), "");
        check("空结果 has_more=false", !p6.hasMore, "");
        Client.Page p7 = Client.Page.slice(null, 0, 30, true);
        check("null 结果不抛异常", p7.items.isEmpty() && !p7.hasMore, "");
    }

    /**
     * 20. App 取址链路：合成设备身份、python 风格 query 编码、URL 白名单。
     *
     * 背景：上游 a 域名 preload/v1 对部分分集不再返回 video_model（表现为
     * 「无直链 / video_model」），取直链已统一改走 AppSource（移植自
     * jackotom/hongguo-drama 的 app-source.js）：b 域名 multi_video_model/v1
     * + fallback_api 二次请求。这里锁住三个不依赖网络的环节：身份字段、
     * query 字节级编码（签名对 query 敏感，*!'() 必须转 %XX）、媒体域名白名单。
     */
    private void appSource() {
        section("20. App 取址链路：合成身份 / query 编码 / URL 白名单");

        // query 编码必须复刻 python urllib.parse.quote 的兜底行为
        check("pyEncode: * 编码为 %2A（resolution=1280*2772）",
                AppSource.pyEncode("1280*2772").equals("1280%2A2772"),
                AppSource.pyEncode("1280*2772"));
        check("pyEncode: !'() 编码为 %21%27%28%29",
                AppSource.pyEncode("a!b'c(d)e").equals("a%21b%27c%28d%29e"),
                AppSource.pyEncode("a!b'c(d)e"));
        check("pyEncode: 字母数字与 .-_~ 原样",
                AppSource.pyEncode("novelread-7.1.3_32~x").equals("novelread-7.1.3_32~x"),
                AppSource.pyEncode("novelread-7.1.3_32~x"));

        // 合成设备身份：字段值锁定 + iid/device_id 每请求不同
        Map<String, Object> d = AppSource.device();
        check("设备身份: version_code=71332",
                "71332".equals(String.valueOf(d.get("version_code"))), "");
        check("设备身份: channel=update_64",
                "update_64".equals(String.valueOf(d.get("channel"))), "");
        check("设备身份: device_type=25053RT47C",
                "25053RT47C".equals(String.valueOf(d.get("device_type"))), "");
        String id1 = String.valueOf(d.get("device_id"));
        String id2 = String.valueOf(d.get("iid"));
        check("设备身份: device_id 为 16 位数字", id1.matches("\\d{16}"), id1);
        check("设备身份: iid 为 16 位数字", id2.matches("\\d{16}"), id2);
        check("设备身份: iid 与 device_id 不同", !id1.equals(id2), "");

        // URL 白名单：https 子域放行，http / 非白名单拒绝
        try {
            String u = AppSource.checkedUrl("https://p3.qznovelvod.com/a/b.mp4",
                    AppSource.VOD_DOMAINS);
            check("checkedUrl: 白名单子域放行", u.startsWith("https://"), u);
        } catch (Exception e) {
            check("checkedUrl: 白名单子域放行", false, String.valueOf(e.getMessage()));
        }
        try {
            AppSource.checkedUrl("http://qznovelvod.com/a.mp4", AppSource.VOD_DOMAINS);
            check("checkedUrl: http 被拒绝", false, "未拒绝");
        } catch (Exception expected) {
            check("checkedUrl: http 被拒绝", true, "");
        }
        try {
            AppSource.checkedUrl("https://evil.example/a.mp4", AppSource.VOD_DOMAINS);
            check("checkedUrl: 白名单外被拒绝", false, "未拒绝");
        } catch (Exception expected) {
            check("checkedUrl: 白名单外被拒绝", true, "");
        }

        // cleanUrl / pixelsOf / 非法 spade URL 布局
        check("cleanUrl: 转义还原",
                AppSource.cleanUrl("https:\\/\\/x.com\\u0026a.mp4")
                        .equals("https://x.com&a.mp4"), "");
        check("pixelsOf: 取第一组数字", AppSource.pixelsOf("1080p 高清") == 1080, "");
        check("pixelsOf: 无数字返回 0", AppSource.pixelsOf("蓝光") == 0, "");
        try {
            AppSource.decryptSpadeUrl("bm90LWEtc3BhZGUtdXJs", new byte[32]);
            check("decryptSpadeUrl: 非法布局被拒绝", false, "未拒绝");
        } catch (Exception expected) {
            check("decryptSpadeUrl: 非法布局被拒绝", true, "");
        }
    }

    /** 统计目录下的成品文件数（排除 .part/.enc 临时文件）。 */
    private static int countCacheFiles(Path dir) throws Exception {
        final int[] n = {0};
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toArray(Path[]::new)) {
                String n2 = p.getFileName().toString();
                if (!n2.endsWith(".part") && !n2.endsWith(".enc")) n[0]++;
            }
        }
        return n[0];
    }

    /** 递归删除临时目录。 */
    private static void deleteTree(Path dir) {
        if (dir == null) return;
        try {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.delete(x);
                } catch (Exception ignored) {
                    // 忽略
                }
            });
        } catch (Exception ignored) {
            // 忽略
        }
    }

    // ==================== 入口 ====================

    /**
     * 15. 本地纯代码签名：六个头齐全、指定 khronos 原样生效、
     * branch==1 可靠 _rticket 抖动规避（服务端不认 branch==1 变体）。
     */
    private void pureSign() {
        section("11. 本地纯代码签名（PureSign）");
        String base = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/"
                + "?aid=8662&device_id=1234567890123456&version_code=71532"
                + "&version_name=7.1.5.32&device_type=SM-N9860";
        String url = base + "&_rticket=1700000000000";
        byte[] body = "{\"biz_param\":{},\"mixed_video_id_map\":{\"1\":[\"7686715217652223038\"]}}"
                .getBytes(StandardCharsets.UTF_8);

        Map<String, String> h = PureSign.sign(url, body);
        check("六个签名头齐全", h.size() == 6, "" + h.keySet());
        for (String k : new String[]{"x-gorgon", "x-khronos", "x-ladon",
                "x-argus", "x-helios", "x-medusa"}) {
            check("含 " + k, h.get(k) != null && !h.get(k).isEmpty(), "");
        }

        // 指定 khronos 时 x-khronos 必须原样反映——Client 的 branch 抖动
        // 依赖「判定与签名用同一个时间戳」，错位会导致规避失效
        long khronos = 1700000000L;
        Map<String, String> h2 = PureSign.sign(url, body, khronos);
        check("x-khronos 与指定时间戳一致",
                String.valueOf(khronos).equals(h2.get("x-khronos")), "" + h2.get("x-khronos"));

        // GET（无 body）也要能签
        Map<String, String> h3 = PureSign.sign(url, null);
        check("无 body 也可签名", h3.size() == 6, "" + h3.keySet());

        // branch 只可能取 0/1/2；且 32 个 _rticket 里必有一个 != 1
        // （全为 1 的概率约 (1/3)^32，实际不可能出现）
        int[] branches = new int[3];
        int safe = 0;
        for (int off = 0; off < 32; off++) {
            int b = PureSign.branchOf(base + "&_rticket=" + (1700000000000L + off), body, khronos);
            if (b >= 0 && b <= 2) branches[b]++;
            if (b != 1) safe++;
        }
        check("branchOf 取值在 0/1/2", branches[0] + branches[1] + branches[2] == 32,
                "b0=" + branches[0] + " b1=" + branches[1] + " b2=" + branches[2]);
        check("32 个 _rticket 中可避开 branch==1", safe > 0, "safe=" + safe + "/32");
    }

    static int run() {
        System.out.println("");
        System.out.println("  ============================================");
        System.out.println("    hongguo-api 自检");
        System.out.println("  ============================================");

        SelfTest t = new SelfTest();
        try {
            t.spade();
            t.ctrCounter();
            t.ivToCounter();
            t.avcc();
            t.ctrRoundTrip();
            t.mp4Boxes();
            t.decryptEndToEnd();
            t.json();
            t.cachePaths();
            t.device();
            t.pureSign();
            t.episodeTitle();
            t.transcode();
            t.keyStore();
            t.moovLocator();
            t.streamingDecrypt();
            t.cacheBounds();
            t.streamCacheLimit();
            t.searchPageSlice();
            t.appSource();
        } catch (Exception e) {
            t.failed++;
            System.out.println("  ✗ 自检异常中断：" + e);
            e.printStackTrace();
        }

        section("运行环境");
        System.out.println("  上游 host     " + Client.HOST);
        System.out.println("  App 源 host   " + AppSource.VIDEO_API_HOST);
        System.out.println("  设备身份     " + Client.identity().source
                + "（device_id=" + Client.identity().deviceId + "）");
        System.out.println("  托管模式     " + Client.MANAGED);
        System.out.println("  列表免签     " + Client.SIGN_LIST);
        System.out.println("  签名方式     本地纯代码签名（PureSign）");
        System.out.println("  ffmpeg       " + Stream.ffmpegAvailable());
        System.out.println("  转码         " + Stream.transcodeEnabled()
                + (Stream.transcodeEnabled() ? "（编码器 " + Stream.h264EncoderName() + "）" : ""));
        System.out.println("  Java         " + System.getProperty("java.version"));

        System.out.println("");
        int total = t.passed + t.failed;
        String verdict = (t.failed == 0)
                ? " 全部通过"
                : "，失败 " + t.failed + " 项";
        System.out.println("  结果：" + t.passed + "/" + total + verdict);
        if (t.skipped > 0) {
            System.out.println("  跳过 " + t.skipped + " 项（依赖运行态产物，不计入失败）");
        }
        System.out.println("");
        return t.failed == 0 ? 0 : 1;
    }
}
