# chuichuihook（锤锤102）

把「锤锤 0.4-beta5」从老的 `de.robv.android.xposed` API 完整复刻到 **libxposed API 102**，
并去掉了对高德 SDK 的编译期依赖。

- 模块包名：`com.cycy.chuichui`
- 目标应用：`com.alibaba.android.rimet`（钉钉）
- 框架：LSPosed 2.2.1+ / API 102
- 构建：GitHub Actions（`.github/workflows/build.yml`），产物自动发到 `latest` release

APK 下载：<https://github.com/m8458246/chuichuihook/releases/download/latest/app-release.apk>

---

## 功能对照

| 功能 | 原版锤锤 | 本版本 |
|---|---|---|
| 定位伪造 | hook `AMapLocationClient.getLastKnownLocation` + 包一层 `setLocationListener` | 同左，**纯反射实现**，不打包高德 SDK |
| 字段覆盖 | 27 个 setter 全量刷到真实回调对象 | 遍历 `setXxx` 自动覆盖（实测 43 个字段） |
| 随机漂移 | 固定 ±5m | 圆盘内均匀采样，半径可配（默认 5m，0=关闭） |
| WiFi 伪造 | `isWifiEnabled` / `getScanResults` / `getConnectionInfo` | 同左，Parcel 快照精确还原 |
| 基站伪造 | `getAllCellInfo` | 同左，Parcel 快照精确还原 |
| 配置编辑 | Compose 界面，只能采集当前位置 | 传统 View：手输坐标 + 原生定位采集 + 逆地理编码 + 地址分段编辑 |
| 配置通道 | `XSharedPreferences`（102 下不可用） | `openRemoteFile` 官方文件通道（主）+ 广播/Provider 兜底 |
| 捐赠弹窗 | 有 | 已去掉 |

---

## 安装

1. 下载 `app-release.apk` 安装（签名固定，覆盖安装不会丢 LSPosed 状态）
2. LSPosed 里启用「锤锤102」
3. 作用域勾选「钉钉」
4. 强行停止钉钉再打开

自测时也可以临时勾选「高德地图」，但注意高德地图自己不用 `AMapLocationClient`，定位 hook 会显示 0。

---

## 使用

1. 打开「锤锤102」→ 新建配置
2. 点「采集当前位置」用系统定位拿真实坐标（室内可能失败，直接手填也行）
3. 需要地址一致就点「逆地理编码」，或手填省/市/区/街道
4. 保存后**钉钉里立即生效**（模块 App 会主动推送；钉钉进程没运行的话，下次定位会自己来要）
5. 点列表项切换当前配置；长按列表项可以「编辑 / 删除」
6. 「同步」按钮把配置写进 LSPosed 共享目录（需要 root；不点也能用，只是钉钉重启后可能读回旧配置）

「运行日志」页显示目标进程回传的日志，能看到：

```
模块加载 process=com.alibaba.android.rimet ...
hook 安装完成: 定位 2 / WiFi 3 / 基站 1
自检通过: 复制字段 43，坐标 25.064531,102.702946，地址 云南省昆明市...
配置来源: remote-file
定位替换 25.064531,102.702946（目标 25.064565,102.702959，偏移 4.2m）
```

---

## 配置通道说明（这版踩过的坑）

| 通道 | 状态 | 说明 |
|---|---|---|
| `XSharedPreferences`（原版） | ❌ | targetApiVersion=102 禁用 `de.robv.*` |
| `getRemotePreferences` | ⚠️ 空 | LSPosed 2.2.1 的 remote prefs 走 daemon 数据库，模块侧没有写入入口 |
| `ContentResolver.call` | ❌ | 钉钉 targetSdk=30，Android 11+ 包可见性 → `Unknown authority` |
| `sendBroadcast(setPackage)` | ❌ | 包可见性同样拦截广播，AMS 直接丢弃 |
| `sendBroadcast(ComponentName)` | ⚠️ | 模块 App 未被 force-stop 时可用（实测能拉起被杀进程） |
| **`openRemoteFile`** | ✅ | daemon 通过 Binder 传 fd，不受包可见性限制 |
| **配置推送**（模块 App → 钉钉） | ✅ | 保存后主动广播，钉钉进程内动态 receiver 收到即更新缓存 |

实际生效顺序（`ConfigReader`）：

```
1. 钉钉自己的 SharedPreferences（chuichui_config）  ← 第一优先级，持久化
2. openRemoteFile("profiles.json")                 ← /data/adb/lspd/modules/<userId>/<pkg>/files/
3. getRemotePreferences("profile")
4. ContentProvider call("config")
5. 显式广播请求（FLAG_INCLUDE_STOPPED_PACKAGES）
6. 模块 App 主动推送（保存配置时）
```

**任何通道拿到的配置都会回写进钉钉自己的 prefs**，所以只要成功过一次，
以后即使模块 App 完全不可用、共享目录被清空，钉钉重启后照样能用。

推送/广播拿到的配置标记 `fromPush`，5 分钟内优先，改完配置不用重启钉钉。

**`/data/adb` 是 root 700，普通 App 写不了**，所以「同步」按钮需要 root 权限：

- 有 KernelSU / Magisk 管理器：首次点「同步」时授权一次即可
- 没有管理器：用 MT 管理器手动复制
  `files/profiles.json` → `/data/adb/lspd/modules/0/com.cycy.chuichui/files/`
- **完全不碰 root 也能用**：钉钉 prefs 会持久化，广播/推送通道就够了

## 坐标系（很重要）

系统 `LocationManager` 返回 **WGS84**，高德/腾讯系 SDK 用 **GCJ-02**，中国境内两者差 **300~600 米**。

所以：

- 「采集当前位置」拿到 WGS84 后会**自动转成 GCJ-02** 再填进输入框（提示里会显示转换偏移量）
- 逆地理编码仍用原始 WGS84 坐标，这样查出来的地址才对得上
- **手动填坐标**时不用转换，直接填 GCJ-02 就行（从高德/腾讯地图复制的都是 GCJ-02）

如果从别处拿到的是 WGS84（GPS 设备、OpenStreetMap 等），需要自己先转换。

## 日志与配置副本

| 文件 | 说明 |
|---|---|
| `/sdcard/Download/chuichuihook/hook.log` | 运行日志，**最新在最上面**，最多 500 行 |
| `/sdcard/Download/chuichuihook/profiles.json` | 配置只读副本，方便审计/备份（目标进程读不到，只给人看） |

日志每行格式：

```
11-11 17:05:50  25.066662,102.705562  目标 25.066646,102.705607  偏移 4.9m  字段 43  云南省昆明市五华区…
```

- 第一段：钉钉实际拿到的坐标（已经带随机漂移）
- 第二段：配置里的目标坐标
- `偏移`：本次漂移距离（应在半径内）
- `字段`：本次覆盖了多少个 AMapLocation 字段

**钉钉每拿到一次定位就写一行**，所以：
- 有坐标行 → hook 生效
- 偏移每次不同且在半径内 → 漂移生效
- 一行都没有 → 模块没加载（看 logcat `-s ChuiChui`）

写入走 MediaStore（Android 11+ 不能直接 File 写 /sdcard），
不需要任何存储权限；写不了时降级成广播回传，落到模块 App 私有目录。

---

## 代码结构

```
app/src/main/java/com/cycy/chuichui/
├── ModuleMain.java            API 102 入口（XposedModule）
├── hook/
│   ├── AmapHooks.java         定位 hook（反射构造 + 全字段复制 + 漂移 + 自检）
│   ├── WifiHooks.java         WiFi 三个 hook
│   └── CellHooks.java         基站 hook
├── model/                     Profile / WifiSnapshot / CellSnapshot / Profiles（纯 JSON）
├── store/
│   ├── ConfigStore.java       App 侧读写 + su 发布
│   └── ConfigReader.java      目标进程侧四通道读取 + 广播缓存
├── provider/
│   ├── ConfigProvider.java    兜底 Provider
│   └── ConfigReceiver.java    广播应答
├── ui/                        MainActivity / EditActivity / LogActivity
└── util/                      Logger / Drift / HookLog / Collector
```

## 本地类型检查

```bash
python3 typecheck/gen_r.py
javac -nowarn -proc:none --release 17 \
  -cp "android.jar:libxposed-api-102.jar" \
  -d typecheck/out $(find app/src/main/java -name "*.java") typecheck/R.java
```

## 已知限制

- 钉钉开了防截屏（`FLAG_SECURE`），`observe_screen` 截图全黑、无障碍树为空，
  所以考勤页的端到端验证只能看日志（`logcat -s LSPosedFramework:V | grep ChuiChui`）。
- 「采集当前位置」用系统 `LocationManager`，室内拿不到点；原版用高德 SDK 是为了网络定位。
  如果确实需要，可以再引入高德定位 AAR（只影响 App 侧，hook 侧不受影响）。
