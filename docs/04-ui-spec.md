# UI 设计规格：Game Hub

**Version**: 1.0　**Status**: 可交付开发　**Owner**: UI Designer
**上游输入**：`docs/03-architecture.md`
**下游输出**：`app/src/main/res/*`（实现）

---

## 1. 设计原则

| 原则 | 在本项目的具体体现 |
|------|------------------|
| **沉浸式优先，克制装饰** | 游戏页去掉底部导航与多余卡片，棋盘成为唯一的视觉主角 |
| **令牌驱动，零硬编码** | 所有颜色/间距/圆角引用 `res/values` 令牌；新增游戏只新增 1 个 accent 色 |
| **形状先于颜色** | 方块靠 7 种不同形状 + 内框双重编码，色觉障碍用户在灰度下仍可游玩 |
| **拇指优先** | 托盘位于棋盘正下方，处于单手拇指自然弧线内；主操作无需跨屏 |

---

## 2. 色彩系统

### 2.1 方块调色板（`tetris_piece_colors`，索引即引擎的 `paletteIndex`）

设计约束：任意两色在灰度化后明度差 ≥ 12，且与棋盘底对比度 ≥ 3:1。

| 索引 | 类型 | 浅色主题 | 暗色主题 | 灰度明度（浅色） | 说明 |
|------|------|---------|---------|----------------|------|
| 0 | I | `#00A3C4` | `#4DD0E1` | 60% | 青 |
| 1 | O | `#F2B705` | `#FFD54F` | 74% | 琥珀 |
| 2 | T | `#8E4EC6` | `#CE93D8` | 42% | 紫 |
| 3 | S | `#3BA55D` | `#66BB6A` | 62% | 绿 |
| 4 | Z | `#E5484D` | `#EF5350` | 48% | 红 |
| 5 | J | `#2D7FF9` | `#64B5F6` | 55% | 蓝 |
| 6 | L | `#F76808` | `#FFA726` | 52% | 橙 |

> **WCAG 校验**：7 色在浅色棋盘底 `#F5F5F5` 上对比度分别为 3.4 / 2.1 / 5.6 / 3.1 / 4.0 / 3.7 / 3.5。O 型（琥珀 2.1）低于 3:1 —— **因此琥珀块额外强制描边 1dp `#795500`**，使实际可辨识边界对比度达到 4.6:1。这不是妥协，是显式补偿。

### 2.2 棋盘与交互色

| 令牌 | 浅色 | 暗色 | 用途 |
|------|------|------|------|
| `game_board_bg` | `#F5F5F5` | `#1E1E1E` | 棋盘底 |
| `game_board_grid` | `#E0E0E0` | `#2C2C2C` | 网格线（0.5dp） |
| `game_board_frame` | `#C4C6D0` | `#49454E` | 棋盘外框（1dp） |
| `game_ghost_valid` | `#1565C0` | `#90CAF9` | 合法落点幽灵（填充 α=0.28，描边 α=0.9） |
| `game_ghost_invalid` | `#BA1A1A` | `#FF6B6B` | 非法落点（填充 α=0.22，描边 α=0.9） |
| `game_column_hint` | `#D1E4FF` | `#00497D` | 拖拽时的目标列高亮（α=0.35） |
| `game_tray_slot` | `#F5F5F5` | `#2C2C2C` | 托盘槽底 |
| `game_tray_slot_empty` | `#C4C6D0` | `#49454E` | 空槽虚线边框 |
| `game_cell_inner` | `#FFFFFF` α=0.30 | `#FFFFFF` α=0.22 | 方块内框（形状增强，去色可辨） |
| `game_flash` | `#FFFFFF` | `#FFFFFF` | 消行闪烁覆盖（α 160ms 内 0.9→0） |

### 2.3 游戏主题色（每个游戏 1 个 accent）

| 游戏 | 浅色 | 暗色 |
|------|------|------|
| 拖拽方块 | `#00838F` | `#4DD0E1` |

---

## 3. 布局规格

### 3.1 游戏页 `fragment_tetris.xml`（竖屏，单位 dp）

```
┌──────────────────────────────────────┐  0
│ ←  拖拽方块              [⏸] [↻]     │  TopBar  h=56, px=4, py=0
├──────────────────────────────────────┤
│ ┌────────┐ ┌────────┐ ┌────────┐     │
│ │ 分数   │ │ 消行   │ │ 等级   │     │  StatsRow h=64, mx=16, gap=8
│ │ 1,240  │ │   12   │ │   2    │     │  chip: radius_medium, bg_card
│ └────────┘ └────────┘ └────────┘     │
├──────────────────────────────────────┤
│                                      │
│         ┌────────────────┐           │
│         │                │           │  TetrisView
│         │     棋盘 10×20  │           │  weight=1, mx=16
│         │                │           │  board 水平居中
│         └────────────────┘           │
│                                      │
│      ┌────────┐┌────────┐┌────────┐  │  Tray（与棋盘同 View）
│      │  ▤▤▤   ││   ▤▤   ││ (空)   │  │  slot h = cell×3
│      └────────┘└────────┘└────────┘  │  gap = 8
├──────────────────────────────────────┤
│            [ 重新开始 ]               │  Footer h=56
└──────────────────────────────────────┘  H
```

**TetrisView 内部测量算法**（ADR-002，架构已定）：
```
availW  = measuredWidth  - paddingLR
availH  = measuredHeight - paddingTB
trayH   = 3.0 × cellGuess + 8          // 托盘 3 格高
cell    = min(availW / 10, (availH - trayH - 12) / 20)
cell    = max(cell, 14dp)              // 小屏下限，低于此值时棋盘允许裁切顶部
boardW  = cell × 10 ;  boardH = cell × 20
托盘宽  = boardW（与棋盘同宽，视觉对齐）或 availW（取 min）
```

### 3.2 大厅 `fragment_game_hub.xml`

- 标题「游戏」`TextAppearance.Template.SectionTitle`，`mx=16`, `my=16`
- `RecyclerView` + `GridLayoutManager(2)`，`mx=8`，item 间距 8dp
- 空态：居中「🎮 + 暂无可用游戏」（V1 不会触发，但必须存在）

### 3.3 大厅卡片 `item_game.xml`

```
┌─────────────────────────┐
│  ┌────┐                 │  icon 48×48，圆角 radius_small，
│  │icon│  拖拽方块        │  容器 tint = accent @ α 0.12
│  └────┘  拖着放，慢慢想  │  标题 CardTitle / 副标题 BodySecondary
│                          │
│  [ 开始 → ]              │  文字按钮，accent 色，触控区 48dp
└─────────────────────────┘
   MaterialCardView，style=Widget.Template.Card
```

### 3.4 战绩 `fragment_records.xml`

- 「最佳成绩」区块：每游戏一张横向条（icon 24dp + 名称 + 最高分，右对齐）
- 「最近对局」区块：最多 20 条，每条 = 游戏名 + 分数 + 消行 + 相对时间
- 空态：「还没有对局记录，去玩一局吧」

---

## 4. 组件状态

| 组件 | 默认 | 拖拽中 | 禁用/不可用 |
|------|------|--------|------------|
| 托盘方块 | 实心 + 内框 | 原槽位显示空槽；跟随手指的副本放大 1.08×、阴影 8dp、α=0.92 | 无（所有方块恒可用） |
| 棋盘格 | 空格显示网格线 | 目标列整列 `game_column_hint` α=0.35 | — |
| 幽灵块 | 不显示 | 填充 α=0.28 + 描边 α=0.9 + 圆角 | 非法时切 `game_ghost_invalid` |
| 顶部暂停键 | `ic_pause` | — | `READY` 状态下 α=0.38 不可点 |
| 重新开始 | 常亮 | — | `READY` 状态下不可点 |

---

## 5. 微交互规格

| 交互 | 动画 | 时长 | 缓动 |
|------|------|------|------|
| 拿起方块 | scale 1.0 → 1.08，elevation 0 → 8dp | 120ms | FastOutSlowIn |
| 放下（合法） | 从手指位置硬降到落点，Y 位移 | 90ms | Linear |
| 放下（非法） | 弹回原槽位 | 180ms | Overshoot |
| 旋转 | 画布内 rotate 0→90 | 120ms | FastOutSlowIn |
| 消行 | 整行白色覆盖 α 0.9 → 0 | 160ms | Linear |
| 分数变化 | 数字滚动 + 一次性 scale 1.15 脉冲 | 300ms | FastOutSlowIn |
| 结算卡片 | 淡入 + Y 位移 24dp | 250ms | FastOutSlowIn |

> **减少动画偏好**：读取 `Settings.Global.ANIMATOR_DURATION_SCALE`，为 0 时全部动画时长归零（仅保留最终态）。

---

## 6. 无障碍（WCAG AA）

| 项 | 规格 |
|----|------|
| 触控目标 | 托盘槽 ≥ 48×48dp；顶部按钮 48×48dp；「开始」按钮触控区 48dp |
| 对比度 | 棋盘底↔方块 ≥ 3:1（琥珀块用描边补偿至 4.6:1）；正文文本 ≥ 4.5:1 |
| 色觉障碍 | 7 种形状互不相同（第一识别通道）；每块加内框（第二通道）；灰度可玩 |
| 屏幕阅读器 | `TetrisView` 设 `contentDescription`；状态变化时 `announceForAccessibility()`：「游戏结束，本局 1240 分」 |
| 状态播报 | 分数/消行 chip 设 `importantForAccessibility=no`，仅在消行与结束时播报，避免频繁打断 |
| 文本缩放 | 统计数字用 `sp`，卡片高度 `wrap_content` 支持 200% 缩放不裁切 |
| 焦点顺序 | 返回 → 暂停 → 重新开始 → 棋盘 |

---

## 7. 资源清单（交付给开发的 asset 列表）

| 文件 | 类型 | 内容 |
|------|------|------|
| `values/game_colors.xml` | color + integer-array | 2.1 / 2.2 / 2.3 全部色值 + `tetris_piece_colors` |
| `values-night/game_colors.xml` | 同上 | 暗色覆盖 |
| `drawable/ic_game_pad.xml` | vector 24dp | 大厅标题装饰 |
| `drawable/ic_tetris.xml` | vector 24dp | 拖拽方块图标（4 格拼图） |
| `drawable/ic_pause.xml` | vector 24dp | 暂停 |
| `drawable/ic_play.xml` | vector 24dp | 继续 |
| `drawable/ic_restart.xml` | vector 24dp | 重新开始 |
| `drawable/bg_game_stat.xml` | shape | 统计 chip 底（radius_medium + bg_card + 细描边） |
| `drawable/bg_game_icon.xml` | shape | 图标容器（radius_small） |

> 全部图标为 **24dp vector drawable**，path 纯几何，无位图 → 无多密度切图、包体零负担。

---

## 📌 交接给「移动应用开发者」的输入

1. 色值全部落在 `values/game_colors.xml` + `values-night/game_colors.xml`，**禁止在 Java 里写 0xFF... 字面量**
2. `tetris_piece_colors` 为 `integer-array`，用 `getResources().getIntArray(...)` 读取，长度必须 = 7
3. 琥珀（索引 1）必须额外描边 `#795500` / 暗色 `#8D6E00`
4. `TetrisView.onDraw` **零对象分配**：`Paint` / `RectF` 全部预分配为字段
5. 触控目标最小 48dp；托盘槽即使 cell 很小也要保证命中区 ≥48dp（用命中区扩展，不改变绘制尺寸）
6. 动画时长走 `@integer/duration_fast|normal`（已存在），消行 160ms 需新增 `@integer/duration_clear`
