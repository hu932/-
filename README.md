# 账号管家（Android）

基于原 HTML 网页版「账号管家」移植的原生 Android 应用（Kotlin + AndroidX）。

## 功能

- **粘贴即解析**：自动扫描文本中的 JSON Cookie 数组（支持 `\_` 转义、tab 分隔的列中提取 UID / UA / 地区）
- **复制即计时**：点击「复制」把 Cookie 写入剪贴板，并开始计时（每秒刷新）
- **记录使用时间**：每次复制都会追加一条使用记录（`usage` 时间戳数组），账号卡片显示「已用 N 次」
- **按日期查看**：标签页下方有日期筛选条（全部 / 今天 / 昨天 / MM-dd），按账号首次入库日期过滤
- **本地 / 共享两个标签页**
  - 本地：只存这台设备（SharedPreferences）
  - 共享：同步到服务器，打开同一 App 的所有设备可见
- 顶部统计：账号总数 / 已使用
- 导入文件（.txt / .json）
- 删除 / 清空，共享页的删除与清空会同步到服务器
- 顶部同步状态：点一下立即同步，每 15 秒自动拉取一次；**长按可修改共享接口地址**

## 目录结构

```
AccountManager/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── server/
│   └── accounts_api.php           # 新的共享后端接口（部署用）
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/accountmanager/
        │   ├── MainActivity.kt        # 界面与业务逻辑
        │   ├── Account.kt             # 账号模型 + JSON 序列化
        │   ├── Parser.kt              # 文本解析（与网页版 parse() 对应）
        │   ├── Storage.kt             # SharedPreferences 持久化
        │   ├── ShareApi.kt            # 共享接口客户端
        │   └── AccountAdapter.kt      # 账号列表适配器
        └── res/
            ├── layout/                # activity_main.xml / item_account.xml
            ├── drawable/              # 卡片、按钮等形状
            └── values/                # colors / strings / themes
```

## 构建运行

1. 用 **Android Studio**（Hedgehog 或更新版本，JDK 17）打开 `AccountManager` 目录，等待 Gradle 同步。
2. 连接设备或启动模拟器，点击 **Run**。

> 说明：
> - `gradle/wrapper/gradle-wrapper.jar` 为二进制文件未包含在源码中，Android Studio 首次打开会自动生成；也可在项目根目录执行 `gradle wrapper` 生成。
> - 共享接口为明文 HTTP，已在 Manifest 中开启 `usesCleartextTraffic`。
> - 接口地址默认 `http://103.146.231.72:2525/accounts_api.php`，也可在登录页/主界面**点接口地址或长按顶部同步状态**修改（或改 `ShareApi.kt` 的 `DEFAULT_URL`）。

## 注册 / 登录（账号数据隔离）

- 首次打开进入登录页，输入用户名 + 密码点「注册并登录」即可建号。
- 之后「登录」返回；每个用户名在服务器和本地都是**独立的一套数据**，互不干扰。
- 勾选「记住登录」则下次打开自动登录；不勾选则退出后需重新登录。
- 主界面右上角「退出」可切换账号。

## 管理后台（server/admin.php）

把 `server/admin.php` 上传到与 `accounts_api.php` 相同目录，浏览器访问 `http://你的域名/admin.php`。

- 默认密码 `admin123`，请在文件顶部 `$ADMIN_PASSWORD` 修改。
- 功能：查看用户/账号、**把旧版未分配的账号迁移到指定用户**、账号在用户间转移、删除账号/用户。

## 后端接口（server/accounts_api.php）

把 `server/accounts_api.php` 上传到你的 PHP 服务器（**需重新部署**），数据自动存到同目录 `users.json` 和 `accounts_data.json`（无需数据库）。支持以下 `act`：

| act | 入参 | 说明 |
| --- | --- | --- |
| `register` | `{username, password}` | 注册，返回 `{username, token}` |
| `login` | `{username, password}` | 登录，返回 `{username, token}` |
| `list` | `token` | 拉取当前用户全部账号 |
| `sync` | `token, {accounts:[...], removed:[...]}` | 合并、删除并返回当前用户权威列表 |
| `clear` | `token` | 清空当前用户账号 |
| `mark_used` | `token, {key}` | 记录一次使用 |
| `dates` | `token` | 按创建日期统计当前用户账号数量 |

除 `register` / `login` 外，其余接口都要在 body 里带 `token`。

返回统一格式：`{"ok":true,"data":...,"msg":"","updated_at":"Y-m-d H:i:s"}`。

## 数据格式约定

粘贴文本每一行可包含：`[Cookie 数组]`、以及 tab 分隔的前后列。
- `c_user` 这个 Cookie 的值会作为 UID；若没有，则取 JSON 之前的第一个非空列作为 UID。
- JSON 之后剩余列：末列作为地区，其余拼接为 UA。
- 每个账号附带 `createdAt`（首次入库时间，用于日期筛选）与 `usage`（使用时间戳数组）。
- 若粘贴的是卡密（原始 cookie 字符串，形如 `name=value; name2=value2`），也能自动解析成账号。

## 下单购买（🛒）

点首页右上角「🛒 下单」进入内嵌 WebView 商城页：
- 可正常登录、下单、查看支付宝二维码；
- 支付页若提供「打开支付宝 App」或 `alipays://` 链接，会自动尝试拉起支付宝 App 完成付款；
- 付款后**自动解析卡密**：
  - 点「下载卡密」（.txt）会自动拦截下载并解析入库；
  - 查看卡密页点顶部「抓取卡密」自动提取本页内容解析；
  - 进入订单/卡密相关页面时也会自动尝试提取。

> 注意：
> - 支付宝的最终付款确认必须由你本人操作，App 无法代付。
> - 解析出的账号会进入「共享」标签页，返回首页后自动同步到服务器。
