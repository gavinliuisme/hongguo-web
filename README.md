# 红果短剧 · 网页版

## 应用预览

**主界面**

![主界面截图](screenshot/main.png)

**播放器**

![播放器界面截图](screenshot/player.png)

红果短剧网页版 —— 后端 Java（零第三方依赖，JDK 20）+ 前端静态网页。

---

## 一、下载发行包

启动前先去 **[Releases 页](https://github.com/sunnysky123/hongguo-web/releases)**
下载与自己系统匹配的那个压缩包，解压就能跑。
**不需要克隆仓库，也不需要预装 Java** —— 每个包都自带对应平台的 Java 运行时。

### 挑哪个包

| 你的系统 | 下载的文件（`1.0.1` 为例） |
|---|---|
| Windows x86-64（绝大多数 PC） | `hongguo-web-1.0.1-Windows-x64.zip` |
| Windows ARM64（骁龙等 ARM 电脑） | `hongguo-web-1.0.1-Windows-arm.zip` |
| Linux x86-64（主流发行版、x86 云服务器） | `hongguo-web-1.0.1-Linux-x64.zip` |
| Linux ARM64（树莓派、ARM 云主机、ARM 发行版） | `hongguo-web-1.0.1-Linux-arm.zip` |
| macOS Apple 芯片（M 系列） | `hongguo-web-1.0.1-macOS-arm.zip` |

> 文件名里的版本号就是 `server/config/config.json` 的 `version` 字段，
> 和包内「平台说明.txt」末尾标的版本一致 —— 两者对得上就说明下对了。
>
> `Windows-arm` 包内置的是同版本的 **x64** JRE：Temurin 没有发布 Windows on ARM
> 的 JRE 25 构建，而 Windows on ARM64 原生兼容 x64 程序，直接用即可。
> 其他四个包都是目标平台的原生运行时。

### 解压并确认结构

用系统自带的解压工具（Windows 下右键「全部解压缩」，
Linux / macOS 下 `unzip hongguo-web-1.0.1-*.zip`）解开后，进入解压出来的目录：

```bash
# Linux / macOS 示例：包内结构直接铺在当前目录
ls
```

应看到 `web/`、`java/`、`server/`、`scripts/`、
`jre/` 以及「平台说明.txt」。`jre/` 就是内置的 Temurin JRE 25，**无需再装 Java**。

* Windows 资源管理器会多套一层 `hongguo-web-1.0.1-Windows-x64\` 同名文件夹，进不去记得再钻一层。
* 命令行 `unzip` 不会自动套文件夹，包内结构直接铺在当前目录。

包内结构就是完整可运行的程序，后面所有命令都在这个目录里执行。

### 只想跑源码的话（可选）

一般用户用不到。要改代码或自定义构建，才需要克隆仓库：

```bash
git clone https://github.com/sunnysky123/hongguo-web.git
cd hongguo-web
```

仓库本身不含 Java 运行时，从源码运行需要本机已有 Java 20+ 或自备 JRE；
运行时从哪来、怎么配，见「三、环境要求」。

---

## 二、一键启动

发行包解压后进入解压目录，按平台执行：

| 平台 | 启动 | 停止 | 构建 JAR |
|---|---|---|---|
| Windows | 双击 `scripts\start.bat` | 双击 `scripts\stop.bat` | 双击 `scripts\build-java.bat` |
| Linux / macOS | `bash scripts/start.sh` | `bash scripts/stop.sh` | `bash scripts/build-java.sh` |

`start` 脚本会检查 Java 运行时、按需构建 API 服务 JAR，直接拉起服务，
并**自动打开浏览器**访问 **<http://127.0.0.1:8000/>**。
`stop` 脚本按进程类型 + jar 路径双重匹配，不会误杀其他 Java 进程。

端口、监听地址等配置都集中在「六、配置项」一节，改完保存即生效。

不想用脚本时也可以直接跑 jar（各平台通用）：

    java -jar java/dist/hongguo-api.jar                # 启动
    java -jar java/dist/hongguo-api.jar --config       # 查看生效配置
    java -jar java/dist/hongguo-api.jar --selftest     # 跑自检
    java -jar java/dist/hongguo-api.jar --stop         # 停掉在跑的实例
    java -jar java/dist/hongguo-api.jar --version      # 显示版本

JAR 不存在时先按上表构建。

### 自动打开浏览器

`start` 脚本启动后会在后台轮询 `GET /health`，
待 API 服务**真正就绪**（约 1~2 秒）后自动打开 <http://127.0.0.1:8000/>。

之所以轮询而不是固定延时，是因为 `Server.start()` 返回时端口虽已在听，
前端页面还要等密钥就绪才能真正可用，过早打开会看到空白页。
临时不想自动打开，见「六、配置项」的 `HG_OPEN_BROWSER`。

### API 服务 JAR 的构建

`java/dist/hongguo-api.jar` **已入库**，clone 或发行包均开箱即用，
无需 JDK、无需编译；`start` 脚本只在 JAR 缺失时（被删除）才会现场编译。
注意：**修改 java/src 源码后需手动重跑上表中的构建命令更新 JAR**
（脚本不做源码时间戳检测），此时才需要本机有 JDK 20+（或把 `jre` 换成完整 JDK）。

> 只需要 JDK 的 `javac` 与 `jar`，不需要 Maven / Gradle，不联网。

---

## 三、环境要求

| 组件 | 版本 | 是否必需 |
|---|---|---|
| Java 运行时 | **>= 20**；发行包已内置 `jre`（**Temurin 25 LTS**，按目标平台） | 必需 |
| JDK（含 javac） | 20+ | 仅重新构建时需要（发行包已内置 JAR） |
| ffmpeg / ffprobe | 任意近期版本 | 可选。剥离 CENC 信令默认走纯 Java 减壳，仅布局不符时回落；转码 HEVC 时才必需 |

### Java 运行时从哪来

**发行包**：每个平台的包都由 `scripts/pack.sh` 打包时放入对应平台的
Temurin JRE 25 LTS，解压即用，目标机无需预装 Java；
启动脚本也不会联网下载任何运行时。

**源码 checkout**：仓库里没有 JRE，`start.bat` / `start.sh` 按以下顺序查找：

    jre\（自行解压的） > JAVA_HOME > PATH

三者都没找到时脚本会提示如何处理并退出，不会自动下载。两种处理方式：

1. 装 Temurin 20+：<https://adoptium.net/>
2. 或把任意 JRE 20+ 解压到项目的 `jre\` 目录（需含 `bin\java.exe`）

```bash
java -version   # 需 20+
javac -version  # 仅重新构建 API 服务时需要
ffmpeg -version # 可选
```

> **若你的 Java 低于 20**：`java/dist/hongguo-api.jar` 是用 JDK 20 编译的
> （class 文件版本 64），低版本运行会报 `UnsupportedClassVersionError`。
> 两种解决办法：
> 1. **直接用**发行包自带的 `jre/`（Temurin 25 LTS，按目标平台），无需任何操作；
>    仓库本身不含 JRE，从源码运行时才需要本机已有 Java 或自行解压到 `jre/`；
> 2. **自行重新编译**——用你本地的 JDK（20 及以上）重新构建即可，产物适配你的版本：
>    ```bash
>    bash scripts/build-java.sh      # Linux / macOS
>    scripts\build-java.bat          # Windows
>    ```
>    构建只依赖 `javac` 与 `jar`，不联网、不需要 Maven。

---

## 四、前端使用

### 打开方式

| 方式 | 地址 | 说明 |
|---|---|---|
| 同源（推荐） | <http://127.0.0.1:8000/> | 静态资源与 API 同源，无需额外配置 |
| 本地文件 | `web/index.html` 双击打开 | `API_BASE` 自动回落 `http://127.0.0.1:8000` |

### 首次使用会自动拿密钥

前端首次加载会调用 `GET /api-key` 自动签发本地密钥并存入 `localStorage`，
无需手动配置。

拿不到密钥时（比如后端还在冷启动），页面**不会把你卡在一张空卡片上**：

| 情况 | 页面表现 |
|---|---|
| 刚打开页面 | 不弹卡片，只在列表区提示「未连接到后端服务，正在自动重试…」，并告知运行 `scripts\start.bat` |
| 点「⚿」按钮 | 卡片内显示蓝色状态行「获取中…」并转圈 |
| 连续失败 | 状态行变橙色：「获取中：HTTP 503，正在第 2 次重试…」，退避 1s→2s→4s…最长 10s，最多 12 次 |
| 重试用尽 | 状态行变红并说明原因，同时保留「⚿ 重新获取」按钮 |
| 后端恢复 | 自动拿到密钥 → **卡片自动关闭** → 首页自动继续加载，全程无需操作 |

想让手动保存的密钥不被后台自动获取覆盖，在卡片第二行「或粘贴已有密钥」框里填入后点「保存」即可。

### 播放历史与收藏

两个入口，共享同一套列表组件：

| 入口 | 位置 | 作用 |
|---|---|---|
| 🕘 播放历史 | 顶栏搜索框右侧 | 查看最近播过的剧，点「继续播放」接着看 |
| ★ 我的收藏 | 顶栏搜索框右侧 | 查看收藏的剧，点「继续播放」接着看 |
| ☆/★ 收藏 | 播放器内，剧名右侧「简介」按钮后面 | 收藏/取消当前这部短剧 |

**历史**：任何一次实际起播（点卡片进播放器、点集数按钮切集、自动连播）都会
自动写入，**不需要手动操作**。同一部剧只保留一条，重复观看时更新集数并置顶。
按要求**不记录播放时间**。

**收藏**：默认不收藏，在播放器里点一下收藏按钮才会存。收藏时会记下当时播到
第几集；之后从历史/收藏续播到别的集数，收藏条目里的进度也会跟着更新，
不会出现「继续播放往回跳」。

**列表里每条显示**：封面（缺失时显示「无封面」占位块）、剧名、进度
（历史为「看到第 12 集 / 共 95 集」，收藏为「共 95 集 · 已看至第 12 集」）、
以及操作按钮：

| 列表 | 操作按钮 |
|---|---|
| 播放历史 | 继续播放 · 删除（删掉这一条记录） |
| 我的收藏 | 继续播放 · 取消（取消这部剧的收藏） |

弹层右上角还有「清空」，一次清掉当前列表（历史或收藏，二者独立）。
点遮罩或按 `Esc` 关闭弹层。

**数据存放**：全部在浏览器 `localStorage` 里，**不上传服务器**。
想迁移就自己把这两个键的 JSON 复制过去。

| 键 | 内容 | 上限 |
|---|---|---|
| `hg_history` | 播放历史 | 200 条 |
| `hg_fav` | 收藏列表 | 200 条 |

超过 200 条自动丢弃最旧的。清缓存/清站点数据会一并清掉历史与收藏。

**行为说明**：
- **从推荐/搜索点开看过的剧，会自动续播到上次看到的那一集**（例如历史记录是第 15 集，
  点开直接播第 15 集）。没看过的剧、或历史只到第 1 集时，才从第 1 集开始。
  搜索结果与推荐列表里，看过但没看完的剧会在封面左下角标出「看到第 N 集」，
  提示点开会续播。
- 历史记录里的集号超出当前总集数（剧下架、集数缩减）时，自动回落到第 1 集。
- 列表里的封面通过后端 `/img` 代理加载（与搜索结果同一套白名单），
  所以换浏览器打开时封面可能需要重新加载。

### 自动连播与预热

播放任意一集时，前端会**并行预热下一集**：

```
播放第 N 集  ──┬──> /stream?vid=<第N集>     （正在播）
                └──> /prewarm?vid=<第N+1集>  （后台解密落盘，不阻塞）
```

`/prewarm` 立即返回（实测 11ms），解密在后台进行。返回体里的 `state` 表示：

| state | 含义 |
| --- | --- |
| `cached` | 已在缓存目录，直接可用（切集秒开） |
| `warming` | 后台解密中 |

前端播放器右上角有「连播 开 / 连播 关」开关，状态存在 `localStorage` 的
`hg_autonext`（`0` 为关闭）。关闭后播完停在当前集，状态栏提示
「播放结束（连播已关闭）」；播完最后一集提示「已播完最后一集」。
单集播放（无剧集列表）时不会触发连播。

> **默认不做转码**：服务端只剥离 CENC 信令，源流 HEVC(H.265) 直出。
> 若你的机器缺 HEVC 解码支持，开启转码的办法见常见问题「画面全黑」。
>
> 解密产物落在 `server/stream-cache/<vid>_<清晰度>.play.mp4`。
> 注意扩展名是 `.play.mp4` 而非 `.mp4`（剥离 CENC 信令后会改名），
> 手写脚本判断缓存是否存在时不要只找 `.mp4`。
>
> **信令剥离默认不依赖 ffmpeg**：解密后由纯 Java 代码删信令盒、修补盒尺寸、
> 平移 chunk 偏移、原样流拷 mdat，产物即 `.play.mp4`。仅当文件布局不在
> 减壳支持范围（如 moov 在尾部）时才回落 ffmpeg remux；ffmpeg 也未安装时
> 产物为 `<vid>_<清晰度>.raw.mp4`——内容已是解密后的明文（仅未剥离信令），
> 可直接播放；缓存命中判定同时覆盖 `.play.mp4` / `.mp4` / `.raw.mp4`
> 三个变体。日后装上 ffmpeg，旧的 `.raw.mp4` 会在下次命中缓存时自动补做
> 剥离，升级为 `.play.mp4`。

### 列表分页与「加载更多」

「推荐 / 榜单 / 最新 / 筛选」与搜索的网格末尾会挂一张**「加载更多」卡片**，
点击它加载下一页，点击后卡片转为「加载中…」并转圈，此时不可再次点击；
数据取尽后该卡片变为「已经到底了」并停止响应。

> 用的是显式点击而非滑动到底自动加载：移动端浏览器的滚动到底触发不稳定
> （快速滚动、列表重排、惯性未停时都容易漏触发），显式按钮更可控。

新卡片只追加不重绘，滚动位置不会跳动，「加载更多」卡片始终保持在网格最后一项。

列表类接口都接受分页参数，便于脚本按需取全量：

| 接口 | 分页参数 | 说明 |
|---|---|---|
| `/rank` | `offset` | 偏移量，响应含 `has_more` 与 `next_offset` |
| `/latest` | `offset` + `seen` | `seen` 为已取过的 series_id 逗号串，用于去重 |
| `/browse` | `offset` + `seen` | 同上 |
| `/search` | `offset` | 响应含 `has_more` 与 `next_offset` |

首屏不带分页参数时行为与从前一致。`seen` 只回传最近一段（前端取尾部
若干条），因为上游 `filter_ids` 过长会显著增大请求体。

---

## 五、API 列表

除 `/`、`/ui`、`/img`、`/health`、`/api-key`、`/stats`、`/admin/*` 外，
所有接口都需带本地密钥：

```bash
curl -H "x-api-key: <你的 key>" "http://127.0.0.1:8000/rank?board=recommend"
```

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 服务与端点自述 |
| GET | `/health` | 健康检查（含签名方式状态） |
| GET | `/api-key` | 自动签发本地密钥（免鉴权） |
| GET | `/search?q=&page=` | 搜索（本地签名，自动走官网/App 双通道） |
| GET | `/search/page?q=&page=` | 同上，分页别名 |
| GET | `/rank?board=recommend\|hot\|new&limit=` | 榜单 |
| GET | `/latest?genre=short_play\|comic_series\|ai_series&only_today=true` | 最新 |
| GET | `/filters?genre=short_play` | 筛选维度 |
| GET | `/browse?genre=&theme=&sort=&days=` | 组合浏览 |
| GET | `/episodes?series_id=` | 剧集列表 |
| POST | `/metrics/batch` | 批量上报播放进度 |
| GET | `/play?series_id=&ep=1-10` | 取各集直链（`encrypted_url` 为上游直链，可播放用 `stream_url`） |
| GET | `/video_url?vid=` | 单集取直链 |
| GET | `/stream?vid=&quality=1080p` | **可播放**（服务端离线解密，支持 Range） |
| GET | `/stream?series_id=&ep=1` | 同上，按集号 |
| GET | `/prewarm?vid=` | **预热下一集**（后台解密落盘，立即返回，供自动连播用） |
| GET | `/img?u=<URL>` | 图片代理（含 HEIC 转换） |
| GET | `/download?vid=&quality=` | 下载解密后的成品 |
| GET | `/stats` | 运行统计 |
| GET/POST/DELETE | `/admin/keys`, `/admin/keys/revoke` | 密钥管理（需 `ADMIN_TOKEN`） |

### 健康检查

```bash
curl http://127.0.0.1:8000/health
```

```json
{
  "ok": true,
  "uptime_s": 313,
  "sign_backends": [
    { "url": "local", "ready": true, "probe": "pure-sign" }
  ],
  "api_host": "api5-normal-sinfonlinea.fqnovel.com",
  "ffmpeg": true,
  "transcode": false,
  "h264_encoder": null
}
```

> `sign_backends` 是兼容旧版 consumers 的形状字段，
> `url: "local"` 表示签名在本进程内完成，恒就绪。
> 末尾三个字段反映转码环境：未开启转码时 `transcode` 为 `false`、
> `h264_encoder` 为 `null`。

### 播放示例

```bash
# 按 vid + 清晰度
curl -H "x-api-key: <key>" \
  "http://127.0.0.1:8000/stream?vid=7621226646028160024&quality=1080p" -o ep1.mp4

# 按剧集 + 集号（文件名自动带上剧名）
curl -H "x-api-key: <key>" \
  "http://127.0.0.1:8000/stream?series_id=7621200213801176126&ep=1" -o ep1.mp4
```

`quality` 可取 `360p` / `480p` / `540p` / `720p` / `1080p` 或 `best`（默认，取最大体积）。
支持 `Range` 请求（返回 `206 Partial Content`），播放器可拖动进度。

---

## 六、配置项

通过环境变量与 `server/config/config.json` 传入，均有合理默认值；
优先级为 **命令行参数 > 环境变量 > 配置文件 > 内置默认值**。

| 变量 | 默认 | 说明 | 配置文件 |
|---|---|---|---|
| `PORT` | `8000` | 监听端口 | `api.port` |
| `BIND_HOST` | `127.0.0.1` | 监听地址。**改成 `0.0.0.0` 即对外暴露，务必同时设 `ADMIN_TOKEN`** | `api.host` |
| `ADMIN_TOKEN` | 空 | `/admin/*` 与 `/stats` 的管理令牌。不设时首次启动自动生成 64 位口令并写入 `server/config/apikeys.json` 的 `admin_token`，重启沿用；环境变量优先 | `server/config/apikeys.json` 的 `admin_token` |
| `RATE_PER_MIN` | `120` | 每分钟请求上限（限流） | 无，仅环境变量 |
| `HG_THROTTLE_MS` | `260` | 上游请求串行节流间隔（毫秒） | `runtime.throttle_ms` |
| `DEVICE_POOL_SIZE` | `0` | 设备身份池大小，0 为关闭 | 无，仅环境变量 |
| `HONGGUO_HOST` | 见 config | 上游 host | 无（默认取 `config.json` 的 `upstream.api_host`） |
| `HONGGUO_UA` | `hongguo-web/1.0` | 上游 User-Agent | 无（默认取 `config.json` 的 `upstream.session_headers.user-agent`） |
| `HONGGUO_DATA_DIR` | `server` | 视频解密缓存目录（缓存落在其下 `stream-cache/`；设备标识与链路密钥同存于 `server/config/apikeys.json`，不随本项迁移） | `runtime.data_dir` |
| `HONGGUO_STREAM_CACHE` | `server/stream-cache` | 解密成品缓存 | 无，仅环境变量 |
| `HONGGUO_CACHE_MAX_FILES` | `30` | 缓存文件数上限，超出后按最后修改时间删除最旧的；设为 `0` 表示不限 | `runtime.cache.max_files` |
| `HONGGUO_SESSION_API_KEY` | 空 | 设置后进入「托管模式」，固定单设备、禁止刷新登录态 | 无，仅环境变量 |
| `HG_TRANSCODE` | 空（不转码） | 设为 `1` 时把 HEVC 转成 H.264，仅在机器缺 HEVC 解码器时才需要 | `runtime.transcode` |
| `HONGGUO_PURE_STRIP` | 空（启用） | CENC 信令剥离方式：空 = 纯 Java 减壳（默认，不依赖 ffmpeg）；`0` = 强制走 ffmpeg remux | 无，仅环境变量 |
| `HG_H264_ENCODER` | 空（自动探测） | 强制指定 H.264 编码器，如 `libx264` / `libopenh264` | 无，仅环境变量 |
| `HG_SIGN_LIST` | 见代码 | 免签接口列表（一般不需改） | 无，仅环境变量 |
| `HG_OPEN_BROWSER` | 空（跟随配置） | 设为 `0` 时服务就绪后不自动打开浏览器 | `launcher.open_browser` |
| `HG_LOG_ENCODING` | 空（跟随控制台） | 强制日志编码，如 `UTF-8` | 无，仅环境变量 |
| `WEB_DIR` | `<项目根>/web` | 前端目录 | 无，仅环境变量 |

> 「配置文件」列给出该环境变量在 `server/config/config.json` 里对应的键名，
> 标「无，仅环境变量」的项没有配置文件入口。配置文件里还有一批表中未列出的
> 运行时调优键——缓存 `mem_max` / `mem_max_mb` / `img_max_mb` / `img_max_kb`、
> 限流桶 `rate_buckets_max` / `rate_bucket_idle_ms`、`sweep_interval_ms` 等，
> 每项都带 `_xxx提示` 注释说明用途；环境变量用于临时覆盖，优先级更高。

### 输出与日志

控制台即全部输出：服务地址、首次自动生成的管理口令、运行统计、告警等都直接
打印在终端（管理口令仅在 `apikeys.json` 里没有、系统自动生成时提示一次，重启
沿用不再重复显示）。启动脚本已瘦身为薄壳，不再写任何日志文件——早期版本
`server/data/log/start.log` 一套已随脚本瘦身移除。

若需要留档，自行重定向即可，例如：

```bash
# Linux / macOS
bash scripts/start.sh > run.log 2>&1
```

```bat
:: Windows
start.bat > run.log 2>&1
```

---

## 七、自检

```bash
java -jar java/dist/hongguo-api.jar --selftest
```

覆盖 21 组共 **162 项**断言：

| 组 | 内容 |
|---|---|
| 1 | spade 解包 5 组运行时真值、ver2 类型拒绝 |
| 2 | AES-128-CTR 计数器构造（高位保留、进位、`0xff+1`） |
| 3 | 逐样本 IV → CTR 计数器（8字节 IV 位于高 64 位、16 字节原样保留、回退 base_iv） |
| 4 | AVCC / NAL 自证（Annex-B、长度链、多 NAL、越界与 forbidden_zero_bit 拒绝） |
| 5 | AES-128-CTR 加解密往返一致性 |
| 6 | MP4 盒解析（构造最小合法 moov，含 ftyp/moov/tenc/senc） |
| 7 | 端到端解密：IV 自证、全部样本解密、AVCC 自洽、逐字节还原、错误 key 拒绝 |
| 8 | JSON 往返与数字语义（转义、精度、skipNulls、`1.0` 输出为 `1`） |
| 9 | 播放缓存路径（`.play.mp4` 变体命中判定） |
| 10 | 设备身份 device_id（格式、注入 base_query、落盘、`iid` 保持 0） |
| 11 | 本地纯代码签名：六个头齐全、时间戳一致、branchOf 取值与 branch==1 抖动规避 |
| 12 | 剧集解析：title 清洗（上游 title 实为简介） |
| 13 | 解码兼容性：默认不转码（直出 HEVC） |
| 14 | 本地链路密钥管理；管理口令生成与 apikeys.json 持久化 |
| 15 | moov 定位：尾部 moov 也能找到（流式解密前置） |
| 16 | 流式解密与整文件解密结果一致 |
| 17 | 缓存上限与过期清扫 |
| 18 | stream-cache 数量上限（超出按修改时间淘汰最旧；上限取 `runtime.cache.max_files`，可被 `HONGGUO_CACHE_MAX_FILES` 覆盖） |
| 19 | 搜索分页切片（skip 生效且 has_more 判据正确） |
| 20 | App 取址链路：合成设备身份字段、python 风格 query 编码（`*!'()` 转 %XX）、媒体 URL 白名单 |
| 21 | CENC 信令纯 Java 减壳（sinf/senc 移除、stco 平移且指向新 mdat、条目类型按 frma/hvcC 改回 hvc1、mdat 逐字节一致、盒链完整、尾部 moov/垃圾输入拒绝、co64 与无 frma 变体） |

这些都是踩坑后固化的回归基线，用于判断「迁移是否等价」。
预期结尾输出：

```
  结果：162/162 全部通过
```

自检不访问网络、不依赖任何外部服务，可在离线环境运行。

---

## 八、目录结构

```
hongguo-web/
├── README.md                  ← 本文件：快速上手 + 完整说明
├── scripts/
│   ├── start.bat / start.sh   一键启动
│   ├── stop.bat / stop.sh     停止服务
│   ├── build-java.bat / .sh   构建 API 服务 JAR（纯 javac，无需联网）
│   └── pack.sh                打包 5 个平台发行版（内置 JRE）
├── java/
│   ├── src/com/hongguo/api/   Java 后端源码（22 个文件，零第三方依赖）
│   │   ├── Main.java          入口（--config / --stop / --selftest / --version）
│   │   ├── Launcher.java      启动编排（API 服务 + 浏览器）
│   │   ├── Server.java        HTTP 路由 + 鉴权 + 限流
│   │   ├── SelfTest.java      自检套件
│   │   ├── Mp4Fixture.java    自检用最小加密 mp4 夹具
│   │   ├── core/
│   │   │   ├── PureSign.java     本地纯代码签名（六个请求头）
│   │   │   ├── Spade.java        spade -> content key
│   │   │   ├── Sm3.java          SM3 摘要（x-medusa 签名用）
│   │   │   ├── Mp4.java          BMFF 盒树 + CENC 解密 + 纯 Java 减壳（剥离信令）
│   │   │   ├── Device.java       本机稳定 device_id
│   │   │   ├── KeyStore.java     密钥管理
│   │   │   └── Safeguards.java   风控 + 缓存 + 节流
│   │   ├── service/
│   │   │   ├── WebSource.java    官网数据源（搜索/分集列表）
│   │   │   ├── AppSource.java    App 取址链路（b 域名 v1 + fallback_api）
│   │   │   ├── Client.java       上游 API 客户端（签名 + 六请求头）
│   │   │   ├── Stream.java       下载 + 解密 + remux
│   │   │   └── Res.java          响应辅助（JSON/静态/Range）
│   │   └── util/
│   │       ├── Config.java       配置加载（JSON + 环境变量覆盖）
│   │       ├── Json.java         自研轻量 JSON
│   │       ├── Crypto.java       MD5 / AES-128-CTR / hex
│   │       ├── Http.java         HttpURLConnection 封装
│   │       └── Log.java          日志与配置读取
│   ├── dist/hongguo-api.jar   API 服务 JAR（已入库，clone 即用）
│   └── build/                 编译中间产物
├── jre/                       Java 运行时（仅发行包内有；仓库不含）
├── server/
│   ├── config/config.json      启动配置（端口、监听、调优参数、上游接口参数 upstream 段）
│   ├── config/apikeys.json     链路密钥、管理口令与 device_id（首次启动自动生成，不入库）
│   └── stream-cache/           解密成品缓存（运行时生成，不入库）
└── web/                       前端静态资源（可直接浏览器打开）
    ├── index.html
    ├── styles.css
    └── app.js
```

---

## 九、常见问题

**Q：`/search` 返回 `code:100103 PARAM_INVALID`？**

A：按顺序排查：

1. 确认 `server/config/apikeys.json` 存在且内容合法（`device_id` 与密钥同存此文件）：

   ```json
   { "device_id": "7091873872252626" }
   ```

   缺失则删除该文件重启，服务会自动生成一个新的。
2. 若配置里显式写了 `device_id`，值必须是 10~20 位纯数字。非法值会被忽略并重新生成。
3. 确认没有把 `HONGGUO_DEVICE_ID` 设成非法值。
4. 多实例同时运行时，用 `HONGGUO_DEVICE_ID` 给每个实例指定不同且稳定的值。

> **为什么 `device_id` 是必需的**：搜索接口会比其它接口多校验一项设备标识。
> 实测同一套请求参数，缺 `device_id` 时上游返回 53 字节的
> `PARAM_INVALID`，补上后即 `code=0` + 完整结果（几十~上百 KB）。
>
> **不要手动加 `iid`**：实测 `device_id` 搭配一个随机 `iid` 会被风控拦截，
> 返回 HTTP 200 但 body 为空。修复方案因此只生成 `device_id`。

`device_id` 相当于本机的设备指纹，**请勿随意删除或改动** `apikeys.json` 里的
`device_id`：短时间内身份跳变会被上游判定为异常流量。

**Q：首播为什么慢？第二集为什么还要等一会儿？**

A：首播需要「下载密文 + 离线解密」，单集约 3–14MB，通常十几秒；
完成后写入 `HONGGUO_STREAM_CACHE`，同集再次播放为秒开
（可用 `quality=360p` 降低首播耗时）。预热能让切集秒开，但只在
**上一集正在播**时触发；若你直接跳到最后一集，前面没有可预热的窗口，
就会正常等待。已预热过的集，日志里能看到 `[prewarm] ...`
且 `/prewarm` 返回 `state=cached`（机制见「前端使用 → 自动连播与预热」）。

**Q：画面全黑，但进度条和时间轴在正常走？**

A：源流是 **HEVC(H.265)**，服务端默认直出不转码。你的浏览器或系统若缺
HEVC 解码支持，就会这样——`readyState` 正常到 4、`videoWidth` 却是 0。
页面已内置检测，会在播放器下方直接给出这条提示。

解决办法任选其一：

1. 换用最新版 Chrome / Edge（桌面版对 HEVC 支持较好）；
2. 安装 HEVC 硬件解码支持（显卡驱动 + 系统解码器）；
3. 开启服务端转码，让它转成兼容性最好的 H.264（较慢，一集约 15 秒）：

   ```bat
   set HG_TRANSCODE=1 && scripts\start.bat
   ```

> 转码默认**关闭**。实测 1080×1920 整集剥离信令约 1 秒（纯 Java 减壳），
> 转 H.264 约 16 秒，所以支持 HEVC 的机器没必要转。
> 若确实开了转码但没找到编码器，日志会提示并退回只剥离信令。

**Q：没装 ffmpeg 能播放吗？**

A：能。剥离 CENC 信令默认走**纯 Java 减壳**（删信令盒、修补盒尺寸、
平移 chunk 偏移、原样流拷 mdat），全程不调用外部进程，产物即
`.play.mp4`。只有文件布局不在减壳支持范围时（如 moov 在文件尾部）
才回落 ffmpeg remux；此时若 ffmpeg 也未安装，产物为 `.raw.mp4`——
内容已是解密后的明文，同样能播，只是仍带 CENC 信令。缓存命中判定
同时覆盖 `.play.mp4` / `.mp4` / `.raw.mp4` 三个变体，日后装上 ffmpeg，
旧的 `.raw.mp4` 会在下次命中缓存时自动补做剥离。
调试时可用 `HONGGUO_PURE_STRIP=0` 强制走 ffmpeg 路径做对照。

**Q：列表接口返回空 body？**

A：这是上游的风控行为，与本地无关：`search` / `multi_video_detail` /
App 取址（b 域名 `multi_video_model/v1`）三个接口上游要求带签名，
若请求未带或签名不被接受，上游返回 HTTP 200 + 空 body。本项目对这三个
接口固定走本地签名，正常不会触发；若偶发出现，稍后重试即可。
排查时先核对上游端点路径（取直链为 b 域名 `multi_video_model/v1`，
其余为 `preload/v1`），再查签名。

**Q：播放失败，日志提示「无直链 / video_model」？**

A：个别分集（后集高发）上游已不再从 a 域名 `preload/v1` 返回 video_model，
取直链已统一改走 App 取址链路（`AppSource`：b 域名 `multi_video_model/v1` +
跟随 `fallback_api` 二次请求，媒体 URL 带 `key_seed` 时自动解密）。
若仍失败，将该分集编号与日志反馈给维护者；
临时规避可换其他清晰度或稍后重试。

**Q：启动时报 `BindException: Address already in use`？**

A：API 服务端口被占用。改端口重试：

```bat
set PORT=8001 && scripts\start.bat
```

或先运行 `scripts\stop.bat` 清理残留进程。

**Q：Windows 双击 `start.bat` 后服务起不来？**

A：先看窗口里的首行错误。常见两种：
- 端口被占用 → 见上一条 `BindException` 的处理
- `UnsupportedClassVersionError` → Java 版本过低，改用发行包自带的 `jre/`；
  或按「三、环境要求」末尾的办法用本地 JDK 重新构建 JAR

**Q：控制台中文显示成「Java锛歫re\bin\java.exe」这类乱码？**

A：这是编码不匹配 —— JVM 按 UTF-8 输出字节，而控制台按 GBK(936) 解码。
程序默认**跟随控制台**，正常情况下不该出现。若你手动给 `java` 加过
`-Dstdout.encoding=UTF-8`，请去掉：它会强制 JVM 输出 UTF-8 字节，
与 GBK 控制台正好错开。脚本里已经不再传这个参数。

若确实需要指定编码（例如把输出重定向进文件再交给别的工具分析），
用环境变量而不是 JVM 参数：`HG_LOG_ENCODING=UTF-8`（不设则跟随控制台）。

**Q：启动后浏览器没有自动打开？**

A：日志里会明确写出结果，两种情况：

| 日志 | 含义 |
|---|---|
| `已打开浏览器` | 正常，通过系统桌面 API 打开成功 |
| `自动打开失败，请手动打开网页：…` | 无图形会话（无头服务器/WSL/SSH），手动复制地址即可 |

服务本身不受影响。想关闭自动打开，见「六、配置项」的 `HG_OPEN_BROWSER`。

**Q：包太大？**

A：完整包约 56MB，其中大部分是内置的 Temurin JRE 25（压缩后约 45MB）——
换来的是目标机无需预装 Java。
若目标机已有 Java 20+（Temurin 25 LTS），删掉包内 `jre/` 目录即可正常启动，
启动脚本会自动回落到系统 Java（查找顺序见「三、环境要求」）。

**Q：打包要联网吗？**

A：要。`pack.sh` 从 Adoptium 按平台下载对应 JRE，首次约 250MB。
下载的 JRE 缓存在 `.pack-cache/`，同一平台重复打包不再联网。
只想打一个平台时用 `bash scripts/pack.sh Linux-arm`，
`bash scripts/pack.sh --list` 可列出全部平台名。

**Q：停止后端口还被占用？**

A：`stop` 脚本按「进程类型 + jar 路径」双重匹配，不会误杀其他 Java 进程。
若仍占用，先用**管理员身份**重跑该脚本；若提示「端口仍在监听，但未能定位到
进程」，说明服务是以更高权限或从项目另一份拷贝启动的，当前权限读不到它的
命令行 —— 用 `netstat -ano | findstr <端口>` 查到 PID 后 `taskkill /PID <pid> /F`
手动结束，或在任务管理器结束对应的 `java.exe`。

**Q：解密报「content key 与密文不匹配」？**

A：多为 `spade` 与密文不配套（重新请求轨道信息会刷新 `spade_a`），
或该内容为 ver2（AES-GCM）路径 —— 当前实现只支持 ver1。

**Q：缓存目录越来越大？**

A：已自动限量。每次解密落盘后会清理 `stream-cache`，按**最后修改时间**保留最新
30 个成品、最旧的自动删除（`.part` / `.enc` 临时文件不计入也不删）。
上限可在「六、配置项」调整。

**Q：想换成本地 key？**

A：`GET /api-key` 会自动签发；也可用 `ADMIN_TOKEN` 调 `/admin/keys` 管理。

**Q：怎么切换集数？**

A：视频下方左侧有个「第 xx 集」按钮，点击后从按钮**上方**展开选集卡片，
里面按**一行五个**排列全部集数，当前集红色高亮，点任意一个即切换，卡片自动收起。
卡片内可滚动（集数多时），点卡片右上角 ✕、按 `Esc` 或点击卡片外部都可关闭。
单集播放（无剧集列表）时该按钮自动隐藏。想从第 1 集重看，在这里选第 1 集即可。

> 集数**只在左侧按钮上出现**。播放器下方正中的状态区只放播放状态，
> 播放成功后为空；集数信息不再重复出现在状态位。

**Q：剧集简介默认能看到吗？**

A：默认**隐藏**。剧集名右侧有个「简介」小按钮，鼠标移上去就在下方浮出简介段落；
鼠标移开、按 `Esc` 或点别处即收起。点击按钮也能切换显示/隐藏，方便触屏或键盘操作。
简介为空时按钮自动隐藏，不占位。

**Q：为什么状态栏文案不会把弹层顶高？**

A：状态栏固定占两行高度（`min-height: 3em` + `line-height: 1.5`），
「解密并缓冲中」这类短提示与 HEVC 兜底长提示占位完全一致，切换时不产生位移。

**Q：手机上顶栏挤得下吗？**

A：320px 起可用。极窄屏（≤430px）下搜索按钮只保留 🔍 图标、图标按钮缩到 30px，
分类标签自己横向滚动。实测 320 / 360 / 390 / 430 / 560 / 768px 六档
`document.scrollWidth` 均等于视口宽度，无横向滚动。

**Q：首次启动一直显示「获取中」，是不是坏了？**

A：不是。后端冷启动约 1~2 秒（签名在本进程内计算，没有外部服务要等），
这期间 `/api-key` 拿不到是正常的。页面会按退避节奏自动重试，后端一就绪就
自动拿到密钥、**卡片自动关闭**、首页自动加载出来，全程不用管
（各状态与重试机制见「前端使用 → 首次使用会自动拿密钥」）。

如果一直失败，按卡片里的橙色/红色状态行看原因：
- 「HTTP 503 / 无法连接」→ 后端没起来，去项目目录跑 `scripts\start.bat`（或 `bash scripts/start.sh`）
- 「已停止自动重试」（红色）→ 多半是反向代理剥掉了 `api_key` 参数，
  或浏览器访问的地址和实际跑后端的实例不是同一个；确认后点「⚿ 重新获取」

**Q：服务重启后提示要重新填密钥？**

A：不用填。收到 401 时前端会自动换新密钥并重新加载，卡片会自己关掉。
这期间最多自动尝试 5 次、每次至少间隔 2 秒；
真的连续失败才会停下来提示，避免无限重试把接口打爆。

---

## 致谢

本项目的逆向分析思路与部分实现，参考自以下开源项目：

- **[hongguo-desktop-releases](https://github.com/waligoraamodio288-rgb/hongguo-desktop-releases)** —— 桌面端版本，提供了本项目最初的分析起点与对照实现，感谢原作者的分享。
- **[jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama)** —— 纯代码签名（x-gorgon / x-khronos / x-ladon / x-argus / x-helios / x-medusa）与官网网页数据源的参考实现，本项目 `core/PureSign.java` 与 `service/WebSource.java` 移植自此。

---

## 注意事项

- 签名与解密全部在服务进程内完成，不监听任何额外端口；
  默认只监听 `127.0.0.1`，若改为 `0.0.0.0` 对外暴露，**必须**设置 `ADMIN_TOKEN`
  （环境变量，或写进 `server/config/apikeys.json` 的 `admin_token`）。
- 目录穿越防护已启用（`path.resolve` + 前缀校验），静态资源优先于鉴权路由。
- 本项目仅供技术研究与学习，请勿用于批量抓取或传播受版权保护的内容。
