# 卸载与清理：会留下什么、怎么清干净、怎么验证

> 这份文档回答一个很实际的问题：**卸载之后数据能不能清干净？**
> 结论先说：**默认不会自动清**（这是刻意的，见 §一），但我们会提供**可达的彻底清除路径**与**可验证的残留报告**，
> 让"清没清干净"从"凭感觉"变成"看得见"。

---

## 一、为什么默认不自动清

我们的架构**故意**把用户数据放在 App 私有目录之外：

| 数据 | 位置 | 卸载 APK 时 | 卸载模块时 |
|---|---|---|---|
| 环境本体（三层只读镜像） | `$LINUX_HOME/layers/` | **不动** | **不动** |
| 可写层（你的会话、配置、密钥、自建内容） | `$LINUX_HOME/upper.img` | **不动** | **不动** |
| 快照 | `$LINUX_HOME/snapshots/` | **不动** | **不动** |
| 离线种子 | `$LINUX_HOME/seeds/` | **不动** | **不动** |
| 模块自身文件 | `/data/adb/modules/<模块id>/` | — | 由管理器删除 |
| App 私有数据（**仅非 root 模式**的环境在这） | `/data/data/<包名>/` | 由系统删除 | 不受影响 |

**为什么这么设计**：`upper.img` 里是用户的真实数据（会话、`settings.yaml`、凭据、自建脚本）。
卸载 App 或换模块，**不等于**"我要删掉这些"。
上游那类"把 16 GB 放在 App 私有目录、卸载即全毁"的方案，是另一个极端 —— 我们选的是**保守**那一侧。

但这带来一个必须正视的后果：**卸载完，磁盘上可能还留着几个 GB**。所以下面三件事必须做好。

---

## 二、★ 手动 `rm -rf` 为什么常常"清不干净"

这是最容易被忽略的一点：

```
环境还在运行  →  只读层被 loop 挂载、可写层被 overlay 占用、node 进程还在跑
             →  rm -rf 会因为"设备或资源忙"删不掉挂载点，**但错误容易被忽略**
             →  结果是留下空目录 + 悬挂的 loop 设备 + 仍在跑的进程
```

**正确顺序永远是**：

```
1) 停环境        linuxctl stop          （它会按相反顺序卸载挂载、停 supervisor）
2) 确认没有残留   linuxctl footprint     （loop 挂载 / 进程 / 端口 / 各路径大小）
3) 再删数据       rm -rf $LINUX_HOME      （或 linuxctl purge）
```

> 所以文档里不会只写一句 `rm -rf` —— 那样十有八九会留东西。

---

## 三、彻底清除的三条可达路径

### 路径 A：App 内「彻底卸载」（推荐，最不容易漏）

设置页/侧边栏提供入口，行为：

1. **先可选备份**：`linuxctl snapshot before-uninstall`（默认勾选，因为下一步不可恢复）；
2. 二次确认（输入确认词或长按），明确写出**将删除的路径与预计大小**；
3. 依次执行：`linuxctl stop` → `linuxctl purge`（内部按 §二 的顺序删）；
4. 完成后展示 **`linuxctl footprint` 的结果**，让用户看到"确实没了"；
5. 提示：App 自身数据由系统在卸载时清理；模块请到管理器卸载。

### 路径 B：模块卸载时按标记清除

模块的 `uninstall.sh` 在管理器里运行 —— **用户在那里无法设置环境变量**，
所以用**标记文件**代替环境变量：

```bash
# 想"卸载模块就一并删数据"时，先创建这个标记：
su -c 'touch $LINUX_HOME/etc/PURGE_ON_UNINSTALL'
# 再去管理器卸载模块 → uninstall.sh 会先 stop 再整体删除
```

- **没有标记** → 保留数据，并打印**精确的残留清单 + 清理命令**（不再是笼统一句 `rm -rf`）；
- **有标记** → 先 `stop` 再删，删完报告结果。

> 现有实现里那个 `SUNSETLINUX_PURGE=1` 环境变量开关**实际够不着**（管理器不会让你注入环境变量），
> 属于"看起来有、其实用不到"的设计，已改为标记文件。

### 路径 C：纯命令行（root 终端）

```bash
# 1) 停 + 看残留
su -c '/data/sunsetlinux/bin/linuxctl stop'
su -c '/data/sunsetlinux/bin/linuxctl footprint'
# 2) 确认没挂载/进程残留后清除
su -c '/data/sunsetlinux/bin/linuxctl purge'
# 或手工（注意顺序）
su -c 'rm -rf /data/sunsetlinux'
```

---

## 四、`linuxctl footprint`：把"清干净"变成可验证

这是本方案的关键 —— 一个**只读**的残留报告（也接进 `doctor`）：

```
== 残留清单 ==
环境根              /data/sunsetlinux                存在   1.2 GB
  layers/           三层只读镜像                     存在   530 MB
  upper.img         可写层（你的数据）               存在   640 MB（稀疏，实际占用）
  snapshots/        快照                             存在    30 MB
  seeds/            离线种子                         存在    57 MB
模块目录            /data/adb/modules/sunsetlinux    存在    1.4 MB
待生效的模块更新     /data/adb/modules_update/...     不存在
-- 运行态 --
loop 设备           无残留                           ✅
挂载点              无残留                           ✅
环境进程            无                               ✅
监听端口            3080 空闲                        ✅
App 私有数据        /data/data/io...sunsetlinux      存在    12 MB（卸载 App 时系统会清）
```

**它回答三个具体问题**：
1. 到底还有哪些东西（而不是"应该没了"）；
2. 每一项多大（用户能判断值不值得保留）；
3. 有没有**运行态残留**（loop / 挂载 / 进程 / 端口）—— 这类残留是最难自己发现的。

---

## 五、容易漏掉的边角

| 项 | 说明 |
|---|---|
| **`/data/adb/modules_update/<id>`** | 管理器安装/更新模块时先放这里、**重启后才生效**。若在重启前卸载，这个目录可能残留 —— `footprint` 会检查它 |
| **loop 设备残留** | 异常退出（如被系统强杀）可能留下未释放的 loop。`footprint` 列出来，`purge` 会先释放 |
| **可写层是稀疏文件** | `upper.img` 的 `ls -l` 大小是上限（如 8 GiB），**实际占用**要看 `du`。`footprint` 报的是实际占用，避免吓人也避免误判 |
| **root 授权记录** | 管理器保存的授权记录**不属于我们**，卸载 App 后可自行在管理器里清理 |
| **电池优化白名单 / 通知设置** | 随 App 卸载由系统清理 |
| **快照** | 是用户主动建的备份；`purge` 会一并删除，所以**先提示**再删 |

---

## 六、验收：怎么证明"清干净了"

```bash
# 清除前
su -c '/data/sunsetlinux/bin/linuxctl footprint'      # 记下有哪些
su -c '/data/sunsetlinux/bin/linuxctl purge'
# 清除后
su -c '/data/sunsetlinux/bin/linuxctl footprint'      # 应全部显示"不存在/无残留"
su -c 'ls -d /data/sunsetlinux'                       # 应报 No such file
su -c 'losetup -a'                                    # 不应有指向我们镜像的 loop
```

**判定标准**：`footprint` 里所有项为"不存在/无残留"，且 `ls -d` 失败、`losetup` 干净。

---

## 七、落地状态

| 项 | 状态 |
|---|---|
| 明确"默认保留、显式清除"的语义 | ✅ 已在 `uninstall.sh` 与本文档 |
| 卸载前先 `stop`（避免 §二 的 busy 残留） | ✅ `uninstall.sh` 已做 |
| **标记文件**代替够不着的环境变量 | ✅ `$LINUX_HOME/etc/PURGE_ON_UNINSTALL`；`uninstall.sh` 认它，`linuxctl purge --arm/--disarm` 开关它 |
| **`linuxctl footprint`**（只读残留报告） | ✅ 已实现：各项大小 + `purge_on_uninstall` 标记状态 + loop/挂载/进程/端口残留；只读，不会顺手创建环境根 |
| **`linuxctl purge`**（先停再删，带确认） | ✅ `purge`（空跑）/ `purge --yes`（真删）；**有挂载或 loop 残留时拒绝删除**，避免删一半 |
| 模块 `uninstall.sh` 打印精确残留清单 | ✅ 逐项大小 + 三条后续动作（立刻删 / 下次卸载删 / 先备份快照） |
| App「彻底卸载」入口（先备份 → 确认 → purge → 展示 footprint） | ⏳ 仅差 UI：`linuxctl` 侧能力已全部就绪 |
| 本文件 + `docs/install.md` 的卸载章节互相引用 | ✅ |
