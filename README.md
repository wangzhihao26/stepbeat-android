# 步调 · StepBeat

原生安卓步频节拍器，中文深色界面，适用于走路、快走和跑步。无需账号、网络、定位或传感器权限。每拍对应一步，显示的是目标步频和已发出的节拍数，不测量实际步数。

## 安装使用

从本仓库 [Actions](https://github.com/wangzhihao26/stepbeat-android/actions) 中成功完成的 Android build 下载 `stepbeat-debug` 产物，解压后将 APK 传到 Android 8.0 或更新版本的手机安装。也可以按下文在本地构建。安装包使用调试签名，供本地体验；生成的 APK 和签名密钥不提交到源码仓库。

1. 选择走路（100）、快走（120）或跑步（170）预设。预设是可修改的初始值。
2. 拖动滑块，或使用 + / − 在 40–220 步/分钟间调整；长按按钮每次调整 5。点击圆盘可输入精确数值。
3. 可按自己的步伐连续点按“轻点测速”，取最近最多 6 次点击的平均间隔。
4. 选择声音、震动或同时开启，分别调整音量和强度。至少保留一种提示方式。
5. 选择不限时或 10 / 20 / 30 分钟，开始训练。定时依据累计运行时长，暂停不计时，运行中更改定时立即生效。
6. 暂停保留时长，继续恢复；结束清零。训练完成后再次开始会开启新一轮。

通知中可暂停或结束。首次启动会请求通知权限；拒绝后仍可运行，在应用内控制。其他应用临时要求降低音量时节拍继续；临时独占音频时暂停并在焦点恢复后自动继续，等待期间不计时，可以点击“取消自动继续”或“结束”阻止恢复。音频被长期占用或拔下耳机时保留手动恢复。设置自动保存；进程被系统终止后不会自动播放，也不会恢复训练计数。

### 锁屏一段时间后停止

“不限时”没有 10、20 分钟的截止条件，但系统休眠、省电策略、音频中断仍可能影响运行。主页“后台运行设置”可打开电池优化列表或应用详情，由用户选择允许后台运行；应用不会自动修改系统设置，也不能保证绕过厂商的限制。

若再次停止，先打开“运行诊断”复制记录。记录含设备版本、当前省电状态、最近训练设置、停止事件、最大调度迟到和最后保存时间，仅保存在本机。运行中每 15 秒保存一次；若进程异常退出，显示的是最后检查点，不能视为精确停止时间，也不能据此直接断言是系统杀进程。

## 工程与构建

原生 Java + Android Views，无第三方运行时依赖。标准工程使用 compileSdk / targetSdk 35、minSdk 26、Android Gradle Plugin 8.9.2、Gradle 8.11.1、JDK 17。

### Windows 离线构建（本次已验证）

```powershell
.\scripts\build-apk.ps1
.\scripts\test.ps1
```

构建脚本优先读取 `ANDROID_HOME` / `JAVA_HOME`，也能探测本机已有的 Unity Android SDK 和配套 JDK。可显式指定：

```powershell
.\scripts\build-apk.ps1 -SdkPath 'C:\Android\Sdk' -JdkPath 'C:\Java\jdk-17'
```

本次安装包由已安装的 Android SDK 32、Build Tools 32.0.0 与 JDK 11 离线编译，目标仍为 API 35。新版本行为通过 SDK 版本判断处理；前台服务类型以等价整数资源声明，使旧版资源编译器也能打包。最终 APK 的 minSdk、targetSdk、服务声明、资源和签名均已检查。旧版 D8 与 JDK 23 编译器不兼容，离线脚本会优先使用 SDK 随附的 JDK 11。

### Android Studio 标准构建

打开本目录，使用 JDK 17 和 Android SDK 35 同步工程，然后构建 `app`。已提供 Gradle Wrapper，固定下载 Gradle 8.11.1：

```powershell
.\gradlew.bat assembleDebug
```

标准 Gradle 构建需要能够访问 Gradle 分发站点、Google Maven 和 Maven Central。此构建路径已在 GitHub Actions 上通过；本机使用离线脚本验证。Wrapper 启动文件由本机官方 Gradle 7.2 生成，下载并启动的 Gradle 版本为 8.11.1。

Linux / macOS 使用 `./gradlew assembleDebug`。标准构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`，Windows 离线脚本产物位于 `dist/stepbeat-debug.apk`。

### 手动构建

GitHub Actions 仅在手动点击 Run workflow 时运行，推送代码和提交 Pull Request 不触发构建。手动运行时会先执行计时算法测试，再构建 APK，成功后保留安装包 14 天。配置见 `.github/workflows/android.yml`。

## 实现结构

- `MainActivity.java`：模式、步频、点按测速、提示开关、定时、训练状态和自绘节拍圆盘。
- `MetronomeService.java`：独立音频优先级线程、短音播放、震动、音频焦点、通知、唤醒锁与资源释放。
- `BeatClock.java`：使用单调时钟和绝对截止时间，避免回调延迟逐拍累积；落后时跳过错过的拍点，不补发密集节拍。
- `TrainingClock.java`：累计实际运行区间，排除手动暂停与临时音频占用期间的等待，不依赖节拍次数或 UI 刷新。
- `RunJournal.java`：保留最近一次训练的本地诊断检查点与中断事件。
- `Settings.java`：本地偏好设置。
- `res/raw/click.wav`：程序合成的 40 毫秒短音，不含外部素材。
- `tests/BeatClockTest.java`：步频上下限、多步频长期调度、迟到跳拍和改速检查。
- `tests/TrainingClockTest.java`：25 分钟与 24 小时不限时、暂停/恢复、反复中断及定时边界检查。

锁屏运行通过前台服务和部分唤醒锁实现。由于支持持续的纯震动模式，服务声明为 `specialUse`，并在清单中说明用途；上架时需按照分发平台要求申报。相关平台行为见 [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)。

## 验证结果与边界

- 基础版本已完成 Java 编译、资源链接、DEX 转换、APK 对齐、v2/v3 签名验证。
- 计时测试通过 500,006 次断言。它验证调度算法，不代表真机毫秒级音频或震动精度。
- 2026-09-29 的后台中断修复通过 JVM 计时回归测试，仅保存源码，未生成新 APK；音频焦点处理、设置入口和诊断界面仍需 Android 设备验证。
- 当前未连接手机，也没有可用模拟器，尚未完成 Android 运行与界面实测。验收步骤见 `docs/DEVICE_TESTS.md`。
- 系统媒体音量、勿扰模式、震动设置、厂商省电策略和硬件能力可能影响提示。无振幅控制的马达以脉冲时长提供强弱差异；无震动硬件的设备使用声音。
- 蓝牙音频有设备相关延迟，首次锁屏使用建议按验收清单确认效果。

调试密钥在首次离线构建时生成于 `.tools/debug.keystore`。此文件已忽略，不用于商店发布。
