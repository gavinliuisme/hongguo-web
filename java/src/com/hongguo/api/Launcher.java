package com.hongguo.api;

import com.hongguo.api.util.Config;
import com.hongguo.api.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 跨平台启动器：直接拉起本地 API 服务。
 *
 * 取代原 Node 版 scripts/launcher.js。迁移后整条链路不再需要 Node.js 运行时，
 * 也不再需要外部的 unidbg 签名服务 —— 签名由本进程内的 PureSign 纯代码计算
 * （见 com.hongguo.api.core.PureSign），播放解密由 Stream 离线完成。
 *
 * 用法：
 *   java -jar hongguo-api.jar                    # 启动服务（默认 127.0.0.1:8000）
 *   java -jar hongguo-api.jar --config           # 打印当前生效配置后退出
 *   java -jar hongguo-api.jar --stop             # 停掉在跑的实例
 *   java -jar hongguo-api.jar --selftest         # 跑自检
 *
 * 启动即跑 API 服务：签名与解密都在本进程内完成，没有第二个组件，
 * 也就不存在"启停哪个服务"的选择 —— 原 api.enabled 开关（双服务时代的
 * 遗留：当时还要单独决定 unidbg 签名服务起不起）已移除，旧配置文件里
 * 残留该项会被忽略。
 *
 * 配置来源（优先级由高到低）：
 *   环境变量 &gt; server/config/config.json &gt; 内置默认值
 *   日常改配置请编辑 server/config/config.json，环境变量仅用于临时覆盖。
 *
 * 环境变量（覆盖配置文件用，一般不需要设）：
 *   PORT / BIND_HOST        API 服务监听，默认 8000 / 127.0.0.1
 */
public final class Launcher {

    private Launcher() {}

    // ==================== 入口 ====================

    public static void main(String[] args) throws Exception {
        // --config：只打印生效配置，不启动任何服务
        for (String a : args) {
            if (a.equals("--config") || a.equals("--show-config")) {
                printConfig();
                return;
            }
        }

        // --stop：按 jar 路径找到并停掉在跑的实例，然后退出。
        // 放在 Java 侧是因为目标与端口都来自 config.json —— 脚本要拿到
        // 它们就得先把JSON 解析一遍，等于把配置逻辑在 bat 里重写一份。
        for (String a : args) {
            if (a.equals("--stop")) {
                boolean killed = stopRunning();
                // 无论是否定位到进程都要查端口：「没找到进程」并不等于
                // 「端口是空的」——Windows 上以管理员权限启动的服务，
                // 普通权限的 --stop 读不到它的命令行，匹配会落空，
                // 而端口仍在监听。此时必须如实告知，否则用户会以为
                // 停止成功，留下一个占着端口的隐形实例。
                reportPorts(Config.num("api.port", 8000), killed);
                // 两种结果都算成功：「停掉了」和「本来就没在跑」都是
                // 幂等停止的正常结局。返回非 0 会让stop.bat 打出
                // "Service exited with code 1"，把一次正常操作报成失败。
                System.exit(0);
                return;
            }
        }

        int apiPort = Config.num("api.port", 8000);
        String bindHost = Config.str("api.host", "127.0.0.1");

        banner();

        // 同进程内直接建Server，无需再 fork 任何子进程
        Server server = new Server();
        String boot = server.ensureBootstrap();
        if (boot != null) Log.info("已签发本地链路密钥   " + boot);
        // 管理口令只在首次自动生成时输出：此时已写入 apikeys.json，
        // 重启沿用同一口令，不再重复打扰；环境变量显式设置时同样不输出。
        if (server.adminTokenFresh()) {
            Log.info("管理口令(ADMIN_TOKEN) " + server.adminToken()
                    + "（已写入 server/data/apikeys.json，重启沿用）");
        }

        server.start(bindHost, apiPort);

        // 浏览器交给 Java 侧开：launcher.open_browser 是配置项，
        // 启动脚本只负责把 JVM 拉起来，不参与任何编排。
        if (Config.bool("launcher.open_browser", true)) {
            openBrowserWhenReady(apiPort);
        }

        // 阻塞主线程：HttpServer 的工作线程是非 daemon，main 返回后 JVM
        // 也不会退出，但显式等待能确保 Ctrl-C / SIGTERM 走 JVM 默认退出
        // 流程，进程状态由 /health 与浏览器体现，控制台不补状态行。
        awaitShutdown();
    }

    /**
     * 服务就绪后打开浏览器。
     *
     * 之所以放在 Java 而不是 bat：host/port 都由 config.json 决定，
     * 脚本要拿到它们就得先把配置读一遍并回传，等于把配置解析
     * 逻辑在 bat 里重写一份。这里直接用刚启动的端口即可。
     *
     * 监听 0.0.0.0 时必须改用 127.0.0.1：0.0.0.0 是"监听所有网卡"的
     * 通配地址，浏览器拿它当目标去连会直接失败（与 bat 里原先
     * HG_BROWSER_HOST 的处理一致）。
     *
     * 单独起线程轮询 /health：Server.start() 返回时端口已经在听，
     * 但前端页面还要等密钥就绪才能真正可用，过早打开
     * 会看到空白页。超时 90s 后放弃，不阻塞主流程。
     */
    private static void openBrowserWhenReady(int apiPort) {
        final String host = browserHost();
        final String url = "http://" + host + ":" + apiPort + "/ui";
        // 探活地址必须与浏览器地址同源。此前硬编码 127.0.0.1，
        // 而服务绑定的是 api.host —— 若用户把 api.host 配成局域网 IP，
        // 服务只在那个 IP 上监听，探 127.0.0.1 永远连不上，
        // 表现就是"服务正常但浏览器一直不开"。
        final String health = "http://" + host + ":" + apiPort + "/health";
        Log.info("就绪后自动打开 " + url);
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 90_000L;
            while (System.currentTimeMillis() < deadline) {
                if (probe(health, 2000)) {
                    String err = browse(url);
                    if (err == null) {
                        Log.info("已打开浏览器");
                    } else {
                        Log.warn("自动打开失败，请手动打开网页：" + url);
                        Log.info("  原因：" + err);
                    }
                    return;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            Log.warn("等待服务就绪超时，未自动打开浏览器，请手动访问 " + url);
        }, "hg-open-browser");
        t.setDaemon(true);
        t.start();
    }

    /** 浏览器可连接的地址：0.0.0.0 / :: 是监听通配符，不能作为连接目标。 */
    private static String browserHost() {
        String h = Config.str("api.host", "127.0.0.1");
        if (h.isEmpty() || h.equals("0.0.0.0") || h.equals("::")) return "127.0.0.1";
        return h;
    }

    /**
     * 用系统默认浏览器打开 URL。
     *
     * <p>只走 {@link java.awt.Desktop}，不降级到 {@code explorer.exe} /
     * {@code xdg-open} / {@code cmd /c start} 这类命令行方式。
     *
     * <p>为什么不留命令行兜底：{@code Desktop.browse()} 本身就是平台原生
     * 入口（Windows 上内部走 ShellExecute，macOS 上走 Launch Services）。
     * 它失败几乎都不是"缺一条命令"，而是环境根本没有图形会话 ——
     * 无头容器、WSL、纯 SSH、CI runner。这类环境下再 fork 出 explorer.exe
     * 或 xdg-open 同样不会成功，只是把"打不开"伪装成"启动了但没反应"，
     * 反而更难判断。失败就如实告知，让用户自己开。
     *
     * @return 成功返回 null；失败返回原因描述
     */
    private static String browse(String url) {
        try {
            if (!java.awt.Desktop.isDesktopSupported()) {
                return "当前环境不支持 AWT（可能是无图形界面或未安装桌面环境）";
            }
            java.awt.Desktop d = java.awt.Desktop.getDesktop();
            if (!d.isSupported(java.awt.Desktop.Action.BROWSE)) {
                return "当前环境不支持 Desktop.BROWSE 动作";
            }
            d.browse(java.net.URI.create(url));
            return null;
        } catch (Throwable t) {
            // AWT 在部分平台会抛 HeadlessException / UnsupportedOperationException
            String msg = t.getMessage();
            return "AWT 调用失败" + (msg == null || msg.isEmpty() ? "" : "：" + msg);
        }
    }

    /**
     * 探测 HTTP 端点是否可用。
     *
     * <p>只求「连得上、有响应」，不校验响应体内容：就绪判定交给
     * 前端自身的重试逻辑，这里过早深入反而会把 Web 层的策略
     * 复制到启动器里。
     *
     * @param url       目标地址
     * @param timeoutMs 单次连接/读取超时
     * @return 收到任何 HTTP 响应（含 4xx/5xx）即视为可用
     */
    private static boolean probe(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            return code > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 阻塞主线程直到进程收到终止信号。
     *
     * 没有子进程需要收编（签名与解密都在本进程内），
     * 这里只把主线程挂住，让 Ctrl-C / SIGTERM 走 JVM 默认退出流程。
     * 控制台不额外打印状态行：服务是否在跑，看浏览器或 /health 即可，
     * 末尾再补一行提示只是噪声。
     */
    private static void awaitShutdown() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 启动横幅。只打印纯ASCII 的系统信息。
     *
     * 这里刻意不输出中文标题：控制台代码页与 JVM 编码一旦不一致，
     * 中文会变成「红红果果」式的重复乱码，而纯 ASCII 在任何代码页下
     * 都正常。需要中文标识时可看浏览器标题栏或日志文件。
     */
    private static void banner() {
        Log.info("  Hongguo Short Drama - Web Launcher (Java Edition)");
        Log.info("  " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + "  Java "
                + System.getProperty("java.version"));
    }

    /**
     * 打印当前生效配置（{@code --config}）。
     *
     * 供用户确认「我改的配置到底生效没有」——排查配置问题时，
     * 比起逐层追问日志，直接看这一段最快。
     */
    private static void printConfig() {
        System.out.println();
        System.out.println("  生效配置（环境变量 > 配置文件 > 默认值）");
        System.out.println("  " + "-".repeat(56));
        String src = Config.loadedFrom();
        System.out.printf("    配置文件      : %s%n",
                src == null ? "未找到（使用内置默认值）" : src);
        // 版本号也一并打出来：排查「装的到底是哪一版」时，
        // 光看配置路径不够，还得知道它自称的版本。
        System.out.printf("    version       : %s%n", Config.version());
        System.out.printf("    api.host       : %s%n", Config.str("api.host", "127.0.0.1"));
        System.out.printf("    api.port       : %d%n", Config.num("api.port", 8000));
        System.out.printf("    签名方式       : %s%n", "本地纯代码签名（PureSign，内置）");
        System.out.printf("    transcode      : %s%n", Config.bool("runtime.transcode", false));
        System.out.printf("    data_dir       : %s%n",
                Config.str("runtime.data_dir", "server/data"));
        System.out.println("  " + "-".repeat(56));
        System.out.println();
    }

    // ==================== 停止在跑的实例 ====================

    /**
     * 停掉本机在跑的服务进程（{@code --stop}）。
     *
     * <p>按 jar 路径匹配，而不是按端口：端口会随 config.json 变，
     * 而 jar 路径是稳定的。
     *
     * <p><b>两个条件缺一不可</b>：进程本体必须是 JVM 或 Node，
     * <i>并且</i>命令行里出现目标文件名。只看后者的教训很实际：
     * 任何提到这个文件名的进程都会被误杀——shell 里那句
     * {@code java -jar .../hongguo-api.jar --stop}、编辑器标签页、
     * 甚至一次 grep，都会命中，而它们与本服务毫无关系。
     * 加上进程类型这一层之后，能通过的只剩下真正在跑的实例。
     *
     * <p>兼容更早版本：机器上可能还留着 Node 时代的
     * {@code launcher.js} / {@code server.js}，或已废弃的
     * {@code unidbg-sign.jar} 签名服务进程，一并收编。
     *
     * @return 至少停掉一个进程时返回 true
     */
    private static boolean stopRunning() {
        String[] names = {"hongguo-api.jar", "unidbg-sign.jar",
                "launcher.js", "server.js"};
        long self = ProcessHandle.current().pid();
        List<ProcessHandle> targets = new ArrayList<>();
        ProcessHandle.allProcesses()
                .forEach(h -> {
                    if (h.pid() == self) return;          // 本进程（--stop 自己）
                    if (!isRuntimeProcess(h)) return;      // 必须是 JVM / Node
                    String cmd = commandLineOf(h);
                    if (cmd == null) return;
                    // 排除"正在执行停止"的进程（可能是本进程，也可能是
                    // 用户同时在另一个窗口敲了 stop）。一次停止不该打断
                    // 另一次停止：两个 --stop 并发时若互杀，双方都会被
                    // SIGTERM 打断，调用方拿到 128+15 的退出码，
                    // 看起来像失败，其实只是撞车。
                    if (cmd.contains("--stop")) return;
                    // 大小写不敏感：Windows 文件名与命令行均不区分大小写，
                    // 而用户启动服务时用的路径大小写不受我们控制。
                    String lower = cmd.toLowerCase();
                    for (String n : names) {
                        if (lower.contains(n.toLowerCase())) { targets.add(h); return; }
                    }
                });

        if (targets.isEmpty()) {
            Log.info("没有找到正在运行的 API 进程。");
            return false;
        }
        for (ProcessHandle h : targets) {
            String cmd = commandLineOf(h);
            String tag = cmd != null && cmd.contains("unidbg-sign.jar") ? "signer(旧版)" : "api";
            System.out.println("   停止 [" + tag + "] PID " + h.pid());
            kill(h);
        }
        // 给操作系统一点时间回收 socket，否则紧接着的端口检查仍会报占用
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /**
     * 进程本体是否是 Java 运行时或 Node。
     *
     * <p>这正是"进程类型"这一层需要的判据：凡是想杀的，都是这两个
     * 运行时起的；而误伤对象（shell、编辑器、grep）都不是。
     *
     * <p><b>必须截到基名再比</b>。{@code command()} 在 Linux 上返回
     * 完整路径（实测 {@code /root/.sdkman/.../bin/java}），在 Windows 上
     * 才是 {@code java.exe}。直接拿返回值比"java"会永远失配，
     * 结果是真实例一个也找不到。
     *
     * <p><b>command() 为空时回退到 commandLine() 的首个 token</b>：
     * Windows 上对高权限或异会话进程，{@code command()} 偶有返回空
     * 的情况（此时命令行往往仍可读）。只认 command() 会把这类
     * 真实实例全部漏掉，表现为"没有找到正在运行的 API 进程"
     * 而服务其实还在跑。
     *
     * <p>读不到名字时返回 false（宁可不杀，不误杀）。
     */
    private static boolean isRuntimeProcess(ProcessHandle h) {
        String exe;
        try {
            exe = h.info().command().orElse("");
        } catch (Throwable t) {
            exe = "";
        }
        if (exe.isEmpty()) {
            String cmd = commandLineOf(h);
            if (cmd != null && !cmd.trim().isEmpty()) {
                String first = cmd.trim();
                int sp = first.indexOf(' ');
                exe = sp > 0 ? first.substring(0, sp) : first;
            }
        }
        int cut = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
        if (cut >= 0) exe = exe.substring(cut + 1);
        String n = exe.toLowerCase();
        if (n.endsWith(".exe")) n = n.substring(0, n.length() - 4);
        return n.equals("java") || n.equals("javaw") || n.equals("node");
    }

    /** 读进程命令行；无权限时返回 null。 */
    private static String commandLineOf(ProcessHandle h) {
        try {
            return h.info().commandLine().orElse(null);
        } catch (Throwable t) {
            // 安全策略或权限不足：跳过该进程
            return null;
        }
    }

    /**
     * 结束一个进程树：先 SIGTERM 给它走正常退出流程，仍在世就强杀。
     *
     * <p>只对直接子进程调 destroy() 不够：目标 JVM 可能再 fork 出孙进程，
     * 强杀父进程后孙进程会被 init 收养并继续持有端口，表现为
     * 「端口仍被占用」。因此先收编子孙再收编自己。
     */
    private static void kill(ProcessHandle h) {
        List<ProcessHandle> all = new ArrayList<>();
        try {
            h.descendants().forEach(all::add);
        } catch (Throwable ignored) {
            // 读不到子孙就只杀自己
        }
        all.add(h);
        for (ProcessHandle d : all) {
            if (!d.isAlive()) continue;
            try {
                d.destroy();
                d.onExit().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 超时或不支持：落到强杀
            }
            if (d.isAlive()) {
                try {
                    d.destroyForcibly();
                    d.onExit().get(1, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 已经退出
                }
            }
        }
    }

    /**
     * 报告配置里的端口是否仍被占用。
     *
     * <p>两种调用场景，措辞完全不同：
     * <ul>
     *   <li>{@code killed=true}：真的停掉了实例，端口仍占用通常意味着
     *       有另一个实例在跑，或杀进程需要管理员权限；</li>
     *   <li>{@code killed=false}：没能定位到进程，端口却还在听 ——
     *       典型原因是服务以管理员权限启动（当前权限不足，读不到它的
     *       命令行），或从本项目的另一份拷贝启动。此时必须点破，
     *       否则用户会以为停止成功，留下一个占着端口的隐形实例。</li>
     * </ul>
     */
    private static void reportPorts(int apiPort, boolean killed) {
        if (!portBusy(apiPort)) return;
        if (killed) {
            Log.warn("端口 " + apiPort + " 仍被占用。若有其他实例在跑属正常；"
                    + "否则可尝试以管理员身份运行，或查看：netstat -ano | findstr " + apiPort);
        } else {
            Log.warn("端口 " + apiPort + " 仍在监听，但未能定位到进程。");
            Log.warn("  常见原因：服务以管理员权限启动（当前权限不足），"
                    + "或从本项目的另一份拷贝启动。");
            Log.warn("  可手动结束：netstat -ano | findstr " + apiPort
                    + " 查到 PID 后执行 taskkill /PID <pid> /F，"
                    + "或在任务管理器结束对应的 java.exe。");
        }
    }

    /**
     * 端口是否仍在接受连接。
     *
     * <p>用 TCP 连接探测，而不是解析 netstat：后者的输出格式三个平台
     * 各不一样（Windows 是 LISTENING，Linux 是 LISTEN，macOS 又是另一套），
     * 照某一种写正则必然在别的平台上失灵。一次 connect 在所有平台
     * 语义一致：连得上，就是有东西在听。
     *
     * <p>连不上时视为未占用：防火墙拦截、绑定在别的网卡上都会走到这里，
     * 宁可漏报也不误报 —— 停止流程本身已经结束，这里只是补充信息。
     */
    private static boolean portBusy(int port) {
        String host = Config.str("api.host", "127.0.0.1");
        boolean wildcard = host.isEmpty() || host.equals("0.0.0.0") || host.equals("::");
        String[] candidates = wildcard ? new String[] {"127.0.0.1"}
                                       : new String[] {host, "127.0.0.1"};
        for (String h : candidates) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(h, port), 1000);
                return true;
            } catch (Exception ignored) {
                // 换下一个候选地址
            }
        }
        return false;
    }
}
