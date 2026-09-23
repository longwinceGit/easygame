# 架构补充：挪车消消消（Parking Jam）

**Version**: 1.0  **Last Updated**: 2026-09-21  **Author**: 软件架构师
**前置阅读**：`docs/03-architecture.md`（ADR-001 ~ ADR-006 仍然全部有效，本文档只做**增量**）

---

## 0. 一句话结论

新增第二款游戏**没有推翻任何一条既有架构决策**，只新增了 3 条 ADR。
壳层改动总计 **3 个文件、4 行代码**（`GameRegistry` 1 行、`nav_graph.xml` 1 个 destination、
`Constants` 1 个常量）。ADR-001 的承诺在本次交付中被**首次真实检验**并通过。

---

## 1. 问题与约束

**问题**：如何在不改壳层逻辑的前提下，接入一款玩法与俄罗斯方块**完全正交**的游戏？
（俄罗斯方块：网格 + 托盘 + 自由放置；挪车消消消：滑块约束 + 顺序匹配 + 生成即求解）

**约束**：

1. 既有三条架构适应度函数必须继续成立（领域层无 `android.*`、壳层不 import 具体游戏、领域层不依赖 `data.*`）
2. 领域层必须能被纯 JVM 单测覆盖——本作的求解器是必需的（生成即求解），它必须跑在没有 Android 的环境下
3. 关卡生成有真实的计算成本（BFS），不能阻塞主线程
4. 不能修改 `game.tetris` 下的任何文件

---

## 2. 限界上下文与包结构

```
com.template.app
├── game/core/                    【壳 · 契约层】本次零改动
│
├── game/parking/                 【新游戏 · 自包含插件包】本次全部新增
│   ├── ParkingGamePlugin.java             注册入口（唯一被壳层引用的类）
│   ├── model/                             纯 Java 实体（可变实体 + 不可变值对象）
│   │   ├── Direction.java                 枚举：UP/DOWN/LEFT/RIGHT（dRow, dCol）
│   │   ├── Vehicle.java                   实体：长度/轴向/箭头/颜色/位置/所在区域
│   │   ├── PassengerGroup.java            值对象：颜色 + 人数
│   │   └── Level.java                     不可变关卡描述（车辆原型 + 乘客队列）
│   ├── engine/                            纯 Java 引擎（零 android.*）
│   │   ├── ParkingConfig.java             全部可调数值 + 关卡曲线
│   │   ├── ParkingEngine.java             状态机 / 移动 / 驶出 / 接客 / 撤销
│   │   ├── MoveResult.java               一次操作的结果（不可变，含失败单例）
│   │   └── ParkingLevelGenerator.java     随机布局 + BFS 可解性证明
│   ├── view/                              渲染与手势（Canvas 单 View）
│   │   ├── ParkingView.java               门面（组装 + 对外 API）
│   │   ├── ParkingGeometry.java           布局几何与坐标换算（无 Canvas/Paint）
│   │   ├── ParkingRenderer.java           全部绘制 + 动画计时
│   │   ├── ParkingAnimator.java           位移动画/驶出动画/浮字的瞬时状态
│   │   └── ParkingTouchHandler.java       点击 / 拖动状态机
│   └── ui/                                MVVM 的 V / VM
│       ├── ParkingFragment.java
│       └── ParkingViewModel.java
│
└── data/                         【基础设施】沿用，零改动
```

**依赖方向**（沿用既有规则，未破坏）：

```
ui.parking ──► engine.parking ──► model.parking
     │              │
     │              └──► 无 android.*、无 data.*
     └──► view.parking ──► engine.parking（只读）
     └──► data.*（成绩落库）
core.GameRegistry ──► parking.ParkingGamePlugin（唯一白名单）
```

### 2.1 渲染反馈通道（浮字 / 连击）

分数与连击的视觉反馈走一条**独立于游戏逻辑**的渲染通道，三层各司其职、互不直接依赖：

- **引擎层**（`ParkingEngine.resolvePickup`）：在一次接客结算里累加分数、记录真正开走的车，
  通过 `MoveResult.boardSteps` / `boardEvent` 把离场车清单交出去——**不引用任何 `android.*`，不碰 UI**。
- **UI 层**（`ParkingFragment.showComboIfAny`）：拿到离场车清单后，若 `size ≥ 2` 即判定为连击，
  算出奖励 `50 × (车数−1)`，调用 `ParkingView.startComboPopup(车数, 奖励)`。
- **渲染层**（`ParkingAnimator` → `ParkingRenderer`）：`startCombo` 写入 `comboText` / `comboStart`，
  `drawComboPopup` 在接客区上方更高处绘制「N连击 +奖励」浮字，并纳入 `isAnimating` 保证持续刷帧。

连击浮字使用**独立字段** `comboText`，与每位乘客的「+分数」浮字（`popupValue`）分开存储与绘制，
因此两者可同时出现、互不覆盖。该浮字通道与俄罗斯方块消行弹「+N」浮字同源，
区别仅在停车连击多了一层「离场车计数 → 判定连击」的逻辑（详见 GDD 4.2 / 6.1）。

---

## 3. ADR-007：关卡生成放在后台线程，主线程只接收结果

### 状态
已接受

### 背景
关卡生成 = 随机布局 + BFS 求解。最坏情况（24 次布局重试 × 5 次 BFS × 6000 状态）
是数十万次状态展开。若在主线程执行，最坏会造成数百毫秒的卡顿甚至 ANR。
但生成是整个游戏的入口，没法"等一会儿再说"。

### 决策
生成器 `ParkingLevelGenerator.generate(level)` 是一个**纯函数**：
输入关卡号，输出不可变的 `Level`，**不触碰引擎**。
`ParkingViewModel` 把它投递到自己的单线程池执行，完成后用 `postValue` 把 `Level`
发回主线程，由 Fragment 调用 `viewModel.applyLevel(level)` 落进引擎。

UI 在此期间显示 `ProgressBar`（`loading` LiveData）。

### 备选方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| **A. 后台线程 + 纯函数 Level**（采纳） | 主线程零风险；引擎状态只在主线程变更；纯函数易单测 | 多一个 loading 状态要处理 |
| B. 主线程同步生成 | 代码最简单 | 最坏数百毫秒卡顿，ANR 风险，无法接受 |
| C. 预生成 N 个关卡缓存 | 运行时零成本 | 关卡失去随机性；缓存耗尽要回退到 A，等于两套代码 |
| D. 降低上限让同步生成可接受 | 简单 | 上限调低 → 生成失败率上升 → 关卡变简单。为了性能牺牲玩法，本末倒置 |

### 影响
- 引擎的写入**仍然全部发生在主线程**（ADR-004 保持成立）
- `Level` 必须不可变，否则跨线程读写会出问题
- 生成器的可测试性大幅提升：纯 JVM 直接调用，无需 Robolectric

---

## 4. ADR-008：可解性证明内建在生成器中，而不是事后校验

### 状态
已接受

### 背景
"随机丢几辆车进停车场"产出的局面**大概率不可解**：一辆竖直的障碍车就能让
被它压住的客用车永久出局（这是我们在 GDD 中禁止 ↓ 箭头的原因）。
若靠"生成 → 校验 → 不合格就丢掉"，不合格率可能高到无法接受。

### 决策
把顺序**求出来**，而不是**验出来**：

1. 随机布局（只保证不重叠）
2. 逐辆 BFS：对每辆还没出去的客用车，搜索"把它开到第 0 行"的最短路
3. 求出一辆就把它从状态中移除，继续下一辆
4. **求出的顺序本身**就是乘客队列的顺序

于是"可解"不是被检查出来的属性，而是**构造的产物**。

### 备选方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| **A. 构造即求解**（采纳） | 100% 可解；顺序天然产生，无需另想办法排队列 | 需要写一个 BFS 求解器 |
| B. 生成后跑求解器校验 | 概念简单 | 失败率高，且失败后要重生成，等于把 A 的成本付两遍 |
| C. 手工设计关卡 | 难度曲线可控 | 无内容团队，不可持续；且违背"无限供给"的目标 |
| D. 逆向打乱（从解状态倒推） | 常用于滑块谜题 | 本作车辆只能**单向**行驶，逆操作不合法，不可用 |

### 影响
- 求解器成为**核心资产**：它同时是生成器、是可解性证明、也是未来的"提示"功能基础
- 状态编码必须紧凑（每车 6 bit 打包进 `long`），否则 BFS 会退化
- 求解器是纯 Java ⇒ 可以直接写 JVM 单测断言"生成 200 关，全部可解"

---

## 5. ADR-009：撤销用"全量快照"而非"逆向操作"

### 状态
已接受

### 背景
撤销必须回滚：车辆位置 + 所在区域、乘客队列（含队首剩余人数）、分数、
已接客数、剩余移除次数、状态机状态。这些字段分散，漏回滚一个就是 bug。

### 决策
每次**写操作之前**抓一份全量快照入栈（`Snapshot`：车辆位置数组 + 区域数组 +
队列数组 + 4 个标量 + 状态）。撤销 = 弹栈 + 整体还原。上限 200 份。

### 备选方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| **A. 全量快照**（采纳） | 不可能漏字段；新增字段时"忘记同步"会以编译错误暴露 | 每次操作分配若干小数组 |
| B. 逆向操作（记录 delta 并反演） | 内存最省 | 每新增一种操作就要写一份反演逻辑；接客/自动接客的反演极易写错 |
| C. 命令模式 + undo() | 优雅 | 同上，且命令对象要持有还原所需的全部上下文，等于 A 的复杂版 |

### 影响
- 一次快照 ≈ 10 车 × 3 数组 + 队列 ≈ 不到 100 个 int。200 份 ≈ 80KB，可忽略
- 快照是**值拷贝**，不共享引用 —— 这是正确性前提，评审时需重点确认

---

## 6. 架构适应度函数（沿用既有 3 条 + 新增 2 条）

既有 3 条继续有效（`docs/03-architecture.md` 第 3 节）。新增：

```bash
# F4：壳层不得 import 具体游戏（GameRegistry 是唯一白名单）—— 改为逐游戏校验
grep -r "import com.template.app.game.parking" \
  app/src/main/java --include=*.java \
  | grep -v "game/parking/" | grep -v "GameRegistry.java" \
  && echo "FAIL: 壳层不得依赖具体游戏" && exit 1

# F5：停车场领域层不得 import android.*
grep -r "import android\." \
  app/src/main/java/com/template/app/game/parking/model \
  app/src/main/java/com/template/app/game/parking/engine \
  && echo "FAIL: 领域层必须保持纯 Java" && exit 1
```

F5 是本作**最需要守护**的一条：`ParkingLevelGenerator` 是纯算法资产，
一旦混入 `android.*` 就再也跑不了 JVM 单测，可解性证明会失去回归保护。

---

## 7. 关键接口

### 7.1 引擎对外契约（只读 + 命令分离）

```java
// 只读（供 View 渲染）
List<Vehicle> lotVehicles();        // 停车场内的车
List<Vehicle> pickupVehicles();     // 接客区里的车
List<PassengerGroup> queue();       // 乘客队列（队首在 index 0）
int score(); int passengersLeft(); int levelIndex();
int removesLeft(); int freeSlots(); State state();
boolean canMove(int vehicleId);      // 命中测试时的可动性提示
int maxForward(int vehicleId);       // 沿箭头可前进格数
int maxBackward(int vehicleId);      // 反向可拖动格数

// 命令（一律返回 MoveResult，失败返回不可变单例）
MoveResult move(int vehicleId, int delta);   // delta>0 沿箭头，<0 反向（拖动）
MoveResult tap(int vehicleId);               // 沿箭头滑到底（可能驶出）
boolean removeBlocker(int vehicleId);        // 仅灰色障碍车
boolean undo();
```

**为什么 `move` 接受 `delta` 而不是"目标格"**：拖动天然产生的是位移量，
引擎再夹紧到合法区间即可。让引擎做夹紧，View 就不必知道边界规则（ADR-004）。

### 7.2 驶出与接客的不变量

下列不变量由 `ParkingEngine` 守护，任何命令返回前都必须成立：

1. 任何车辆只处于三处之一：停车场 / 接客区 / 已离开（`Vehicle.place`）
2. 接客区车辆数 ≤ `PICKUP_SLOTS`
3. **不存在停留在第 0 行的车辆**——到达第 0 行即驶出，这一条保证"接客区满时拦停"逻辑不需要处理第 0 行的车
4. 队列人数总和 + 已接客数 == 关卡总人数（守恒）
5. `state == SOLVED` ⟺ 队列为空
6. 若 `state == RUNNING`，则至少存在一辆可移动的车辆（否则立刻转 `STUCK`）

---

## 8. 容量与性能估算

```
单次 BFS 状态数         ≤ 6000
每状态展开动作数        ≤ 2 × 车辆数 ≤ 18
一关 BFS 次数           ≤ 客用车数 ≤ 5
布局重试次数            ≤ 24

最坏展开次数  ≈ 24 × 5 × 6000 = 720,000
每展开成本     ≈ 重建占用(9车×3格) + 哈希 ≈ 0.5–2 µs
最坏耗时       ≈ 0.4 – 1.4 s（后台线程，玩家看到 loading）
典型耗时       ≈ 5 辆 × 数百状态 ≈ < 20 ms
```

**结论**：典型情况玩家感知不到 loading；最坏情况也只是一次 1 秒左右的等待，
且发生在后台。这在"程序生成关卡"的代价里是可接受的。
若真机实测 P95 > 800ms，按 PRD §7 回滚准则降级为固定种子关卡。

---

## 9. 反模式自查

| 反模式 | 本次是否出现 | 说明 |
|--------|-------------|------|
| 过早抽象 | 否 | 没有为"撤销"抽接口（只有 1 个实现）；没有为"生成器"抽策略（只有 1 种算法） |
| 金锤子 | 否 | 没有把俄罗斯方块的 `TetrisView` 抽象成"通用棋盘 View"——两款游戏的交互模型完全不同，强行复用才是灾难 |
| 简历驱动开发 | 否 | 没有引入 Kotlin / Compose / Hilt / 协程。工程是 Java + ViewBinding，就继续用 Java + ViewBinding |
| 共享数据库 | 否 | 沿用 `game_records` 单表，按 `gameId` 区分，与既有设计一致 |
| 复杂度搬家 | 部分存在（**已接受**） | 求解器的复杂度从"内容团队手工设计关卡"搬到了"代码里"。这是无内容团队的必然取舍 |

---

## 10. 与既有 ADR 的关系

| ADR | 是否受影响 | 说明 |
|-----|-----------|------|
| ADR-001 插件注册表 | **被验证** | 第二款游戏接入，壳层仅改注册 1 行 |
| ADR-002 单自定义 View + Canvas | **沿用且加强** | 倾斜棋盘 + 水平接客带 + 驶出动画跨坐标系，全部压在同一个 View 内 |
| ADR-003 领域层纯 Java | **沿用且加强** | 求解器是纯算法资产，离线验证直接靠它才跑得起来 |
| ADR-004 渲染只读引擎 | **沿用** | 拖动产生的位移交给引擎夹紧 |
| ADR-005 持久化沿用 Room | **沿用** | 复用 `game_records`，不改表 |
| ADR-006 移除笔记 Demo | 无关 | — |

## 11. V1.1 增补：ADR-010 倾斜棋盘

### 状态
已接受

### 背景
参考截图的停车场是一块**旋转过的菱形场地**，车头朝各个方向斜着停——这是该品类
视觉识别度最高的一部分。正交网格做不出来这个观感。

### 决策
对棋盘整体做一次 **-22° 刚体旋转**：绘制用 `canvas.rotate(angle, cx, cy)`，
触摸用一次逆旋转变换把屏幕坐标转回棋盘坐标再命中。
乘客队列与接客区不参与旋转（它们在截图里就是水平的）。

### 备选方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| **A. 刚体旋转整体棋盘**（采纳） | 一次 rotate 搞定绘制；纯旋转不改长度，描边/命中区无需补偿 | 需要一次逆变换做命中 |
| B. 真等距投影（菱形格） | 最接近截图 | 格子变菱形，车辆矩形与格子的映射复杂，命中测试要解方程 |
| C. 正交网格 | 最简单 | 完全失去截图的观感，等于没做 |

### 影响
- 格子尺寸由旋转后外接盒决定：`cell ≈ min(可用宽, 可用高) / 7.81`，360dp 屏上约 42dp，比正交更大
- 车辆绘制改为"以中心 + 角度"的通用函数，棋盘内移动与驶出动画复用同一份画法
- 驶出动画必须在**屏幕坐标系**画（终点是水平接客位），不能在旋转坐标系里画 —— 这也是
  `ParkingAnimator` 把两种动画拆开的原因
