# DSH 的 profile / 插件层（决定 dsh 层到底要装什么）

> 本文是实测结论。**它推翻了"dsh 层只需要 `npm i -g @deepseek-ai/dsh`"这个假设**，
> 是 §5.3 层内容定义的修正依据。

## 1. DSH 的启动模型（实测）

`dsh web` 并不是"跑一个 CLI"，而是：

1. `dsh` 入口 `lib/bin.js`（ESM，`bin: {"dsh": "lib/bin.js"}`），`web` 是 `--profile web` 的硬编码别名。
2. 通过 `@deepseek-ai/dsh-app-boot` 的 `loadLayeredEnv` 加载 **profile 工作区**：
   `$DSH_HOME/profiles/<profile>/`。
3. profile 由 **bundle 列表**组合而成。实测 `/root/.dsh/profiles/web/package.json`：

```json
{
  "name": "dsh-profile-web",
  "private": true,
  "dependencies": {
    "dsh-device-shell-guide": "link:/root/dsha-device-shell-guide",
    "dsh-task-notifier": "link:/root/dsha-task-notifier",
    "dsh-status-overlay": "link:/root/dsha-status-overlay",
    "dsh-web-mobile": "link:/root/dsha-web-mobile",
    "dsh-app-integration": "link:/root/dsha-app-integration"
  },
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app",
        "dsh-device-shell-guide",
        "dsh-task-notifier",
        "dsh-status-overlay",
        "dsh-web-mobile",
        "dsh-app-integration"
      ],
      "patchReload": "startup"
    }
  }
}
```

4. `profiles/web/cordis.yml` 是**空入口列表** `[]`，整棵树由各 bundle 的 `cordis.patch.yml`
   以 patch 形式插入：

```yaml
- insert:
    - id: dsh-web-mobile
      name: 'dsh-web-mobile'
```

5. 插件包通过 `package.json` 的 `dsh.bundle.patch` 声明自己的 patch；
   客户端插件再声明 `dsh.client`（`platform: "web"` + `inject` 到具体客户端 UI 包）。
6. 插件的 `node_modules` 在 DSHA 里是**指向 DSH 自身 `node_modules` 的符号链接**，
   用来解析 `peerDependencies`（`@deepseek-ai/cordis`、`dsh-system-prompt`、`dsh-llm` 等）。
   所以这些插件目录本身很小（23–335 KB）。

**结论：一个能用的 mobile profile 必须把 `$DSH_HOME/profiles/**` 一起交付，
只装全局 npm 包是不完整的 —— 界面会是桌面的，也没有移动端适配。**

## 2. DSHA 所用插件的来源与许可（**法律边界**）

| 插件 | 版本 | 许可 | npm 公开 | 可复用性 |
|---|---|---|---|---|
| `dsh-web-mobile` | `2.4.1-dsha.2` | **MIT**（有 LICENSE 文件） | ✅ `2.4.1` | ✅ 可用。作者 **mexiaosh**，[GitHub](https://github.com/mexiaosqwq/dsh-web-mobile) 公开 |
| `dsh-task-notifier` | `0.1.3` | **MIT**（声明，无 LICENSE 文件） | ✅ `1.1.0` | ✅ 建议改用 npm 公开版 |
| `dsh-device-shell-guide` | `0.1.19` | **MIT**（声明，无 LICENSE 文件） | ❌ E404 | ⚠️ MIT 允许复用，但须附许可与来源；这是 DSHA 内置插件 |
| `dsh-status-overlay` | `0.1.4` | **MIT**（声明，无 LICENSE 文件） | ❌ E404 | ⚠️ 同上 |
| `dsh-app-integration` | `1.1.0` | **无 license 字段** | ❌ E404 | ❌ **默认保留所有权利，不得复制进本项目** |

`dsh-app-integration` 自述功能是「DSHA 后台任务、网页返回和草稿恢复适配」。
**我们的 App 用原生方式（前台服务、WebView、通知）承担这部分职责，因此不需要它。**

## 3. 本项目的决定

### 3.1 dsh 层的完整内容（修正 §5.3）

```
/usr/local/lib/node_modules/@deepseek-ai/dsh/**    # DSH 主体（含其 191 个依赖）
/usr/local/bin/dsh -> ../lib/node_modules/@deepseek-ai/dsh/lib/bin.js
/root/.dsh/profiles/**                             # ★ 新增：profile 工作区
```

- `/root/.dsh/profiles/**` 放在**只读 dsh 层**里，用户状态（`sessions/`、`settings.yaml`、
  `.credentials.yaml`、`storages/` 等）由运行时写入**可写上层**。
  overlayfs 天然完成这个切分，**不需要符号链接**。
- 好处：`reset`（清空可写层）会把 profile 恢复成出厂状态，而用户数据按设计被清掉——语义正确；
  升级 dsh 层即可整体替换 profile 与插件，用户数据不受影响。

### 3.2 v1 的 profile bundles（**零 DSHA 私有代码依赖**）

```json
"dsh": { "profile": {
  "bundles": [
    "@deepseek-ai/dsh-base",
    "@deepseek-ai/dsh-web-app",
    "dsh-web-mobile",
    "dsh-task-notifier"
  ],
  "patchReload": "startup"
}}
```

- 全部来自**公开 npm**。
- 原生集成（通知、后台、返回手势、草稿）由**我们的 App**负责，不依赖 `dsh-app-integration`。
- 不携带任何无许可代码，可直接分发。

### 3.3 后续可选

- 自研 `dshroid-device-shell`（设备能力插件）：需要与 App 的 `/app/*` 桥接能力对接时再写。
- 自研 `dshroid-status-overlay`：如果确实需要"锁屏/悬浮条式"状态展示。
- 若想复用 MIT 的 `dsh-device-shell-guide` / `dsh-status-overlay`，**必须**：
  随附 MIT 许可文本、注明原作者与来源、并保留版权声明。**这是可选项，不是默认。**

### 3.4 打包注意

- 保持 `@img/*` 目录结构不被展平（sharp 的 libvips 靠 RPATH `$ORIGIN` 定位，见 findings §6.3）。
- 插件的 `peerDependencies` 必须能解析到 `@deepseek-ai/cordis` 等。两种做法：
  1. 与 DSHA 相同：把每个插件目录下的 `node_modules` 指向 DSH 自身的 `node_modules`；
  2. 更干净：在 profile 根用一个统一的 `node_modules` 承载所有插件与 peer 依赖，
     避免每个插件各自一份软链。
  **推荐做法 2**，并在 `doctor` 里校验插件能否被解析。
- 层构建时**先在宿主/CI 上把 profile 装好再打包**，不要依赖设备首次运行去联网装插件。

## 4. 给实现的强制要求

1. `supervise.sh` 启动 DSH 时必须确保 `DSH_HOME=/root/.dsh`，
   且 `/root/.dsh/profiles/web/` 已存在（来自只读层）。缺失时要在日志里**明确报错**，
   而不是静默让 DSH 自己联网装插件。
2. `doctor` 必须检查 profile 与插件可解析性（例如
   `linuxctl exec -- node -e "import('dsh-web-mobile').then(()=>console.log('ok'))"` 之类的探针），
   失败要给出明确指引。
3. 层构建脚本**不得**把 DSHA 的 `/root/dsha-*` 私有目录或 `dsh-app-integration` 打进产物。

---

## 5. ✅ 已验证的配方（实测跑通，可直接照抄）

下面这条链路**已在本机完整跑通**：用**只用公开 npm 包**搭出 profile，DSH 成功启动，
鉴权链路 401 → 303 → 200 全部验证通过。**零 DSHA 私有代码依赖。**

### 5.1 目录与文件

```
$DSH_HOME/profiles/web/package.json
$DSH_HOME/profiles/web/cordis.yml        # 内容就是 []
$DSH_HOME/profiles/web/node_modules/     # 插件 + peer 依赖的链接
```

`package.json`（实测可用）：

```json
{
  "name": "dsh-profile-web",
  "private": true,
  "dependencies": {
    "dsh-web-mobile": "^2.4.1",
    "dsh-task-notifier": "^1.1.0"
  },
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app",
        "dsh-web-mobile",
        "dsh-task-notifier"
      ],
      "patchReload": "startup"
    }
  }
}
```

### 5.2 安装步骤（实测命令）

```bash
cd $DSH_HOME/profiles/web
npm install --legacy-peer-deps --no-audit --no-fund     # 实测：added 11 packages in 8s

# ★ 关键：把 @deepseek-ai/* 指向 DSH 自带的 node_modules
ln -sfn /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai \
        $DSH_HOME/profiles/web/node_modules/@deepseek-ai
```

> **为什么必须用软链而不是让 npm 装**：npm 上 `@deepseek-ai/dsh-llm`、
> `@deepseek-ai/dsh-base`、`@deepseek-ai/dsh-web-app` 等子包虽然公开，
> 但**版本严重滞后**（只有 `0.0.1-rc.1`，而 DSH 主体是 `0.1.5-rc.2`）。
> 插件的 `peerDependencies` 要求 `0.1.5-rc.2` → **从 npm 解析会装到错误版本**。
> DSH 主体的 `node_modules/@deepseek-ai` 里有全部 240 个匹配版本的包，软链过去才是对的。
> （实测：不软链则插件无法解析 peer 依赖。）

### 5.3 启动与鉴权（实测结果）

```bash
DSH_HOME=/root/.dsh dsh web --no-open --host 127.0.0.1 --port 3080
```

stdout 实测输出（绑定 127.0.0.1 时**没有** `(LAN: ...)` 后缀）：

```
dsh web: http://127.0.0.1:3099/?token=OZqp9Fe5V7TiCKHe01jOAn6FdEgCXZ_THoU-DyvWAys
```

解析命令（**实测有效**，注意 `[^ ]*` 而非 `.*`）：

```bash
URL=$(sed -n 's/^dsh web: \([^ ]*\).*/\1/p' "$LOG" | head -1)
```

鉴权链路（curl 实测）：

| 请求 | 结果 |
|---|---|
| `GET http://127.0.0.1:3099/` | **401** `dsh web authentication required; ...` |
| `GET <带 token 的 URL>` | **303** → `Location: http://127.0.0.1:3099/` + `Set-Cookie` |
| 带 Cookie `GET /` | **200**，正文 148998 字节（DSH Web GUI） |

→ 这证明 WebView 只要加载 `status.dsh.url` 就能自然落到已鉴权的 `/`。

### 5.4 层构建时的落点

在宿主/CI 上按 §5.1–5.2 把 profile 装好，然后整体打进 `dsh.squashfs`：

```
/usr/local/lib/node_modules/@deepseek-ai/dsh/**
/usr/local/bin/dsh
/root/.dsh/profiles/web/{package.json,cordis.yml,node_modules/**}
```

⚠️ 打包时**必须保留软链结构**（`@deepseek-ai` 是指向 DSH 自身 node_modules 的软链）。
**注意 squashfs 支持符号链接**，`mksquashfs` 默认保留；不要在构建时用 `-no-symlinks` 之类选项。

### 5.5 ✅ 已实现并实测的安装脚本

仓库已提供 **[`rootfs/profiles/install-web-profile.sh`](../rootfs/profiles/install-web-profile.sh)**，
模板在 **[`rootfs/profiles/web-profile/`](../rootfs/profiles/web-profile/)**。

实测结果（两次独立运行）：

| 步骤 | 实测 |
|---|---|
| `npm install --legacy-peer-deps` | `added 11 packages` / 5–6 s |
| 软链 `@deepseek-ai` | 自动算成**相对路径**软链 |
| 内置自检（4 个包的 `import` 探针） | 全部 `ok` |
| 用产出的 profile 启动 `dsh web` | 成功，打印带 token URL |
| 鉴权链路 | 裸 `/` → 401，带 token → 303，带 Cookie → 200（148998 字节） |

**‼️ 相对软链的深度陷阱（必须遵守）**

相对软链的层级是在**安装时**按目标目录深度算出来的。所以：

- **必须在最终路径上直接安装**：构建 chroot 里就把 profile 装到 `/root/.dsh/profiles/web`，
  **不要**先装到临时目录再 `cp`/`mv` 过去——那样软链层级会算错，运行时插件解析失败。
- 运行时合并后的 rootfs 里路径仍是 `/root/.dsh/profiles/web`，深度一致，软链有效。
- 若你确实需要改变安装路径，请**重新运行安装脚本**，不要搬目录。

（实测佐证：同一份 profile 装到不同深度的目录，算出的软链层数会不同，说明层级随深度变化。）

### 5.6 ✅ 分层打包已端到端实测（含软链存活验证）

已用真实内容产出一个完整的 `dsh` 层并逐项验证：

| 步骤 | 实测结果 |
|---|---|
| 暂存树（硬链接，不占额外空间） | 25380 个文件，apparent 190 MB |
| profile 装到**最终路径** `root/.dsh/profiles/web` | 自动算出 **5 层**相对软链，自检 4 项全 ok |
| `mkfs.erofs -b 4096 --all-root` | 镜像 **201.5 MB**，29045 inode |
| `dump.erofs --ls` 看 profile 的 `node_modules` | `@deepseek-ai` 的 **TYPE=7（符号链接）** ✅ |
| **`fsck.erofs --extract` 解出后检查软链** | 目标串逐字节保留、**在树内可达** ✅ |
| 关键文件抽查 | `usr/local/bin/dsh`、`lib/bin.js`、`profile/package.json`、`cordis.yml`、`dsh-web-mobile/package.json` 全在 ✅ |
| `assert_no_forbidden` / `assert_profile_symlink` | 真实产物 rc=0；**绝对软链与缺失软链都能抓住（rc=1）** ✅ |
| `.erofs.zst` / `.erofs.gz` | **31.2 MB** / **47.8 MB**，`zstd -d`/`gzip -dc` **往返逐字节一致** ✅ |
| **层内自带的 `dsh` 直接启动** | 成功打印 `dsh web: http://…/?token=…` ✅ |
| 该实例的鉴权链路 | 裸 `/`→**401**、带 token→**303**、带 Cookie→**200**（148998 字节）✅ |

⇒ **dsh 层自足可启动**，且"软链在打包后是否存活"这个最大风险点已被证明没问题。
产物在 `dist/dsh-0.1.5-rc.2.erofs.{zst,gz}`，哈希见 `dist/SHA256SUMS.dsh-layer.txt`。

**‼️ 层级复核（独立验证，务必记住）**：真实布局下
`relpath('/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai',
'/root/.dsh/profiles/web/node_modules')` = **5 层** `../../../../../`；
写成 4 层会解析到 `/root/usr/local/...`（错的）。
**所以绝不要在文档或代码里手写层数**——一律用 `os.path.relpath` 动态算，
并由 `layer_spec_assert_profile_symlink` 在打包前拦截。

## 6. `pnpm` 是 runtime 层的必需组件（实测）

`dsh plugin --profile web <args...>` 的实现在 `lib/plugin-*.js`：

```js
const result = spawnSync("pnpm", args.map(...), { ... });
// 失败时：
//   `${NAME}: pnpm not found on PATH — install pnpm to manage profile plugins`
```

**结论：`pnpm` 必须存在于 PATH 上。** 否则用户无法用
`dsh plugin --profile web add <包名>` 安装第三方插件。

这直接关系到本项目的核心诉求之一——**"拿不到第三方开发的内测内容"**：
- DSH 的插件安装机制本身就是开放的（pnpm registry 名、本地路径 spec 都能传）。
- 只要 runtime 层带上 `pnpm`，用户就能自行安装任何第三方发布的 DSH 插件，无需等上游发版。

**因此 `runtime` 层必须包含 `pnpm`。** 推荐做法：在装 Node 的同时
`npm i -g pnpm`（装到 `/opt/node` 的 prefix 下，保证 `pnpm` 落在 PATH 里，且版本随层锁定）。

其它实测到的行为（`lib/plugin-*.js` 注释）：
- pnpm 以 **profile 目录为 cwd** 运行，所以 `.`、`../plugin` 这类相对路径 spec、
  registry 包名、以及其余 pnpm 参数都是**原样透传**的。
- 该命令会在 profile **不存在时自动初始化**，随后做
  `dsh.profile.bundles` 与已安装状态的**对账（reconcile）**。
- 用户通过 `dsh plugin add` 装的插件会写进
  `/root/.dsh/profiles/web/node_modules/`——该路径来自**只读层**，
  overlayfs 会自动 copy-up 到可写上层。
  → 语义正确：`reset` 之后用户自装的插件会消失，回到出厂的 4 个 bundle。
  → 若要把某个第三方插件"固化进发行版"，应改 `web-profile/package.json` 重新构建层，
    **不要**依赖设备上的手工安装。
