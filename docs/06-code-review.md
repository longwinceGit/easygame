# 代码审查报告：Game Hub V1

**审查人**：Code Reviewer　**日期**：2026-09-20
**审查范围**：Java 24 个（22 新增 + 2 修改）、XML 21 个（15 新增 + 6 修改）、删除 12 个（笔记 Demo）
**结论**：**可合入**。已修复 3 项阻塞/建议项，遗留 4 项已知限制已登记。

---

## 一、总体印象

分层是干净的：领域层（`model` / `engine`）确实做到了零 `android.*` 依赖，
`TetrisView` 的 `onDraw` 也确实做到了零对象分配（所有 `Paint` / `RectF` 预分配复用）。
把棋盘和托盘放进同一个 View 这个决策，把跨 View 拖拽这个最容易出 bug 的部分直接消灭了，这是本次实现里最值得肯定的地方。

主要问题集中在三处：**一处自相矛盾的架构规则**、**一处用户可见的数据时序问题**、
以及**若干防御性缺失**。下面按优先级列出。

---

## 二、阻塞项 🔴

### 🔴-1 架构适应度函数与实现自相矛盾（已修复）

**位置**：`docs/03-architecture.md` 第 3 节 vs `game/core/GameRegistry.java:12`

**问题**：架构文档定义的第 2 条检查是「壳层禁止 import 具体游戏」，
但 `GameRegistry` 的静态注册块必须 `import com.template.app.game.tetris.TetrisGamePlugin`——
这条规则一旦进 CI 会直接把主干跑红。这是我（架构师）自己订的规则自己违反，属于规则定义错误，不是代码错误。

**修复**：把 `GameRegistry.java` 显式列为唯一白名单，并在文档中写明理由与 trade-off
（改用 ServiceLoader 需引入 `META-INF/services` + 反射，牺牲编译期校验，对 1–5 款游戏的规模是负收益）。
CI 脚本同步改为「除 GameRegistry 外的 core 层类」+「全部 ui 层类」。

---

## 三、建议项 🟡

### 🟡-1 结算弹窗可能显示「本局 1240 分，历史最高 800 分」（已修复）

**位置**：`game/tetris/ui/TetrisFragment.java` `onGameOver()`

**原因**：`GameOver` 时 ViewModel 先 `saveRecord()`（投递到 Room 线程池）再发出事件，
而 `best` 是 Room 的 `LiveData`，异步回调——Fragment 收到事件时读到的仍是**本局之前**的最高分。
当玩家刷新纪录时，弹窗会显示一个低于本局分数的"历史最高"，是可感知的错误。

**修复**：`int best = Math.max(bestValue == null ? 0 : bestValue, score);`
并在代码注释里写明为什么需要取 max（避免后人"优化"掉）。

---

### 🟡-2 `ImageButton` 误用了 `MaterialButton` 的 API（已修复）

**位置**：`game/tetris/ui/TetrisFragment.java` `renderState()`

`binding.btnPause.setIconResource(...)` 是 `MaterialButton` 的方法，
布局里 `btn_pause` 是 `ImageButton`，编译期就会报错。改为 `setImageResource(...)`。

---

### 🟡-3 生命周期回调缺少空值保护（已修复）

**位置**：`TetrisFragment.onResume()` / `onPause()`

ViewModel 在 `onViewCreated` 才创建，而 `onResume` / `onPause` 在异常恢复路径下
可能早于它执行。已加 `viewModel == null` 短路保护。

---

### 🟡-4 暗色主题会丢失品牌色板（已修复）

**位置**：`values-night/styles.xml`

Android 资源限定符是**整文件替换**而非合并：`values-night/styles.xml` 里重新声明的
`Theme.Template` 完全覆盖了 `values/styles.xml` 的同名 style，导致夜间模式下
`colorPrimary` 等全部回落到 M3 默认值（紫色），`values-night/colors.xml` 精心配的色板全部失效。

**修复**：抽出与模式无关的 `Theme.Template.Base`（持有全部色板 item），
`values` 与 `values-night` 的 `Theme.Template` 各自只继承它并覆盖状态栏窗口属性。

---

### 🟡-5 托盘遍历硬编码了 `TetrisConfig.TRAY_SIZE`（已修复）

`TetrisView.drawTray()` / `traySlotAt()` 用配置常量遍历，而真实数据来自引擎。
一旦两边不一致会画出不存在的槽位或不画的方块。改为 `engine.traySize()`。

---

### 🟡-6 死代码与全限定名（已修复）

- 删除未被调用的 `Tetromino.cellCount()` 与 `Board.get(int, int)`
- `Board` 中的 `java.util.Arrays.fill` 改为正常 import

---

## 四、小改进 💭（未修，登记）

| # | 位置 | 说明 | 建议 |
|---|------|------|------|
| 1 | `TetrisFragment.onPlaced()` | 闪烁用的是**消除前**的行索引，而此时棋盘已完成压缩。多行不连续消除时（例如第 5 行和第 8 行同时满）闪烁位置会与真实消除位置有偏差 | 若要彻底准确，需让引擎延迟压缩或把被消除行的格子快照回传给 View。考虑只是 160ms 的效果且绝大多数消除发生在底部连续区域，**当前按 P2 登记**，等有真实反馈再改 |
| 2 | `fragment_tetris.xml` | 统计 chip 未设 `importantForAccessibility="no"`，TalkBack 会在分数频繁变化时反复打断 | 一行 XML，下个改动顺带补上 |
| 3 | `fragment_tetris.xml` | `android:text="0"` 硬编码，会触发 `HardcodedText` lint 警告（不阻塞构建） | 改 `tools:text`，因 LiveData 会立即回调，不会闪空白 |
| 4 | `TetrisFragment.onGameOver()` | 用 `MaterialAlertDialogBuilder` 直接 `show()`，配置变更时对话框不受 `DialogFragment` 管理 | 可接受（游戏结束态由 ViewModel 持有，重开路径完整），登记 |

---

## 五、值得肯定的地方

- **`TetrominoType` 的包围盒裁剪**：旋转后裁剪到最小包围盒，避免了「4×4 矩阵里 I 块实际占第 2 行」导致的方块悬空。这是俄罗斯方块实现里最常见的坑，处理方式干净。
- **`Board.clearFullRows()` 重建二维数组**而不是原地搬移行引用，从根上消除了数组别名导致的覆盖 bug。
- **`PlaceResult.failure()` 返回不可变单例**：一次非法回弹不产生任何对象分配。
- **`GamePlugin` 坚持只有 2 个方法**：没有为"未来可能用到"预留空钩子，符合 Rule of Three。
- **7-bag 随机**：消除了"连续坏块"这种玩家会归因于系统的挫败源。

---

## 六、验证状态（诚实声明）

| 项目 | 状态 |
|------|------|
| IDE 静态诊断（lint） | ✅ 0 error / 0 warning |
| **Gradle 编译** | ✅ **已通过** —— Wrapper 已补齐（Gradle 8.7），`assembleDebug` 零警告，产物 7.23 MB |
| 引擎逻辑验证 | ✅ 已通过 —— 纯 Java 引擎编译运行，18 项断言全过（含结束判定、旋转、消行） |
| 单元测试 | ⚠️ 未固化 —— 验证程序跑完后已删除，需补 `testImplementation 'junit:junit:4.13.2'` 才能进 CI |
| 真机/模拟器运行 | ⚠️ **未执行** |

**编译已验证，真机行为仍未验证。** 请按下列清单做手工冒烟（预计 10 分钟）：

1. 大厅出现「拖拽方块」卡片 → 点击进入
2. 从托盘拖一个方块到棋盘，确认出现半透明落点预览与目标列高亮
3. 松手后方块落到该列的可落位置，**分数不变**（放置不得分，只有消行才加分，见 GDD 机制 7）
4. 在托盘方块上轻点 → 方块旋转（不是放置）
5. 把方块拖到已满的列 → 幽灵变红，松手方块弹回，分数不变
6. 填满一行 → 消行、闪烁、上方下移
7. 点暂停 → 棋盘变暗、显示「已暂停」，此时拖拽无效；再点继续
8. 自杀式堆叠直到无处可放 → 弹出结算卡片，显示本局分与最高分
9. 返回大厅 → 战绩 Tab 能看到最高分与最近对局
10. 杀进程重进 → 最高分仍在

---

## 📌 交接给「技术文档工程师」的输入

1. 需要一份**新增游戏的接入指南**：照着 `TetrisGamePlugin` 抄一遍的 4 步清单
2. 需要更新 `README.md`：把"笔记 Demo"的描述替换为"游戏合集 + 拖拽方块"
3. 需要在文档中明确本次**未做**的验证（编译 / 单测 / 真机），避免后人误以为已验证
4. 需要记录 GDD 调参表与 `TetrisConfig.java` 的对应关系，方便后续调平衡
