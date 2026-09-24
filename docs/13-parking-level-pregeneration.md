# 13 · 挪车消消消：关卡预生成入库方案

> 状态：已落地（第 1–4 步完成并验证；第 5 步由「离线预置池」改为「运行时预热」；第 6 步待真机验证）
> 关联：`10-architecture-parking-jam.md`（架构 ADR）、`09-gdd-parking-jam.md`（数值来源）
>
> **2026-09 更新**：生成器已从「BFS 证明整关可解」改为**弱校验**（只保证开局有车可动，
> 被堵死的车由「移除 / 排序」道具兜底），单关生成成本大幅下降；随包预置池因配置漂移
> （见 §5.1）整池作废，池化策略相应改为 **运行时预热 + 懒生成兜底**。
> §2 基线、§3.1 桶映射、§4.3 路径、§7/§8 第 5 步均已随之更新。

---

## 1. 结论

**方案可行，收益显著。** 要消除的是**运行时的关卡生成**（在线算布局 + 队列），而非「渲染计算」。

- **早期（BFS 时代）**：昂贵的是为**证明关卡可解**而跑的 BFS，单关 0.2~3.7s（见 §2.1 历史基线）；
  求解出的驶出顺序直接被 `buildQueue()` 翻译成乘客队列。
- **现在（弱校验时代）**：生成器只做「开局有 ≥3 辆车能直线开走」的检查，
  单关降到毫秒级；但首次进某关、点一次「刷新」仍要现算一次，深关 36 辆车的布局仍有可感知开销。

把关卡预先算好存进数据库，运行时只做一次 `SELECT` + 反序列化，即可把这块成本从关键路径移走。

落地形态：**运行时预热补池 + 运行时随机取用 + 懒生成兜底**（路径 C，见 §4.3）。

---

## 2. 现状基线（实测）

### 2.1 历史基线：BFS 求解时代（已不再适用）

> 以下数据产生于「生成时必须 BFS 证明整关可解」的版本，仅作对照保留。
> 生成器改为弱校验后，这些耗时**不再成立**（见 §2.2）。

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

### 2.2 当前基线：弱校验 + 高密度（待实测补齐）

生成器移除全局 BFS 后，单关成本 = 随机布车（枚举候选位置）+ 开局可动性扫描，
与车数近似线性，**不再出现秒级最坏值**。但下列变化又把一部分成本推了回来：

- 车辆数从 20 提升到 **36**（`MAX_FILL_RATIO` = 0.90），放车与可达性检查规模随之变大；
- 关卡按 `config_version` 过滤，配置漂移后**随包预置池整池作废**，首次进每个桶都要现算。

因此仍需池化：**运行时预热**（进入第 N 关后，后台补齐 N 及后续若干关）
把生成移出关键路径，玩家真正走到时直接命中缓存。

> 待办：用 `verify_tmp/Diag` 同法重测「弱校验 + 36 辆」的单关耗时，回填本节。

---

## 3. 两个让方案成立的关键洞察

### 3.1 难度在 L6+ 饱和 → 有限池足够

```java
vehicleCount(levelIndex)      = clamp(START_VEHICLES + (levelIndex - 1) * VEHICLES_PER_LEVEL, 5, MAX_VEHICLES)
                              = clamp(12 + (levelIndex - 1) * 2, 5, 36)
minSolutionMoves(levelIndex)  = vehicleCount + min(3, 1 + levelIndex / 4)   // 现仅作分桶元数据
bucketOf(levelIndex)          = vehicleCount * 10 + (minSolutionMoves - vehicleCount)
```

按当前配置推导，难度桶 **13 个**，L13 之后饱和（车数封顶 36、额外步数封顶 3）：

| 关卡 | 车数 | 最少步数 | 桶 | | 关卡 | 车数 | 最少步数 | 桶 |
|---|---|---|---|---|---|---|---|---|
| L1 | 12 | 13 | 121 | | L7 | 24 | 26 | 242 |
| L2 | 14 | 15 | 141 | | L8 | 26 | 29 | 263 |
| L3 | 16 | 17 | 161 | | L9 | 28 | 31 | 283 |
| L4 | 18 | 20 | 182 | | L10 | 30 | 33 | 303 |
| L5 | 20 | 22 | 202 | | L11 | 32 | 35 | 323 |
| L6 | 22 | 24 | 222 | | L12 | 34 | 37 | 343 |
| — | — | — | — | | L13+ | 36 | 39 | 363 |

**关卡号无限递增，但难度只有 13 档** —— 有限关卡池即可服务任意深的关卡号。这是可行性基石。
桶数随 `START_VEHICLES` / `VEHICLES_PER_LEVEL` / `MAX_VEHICLES` 变化，改这些常量后本表需重算。

### 3.2 必须连「乘客队列」一起存

队列顺序是关卡的一部分，必须随布局一起存：

- **BFS 时代**：队列 = 求解出的驶出顺序，可解性由构造保证。
- **弱校验时代**：队列 = 「开局能开走的车」优先 + 其余随机（`buildQueue(layout, escapable)`），
  不再承载可解性承诺，但**顺序仍然是随机生成的结果**。

```java
return new Level(levelIndex, layout, buildQueue(layout, escapable));
```

- 只存布局 → 运行时重算队列会得到**不同顺序**（随机种子不同），
  同一关卡每次加载都不一样，「刷新」与「继续」的语义都被破坏；
- 存 `布局 + 队列` → 关卡可完整复现，运行时**零计算、零验证**。

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
  bucket         INTEGER NOT NULL,   -- 数字难度桶，由 levelIndex 映射；见 §3.1（当前 13 个，L13 后饱和）
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
| A. 纯预置资产 | 离线生成 → `assets/parking_levels.tsv` / `createFromAsset()` | 运行时零成本，首启秒开 | 需构建步骤；**配置漂移后整池作废**（§5.1），每次调数值都要重产资产；老用户升级不会重灌 |
| B. 运行时懒缓存 | 首次在线生成并入库，之后随机读（cache-aside 写回） | 无构建步骤，内容永远新鲜 | 每桶首次仍要现算一次 |
| C. **运行时预热（现行）** | 进入第 N 关后，后台补齐 N 及后续若干关覆盖的桶，每桶 3 条 | 无构建步骤；配置漂移后自动重灌；刷新有多样性 | 首次进第 1 关仍有 1 次在线生成 |

**现行 C**；B 是自愈底座，必须先落地（池空 / 资产缺失时兜底）。

#### 预热实现要点（`ParkingViewModel.warmUp()`）

- **按桶去重**：深关的关卡号不同但桶相同（L12+ 全落 343），
  用 `Map<bucket, 代表关卡号>` 去重后再补齐，避免为同一桶重复生成。
- **独立线程池** `warmupExecutor`：预热是"攒库存"的慢活，若与玩家取关共用
  `generatorExecutor` 那条单线程队列，玩家点「刷新」会排在预热后面干等——正是要消除的卡顿。
- **后台低优先级**：`THREAD_PRIORITY_BACKGROUND`，不与主线程抢 CPU。
- 参数：`ParkingConfig.WARMUP_LEVEL_COUNT`（预热后续几个关卡号，默认 6）、
  `WARMUP_PER_BUCKET`（每桶目标条数，默认 3，保证刷新时不重复拿到同一张图）。

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

- **BFS 时代**：预生成把求解搬到「构建/CI」，如已修复的数组越界会表现为**构建失败**而非用户崩溃。
- **弱校验时代**：生成回到用户设备（后台低优先级线程），但单关已是毫秒级、无秒级最坏值。
  需注意的是高密度（0.90）下 `randomLayout` 可能放不下目标车数，
  生成器会**降车重试**并保留 2 车保底关（`emergencyLevel`）——玩家永远有得玩。

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
5. **运行时预热**：`ParkingViewModel.warmUp()` —— 进入第 N 关后，后台补齐 N 及后续
   `WARMUP_LEVEL_COUNT` 个关卡号所覆盖的难度桶，每桶补到 `WARMUP_PER_BUCKET` 条。
   （替代原「离线预生成脚本」：`config_version` 一变离线资产就整池作废，维护成本高。）
6. **验证**：复用 `Stress` / `Diag` 思路做池化后压测 —— 零异常、取关分布均匀、无重复暴露。

---

## 8. 实施状态

| 步骤 | 内容 | 产物 | 状态 |
|---|---|---|---|
| 1 | 配置指纹 + 桶映射 | `ParkingConfig.configVersion()` / `bucketOf()` | 完成 |
| 2 | 序列化层 | `ParkingLevelCodec` | 完成（round-trip 200 关零差异） |
| 3 | Room 落地 | `ParkingLevelEntity` / `ParkingLevelDao` / `Migration(1,2)` | 完成（迁移 SQL 与 Room 导出 schema 逐字核对一致） |
| 4 | 取关改造 | `ParkingLevelStore` + `ParkingViewModel.requestLevel()` | 完成 |
| 5 | 运行时预热 | `ParkingViewModel.warmUp()` + `ParkingLevelStore.countInBucket()` | 完成（替代已失效的离线预置池） |
| 6 | 端到端压测 | 待真机 | 待办 |

### 关键实现决策

- **cache-aside + 写回自愈**：取关先读池，未命中才在线生成并把结果写回，
  保证任何时刻（含预置池缺失）玩家都有得玩。
- **补池是必需的，不是优化**：只靠懒生成，每桶首次只攒下 1 条，
  「刷新」会反复返回同一张图。原先靠随包预置池解决，但该池受配置漂移影响
  （`config_version` 一变整池作废、需重新产出资产），现改为**运行时预热**：
  配置变更后自动重灌，无构建步骤。故第 5 步与第 4 步必须配套。
- **预热必须按桶而非按关卡号补齐**：关卡号无限递增但桶有限（当前 12 个），
  按关卡号补会重复填满靠前的桶而漏掉后面的桶。
- **迁移 SQL 必须与 Room 导出 schema 一致**：实体 `layout`/`queue` 未标 `@NonNull` 时
  Room 生成可空列，与手写 `NOT NULL` 不匹配会让升级用户崩溃。
  已用 `app/schemas/.../2.json` 逐字核对（`verify_tmp` 之外的常规检查手段）。

### 实测数据

> 前两条为**历史数据**（BFS 时代 + 10 车上限），随包预置池现已因配置漂移失效，仅作对照。

- 预置池（历史）：280 关（7 桶 × 40），生成耗时 83s，**产物 39KB**（≈139B/关，与 §4.1 估算吻合）。
- 编解码：200 关 round-trip 零差异；损坏/越界输入全部拒绝（返回 null 触发回退）。
- 难度桶（历史）：L1–L30 共 **7 个**，L8 后饱和（51 / 61 / 71 / 82 / 92 / 102 / 103）。
- 难度桶（当前）：按 `START_VEHICLES=12 / VEHICLES_PER_LEVEL=2 / MAX_VEHICLES=36`
  推导为 **13 个**，L13 后饱和（121 … 363，见 §3.1）。
- 弱校验后单关生成耗时：**待实测**（见 §2.2 待办）。
