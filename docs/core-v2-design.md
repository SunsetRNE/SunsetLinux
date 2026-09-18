# SunsetLinux · 内核（core）v2 设计提案

> 状态：**提案，待拍板**（§6 的五条决策定了才动手）。
> 背景：用户判断"现有 root 架构是从免 root 演化来的，不太合适了，需要重新设计架构内核部分"。
> 本文件先把**为什么**钉在证据上，再给**新内核的边界与不变量**，最后给可分段发布的迁移路径。

---

## 1. 先看证据：2026-09-18 一晚的六个事故

这六个都不是"手滑"，它们反过来指认同一处结构问题。

| # | 现象（用户看到的） | 直接原因 | 指认的结构缺陷 |
|---|---|---|---|
| 1 | 点「仅启动环境」后五张卡片全灰、「启动 DSH」点了没反应 | `start.sh` **前台** spawn 守护进程 ⇒ `entry.sh`（按设计永不退出）让整条 `su -c … start` 链永不返回 ⇒ App 的 `busy` 永久为真 | **控制面靠"命令返回"**：UI 状态与一个进程的生死耦在一起 |
| 2 | 「端口被占，没有写偏移」 | 让路后的端口只在抓到 URL 后才写 | 状态**由各调用方各自推导**，没有"谁说都算数"的状态源 |
| 3 | 「脚本似乎在尝试重复挂载？」`run/mounts` 26 条、两条守护链 | `running_ns_pid` 只认"已就绪"，建树 30~50s 窗口里判成 stopped ⇒ 第二次 start 又建一棵树 | **生命周期没有单一所有者**：判据是"某几个文件 + 某个 pid 活着"，没有世代/锁/中间态 |
| 4 | 宿主 `grep -c sunsetlinux /proc/1/mountinfo` = **33** | `make-rprivate` 唯一那次尝试在 util-linux 可取到**之前**，兜底从未执行 ⇒ 传播从未被切断 | **命名空间/挂载树是流程副作用**，没有所有者、没有"必须成立的前置条件" |
| 5 | `stop` 反复报 loop 残留、卸不掉 | `cleanup_stale_loops` 见到 loop 还挂在**别的 ns** 就跳过 detach；泄露副本无人负责 | **teardown 没有契约**：靠"逆序 umount + 猜"而不是"谁建谁拆、且可核对" |
| 6 | 模块包漏掉 `runtime/common/host-residue.sh` | `mkmodule.sh` 的清单是硬编码 | 单一事实源原则没有贯彻到**打包/契约**（同一类问题在 0.2.6 的内嵌离线包上出现过） |

**一句话结论**：现在的"内核"其实是**一串会被前台/后台、信号、竞态、传播属性影响的 shell 流程**。
它承担了内核该做的事（生命周期、命名空间、挂载、端口、状态），却没有内核该有的**所有者、
状态机、契约与不变量**。

---

## 2. 现状内核都在哪（盘点，别重造已有的东西）

| 关注点 | 现在由谁负责 | 问题 |
|---|---|---|
| "在不在跑" | `start.sh:running_ns_pid`、`linuxctl.sh:ns_pid_alive/env_ready`、App 的 `DshStatus.parse`、WebUI 自己的判据 | **四份实现**，语义靠注释对齐（今晚第 3 条就是它们不一致的产物） |
| 状态存放 | `run/` 下 14 个文件：`ready` `started` `supervisor.pid` `env-mode` `layer-mode` `mounts` `mounted.json` `start.lock` `last-error` `stopping` `dsh.{pid,port,url}` `service.log`… | 没有 schema、没有版本、没有原子快照；**多写者**（宿主 + 环境内 + 模块） |
| 会话/命名空间 | "恰好活着的那个 `unshare -m` 子进程" | 没有世代号、没有所有者记录、没有前置条件（传播属性） |
| 挂载树 | `start.sh` 的 `build_mount_tree` 顺序流程 | 建与拆是两段独立的猜；泄漏无人管 |
| 端口 | `config_port` → `port_pick_free` → `supervise.sh` 解析 URL 写回 | 三次推导、两处落盘时机 |
| 命令语义 | `linuxctl <verb>` 同步返回；App 等它返回 | 长任务（建树 30~50s、provision 数十分钟）与超时全靠猜 |
| 合同 | §3.1 的 `status` JSON（App 依赖，冻结） | 只是**只读快照**，没有"提交作业/订阅事件"的通道 |

**非内核**（v2 不动）：建层与频道（`device-provision.sh`、`channel.json`、验签）、更新与回滚、
App UI、WebUI、终端 UI。它们都是内核的**客户端**。

---

## 3. v2 内核：五个概念 + 一组不变量

### 3.1 组件与职责

```
        App / WebUI / linuxctl CLI / 模块 service.sh      ← 都只是客户端
                        │  ① 提交作业  ② 订阅事件  ③ 读快照
                        ▼
   ┌──────────────────────── sunsetd（宿主侧常驻内核）────────────────────────┐
   │  Session Manager   ：世代、所有者、前置条件、teardown 契约                 │
   │  Job Engine        ：提交即返回、进度/结果事件、取消、幂等键               │
   │  Backend (可插拔)  ：chroot-overlay │ proroot │ proot                     │
   │  State Store       ：state.json（原子写、带 schema+generation）           │
   │  Control Channel   ：unix socket + 行分隔 JSON（版本化）                  │
   │  Observability     ：结构化事件（log.jsonl）+ 不变量自检（doctor）         │
   └──────────────────────────────────────────────────────────────────────────┘
```

- **客户端不需要知道 shell、ns、loop**；它们只提交作业、读快照、看事件。
- root 与免 root 的差别**只在 Backend**：root = `chroot + overlay + 私有 mount ns`；
  免 root = `proroot/proot`。这正是用户说的"root 不该是免 root 的演化产物" ——
  **两个后端实现同一个接口**，而不是一套脚本里到处 `if mode == root`。

### 3.2 状态机（唯一状态源）

```
idle → preparing → mounting → starting → running ⇄ degraded
                                              │
                                   stopping → stopping_teardown → idle
   任意阶段 → failed（带 last_error + 证据）
```

- 每个状态**由 sunsetd 写入** `state.json`（原子 rename，含 `generation`、`phase`、
  `since`、`job_id`、`backend`、`port`、`pid`）。所有客户端读同一份。
- "在不在跑"这类问题**不允许任何调用方自己推导**（今晚第 2、3 条的直接解药）。
- 中间态是一等公民：`mounting`/`starting` 期间，App 自然把启动卡置灰（不必再补 `start.lock`）。

### 3.3 会话与世代（ownership）

- 一次成功的 `start` 产生一个 **session**，带**单调递增的 generation**；
  `stop` 结束它。任何时刻至多一个"活着的 session"，由 sunsetd 保证（互斥内建在作业引擎里，
  不需要外部锁文件）。
- **前置条件（fail-loud）**：进 `mounting` 之前必须确认 mount ns 已 rprivate
  （`ns_is_private`）。拿不到就**不要建树**——今晚第 4 条 33 条泄漏就是"先建了再说"的代价。
  真想继续，必须是显式 `--unsafe-propagate`，并把 `degraded` 写进状态。
- **teardown 契约**：挂载表是 session 的**数据**（建之前先登记：`{generation, path, source,
  kind, order}`），拆的时候按它逆序做，且：
  1. 只拆本 generation 登记的项（世代隔离，解决"两棵树"的歧义）；
  2. 结束前做一次**宿主残留 sweep**（`/proc/1/mountinfo` 里属于本项目的挂载点）；
  3. sweep 之后才允许 `losetup -d`（今晚第 5 条）。

### 3.4 作业模型（把 UI 从"命令返回"里解放出来）

- 所有会改变状态的动作都是**作业**：`start|stop|restart|dsh.start|dsh.stop|layer.update|snapshot|exec`。
- 客户端 `submit` 拿到 `job_id` **立刻返回**；进度/结果通过**事件流**或 `job.status` 获取。
  ⇒ 今晚第 1 条（UI 被命令生死绑住）在结构上不可能再发生。
- 作业带**幂等键**（同一意图重复提交 —— 用户连点、App 重试 —— 只跑一次，返回同一个 job）。
- **超时不猜**：长任务的"还没好"由状态推导（`phase` + `since` + 心跳），不是 `wait_ready 45`。

### 3.5 控制面与契约

- 通道：`$LINUX_HOME/run/control.sock`，行分隔 JSON：`{"v":2,"op":"submit|status|subscribe|job",...}`。
- 兼容：**§3.1 的 `status` JSON 保持冻结**，由内核生成（App 不改也能用）；
  `linuxctl` 变成瘦客户端（`linuxctl status` = 读内核快照；内核不在时回退到 v1 文件读取）。
- 版本与能力：`{"protocol":2,"caps":["jobs","events",...],"module_min":"1.0.x","app_min":"0.3.x"}`；
  客户端按 caps 降级（老 App + 新内核、新 App + 老内核都能动）。

### 3.6 不变量（每条都对应一个今晚踩过的坑，doctor 变"断言不变量"）

| # | 不变量 | 违反时的表现（今晚） |
|---|---|---|
| I1 | 至多一个活着的 session；`state.json.generation` 单调 | 两棵树、两条守护链（第 3 条） |
| I2 | 建树前置：mount ns 已 rprivate（或显式降级标记） | 33 条宿主泄漏（第 4 条） |
| I3 | 每个挂载在建之前先登记；teardown 只拆本世代 | 26 条 `run/mounts`、残留（第 3、5 条） |
| I4 | 生效端口来自 `state.json.port`，且 URL 与它一致 | "没有写偏移"（第 2 条） |
| I5 | 作业提交即返回；UI 状态只来自快照/事件 | busy 卡死 20 分钟（第 1 条） |
| I6 | 打包清单与目录一致（由 CI 断言） | 模块包漏文件（第 6 条） |

---

## 4. 后端抽象：root 与免 root 的关系（用户点名的部分）

| 能力 | `chroot` 后端（root） | `proot` 后端（免 root） |
|---|---|---|
| 进入 rootfs | `unshare -m -u` + chroot | proroot/proot |
| 分层 | erofs lower + ext4/dir upper + overlay | 无 overlay，直接目录（或 fuse-overlayfs） |
| 挂载树 | 需要（11 步）、需要 rprivate 前置 | 不需要 |
| 生命周期 | 与 App 解耦（模块 service.sh 常驻） | 随 App 进程（可由前台服务保活） |
| 端口/URL/状态 | **同一套** | **同一套** |
| 作业/事件/契约 | **同一套** | **同一套** |

也就是说：**内核只认 `Backend` 接口**（`prepare / enter / exec / publish_port / teardown /
capabilities`），两种模式是它的两个实现。今天的 root 路径之所以脆弱，很大程度是因为
它长在"proot 那套脚本 + 后来加的模式分支"上。

---

## 5. 迁移路径（每步可发布、可回退）

| 阶段 | 内容 | 兼容性 | 完成判据 |
|---|---|---|---|
| **P0（已在做）** | 把不变量写成**会红的闸门**：起锁、`starting` 中间态、宿主残留清理、打包清单完整性 | 无变化 | 本仓 CI 全绿（今天已落 4 条） |
| **P1** | `sunsetd` 出现（**只做 root**）：唯一状态机 + `state.json` + `status` 由它生成；`linuxctl` 变瘦客户端 | App 无感（同一份 JSON） | 四份"在不在跑"的实现删到**一份**；`contract-check` 全过 |
| **P2** | 作业模型 + 控制 socket + 事件流；App 改成"提交 + 订阅"（busy 不再与命令生死耦合） | App 需要一版更新；老 App 仍走 v1 命令 | 连点/重试不会产生第二个 job；建树 50s 期间 UI 正常 |
| **P3** | Backend 抽象：proot 迁到同一接口；删除模式分支与重复判据 | 两个 App 都要跟一版 | `runtime/{root,proot}` 里不再有彼此的 `if` |
| **P4** | 清账：删 v1 路径与兼容层；`doctor` 改为"断言 §3.6 的六条不变量" | 需要模块 + App 同步大版本 | doctor 输出 = 不变量清单，无"经验性检查" |

---

## 6. 已定案的决策（2026-09-18，用户拍板）

| # | 决策 | 定案 | 备注 |
|---|---|---|---|
| D1 | 内核用什么写 | **Kotlin，跑在 `app_process` 上**（模块带一个小 dex） | 原推荐是 Node，**核设备后发现不成立**：宿主侧没有 Node（唯一的 node 是环境内那份 122 MB 的 glibc 构建，bionic 宿主跑不了）；要么带 bionic Node（模块 +60~80 MB、还要背书第三方二进制），要么换宿主一定能跑的运行时。已实测：`/system/bin/app_process64` 存在、容器里有 `d8` 8.6.2、Kotlin 2.3.10 的 Gradle 插件在缓存里、`kotlin → jar → classes.dex` 全通（2.4 MB，含 stdlib）。附带最大好处：**内核与 App 可以共用同一份 Kotlin 契约模型**（今晚第 2、3 条那种"两边各自推导"从结构上消失） |
| D2 | 内核常驻范围 | root 常驻（模块 `service.sh` 起）+ 免 root 用**前台服务**保活 | 代价：免 root 多一条常驻通知 |
| D3 | 控制面 | **unix socket + 行分隔 JSON**，保留"命令 + 文件"作为降级 | 内核不在时行为与 v1 完全一致 |
| D4 | 兼容策略 | **v2 内核 + v1 契约兼容**（App/模块可落后一版） | §3.1 的 `status` JSON 由内核生成，键不变 |
| D5 | 换代节奏 | **先 P1**（sunsetd 唯一状态机 + status 由内核出），再谈 P2 | P1 就能把"在不在跑"的四份判据删到一份 |

### 6.1 待拍板的（D1 之外的遗留选项，不阻塞 P1）

- 内核与 App 的代码共享范围：只共享契约模型（推荐先这样），还是把 `StartControls` 一类判定也搬进共享模块。
- 免 root 的前台服务形态：常驻通知 + 保活（P3 再定）。

---

## 6.9 原提案（留档）：五件事的推荐与代价

| # | 决策 | 选项 | 我的建议 |
|---|---|---|---|
| D1 | 内核用什么写 | (a) 继续 mksh 脚本 (b) 环境内 **Node**（把 node 放进 runtime 层，不依赖 DSH 层） (c) **C/Go 静态二进制**（NDK 已在用：`libsunsetlinux_pty.so`；Go 需额外工具链） | **(b) Node**：本机已有 node 工具链与测试习惯（JS 契约测试 89 条），写状态机/事件/socket 比 shell 稳一个量级；代价 = runtime 层大 ~40 MB（(c) 最小但要自己写平台层） |
| D2 | 内核常驻范围 | (a) 只 root 常驻（模块 `service.sh`） (b) root 常驻 + 免 root 用**前台服务**保活 (c) 完全随 App 进程 | **(b)**：免 root 也能"关掉 App 环境不倒"，代价是一条常驻通知 |
| D3 | 控制面 | (a) unix socket + JSON 行 (b) 继续"命令 + 文件" | **(a)**，但**保留 (b) 作为降级**（内核不在时 v1 行为不变） |
| D4 | 兼容策略 | (a) v2 内核 + v1 契约兼容（App 可落后一版） (b) 大爆炸（模块 2.0 + App 1.0 同步） | **(a)**：今晚的教训是"契约一破，真机就只剩猜"；先兼容再清账 |
| D5 | 换代节奏 | (a) 先把 P1 做完再谈 P2 (b) 一次把 P1+P2 做完再发 | **(a)**：P1 就能删掉 3 份重复判据，收益最大、风险最小 |

---

## 7. 明确**不做**的（防止范围爆炸）

- 不重写建层/频道/验签（`device-provision.sh`、`channel.json`）——它们工作得不错，只是内核的客户端。
- 不改 §3.1 `status` JSON 的既有键（App 已依赖）；v2 只**新增**。
- 不引入 netns（会断网）、不依赖 `CONFIG_PID_NS`（本机没有）、不要求 bash（设备只有 mksh）。
- 不为了"优雅"放弃已证明可行的设备事实：loop+erofs、`/mnt/pass_through`、`env-procs.sh` 的
  `/proc/<pid>/root` 判据、`nsenter -- …` 的 `--` 规则 —— 这些都要**原样继承**成后端的实现细节。

---

## 8. P1 工作项与验收（已开工）

| 项 | 内容 | 状态 |
|---|---|---|
| P1-a | 状态模型 + 契约 JSON + 单测 + 构建管道 | ✅ 已落地：`app/sunsetd/`（`:sunsetd` 子项目）· `KernelModel.kt`（[Phase]/[Backend]/[SessionState]，含迁移表与 I1/I5 不变量）· `Json.kt`（零依赖小 JSON，**能力边界写在文件头**）· `KernelModelTest` **11 条全过**；`kotlin → jar → d8 → classes.dex` 实测通过 |
| P1-b | `sunsetd` 主循环：控制 socket（行分隔 JSON）+ `state.json` 原子写 + 心跳/看护 | 待做 |
| P1-c | 相位驱动：内核 spawn 现有 `start.sh`/`stop.sh`，读标记 + 进程退出码推相位，写世代 | 待做 |
| P1-d | `linuxctl status` 变瘦客户端：**socket 优先，文件降级**（App 无感） | 待做 |
| P1-e | 模块打包：dex 进模块 + `service.sh` 用 `app_process` 起内核 + **真机验 SELinux 域** | 待做（唯一未验证的风险点；失败可回退 v1） |
| P1-f | 契约回归：内核生成的 `status` 必须过 `tools/contract-check.mjs`；内核单测进 CI | 待做 |

**P1 完成判据**（都要求真机证据）：

1. "在不在跑"只剩**一份**实现（`sunsetd` 的状态）；`start.sh:running_ns_pid`、`linuxctl:ns_pid_alive/env_ready`、
   App 的推导全部改成读内核状态；
2. 建树 30~50 秒期间 `status.phase ∈ {mounting, starting}`，App 的启动卡自动置灰（不再依赖 `start.lock` 这种补丁）；
3. `linuxctl status` 走 socket 时与 v1 JSON **逐键一致**（`contract-check.mjs` 两种路径都跑）；
4. 内核不在（模块被停用 / 免 root 未起前台服务）时，命令行为与今天完全一致（降级路径）；
5. `app_process` 起内核在真机 root 域下可用（或给出可回退的替代启动方式）。
