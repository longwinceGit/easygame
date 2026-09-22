# 13 · 挪车消消消：关卡预生成入库方案

> 状态：已落地（第 1–5 步完成并验证；第 6 步待真机/设备验证）
> 关联：`10-architecture-parking-jam.md`（架构 ADR）、`09-gdd-parking-jam.md`（数值来源）

---

## 1. 结论

**方案可行，收益显著。** 但需修正表述：真正要消除的不是「渲染计算」，而是**运行时 BFS 求解**。

随机布车本身几乎不耗时（微秒级），昂贵的是 `ParkingLevelGenerator` 为了**证明关卡可解**而跑的 BFS
（求解出的驶出顺序直接被 `buildQueue()` 翻译成乘客队列）。把「已证明可解」的关卡预先算好存进数据库，
运行时只做一次 `SELECT` + 反序列化，即可把这块成本从运行时彻底移走。

落地形态：**预生成池 + 运行时随机取用 + 兜底回退**（混合方案，见 §4.3）。

---

## 2. 现状基线（实测）

纯 JVM（x86_64 模拟器），每关 25 次采样（`verify_tmp/Diag.java`）：

| 关卡 | 车数 | 平均耗时 | 最坏单次 |
|---|---|---|---|
| L1 | 5 | 18ms | 104ms |
| L2 | 6 | 33ms | 226ms |
| L3 | 7 | 172ms | 947ms |
| L4 | 8 | 437ms | 1718ms |
| L5 | 9 | 847ms | 2100ms |
| L6 | 10 | 742ms | **3673ms** |
| L7 | 10 | 992ms | 3063ms |
| L8+ | 10 | 805ms | 2599ms |

**每过一关、每点一次「刷新」，都要付 0.2~3.7 秒后台重算。**

佐证：实机日志中 `com.template.app` 占 65% CPU、整机 iowait 90%，并连带触发
`system_server` / `systemui` 集体 ANR —— 运行时生成是整机卡顿主因，不止影响本 App。

---

## 3. 两个让方案成立的关键洞察

### 3.1 难度在 L6+ 饱和 → 有限池足够

```java
vehicleCount(levelIndex)      = clamp(4 + levelIndex, 5, MAX_VEHICLES=10)
minSolutionMoves(levelIndex)  = vehicleCount + min(3, 1 + levelIndex/4)
```

实际「难度桶」只有 **7 个**，L8 之后不再变化：

| 关卡 | 车数 | 最少步数 | 桶 |
|---|---|---|---|
| L1 | 5 | 6 | A |
| L2 | 6 | 7 | B |
| L3 | 7 | 8 | C |
| L4 | 8 | 10 | D |
| L5 | 9 | 11 | E |
| L6–L7 | 10 | 12 | F |
| L8–∞ | 10 | 13 | G |

**关卡号无限递增，但难度只有 7 档** —— 有限关卡池即可服务任意深的关卡号。这是可行性基石。

### 3.2 必须连「乘客队列」一起存

可解性来自：BFS 求出的**驶出顺序**被翻译成乘客队列。

```java
return new Level(levelIndex, layout, buildQueue(layout, solution.order));
```

- 只存车辆布局 → 运行时仍需重跑 BFS 才能推出队列 → **一分钱没省**；
- 存 `布局 + 队列` → 可解性由构造保证，运行时**零验证**。

---

## 4. 方案设计

### 4.1 存储量级（可忽略）

单车打包 13 bit（row 3 / col 3 / length 1 / horizontal 1 / dir 2 / color 3）≈ 2 字节：

- 布局：10 车 × 2B ≈ **20B**
- 队列：≤10 组 × ~1B ≈ **10B**
- 元数据（id / 桶 / 配置版本 / 步数）≈ **16B**

**≈ 50B/关（二进制），文本化约 ~200B/关。**
7 桶 × 300 关 = 2100 关 → **约 100KB（二进制）/ 500KB（文本）**，相对 APK 体积可忽略。

### 4.2 表结构

```text
parking_levels(
  id             INTEGER PRIMARY KEY,
  config_version INTEGER NOT NULL,   -- 配置指纹，见 §5.1
  bucket         INTEGER NOT NULL,   -- A..G，由 levelIndex 映射
  vehicle_count  INTEGER NOT NULL,
  layout         TEXT    NOT NULL,   -- "row,col,len,h,dir,color|..."
  queue          TEXT    NOT NULL,   -- "color:count;..."
  min_moves      INTEGER NOT NULL
)
CREATE INDEX idx_pool ON parking_levels(config_version, bucket);
```

取关：`SELECT ... WHERE config_version=? AND bucket=? ORDER BY RANDOM() LIMIT 1`

### 4.3 三条落地路径

| 路径 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A. 纯预置资产 | 离线生成 → `Room.createFromAsset()` | 运行时零成本，首启秒开 | 需构建步骤；内容固定到下次发版；老用户升级不会重灌资产 |
| B. 运行时懒缓存 | 首次在线生成并入库，之后随机读 | 无构建步骤，内容永远新鲜 | 每桶首次仍付 0.2~3.7s |
| C. **混合（推荐）** | 预置小池（每桶 30~50）+ 空闲后台补池 | 兼顾首启体验与长期新鲜度 | 实现最复杂 |

**推荐 C**；B 是自愈底座，必须先落地。

### 4.4 复用现有基础设施

- `ParkingLevelGenerator` 是**纯 Java、零 android 依赖**（ADR-003/008），已验证可用
  `javac/java 17` 在 JVM 直接编译运行 —— **离线预生成可是纯 JVM 脚本/Gradle task，无需模拟器**。
- Room 2.6.1 已配置（`annotationProcessor`、`room.schemaLocation` 已开），
  `AppDatabase` 为 DCL 单例，加表成本低。

---

## 5. 必须处理的风险

### 5.1 配置漂移导致存量关卡失效（最高优先级）

`ParkingConfig` 中 `ROWS / COLUMNS / COLOR_COUNT / MAX_VEHICLES / PICKUP_SLOTS` 任一改动，
历史关卡可能**直接崩溃或不可解**：

- `COLUMNS` 缩小 → 存量 `col + length > COLUMNS`，越界；
- `COLOR_COUNT` 减小 → `colorIndex` 超出颜色数组 → 即 `ArrayIndexOutOfBoundsException`
  （与已修复的生成器越界同类）。

**对策**：存 `config_version`（由上述常量算指纹），读取时校验，不匹配即视为池空 →
回退在线生成 + 清空重建。

### 5.2 Room 迁移会崩老用户

现状：

```java
@Database(entities = {GameRecord.class}, version = Constants.DATABASE_VERSION /* =1 */)
```

**无 `fallbackToDestructiveMigration`，无任何 Migration。**
加实体并升到 v2 却不写 `Migration(1,2)` → 已装用户升级时 `IllegalStateException`。

**对策**：显式提供 `Migration(1,2)` 建表。且 `createFromAsset` **仅在数据库首次创建时生效**，
老用户走迁移路径而非资产灌入 —— 这是「必须有运行时兜底」的硬理由。

### 5.3 重复暴露

L6 之后全部落在同一 10 车桶，深入后易反复遇到同一关。

**对策**：池要大（每桶 ≥200）；取关用「洗牌后游标轮询」而非纯随机，保证池耗尽前不重复；
记录最近 N 个已用 id 做排除。

### 5.4 线程与「刷新」语义

- Room 查询**不可在主线程**；沿用 `generatorExecutor`（`parking-level-gen`）即可，读取仅毫秒级，
  `loading` 会一闪而过。
- `refreshLevel()` 语义保持「重新随机取同关号」，池化后即从桶里再取一条，瞬间完成。
- **「随机」的玩家感知被保留而非牺牲** —— 只要池足够大、取关随机。

### 5.5 生成器缺陷暴露面改变

预生成把 BFS 从「用户设备」搬到「构建/CI」。如已修复的数组越界，在离线阶段表现为**构建失败**，
而不再是用户崩溃 —— 额外可靠性收益。

---

## 6. 收益量化

| 指标 | 现状 | 池化后 |
|---|---|---|
| 每关生成耗时 | 18ms ~ 3673ms | **< 5ms**（一次 SELECT + 解析） |
| 后台 CPU/GC 尖峰 | 每次过关/刷新全核占用 | **消除** |
| 低端机 ANR 风险 | 高（日志已证实整机被拖垮） | **大幅下降** |
| 刷新响应 | 0.2~3.7s | 即时 |
| 存储成本 | — | ~100KB |
| APK 增量 | — | ~0.1~0.5MB |

---

## 7. 实施顺序

1. **配置指纹 + 桶映射**：`ParkingConfig.configVersion()` / `bucketOf()`，纯函数、可单测。
2. **序列化层**：`ParkingLevelCodec`，`Level` ⇄ 紧凑字符串（布局+队列），配 round-trip 单测。
3. **Room 落地**：新实体 + `Migration(1,2)`（**必做**）+ DAO（随机取关）。
4. **取关改造**：`ParkingViewModel.requestLevel()` 优先读池，池空回退现有生成并写回池（自愈）。
5. **离线预生成脚本**：复用纯 Java 生成器 + `javac` 直跑方式，产出资产 DB。
6. **验证**：复用 `Stress` / `Diag` 思路做池化后压测 —— 零异常、取关分布均匀、无重复暴露。

---

## 8. 实施状态

| 步骤 | 内容 | 产物 | 状态 |
|---|---|---|---|
| 1 | 配置指纹 + 桶映射 | `ParkingConfig.configVersion()` / `bucketOf()` | 完成 |
| 2 | 序列化层 | `ParkingLevelCodec` | 完成（round-trip 200 关零差异） |
| 3 | Room 落地 | `ParkingLevelEntity` / `ParkingLevelDao` / `Migration(1,2)` | 完成（迁移 SQL 与 Room 导出 schema 逐字核对一致） |
| 4 | 取关改造 | `ParkingLevelStore` + `ParkingViewModel.requestLevel()` | 完成 |
| 5 | 离线预生成播种 | `SeedPool` → `assets/parking_levels.tsv` | 完成（280 关 / 39KB） |
| 6 | 端到端压测 | 待真机 | 待办 |

### 关键实现决策

- **cache-aside + 写回自愈**：取关先读池，未命中才在线生成并把结果写回，
  保证任何时刻（含预置池缺失）玩家都有得玩。
- **预置池是必需的，不是优化**：只靠懒生成，每桶首次只攒下 1 条，
  「刷新」会反复返回同一张图，比改动前更糟。故第 5 步与第 4 步必须配套。
- **迁移 SQL 必须与 Room 导出 schema 一致**：实体 `layout`/`queue` 未标 `@NonNull` 时
  Room 生成可空列，与手写 `NOT NULL` 不匹配会让升级用户崩溃。
  已用 `app/schemas/.../2.json` 逐字核对（`verify_tmp` 之外的常规检查手段）。

### 实测数据

- 预置池：280 关（7 桶 × 40），生成耗时 83s，**产物 39KB**（≈139B/关，与 §4.1 估算吻合）。
- 编解码：200 关 round-trip 零差异；损坏/越界输入全部拒绝（返回 null 触发回退）。
- 难度桶：L1–L30 共 **7 个**，L8 后饱和（51 / 61 / 71 / 82 / 92 / 102 / 103）。
