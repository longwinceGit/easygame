# easygame

> 一个 Android 游戏合集。当前内置两款游戏：
> **拖拽方块**（可拖拽的俄罗斯方块）和 **挪车消消消**（颜色匹配 + 华容道式挪车解谜），
> 架构已为接入更多游戏做好准备。

---

## 游戏亮点

### 无广告

工程未引入任何广告 SDK，也没有统计、埋点或数据上报。
全部第三方依赖只有 7 个（Material 3 / AndroidX / Room），见文末依赖表——
**没有广告组件、没有追踪代码、没有弹窗**。打开即玩，玩完即走。

### 可离线

`app/src/main/AndroidManifest.xml` **没有声明 `INTERNET` 权限**。

这不是"支持离线游玩"，而是**应用在系统层面就拿不到网络**——
它想联网也联不了。成绩与历史最高分全部存在本地 Room 数据库中，
地铁、飞机、无信号环境照常玩，也不存在断网后数据丢失的问题。

可以自己验证这两条：

```powershell
# 应无任何输出
Select-String -Path app\src\main\AndroidManifest.xml -Pattern "INTERNET"
Select-String -Path app\build.gradle -Pattern "admob|ads|firebase|umeng"
```

### 拖拽即放，还能填洞

手指拖到哪就放到哪，不用在小小的按钮上戳来戳去。
更关键的是：**手指的高度决定方块从哪一层进入棋盘**，
所以你能把方块递到被上方挡住的空腔里——填洞这类空间规划的乐趣不会被操作方式吃掉。

### 没有时间压力

方块不会自己往下掉。你可以慢慢想，随时拿起、随时放下，
切走再回来局面完全不变。难度只来自棋盘空间本身。

### 可持续扩展

壳（大厅、导航、主题、成绩）与游戏本体通过 `GamePlugin` 接口解耦。
加第 2 款游戏时，**壳层代码只改 1 行注册**。

---

## 快速开始

| 工具 | 版本 |
|------|------|
| Android Studio | Hedgehog (2023.1) 及以上 |
| Gradle | 8.7（Wrapper 已内置） |
| AGP | 8.2.2 |
| JDK | 17 |
| minSdk | 21 (Android 5.0) |
| targetSdk / compileSdk | 34 (Android 14) |

```bash
./gradlew assembleDebug          # macOS / Linux
gradlew.bat assembleDebug        # Windows
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

首次构建会自动下载 Gradle 8.7 与依赖（走阿里云镜像），约 2–3 分钟。

---

## 怎么玩

| 操作 | 手势 |
|------|------|
| 放置方块 | 从底部托盘**按住**方块 → 拖到想去的位置 → 松手。方块会从你松手的高度继续下落，直到不能再落为止 |
| 旋转方块 | 在托盘方块上**轻点**一下（不是拖） |
| 取消放置 | 拖出棋盘区域松手，方块弹回托盘，**不扣分、不消耗** |
| 补充方块 | 托盘 3 个全部放完，自动补充新的 3 个 |
| 游戏结束 | 托盘中剩下的方块在**所有位置 × 所有旋转**下都放不下 |

> **填洞技巧**：松手的高度决定方块从哪一层"进入"棋盘。
> 想把一个方块塞进被上方挡住的空腔时，把手指**压低到那个空腔附近**再松手——
> 只在高处松手，方块会被上方的方块挡住而停在外面。
> 松手前注意看半透明落点预览，它会准确显示方块最终会停在哪。

> **旋转提示**：有时当前朝向放不进去、但换个朝向就能放。
> 这时把方块放回托盘**轻点一下**旋转即可——游戏没结束，它只是需要转个方向。

### 计分

放置方块**不得分**，只有消除整行才计分。一次消得越多，奖励越高：

| 消除行数 | 得分（× 等级） |
|---|---|
| 1 行 | 100 |
| 2 行 | 400 |
| 3 行 | 800 |
| 4 行 | 1300 |

连续几次放置都消行还有连击加成。消行时会在棋盘上弹出「+N」的得分浮字。

---

## 挪车消消消

停车场被塞满了，上方有一排彩色小人在等车。
点一辆车，它会沿车头箭头一路开到底，开出边界就驶入顶部接客区；
颜色和队首乘客一致就接人离开，不一致就占一个车位等着——而车位只有 4 个。

| 操作 | 手势 |
|------|------|
| 移动车辆 | **点击** = 沿箭头滑到底（贴边即驶出）；**按住拖动** = 沿车身轴向精确停位（可反向） |
| 排序 ×1 | 把剩余乘客按颜色聚成一团，方便连续接客 |
| 刷新 | 重新随机生成本关 |
| 移除 ×1 | 点掉一辆碍事的车，它的乘客也会一并离开 |
| 撤销 | 无限次，悔棋不用钱 |

**没有时间压力**：车辆不会自己动，你可以慢慢想。卡住了就撤销或刷新，不用重开整个进度。

关卡由程序生成，并且**在生成时就被求解器证明可解**——不存在"随机出来的死局"。

---

## 架构

```
┌─────────────────────────────────────────────────┐
│  UI 层     ui.hub / ui.records / game.tetris.ui │
├─────────────────────────────────────────────────┤
│  契约层    game.core（GamePlugin / Registry）    │
├─────────────────────────────────────────────────┤
│  领域层    game.tetris.model / engine            │
│            ↑ 纯 Java，零 android.* 依赖          │
│  渲染层    game.tetris.view（Canvas 单 View）    │
├─────────────────────────────────────────────────┤
│  基础设施  data（Room / Repository）              │
└─────────────────────────────────────────────────┘
```

三条硬性依赖规则：

1. `game/tetris/model/` 与 `game/tetris/engine/` **不得 import 任何 `android.*`**
   —— 引擎可被纯 JVM 单测覆盖，换渲染层时逻辑不用动
2. 除 `GameRegistry.java` 外，**壳层不得 import 具体游戏**
   —— `GameRegistry` 是注册点，那行 import 就是注册本身，是唯一白名单
3. **领域层不得依赖 `data.*`** —— 计分和存档是两件事

棋盘与托盘画在**同一个自定义 View** 里：跨 View 拖拽是这类游戏最容易出 bug 的地方，
合二为一后所有坐标天然统一。

---

## 项目结构

```
androidTemplete/
├── gradlew / gradlew.bat / gradle/wrapper/       # Gradle 8.7 Wrapper
├── build.gradle / settings.gradle / gradle.properties
├── docs/                                         # 设计与工程文档
└── app/src/main/java/com/template/app/
    ├── MainActivity.java                         # NavHost + 底部导航（游戏 / 战绩）
    │
    ├── game/
    │   ├── core/                                 # 【壳】插件契约
    │   │   ├── GamePlugin.java                   #   只有 2 个方法的极简契约
    │   │   ├── GameDescriptor.java               #   游戏元信息（不可变）
    │   │   └── GameRegistry.java                 #   注册表 ← 新增游戏改这里
    │   │
    │   ├── tetris/                               # 【游戏 1】自包含插件包
    │   │   ├── model/                            #   纯 Java：方块、棋盘
    │   │   ├── engine/                           #   纯 Java：状态机、计分、7-bag
    │   │   ├── view/TetrisView.java              #   Canvas 渲染 + 拖拽手势
    │   │   ├── ui/                               #   Fragment + ViewModel
    │   │   └── TetrisGamePlugin.java             #   注册入口
    │   │
    │   └── parking/                              # 【游戏 2】挪车消消消，与 tetris 平级、零依赖
    │       ├── model/                            #   纯 Java：车辆、方向、乘客组、关卡
    │       ├── engine/                           #   纯 Java：状态机、移动/驶出/接客、撤销、
    │       │                                     #   关卡生成 + BFS 可解性证明
    │       ├── view/                             #   倾斜棋盘渲染 + 手势 + 驶出动画
    │       ├── ui/                               #   Fragment + ViewModel
    │       └── ParkingGamePlugin.java            #   注册入口
    │
    ├── data/                                     # 【基础设施】Room 成绩存档
    └── ui/
        ├── hub/                                  #   游戏大厅
        └── records/                              #   战绩
```

---

## 文档索引

| 文档 | 内容 | 读者 |
|------|------|------|
| `docs/01-prd-game-hub.md` | 产品需求：范围、目标指标、不做的事 | 产品 / 全体 |
| `docs/02-gdd-drag-tetris.md` | 游戏设计：核心循环、机制规格、计分公式、调参表 | 设计 / 开发 |
| `docs/03-architecture.md` | 架构设计：分层、包结构、6 条 ADR | 开发 |
| `docs/04-ui-spec.md` | UI 规格：色板、布局尺寸、无障碍要求 | 设计 / 开发 |
| `docs/06-code-review.md` | 代码审查报告、遗留问题与冒烟清单 | 开发 |
| **`docs/07-dev-guide.md`** | **开发者指南：新增游戏的完整步骤、调参、验证清单** | **开发** |
| `docs/08-prd-parking-jam.md` | 挪车消消消：产品需求、目标指标、不做的事 | 产品 / 全体 |
| `docs/09-gdd-parking-jam.md` | 挪车消消消：机制规格、关卡生成算法、动画规格 | 设计 / 开发 |
| `docs/10-architecture-parking-jam.md` | 挪车消消消：增量架构、ADR-007~010、适应度函数 | 开发 |
| `docs/11-ui-spec-parking-jam.md` | 挪车消消消：倾斜棋盘、车辆立体感、色板、动画 | 设计 / 开发 |
| `docs/12-code-review-parking-jam.md` | 挪车消消消：审查报告、被测试抓出的缺陷、冒烟清单 | 开发 |

---

## 依赖库

全部第三方依赖，共 7 类。**无广告 SDK、无统计埋点、无网络库**。

| 类别 | 库 | 版本 |
|------|-----|------|
| UI | Material Design 3 | 1.11.0 |
| UI | ConstraintLayout / RecyclerView | 2.1.4 / 1.3.2 |
| 导航 | Navigation Fragment / UI | 2.7.7 |
| 架构 | Lifecycle ViewModel / LiveData | 2.7.0 |
| 数据 | Room Runtime / Compiler | 2.6.1 |
| 兼容 | Desugar JDK Libs | 2.0.4 |
| 核心 | AndroidX Core / AppCompat / Fragment | 1.12.0 / 1.6.1 / 1.6.2 |

---

## 当前验证状态

| 项目 | 状态 |
|------|------|
| Gradle 编译（assembleDebug） | 已通过，零警告，产物 7.23 MB |
| 引擎逻辑验证 | 已通过（纯 Java 引擎编译运行，18 项断言全过） |
| 单元测试 | 未固化到工程（需加 `testImplementation 'junit:junit:4.13.2'`） |
| 真机运行 | **未执行** |

编译通过只证明代码能跑，不证明玩法对。请按 `docs/06-code-review.md` 第六节的
10 步冒烟清单装到真机上过一遍。
