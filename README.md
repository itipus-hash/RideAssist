# RideAssist 骑行助手

原生 Android 应用（Kotlin + Jetpack），通过 BLE 蓝牙对接 BMS 电池保护板，并集成外置胎压监测（TPMS）。

> **纯只读工具**：只读取保护板数据，不下发任何参数写入或配置修改指令。
> 架构上不存在写保护板、均衡控制、密码验证等功能入口。
>
> **无本地持久化**：不引入 Room 与任何存储权限，应用无数据库、无文件写入。

## 功能

- **BMS 实时监测**：总压 / 电流 / 功率 / SOC / 剩余容量 / MOS 温度 / 单体电压与压差 / 告警状态
- **胎压监测**：外置 TPMS 广播式传感器，前 / 后轮独立显示，低压 / 高压越界标红
- **悬浮信息窗**：可自选展示项目（双列布局）、字号、透明度；长按临时隐藏，回应用自动恢复
- **快捷导航**：一键调起高德地图——支持地名搜索、经纬度坐标（直接导航 / 路径规划二选一）、自定义链接；按钮名称与目的地分离
- **会话统计**：速度 / 里程 / 能耗实时统计（含派生量不变性断言，防统计口径漂移）

## 界面预览

![界面预览](docs/screenshots/preview.png)

<details>
<summary>单页大图</summary>

| 首页 | 实时 | 数据 | 悬浮显示 |
|---|---|---|---|
| ![首页](docs/screenshots/s_home.png) | ![实时](docs/screenshots/s_realtime.png) | ![数据](docs/screenshots/s_data.png) | ![悬浮显示](docs/screenshots/s_overlay.png) |

</details>

## 下载

最新 Debug 构建（免安装包名 `.debug` 后缀）：

- [apk/RideAssist-v1.0.0-debug.apk](apk/RideAssist-v1.0.0-debug.apk)

> Debug 构建仅用于体验与测试，日常使用建议自行编译 Release 版。

## 构建

```
1. 用 Android Studio 打开本目录（wrapper 已指向 Gradle 8.13，JBR 21 可直接用）
2. 等待 Gradle 同步完成（已配置阿里云镜像，国内下载快）
3. 连接真机运行（BLE 功能必须在真机验证，模拟器不支持）
```

命令行构建：

```
./gradlew assembleDebug
```

最低 Android 版本见 `app/build.gradle.kts` 的 `minSdk`。

## 兼容性

- **目前仅兼容「彦阳 BMS」系列保护板**（BLE 协议为该系列定制，尚未适配其他品牌）
- 其他品牌保护板需按各自协议实现解析层（`core/protocol/`），欢迎 PR

## 使用前提

- 手机需授予「显示悬浮窗」权限（悬浮信息功能）
- BMS 保护板需开启蓝牙并处于可连接状态
- 胎压功能需外置 TPMS 传感器（广播式）

## 免责声明

- 本项目为社区开发的**第三方只读工具**，与任何保护板厂商无关
- BLE 协议参数来自社区对设备的观察与测试，仅供学习交流
- 数据仅供参考，**不作为电池安全判断依据**；电池安全请以专业设备与厂商为准
- 使用本项目产生的任何风险由使用者自行承担

## License

[MIT](LICENSE)
