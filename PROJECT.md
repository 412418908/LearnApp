# LearnApp 项目说明

刷题聊天 Android 应用：把题库 zip 导入后，以微信聊天的方式逐题练习（双击出答案、上滑归档进入下一题）。

## 目录结构

```
LearnApp/            仓库根
├── LearnApp/        Android 工程（Kotlin + Gradle KTS）
│   └── app/src/main/java/com/example/learnapp/
│       ├── MainActivity.kt          题库列表页（含滚动位置恢复）
│       ├── QuestionListActivity.kt  题目列表页
│       ├── ChatActivity.kt          聊天刷题页（核心交互）
│       ├── SessionManager.kt        题库导入/进度管理
│       ├── HtmlRender.kt            题目 HTML 渲染（图片 60% 透明度）
│       └── ...
├── question_parser/ Python 题库解析脚本（docx → questions.jsonl）
└── readme           原始说明（new project）
```

## 编译环境（本机已配置）

| 项 | 值 |
|---|---|
| JDK 17 | `C:\Program Files\Java\jdk-17` |
| Android SDK | `C:\Users\jerry\.android\sdk`（cmdline-tools 12.0 + platform-tools + android-34 + build-tools 34.0.0） |
| 代理 | `127.0.0.1:7890`（gradle.properties 与 git 全局 socks5h 均走此代理） |
| local.properties | `LearnApp/local.properties` 指向 SDK 目录（已被 gitignore） |

### 编译命令

```powershell
cd C:\gitlab\LearnApp\LearnApp
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
.\gradlew.bat assembleDebug
```

产物：`LearnApp/app/build/outputs/apk/debug/app-debug.apk`（包名 `com.example.learnapp`，应用名「刷题聊天」，minSdk 26 / targetSdk 34）

## 关键功能与交互

- **聊天页手势**（ChatActivity.NextQuestionGesture）：
  - 双击题目气泡 → 显示答案
  - 上滑超过 96dp 或快速甩动 → 归档当前题并进入下一题（气泡跟手 + 回弹动画）
  - 下滑 → 交还 RecyclerView 正常滚动
  - 注意：DOWN 时必须 `requestDisallowInterceptTouchEvent(true)`，否则垂直手势被 RecyclerView 拦截
- **消息布局**：居顶向下排列（LinearLayoutManager 默认），新题自动滚动到列表顶部
- **微信样式**：
  - 左侧气泡：头像 + 昵称「太上老君」+ 白色气泡 + 左侧小箭头（`bubble_arrow_left.xml`，与气泡间距 6dp 无缝衔接）
  - 聊天字体浅灰 `#999999`，题目/答案 8sp，右侧气泡 12sp（dimens.xml）
  - 聊天标题固定「2026江北高中学群」（strings.xml），状态栏白色（themes.xml）
- **题目图片**：最大宽度按布局动态计算（HtmlRender.calcMaxImageWidth），60% 透明度烘焙进像素（Drawable.alpha 在部分机型与 ImageSpan 组合会渲染空白，勿用）
- **列表页**：从聊天页返回时恢复上次滚动位置（onPause 记录 / loadData 恢复）

## Git

- 远程：`https://github.com/412418908/LearnApp.git`
- 当前分支：`trae/agent-5fxWhL`
- `.gitignore` 已修正：`build/`、`.gradle/` 不带前导斜杠（可匹配嵌套目录），639 个误跟踪的构建产物已从索引移除
- 推送注意：若凭据失效，报 "Invalid username or token"，删除 Windows 凭据管理器中的旧 GitHub 令牌后重新推送会触发浏览器授权

## 常见坑

1. `gradle.properties` 里的代理端口若与本机代理不一致，依赖下载会全部失败
2. 首次构建需下载 Gradle 8.7 与全部依赖（约 2 分钟），后续增量构建约 30 秒
3. 修改布局涉及气泡宽度时，同步更新 HtmlRender.calcMaxImageWidth 的 outsideDp 估算
