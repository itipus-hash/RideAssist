# 彦阳保护板安卓 BLE APP

原生 Android 应用，Kotlin + Jetpack，通过 BLE 蓝牙对接彦阳 BMS 保护板。

> **本应用为纯只读工具**：只读取 BMS 数据，不下发任何参数写入或配置修改指令。
> 架构上不存在写保护板、均衡控制、密码验证等功能入口。
>
> **无本地持久化**：按当前需求已移除快照/告警日志/CSV 导出，
> 因此不引入 Room 与任何存储权限，应用无数据库、无文件写入。

## 快速开始

```
1. 用 Android Studio 打开本目录（wrapper 已指向 Gradle 8.13，JBR 21 可直接用）
2. 等待 Gradle 同步完成（已配置阿里云镜像，国内下载快）
3. 连接真机运行（BLE 功能必须在真机验证，模拟器不支持）
```

命令行构建：

```bash
./gradlew assembleDebug          # 调试包
./gradlew assembleRelease        # 发布包（混淆 + 资源压缩）
```

## 环境要求

| 项 | 版本 |
|---|---|
| Android Gradle Plugin | 8.13.2 |
| Gradle | 8.13+（wrapper 已配置） |
| JDK | 17+（Android Studio 自带 JBR 21 可用） |
| Kotlin | 2.2.20 |
| KSP | 2.2.20-2.0.4 |
| compileSdk | 36（本机已安装） |
| targetSdk | 34 |
| minSdk | 21（Android 5.0） |

> **版本说明**：
> - `compileSdk=36` 因本机仅安装了 android-36；`targetSdk=34` 严格遵循需求文档，运行时权限行为不变。
> - Room 必须用 **2.7.x**：2.6.x 内置的 kotlinx-metadata 仅支持到 Kotlin metadata 2.0.0，
>   搭配 Kotlin 2.2 会在注解处理阶段报 `Provided Metadata instance has version 2.2.0`。
> - 注解处理用 **KSP 而非 kapt**：kapt 在 Kotlin 2.2 下会出现 stub「类重复」误报。

## 构建产物

| 变体 | 体积 | 说明 |
|---|---|---|
| Debug | 7.7 MB | 带调试日志，可直接安装 |
| Release | 2.2 MB | 混淆 + 资源压缩，未签名 |

发布前需配置签名，见 `app/build.gradle.kts` 的 `signingConfigs`。

## 包结构

```
com.yanyang.yybms
├── YybmsApp.kt                 应用入口 + 手写依赖容器
├── core
│   ├── ble                     BLE 传输层
│   │   ├── BleManager          连接/自动连接/重连/收发（唯一发送入口只读）
│   │   ├── BleScanner          扫描、关键词过滤、权限判断
│   │   ├── BleUuids            UUID 配置与设备名关键词
│   │   └── ConnectionState     状态机与设备模型
│   └── protocol                彦阳协议层
│       ├── YybmsProtocol       帧结构 + CRC16/MODBUS
│       ├── YybmsCommand        只读命令集
│       ├── FrameAssembler      粘包/分包/噪声容错
│       ├── ByteReader          大端序安全读取
│       ├── YybmsPayloadParser  数据域解析（全容错）
│       └── model/              领域模型
├── data
│   ├── prefs                   SharedPreferences 设备记录与开关
│   └── repo                    MonitorRepository（轮询与聚合）
├── ui
│   ├── main                    单页容器 + 详情页跳转
│   ├── monitor                 实时监控
│   ├── cells                   单体压差详情（常亮）
│   ├── settings                设置
│   └── widget                  TrendChartView 自绘折线图
└── util                        震动提醒
```

## 页面结构

单页结构，无底部导航：

- **实时监控**（首页）— 连接状态、参数卡片、曲线图
- **单体压差详情** — 点击首页「单体电芯」卡片进入，**屏幕常亮**，数据每秒实时刷新

## 通讯流程

```
APP ──写──> 写特征 (0x03 只读帧) ──> BMS
APP <─通知── 通知特征 (0xDD...0x77) ──<─ BMS
                    │
                    ▼
            FrameAssembler  切分完整帧（处理粘包/分包）
                    │
                    ▼
            CRC16 校验     不通过则丢弃并重新同步帧头
                    │
                    ▼
            PayloadParser  按命令字解析，异常不崩溃
                    │
                    ▼
            MonitorRepository  每 1 秒轮询一轮，聚合状态
                    │
                    ▼
            ViewModel (StateFlow) ──> UI 卡片 + 曲线
```

### 帧格式

| 位置 | 内容 |
|---|---|
| 0 | 帧头 `0xDD` |
| 1 | 类型 `0x03`（读） |
| 2 | 数据域长度 LEN |
| 3.. | 数据域，第 1 字节为命令字 |
| LEN+3 | CRC 低字节 |
| LEN+4 | CRC 高字节 |
| LEN+5 | 帧尾 `0x77` |

CRC 为 CRC16/MODBUS（初值 0xFFFF，多项式 0xA001），计算范围 `[1, 3+LEN)`。

### 只读命令集

| 命令字 | 功能 |
|---|---|
| 0x03 | 总电压、总电流、SOC、SOH、剩余容量、充放电状态 |
| 0x04 | 单体电芯电压（自适应 4~24 串） |
| 0x05 | T1 / T2 温度 |
| 0x06 | 保护告警标志位 + 均衡状态 |
| 0x07 | 额定容量、出厂 SOH |
| 0x0A | 最高/最低单体电压及压差 |

> 不同固件版本命令字可能不同，集中定义在 `YybmsCommand`，现场适配时只改 `id`。

## 连接策略

| 场景 | 策略 |
|---|---|
| 手动连接 | 失败重试 3 次，间隔 5 秒 |
| 开机自动连接 | 单次 5 秒超时，最多重试 3 次，失败终止并文字提示 |
| 运行中断连 | 3 秒间隔重连，最多 3 次 |

自动连接全程不弹窗、不阻塞 UI，通过页面文字提示，并提供"取消自动连接"按钮随时终止。

**覆盖规则**：只有新设备连接成功才写入 SharedPreferences；扫描到设备或连接失败都不覆盖已有记录。

## 权限处理

| 权限 | 适用版本 | 用途 |
|---|---|---|
| BLUETOOTH / BLUETOOTH_ADMIN | ≤ API 30 | 蓝牙通讯 |
| BLUETOOTH_SCAN（带 `neverForLocation`） | ≥ API 31 | 蓝牙扫描 |
| BLUETOOTH_CONNECT | ≥ API 31 | 蓝牙连接 |
| ACCESS_FINE_LOCATION | ≤ API 30 | BLE 扫描必需 |
| POST_NOTIFICATIONS | ≥ API 33 | 告警通知（可选） |
| VIBRATE | 全部 | 告警震动 |

**权限流程设计**（针对"扫不到设备且无提示"这个问题）：

1. **先权限、后蓝牙** — Android 12+ 未授 `BLUETOOTH_CONNECT` 时调用
   `adapter.isEnabled()` 会抛 `SecurityException`，若先检测蓝牙就会表现为静默失败。
2. **三态处理** — 区分「首次申请」「已拒绝可再申请」「已永久拒绝」：
   - 首次 → 系统权限弹窗
   - 可再申请 → 应用内说明用途后重新申请
   - 永久拒绝 → 引导跳转系统设置页
3. **常驻横幅** — 权限缺失时首页顶部显示红色横幅与「去授权」按钮，
   不依赖一次性弹窗，随时可再次触发。
4. **`neverForLocation` 关键** — `BLUETOOTH_SCAN` 必须声明
   `android:usesPermissionFlags="neverForLocation"`，否则系统会连带要求
   定位权限且可能直接拒绝，表现为扫不到设备。

## 验证脚本

```bash
python verify.py            # 资源引用、binding id、ViewModel API 一致性
python verify_protocol.py    # CRC 算法交叉验证 + 粘包/分包 8 项用例
```

## 现场排障

| 现象 | 排查方向 |
|---|---|
| 扫不到设备 | 看首页是否有红色权限横幅；有则按提示授权；Android 11 及以下需授予定位 |
| 搜不到但设备列表空 | 确认设备名是否含关键词（过滤框会按名称过滤） |
| 连上但无数据 | UUID 是否匹配，检查 `BleUuids` 中的服务/特征 UUID |
| 数值明显不对 | 核对 `YybmsPayloadParser` 中该命令的字段偏移与倍率 |
| 频繁断开 | 信号强度、模组供电、是否开启省电模式 |
| 单体详情页息屏 | 该页已开启常亮，检查是否有其他应用强制休眠 |

调试日志：Debug 包会自动输出收发帧十六进制，Tag 为 `BleManager`。

```bash
adb logcat -s BleManager:D
```