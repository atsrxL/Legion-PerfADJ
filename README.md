# 档位调校（PerfTuner）

联想拯救者 Y700 五代（TB323FU）上，用图形界面调整游戏助手**节能 / 均衡 / 野兽**三个性能档位的 CPU、GPU 频率范围和温度分段。需要 root（KernelSU 已验证）。

## 它改的是什么

游戏助手侧栏切换档位时，只写入 `Settings.Global game_helper_game_mode`（节能 4、均衡 1、野兽 2、超神 16）。真正执行策略的是系统应用 TAssistent（`com.zui.tassistent`）：它读取

```
/data/user/0/com.zui.tassistent/files/gameperfconfig.xml
```

并按档位和机身温度下发高通性能锁（CPU 两簇的最低/最高频率、GPU 功率级）。只有当 `/system/etc/gameperfconfig.xml` 的版本号**更高**时，它才用系统副本覆盖数据目录的副本。

本应用：

- 只修改 `pkg="default"` 的默认策略，即厂商没有单独适配的应用（例如自己加入游戏列表的模拟器）。约 43 款有独立策略的游戏、超神档、温控引擎配置都不改动。
- 只替换被改动的数字，其余内容逐字节保留；版本号写为 `max(当前版本, 系统版本 + 1)`。
- 写入方式：复制到同目录临时文件 → 属主 `system:system`、权限 `600`、`restorecon` → `mv` 原子替换 → `am force-stop com.zui.tassistent`。随后核对 SHA-256、SELinux 标签 `system_app_data_file`，并读回整份文件比较。
- 游戏模式运行中（`/sys/devices/system/cpu/cpu0/thermal/control` 非 0）拒绝写入，避免在游戏中重启 TAssistent。
- 所有 root 命令用 `su --mount-master` 在全局挂载命名空间执行。Android 的应用数据隔离会让本应用自己的挂载命名空间里看不到 `/data/user/0/com.zui.tassistent`；不加这个参数时读取会误报“没有自定义配置”，写入会失败（0.1.0 的问题，0.1.1 修复）。
- 保存后**下次进入游戏时生效**。

配置格式：每个温度段每簇一个 `下限_上限`。CPU 填 `<PreEnv>` 频率表下标（升序，从 0 开始）；GPU 填 KGSL 功率级（0 最快）；`-1` 表示这一侧不锁定。界面把它们换算成 GHz/MHz，滑块拉到最左/最右即写 `-1`。温度为机身表面温度 `quiet-therm`（℃），不是 CPU 核心温度。

## 使用

1. 安装 APK，在 KernelSU 管理器「超级用户」里为「档位调校」开启 root。
2. 默认单页：节能 / 均衡 / 野兽各有三组配置。Factory 为官方第一温度段，只读；配置 2、配置 3 可改名、调整频率范围并设为默认。横屏宽度足够时，配置编辑与保护参数并排显示，窄屏上下排列。
3. 同页「共用保护」设置三个档位共用的阈值 T（默认 47℃）和保护参数。低于 T 使用各档默认配置；达到 T 后使用保护参数与该档配置的较低限制，避免节能档在保护阶段反而提速。保护初值取官方野兽档 47℃ 段。
4. 勾选右上角「精细模式」，同页向下展开逐温度段编辑，横屏两列平铺；取消勾选收起。精细模式保存只使用分段草稿，不应用上方默认配置和共用保护。两种草稿分别保留，勾选本身不写入策略。
5. 点底部「保存并应用」（首次可为「应用默认模式」），确认改动；下次进入游戏生效。恢复原厂策略不等于删除自定义配置档。

恢复：状态卡片上的「恢复原厂配置」会用系统副本覆盖回去；或手动执行

```sh
su -c 'cp /system/etc/gameperfconfig.xml /data/user/0/com.zui.tassistent/files/gameperfconfig.xml; am force-stop com.zui.tassistent'
```

## 限制

- 原厂温控（`thermal-engine`，按 `/vendor/etc/thermal-engine_*.conf`）依然生效，机身过热时仍会降频；本应用不修改它。
- 系统更新若带来版本更高的策略文件，TAssistent 会用它覆盖本修改，需要重新保存。界面会显示回到「原厂」。
- TAssistent 有接收云端策略包的入口，未确认云端更新是否会覆盖自定义配置。
- 只能选择频率表中已有的频点，不能超过硬件上限。

## 构建

在构建机上（Android SDK platform 36、build-tools 35.0.0、JDK 17+）：

```sh
ANDROID_HOME=/path/to/sdk sh scripts/vm-build.sh
# 可选：GAMEPERFCONFIG=/path/to/gameperfconfig.xml 让单元测试也覆盖真机厂商文件
# 产物：app/build/outputs/apk/release/app-release.apk（debug 签名，可直接侧载）
```

## 验证记录（0.1.1，2026-11-17）

环境：Lenovo TB323FU，ZUI 18.0.12.104 ROW，Android 16，KernelSU，SELinux Enforcing。

- 构建机 VM 301：8 项单元测试通过（含用真机 `/system/etc/gameperfconfig.xml` 做的读写往返测试），`:app:assembleRelease` 成功，无编译警告。产物 `at@192.168.123.154:/home/at/perftuner-artifacts/0.1.1/PerfTuner-0.1.1.apk`，SHA-256 `62fa5cc4246719be6a69c3b731db6437a08478f6442d7ce3c3025d1606a60947`（同目录 `SHA256SUMS`）。
- 未授权 root 时显示授权引导和「重试」；在 KernelSU 中授权后正常读出原厂配置。
- 在界面上把野兽档「低于 42℃」的中核上限拖到 2.75 GHz、GPU 上限拖到 1000 MHz：对话框列出这两处改动；保存后提示成功，文件版本 `20260509`，属主 `system`、标签 `system_app_data_file`，内容与手工构造的同等修改逐字节相同（只改了 3 行）。
- 进入游戏（`com.armsx3`）切到野兽档：`policy0/scaling_max_freq=2745600`、GPU `max_pwrlevel=3`；均衡档仍为原厂值。
- 界面「恢复原厂配置」后文件与系统副本逐字节相同。
- 未实测：较高温度段的生效、游戏中拒绝写入的分支、重启后的保持。

## 0.2.0（2026-11-17）

- 新增默认模式、每档三组配置和共用保护温度页；原编辑器移至精细模式。
- 默认模式保留厂商阶段数量，将第一段写为配置档参数，其余段写为相同保护参数，阈值为 T / T+1 / T+2。适配 TAssistent 的阶段选择逻辑，不添加常驻守护程序；切换不是实时的，由系统采样和回差决定。
- VM 301 构建成功；16 项测试通过、0 跳过、0 失败，包含真实厂商策略往返。
- APK：`at@192.168.123.154:/home/at/perftuner-artifacts/0.2.0/PerfTuner-0.2.0.apk`，7,073,997 字节，SHA-256 `808f656549f54e2e497e105da8643341e3ee39357a124df28a47a136036636c4`。
- Y700 已覆盖安装 0.2.0/code3。实际界面层级确认默认进入档位页、Factory 只读、三个配置卡、保护页；通过界面保存默认模式成功，状态变为默认模式，应用配置文件已持久化。
- 实机触发验证未完成：临时阈值 30℃，低温野兽档读到 GPU level 4；短时负载期间游戏场景多次退出，未取得稳定越过阈值的证据，不视为保护触发通过。配置改名、重启持久性也尚未实机验证。
- 测试结束已逐字节恢复测试前的用户策略（非系统原厂副本），清除本次测试新增的应用偏好，恢复档位 4、屏幕超时 120000ms；临时负载已结束。

## 0.2.1（2026-11-17）

- 删除三页导航，改为横屏单页双列；压缩状态和说明区域，保留滑块触控尺寸。精细模式由顶部复选框展开，保护参数直接平铺。
- VM 301 Release 构建和测试任务成功；Y700 已覆盖安装 0.2.1/code4。横屏截图确认默认配置与保护参数在一屏内；勾选精细模式后同页显示两列温度段，自定义配置显示名称输入框。
- 此次布局验证未保存策略；设备现有策略不变。保护触发实测限制仍见 0.2.0 记录。
- APK：`at@192.168.123.154:/home/at/perftuner-artifacts/0.2.1/PerfTuner-0.2.1.apk`，7,057,609 字节，SHA-256 `1d56984e5a291fce36267d4afe30814d7853bdf5052b8c4cc2e77eef8f1c6f64`。
