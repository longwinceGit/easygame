# 架构设计：Game Hub Android App

**Version**: 1.0　**Status**: 已接受　**Owner**: Software Architect
**上游输入**：`docs/01-prd-game-hub.md`、`docs/02-gdd-drag-tetris.md`
**下游输出**：`docs/04-ui-spec.md`（UI 规格）

---

## 1. 约束与问题

| 约束 | 来源 |
|------|------|
| 必须复用现有模板：MVVM + Repository + Room + Navigation + Material 3 | 用户"基于当前项目架构" |
| 第一款游戏为可拖拽俄罗斯方块 | PRD |
| 后续可低成本接入多款游戏（PRD G1：≤1 人日，且不改壳层文件） | PRD |
| compileSdk 34 / minSdk 21 / Java 17 / 无 Kotlin / 无额外三方依赖 | 现有 `app/build.gradle` |
| 单款游戏逻辑必须可被 JVM 单测 | GDD 交接项 1 |

**核心架构问题**：如何做到「加第二款游戏不改壳层代码」，同时**不给第一款游戏引入无用的抽象**（架构反模式：过早抽象 / Rule of Three）。

---

## 2. 领域划分（限界上下文）

```
┌──────────────────────────────────────────────────────────┐
│  Hub Context（壳）                                         │
│  职责：游戏发现、导航、主题、成绩聚合                        │
│  包：ui.hub / ui.records / game.core                       │
├──────────────────────────────────────────────────────────┤
│  Tetris Context（游戏）                                    │
│  职责：棋盘、方块、计分、渲染、手势                          │
│  包：game.tetris.*                                        │
│  ⚠️ 壳对 Tetris 内部零知识，只知道它是个 GamePlugin          │
├──────────────────────────────────────────────────────────┤
│  Record Context（基础设施）                                │
│  职责：对局记录持久化与聚合查询                              │
│  包：data.*                                               │
└──────────────────────────────────────────────────────────┘
```

**上下文映射**：Hub → Tetris 通过 `GamePlugin` 接口（**防腐层**）。Hub 只依赖 `descriptor()` 返回的元信息（id / 标题 / 图标 / 主题色 / 导航目标），永远不 import `game.tetris.*` 的任何类。

> **唯一例外**：`GameRegistry.java` 必须 import 具体插件类——这行 import 就是"注册"本身，
> 也正是 PRD Story 5 允许的"壳层 ≤1 行改动"。除它以外的任何壳层类都不允许。
> 若将来为消除这一行引入 ServiceLoader / 反射，会牺牲编译期校验与可追踪性，
> 对本项目（1–5 款游戏）是负收益，见 ADR-001 备选方案 C。

---

## 3. 分层与依赖方向

```
UI 层        ui.hub / ui.records / game.tetris.ui
                    ↓ 依赖
契约层        game.core (GamePlugin / GameDescriptor / GameRegistry)
                    ↓ 依赖（仅元信息）
────────────────────────────────────────────────────────
游戏领域层    game.tetris.model + game.tetris.engine   ← 纯 Java，零 android.*
游戏渲染层    game.tetris.view                          ← android.*
────────────────────────────────────────────────────────
基础设施层    data (Room / Repository)
```

**硬性依赖规则（写入 CI 的架构适应度函数）**：

```bash
# 1. 游戏领域层禁止依赖 Android 框架
grep -rn "import android\." app/src/main/java/com/template/app/game/tetris/model \
                            app/src/main/java/com/template/app/game/tetris/engine \
  && echo "❌ 领域层不得依赖 android.*" && exit 1

# 2. 壳层禁止直接依赖具体游戏（GameRegistry.java 是唯一白名单：它就是注册点）
grep -rn "import com.template.app.game\.[a-z]*\." app/src/main/java/com/template/app/ui \
  && echo "❌ 壳层不得依赖具体游戏实现" && exit 1
grep -rn "import com.template.app.game\." \
     $(ls app/src/main/java/com/template/app/game/core/*.java | grep -v GameRegistry.java) \
  && echo "❌ 除 GameRegistry 外，core 层不得依赖具体游戏实现" && exit 1

# 3. 领域层禁止依赖数据层
grep -rn "import com.template.app.data" app/src/main/java/com/template/app/game/tetris/engine \
  && echo "❌ 领域层不得依赖基础设施" && exit 1
```

---

## 4. 包结构

```
com.template.app
├── MainActivity.java                     # NavHost + BottomNavigation（复用模板）
│
├── game/
│   ├── core/                             # 【壳】插件契约 —— 新增游戏不改这里
│   │   ├── GameDescriptor.java           #   游戏元信息（不可变值对象）
│   │   ├── GamePlugin.java               #   插件接口（5 个方法的极简契约）
│   │   └── GameRegistry.java             #   注册表（唯一需要改动 1 行的地方）
│   │
│   └── tetris/                           # 【游戏1】自包含插件包
│       ├── model/                        #   ← 纯 Java
│       │   ├── TetrominoType.java        #     7 种四连块 + 4 个旋转态（预计算缓存）
│       │   ├── Tetromino.java            #     实例：类型 + 当前旋转
│       │   └── Board.java                #     棋盘：碰撞 / 落点 / 消行
│       ├── engine/                       #   ← 纯 Java
│       │   ├── TetrisEngine.java         #     状态机 + 计分 + 7-bag + 结束判定
│       │   ├── TetrisConfig.java         #     所有可调数值（GDD 调参表的代码映射）
│       │   └── PlaceResult.java          #     放置结果（不可变）
│       ├── view/
│       │   └── TetrisView.java           #     Canvas 渲染 + 拖拽手势（board + tray 同 View）
│       ├── ui/
│       │   ├── TetrisFragment.java       #     MVVM View
│       │   └── TetrisViewModel.java      #     MVVM ViewModel（持有 engine）
│       └── TetrisGamePlugin.java         #     插件注册入口
│
├── data/                                 # 【基础设施】沿用模板模式
│   ├── AppDatabase.java                  #   Room 单例（DCL）
│   ├── GameRecordDao.java                #   DAO：最高分 / 最近对局 / 分组聚合
│   ├── GameRecordRepository.java         #   Repository（单例 + Executor）
│   └── model/
│       ├── GameRecord.java               #   @Entity
│       └── GameBest.java                 #   聚合查询 POJO
│
└── ui/
    ├── hub/
    │   ├── GameHubFragment.java          #   游戏大厅（读 GameRegistry）
    │   ├── GameHubViewModel.java
    │   └── GameAdapter.java
    └── records/
        ├── RecordsFragment.java          #   战绩
        └── RecordsViewModel.java
```

---

## 5. 关键接口

### 5.1 插件契约（壳与游戏的唯一耦合点）

```java
public interface GamePlugin {
    /** 游戏元信息：大厅列表渲染所需的全部数据 */
    @NonNull GameDescriptor descriptor();

    /** 是否对玩家可见。false 时大厅显示"敬请期待"占位卡 */
    boolean isEnabled();
}
```

```java
public final class GameDescriptor {
    public final String id;                 // 唯一 ID，也是存档的 gameId
    @StringRes  public final int titleRes;
    @StringRes  public final int subtitleRes;
    @DrawableRes public final int iconRes;
    @ColorRes   public final int accentRes; // 卡片主题色
    @IdRes      public final int navDestinationRes; // nav_graph 中该游戏的 destination
}
```

> **为什么 `GamePlugin` 只有 2 个方法？** 这是对抗"过早抽象"的关键决策。壳真正需要知道的只有"展示什么"和"跳到哪"。游戏的生命周期、渲染、状态机全部自治。等到第二款游戏接入时如果确实需要 `onGameRegistered()` 之类的钩子，**那时再加**——加方法比删方法安全得多。

### 5.2 引擎接口（GDD 交接项 2 的完整映射）

```java
public final class TetrisEngine {
    public enum State { READY, RUNNING, PAUSED, GAME_OVER }

    public TetrisEngine();                 // 随机种子
    public TetrisEngine(long seed);        // 可复现单测

    // —— 查询（供渲染层读取，无副作用）——
    public int[][] getCells();             // 0=空，1..7=调色板索引+1
    public Tetromino[] getTray();          // 已消耗的槽位为 null
    public int landingRow(int slot, int left);   // 幽灵预览；-1 表示不可放
    public boolean canPlaceAnywhere(int slot);   // 结束判定
    public int getScore(); int getLines(); int getLevel(); int getCombo();
    public State getState();

    // —— 命令（产生状态变化）——
    public void start();
    public void pause(); public void resume(); public void restart();
    public void rotate(int slot);
    public PlaceResult place(int slot, int left);
}

public final class PlaceResult {
    public final boolean success;
    public final int[] clearedRows;   // 被消除的行索引（供 View 播放闪烁动画）
    public final int gained;          // 本次得分
    public final int combo;           // 本次连击数
    public final boolean gameOver;
}
```

---

## 6. 架构决策记录（ADR）

### ADR-001：游戏层采用「插件注册表」而非硬编码跳转

**状态**：已接受

**背景**：PRD G1 要求新增一款游戏 ≤1 人日且不改壳层文件。

**决策**：引入 `GamePlugin` 接口 + `GameRegistry` 静态注册表。大厅从注册表读取列表，点击时用 `descriptor().navDestinationRes` 导航。

**备选方案**：
| 方案 | 优点 | 缺点 |
|------|------|------|
| A. 硬编码：大厅写死 3 个按钮 | 零抽象，最简单 | 每加一款游戏都要改大厅 Fragment + 布局，违反 G1 |
| B. 插件注册表（选用） | 加游戏 = 加文件 + 注册 1 行 | 多 2 个接口文件 |
| C. ServiceLoader / SPI 动态发现 | 连注册 1 行都省了 | 需要 `META-INF/services` 配置 + 反射，minSdk21 下增加包体与调试成本，且编译期无法校验 |

**影响**：✅ 加游戏的边际成本降到最低；❌ 多 2 个文件的抽象成本（可接受，因为第一款游戏真实使用了它，不是为"未来可能"预留）。

---

### ADR-002：渲染采用「单一自定义 View + Canvas」而非逐格 View / 游戏引擎 / Compose

**状态**：已接受

**背景**：10×20 棋盘 + 托盘，需要 60fps 拖拽反馈。

**决策**：一个 `TetrisView` 同时绘制棋盘与托盘，所有手势在**同一个 View** 内处理。

**备选方案**：
| 方案 | 优点 | 缺点 |
|------|------|------|
| A. 200 个 ImageView 网格 | 布局可视化 | 200 个 View 的 measure/layout 会让拖拽掉帧；拖拽跨 View 的命中判定极其复杂 |
| B. **单一自定义 View（选用）** | 一次 `onDraw` 完成全部绘制；拖拽坐标天然统一；无额外依赖 | 无 XML 预览；需手写测量逻辑 |
| C. OpenGL / libGDX | 性能上限高 | 引入 ~2MB 依赖与完全不同的生命周期，与"轻量模板"定位冲突 |
| D. Jetpack Compose | 声明式，现代化 | 当前工程是纯 Java + ViewBinding，引入 Compose 需 Kotlin + 全新工具链，属于重写而非扩展 |

**关键设计点**：**board 与 tray 必须在同一个 View 内**。若拆成两个 View，跨 View 拖拽要么用 `startDragAndDrop`（API 21 支持有限、无法自定义拖拽影像的细节），要么自己转发触摸事件（坐标转换 + 父容器拦截，复杂度高且易出 bug）。合二为一是**复杂度更低**的选择。

**影响**：✅ 手势逻辑单一、性能可控；❌ 布局灵活性下降（棋盘与托盘的相对位置由 View 内部计算而非 XML 约束）。

---

### ADR-003：游戏领域层为纯 Java，禁止依赖 `android.*`

**状态**：已接受

**背景**：GDD 要求引擎可 JVM 单测、可注入随机种子。

**决策**：`game/tetris/model/` 与 `game/tetris/engine/` 不 import 任何 Android 类。方块颜色在领域层用**调色板索引**（0–6）表示，由渲染层映射到 `R.array` 的颜色数组。

**影响**：✅ 引擎可被纯 JUnit 直接测试（无需 Robolectric）；✅ 换渲染层时逻辑零改动；❌ 颜色等资源型数据需通过索引间接表达。

---

### ADR-004：渲染层直连引擎（只读），变更一律经 ViewModel

**状态**：已接受

**背景**：60fps 拖拽下，每次 `invalidate()` 前都要构造棋盘快照（200 int）会产生 GC 压力。

**决策**：
- **读**：`TetrisView.attach(engine)`，绘制时直接读引擎的 `getCells()` / `getTray()`，并调用纯函数 `landingRow()` 计算幽灵落点。
- **写**：任何状态变更（放置 / 旋转 / 暂停）必须通过 `TetrisView.Listener` → `TetrisFragment` → `TetrisViewModel` → `engine`。View **永不直接修改**引擎。

**为什么读可以直连**：读是幂等的、无副作用的，不破坏 MVVM 的单向数据流；而"每帧复制棋盘的快照"是**用架构纯洁性换取 GC 抖动**，在 60fps 场景下是错误的权衡。

**影响**：✅ 零分配渲染、单向写数据流得以保留；❌ View 与引擎类型耦合（可接受，二者同属 Tetris Context）。

---

### ADR-005：成绩持久化沿用 Room，不引入 SharedPreferences

**状态**：已接受

**背景**：GDD 长期循环需要"最高分"与"最近对局"。

**决策**：复用模板 Room 基础设施，新增 `game_records` 表与 `GameRecordRepository`。库名改为 `game_hub.db`，版本 1。

**影响**：✅ 天然支持"最近对局列表"与按游戏分组的聚合查询；✅ 与模板架构一致，新人零学习成本；❌ 单条最高分写入比 SP 重（但每局仅一次，可忽略）。

---

### ADR-006：移除笔记 Demo，替换为游戏业务

**状态**：已接受

**背景**：模板 `README.md` 明确"开始开发新项目时，将 Demo 代码替换为你的业务逻辑即可"。笔记 Demo 与新游戏并存会导致导航图、底部导航、数据库实体的语义混乱。

**决策**：删除 `data/model/Note.java`、`NoteDao`、`NoteRepository`、`ui/home`、`ui/detail`、`ui/adapter` 及对应布局/菜单；底部导航改为「游戏」「战绩」两个 Tab。

**回滚代价**：低（Git 可恢复）。

---

## 7. 质量属性

| 属性 | 设计 | 验证方式 |
|------|------|---------|
| 可扩展性 | `GameRegistry` 一行注册 | PRD Story 5 验收 |
| 可维护性 | 依赖方向单向 + 适应度函数脚本 | 见第 3 节 grep 检查 |
| 性能 | 单 View 单 `onDraw`；`onDraw` 内**零对象分配**（Paint/RectF 全部预分配复用） | 拖拽帧时间 < 8ms |
| 可测试性 | 引擎纯 Java + 可注入种子 | JVM 单测覆盖 7-bag、消行、结束判定 |
| 可靠性 | `onPause` 自动暂停；Room 写入走 `Executor` 不阻塞主线程 | 手工验证 |
| 主题一致性 | 全部颜色走 `res/values` 令牌 + `values-night` | 深浅色双主题实测 |

---

## 8. 技术选型决策矩阵（渲染方案）

| 维度 | 权重 | A: 逐格 View | B: 自定义 View | C: 游戏引擎 |
|------|------|-------------|---------------|------------|
| 帧率表现 | 30% | 3 | 9 | 10 |
| 实现复杂度 | 25% | 4 | 7 | 3 |
| 依赖体积 | 20% | 10 | 10 | 2 |
| 团队熟悉度 | 15% | 9 | 7 | 3 |
| 后续可扩展 | 10% | 3 | 8 | 9 |
| **加权得分** | | **5.35** | **8.20** | **5.60** |

→ **选用 B**。

---

## 📌 交接给「UI 设计师」的输入

1. 棋盘与托盘**在同一个自定义 View 内**，托盘位于棋盘下方（竖屏拇指区）
2. 棋盘按可用高度自适应：cell = min(宽/10, (高 - 托盘区 - 间距)/20)，水平居中
3. 需要你定义：7 种方块的调色板（浅色/暗色两套）、棋盘底、网格线、幽灵块（合法/非法两态）、托盘槽底、拖拽抬升态
4. 需要你定义：大厅卡片、战绩页面的视觉规格（沿用 `Widget.Template.Card` / M3 令牌）
5. 约束：所有颜色必须落在 `res/values` + `values-night`，不允许硬编码；方块靠**形状 + 内框**双重编码，保证色觉障碍可玩
