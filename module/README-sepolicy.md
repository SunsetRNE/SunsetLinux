# sunsetlinux · SELinux 说明（为什么**默认不带** sepolicy.rule）
#
# 设备实测（KernelSU，context=u:r:ksu:s0 的真 root）：
#   ls -Zd /data                       → u:object_r:system_data_root_file:s0
#   ls -Z  /data/*                     → u:object_r:system_data_file:s0（多数条目）
#   ls -Zd /system                     → u:object_r:system_file:s0
#   ls -Z  /system/bin/sh              → u:object_r:shell_exec:s0
#
# 也就是说：
#   1. 我们的环境根 `/data/sunsetlinux` 会继承 `/data` 的类型 `system_data_root_file`，
#      其下新建目录/文件继承 `system_data_file`。KernelSU 的 su 域对这两类都有
#      读写与执行权限 —— 这是 KernelSU 的既有策略，不需要我们补规则。
#   2. 模块脚本由 KernelSU 在执行时切换上下文运行，不是从 /data 直接 exec 到
#      受限域；`unshare`/`mount`/`chroot` 都是 su 域内的常规操作。
#   3. 因此**默认不需要 sepolicy.rule**，而"能不加就不加"是明确要求：
#      加规则等于扩大策略面，且一旦设备/内核升级就可能失效并留下隐患。
#
# 什么时候才需要自带 sepolicy.rule？
#   只有一种情况：`linuxctl doctor` 的第 5 节在 **与本项目路径相关** 的条目上
#   报出 avc denial，例如（真机上取样，然后对症下药）：
#     avc: denied { execute } for comm="sh" path="/data/sunsetlinux/bin/linuxctl.sh" ...
#     avc: denied { search }  for ... scontext=u:r:ksu:s0 tcontext=u:object_r:system_data_file:s0
#
#   确认是这两类之后，最小化的规则形如（**请先在真机上验证上下文再启用**）：
#
#     allow ksu system_data_file:file { read write execute open getattr map };
#     allow ksu system_data_root_file:dir { search read write open add_name remove_name };
#     allow ksu system_data_root_file:file { read write create open getattr };
#
#   注意上面的规则必须落在 module/sepolicy.rule 里才会被 KernelSU/Magisk 加载。
#
# 明确禁止的做法（本项目不做，也请维护者不要加）：
#   - setenforce 0 / 关闭 SELinux
#   - permissive 域
#   - 用 magiskpolicy --live 之类运行时全局放宽
#   - 把 /data/sunsetlinux 打成 system_file 之类"看起来更宽松"的类型
#
# 一句话：**先 doctor，再决定；能不加就不加。**
