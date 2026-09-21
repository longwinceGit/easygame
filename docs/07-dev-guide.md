# 开发者指南

**适用版本**：Game Hub V1.1（已接入第二款游戏）　**最后更新**：2026-09-21

读完本文你将能够：

- 理解代码的分层与依赖规则
- **在 30 分钟内接入一款新游戏**（不改壳层文件）
- 调整拖拽方块 / 挪车消消消的数值平衡
- 知道哪些部分**没有**被验证过

> **ADR-001 已经被真实验证过一次**：`game/parking/` 整包接入时，壳层只改了
> `GameRegistry` 1 行 + `Constants` 1 个常量 + `nav_graph.xml` 1 个 destination。
> 下面第 4 节的"接入 4 步"就是照着它走通的，`game/parking/` 是最新、最完整的参考实现。

---

## 1. 我该改哪个文件？

用这张表反查，不要凭感觉找。

| 你要做的事 | 改哪里 |
|-----------|--------|
| 改方块下落/消行/计分规则 | `game/tetris/engine/TetrisEngine.java` |
| 改棋盘尺寸、托盘格数、分数值 | `game/tetris/engine/TetrisConfig.java` |
| 改方块形状或旋转 | `game/tetris/model/TetrominoType.java` |
| 改碰撞/落点/消行算法 | `game/tetris/model/Board.java` |
| 改拖拽手感、阈值、命中区 | `game/tetris/view/TetrisView.java`（常量在文件头部） |
| 改方块颜色 / 棋盘配色 | `res/values/game_colors.xml` + `res/values-night/game_colors.xml` |
| 改游戏页布局 | `res/layout/fragment_tetris.xml` |
| 改大厅卡片样式 | `res/layout/item_game.xml` |
| 加文案 | `res/values/strings.xml` |
| **加一款新游戏** | 见第 3 节 |

---

## 2. 依赖规则（会被 CI 检查）

```
ui.*  →  game.core  →  (元信息)
ui.*  →  data.*
game.tetris.ui  →  game.tetris.engine  →  game.tetris.model
game.tetris.view  →  game.tetris.engine（只读）
```

三条硬性规则：

1. **`game/tetris/model/` 与 `game/tetris/engine/` 不得 import 任何 `android.*`**
   —— 这样引擎才能被纯 JVM 单测直接覆盖，换渲染层时逻辑也不用动。
2. **除 `GameRegistry.java` 外，壳层不得 import 具体游戏包**
   —— `GameRegistry` 是注册点，那行 import 就是注册本身，是唯一白名单。
3. **领域层不得依赖 `data.*`**
   —— 计分和存档是两件事。

冒烟自检：

```bash
# 1. 领域层不得依赖 Android
grep -rn "import android\." \
  app/src/main/java/com/template/app/game/tetris/model \
  app/src/main/java/com/template/app/game/tetris/engine \
  && echo "❌" && exit 1

# 2. 壳层不得依赖具体游戏
grep -rn "import com.template.app.game\.[a-z]*\." \
  app/src/main/java/com/template/app/ui && echo "❌" && exit 1

# 3. 领域层不得依赖数据层
grep -rn "import com.template.app.data" \
  app/src/main/java/com/template/app/game/tetris/engine && echo "❌" && exit 1
```

---

## 3. 接入一款新游戏（4 步）

以接入「2048」为例。**全程不修改壳层的任何文件**，除第 4 步的一行注册。

### 第 1 步：写游戏本体

新建包 `game/g2048/`，照 `game/tetris/` 的结构来：

```
game/g2048/
├── model/         # 纯 Java
├── engine/        # 纯 Java，状态机与计分
├── view/          # 自定义 View 或普通布局
├── ui/            # Fragment + ViewModel
└── G2048GamePlugin.java
```

> 只有一条硬要求：**逻辑层别 import `android.*`**，这样你以后能给它写单测。
> 其余你怎么组织都行——壳不关心。

### 第 2 步：实现 `GamePlugin`

照抄 `game/tetris/TetrisGamePlugin.java`，只改 6 个参数：

```java
public final class G2048GamePlugin implements GamePlugin {

    private static final GameDescriptor DESCRIPTOR = new GameDescriptor(
        "g2048",                        // id：唯一，同时是存档的 game_id
        R.string.g2048_title,           // 名称
        R.string.g2048_subtitle,        // 一句话简介
        R.drawable.ic_g2048,            // 24dp vector 图标
        R.color.game_g2048_accent,      // 主题色
        R.id.nav_g2048);                // nav_graph 里的 destination id

    @NonNull
    @Override
    public GameDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public boolean isEnabled() {
        return true;   // 传 false 时大厅显示"敬请期待"灰卡
    }
}
```

### 第 3 步：加导航目的地与资源

`res/navigation/nav_graph.xml` 里加一个 fragment：

```xml
<fragment
    android:id="@+id/nav_g2048"
    android:name="com.template.app.game.g2048.ui.G2048Fragment"
    android:label="@string/g2048_title"
    tools:layout="@layout/fragment_g2048" />
```

再补上 `strings.xml` 的两个字符串、`drawable/ic_g2048.xml` 图标、以及 `game_colors.xml` 里的 accent 色（**浅色与暗色各一份**）。

### 第 4 步：注册（唯一需要改壳层的地方）

`game/core/GameRegistry.java` 的静态块里加一行：

```java
static {
    register(new TetrisGamePlugin());
    register(new G2048GamePlugin());   // ← 新增这一行
}
```

完成。大厅会自动出现新卡片，点击跳转到你的 Fragment，成绩会自动存进
`game_records` 表（`game_id = "g2048"`），战绩页会自动聚合它的最高分。

> **存档是免费的**：只要你的 ViewModel 在结束时调一次
> `repository.save(new GameRecord(gameId, score, lines, level, System.currentTimeMillis()))`，
> 大厅、战绩、最高分展示全部自动生效。参考 `TetrisViewModel.saveRecord()`。

---

## 4. 调整数值平衡

GDD 的调参表已经 1:1 映射到 `game/tetris/engine/TetrisConfig.java`。
**改平衡只改这一个文件**，不要把魔法数字散落到逻辑里。

| GDD 变量 | 代码常量 | 当前值 | 说明 |
|---------|---------|-------|------|
| `BOARD_W` | `BOARD_WIDTH` | 10 | 棋盘列数 |
| `BOARD_H` | `BOARD_HEIGHT` | 20 | 棋盘行数 |
| `TRAY_SIZE` | `TRAY_SIZE` | 3 | 托盘格数，改这个会显著改变难度 |
| `LINE_SCORE` | `LINE_SCORE` | 100 | 每行基础分，计分基准单位 |
| `MULTI_BONUS[2..4]` | `MULTI_BONUS` | 200/500/900 | 一次消多行的额外奖励，激励倍率 2.0/2.67/3.25 |
| `COMBO_UNIT` | `COMBO_UNIT` | 50 | 连击奖励单位 |
| `LEVEL_LINES` | `LEVEL_LINES` | 10 | 每消多少行升 1 级 |

> **计分规则**：放置方块**不得分**，只有消行才得分。
> 单次消 n 行 = `(n × LINE_SCORE + MULTI_BONUS[n] + 连击奖励) × level`。
> 完整得分表见 GDD 机制 7。

拖拽手感的常量在 `TetrisView.java` 文件头部（不走 `TetrisConfig`，因为它们依赖 dp 换算）：

| 常量 | 当前值 | 影响 |
|------|-------|------|
| `TAP_TIMEOUT_MS` | 250ms | 超过这个时长的按压松手不算"点击旋转" |
| `MIN_TOUCH_DP` | 48dp | 托盘槽最小触控命中区（只影响命中，不改绘制尺寸） |
| `DRAG_SCALE` | 1.08 | 拖起时的放大倍数 |
| `MIN_CELL_DP` | 14dp | 格子尺寸下限，小屏上棋盘可能超出可视区 |

**改完怎么判断好坏**（GDD 第 4 节已定义失败标准）：

| 现象 | 意味着 | 调整方向 |
|------|-------|---------|
| 平均单局 < 60 秒 | 空间压力过大 | 加大 `BOARD_HEIGHT` 或减小 `TRAY_SIZE` |
| 平均单局 > 15 分钟 | 毫无压力 | 减小 `BOARD_HEIGHT` 或加大 `TRAY_SIZE` |
| 拖拽成功率 < 90% | 投放区太苛刻 | 见 `TetrisView.endDrag()` 的 `inDropZone` 判定 |
| 玩家从不凑 4 行 | 激励倍率不够 | 提高 `MULTI_BONUS[4]`（当前 900） |

---

## 5. 给引擎写单元测试

引擎是纯 Java 且支持注入种子，所以**不需要 Robolectric**，直接 JUnit：

```java
TetrisEngine engine = new TetrisEngine(42L);   // 固定种子 → 完全可复现
engine.start();
assertEquals(TetrisEngine.State.RUNNING, engine.getState());

PlaceResult r = engine.place(0, 0, 0);   // slot, left, fromTop
assertTrue(r.success);
// 只有消行才得分：空棋盘上放一块不会消行
assertEquals(0, r.gained);
```

建议优先覆盖这三条最容易出错的路径：

1. **7-bag**：连续取 7 个方块，7 种类型应各出现一次
2. **消行压缩**：构造不连续的两行满行（如第 5 行和第 18 行），验证消除后上方行正确下移
3. **结束判定**：把棋盘填到只剩 1 格空洞，验证 `canPlaceAnywhere()` 对所有列 × 所有旋转的判定

> ⚠️ 这些用例**尚未编写**。这是当前最大的质量缺口。

---

## 6. 已知限制（不要当成 bug 上报）

| # | 现象 | 原因 | 位置 |
|---|------|------|------|
| 1 | 多行不连续消除时，闪烁位置可能与实际消除位置略有偏差 | 闪烁用的是消除前的行索引，而棋盘已完成压缩；彻底修好需要引擎延迟压缩或回传行快照 | `TetrisFragment.onPlaced()` |
| 2 | 拖拽过程中无法旋转 | 单指拖拽时屏幕无法可靠接收第二次点击；想旋转请先放回托盘再轻点（零惩罚） | 设计决策，见 GDD 机制 2 |
| 3 | 琥珀色方块有一圈深色描边 | 琥珀与浅色棋盘底对比度仅 2.1:1，低于 WCAG 的 3:1，用描边补偿到 4.6:1 | `TetrisView.drawCell()` |
| 4 | 小屏（cell 被压到 14dp）时托盘可能超出可视区 | 棋盘高度优先，格子尺寸有下限 | `TetrisView.onSizeChanged()` |
| 5 | 统计数值会被 TalkBack 频繁播报 | 尚未设 `importantForAccessibility="no"` | `fragment_tetris.xml` |

---

## 7. 验证清单（发布前必跑）

**编译已通过**（`gradlew.bat assembleDebug`，零警告，产物 7.23 MB）。
但**真机行为尚未验证过**，以下内容仍需人工过一遍：

- [x] 编译通过
- [ ] 大厅出现「拖拽方块」卡片，点击进入
- [ ] 拖拽时出现半透明落点预览 + 目标列高亮
- [ ] 松手后方块落到该列的可落位置，**分数不变**（放置不得分，只有消行才加分）
- [ ] 托盘方块上轻点 → 旋转（不是放置）
- [ ] 拖到已满的列 → 幽灵变红、松手弹回、分数不变
- [ ] 填满一行 → 消行、闪烁、上方下移
- [ ] 暂停 → 棋盘变暗、拖拽失效；再点继续
- [ ] 堆到无处可放 → 弹出结算卡片（本局分 / 最高分）
- [ ] 回到战绩 Tab → 最高分与最近对局正确
- [ ] 杀进程重进 → 最高分仍在
- [ ] 切到暗色主题 → 棋盘与卡片配色正确（**这是本次修过的 bug，务必回归**）

---

## 8. 常见问题

**Q：我想改大厅的网格列数（现在是 2 列）**
`GameHubFragment.onViewCreated()` 里的 `new GridLayoutManager(requireContext(), 2)`。

**Q：新游戏不想用 Room 存档**
可以，存档是可选的。不调 `repository.save()` 就不会有记录，战绩页不显示它而已。

**Q：能不能让游戏页全屏（去掉底部导航）**
可以，但底部导航是模板的既有结构。若确定要去掉，改 `activity_main.xml` 与
`MainActivity`，并相应调整 `nav_graph` 的 startDestination。

**Q：为什么 `GamePlugin` 只有 2 个方法？**
刻意如此。壳真正需要知道的只有"展示什么"和"是否可见"。等第 2 款游戏接入时
如果确实需要新钩子，那时再加——加方法比删方法安全得多（见 ADR-001）。

---

## 8. 附录：挪车消消消（parking）调参与验证速查

第二款游戏是**最新的参考实现**。它比俄罗斯方块多两样东西，值得新游戏作者抄：

### 8.1 数值都在一处

`game/parking/engine/ParkingConfig.java` —— 棋盘尺寸、接客位数、颜色数、
道具次数、计分、关卡曲线 `vehicleCount(level)`、生成器上限。
**改这里就等于改玩法**，其他文件里不允许出现这些数字。

### 8.2 生成即求解（ADR-008）

关卡不是"随机丢车然后祈祷能解"，而是先随机布局，再用 BFS 逐辆求出驶出顺序，
**把这个顺序当作乘客队列的顺序**——可解是构造出来的，不是检查出来的。

这条有三条硬约束，动生成器前先读 `docs/09-gdd-parking-jam.md` 机制 8：

1. BFS 中**非目标**车最多开到离边界一格，不得到达边界（否则求出的解在真实引擎里复现不了）
2. 状态编码：每车一个 char 打进 String（早期用 long 打包 6 bit 卡在 9 车，8×8 + 10 车后改为 String），最多 `ParkingConfig.MAX_VEHICLES = 10` 车
3. `GENERATOR_MAX_BFS_STATES` / `GENERATOR_MAX_ATTEMPTS` 直接决定生成耗时

### 8.3 离线验证（强烈建议照抄）

领域层是纯 Java，所以可以直接这样验证：

```powershell
$src='app\src\main\java\com\template\app\game\parking'
$out="$env:TEMP\parking-verify"
javac -encoding UTF-8 -d $out "$src\model\*.java" "$src\engine\*.java" YourVerify.java
java -cp $out YourVerify
```

验证内容：批量生成关卡并证明可解 + fuzz 随机走子并检查引擎不变量
（车辆不重叠、接客区不超员、乘客数守恒、SOLVED ⟺ 队列为空）。
**本次交付有三个真实缺陷全靠它抓出来**，详见 `docs/12-code-review-parking-jam.md` 第 2 节。

待办：把这套验证固化成 `src/test/java` 下的 JUnit 用例（需先加
`testImplementation 'junit:junit:4.13.2'`），目前是临时脚本。

### 8.4 倾斜棋盘的两个坑

`game/parking/view/ParkingGeometry.java`：

- 绘制用 `canvas.rotate(-22°, centerX, centerY)`，**命中测试必须先做一次逆旋转**
- 驶出动画的终点（接客位）在旋转坐标系之外，所以驶出动画要在屏幕坐标系画
  —— `ParkingAnimator` 把"棋盘内移动"和"驶出"拆成两组状态就是这个原因
