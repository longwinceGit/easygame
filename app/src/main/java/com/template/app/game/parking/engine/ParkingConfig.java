package com.template.app.game.parking.engine;

/**
 * 挪车消消消的全部可调数值。
 * <p>
 * 改这里就等于改玩法——所有数值都来自 {@code docs/09-gdd-parking-jam.md} 第 4 节，
 * 不允许在别处硬编码。
 */
public final class ParkingConfig {

    private ParkingConfig() {
        // 常量类禁止实例化
    }

    /**
     * 停车场列数。
     * <p>
     * 10×10：格子更多 → 单个格子更小 → 车辆图标随之变小（视觉诉求），
     * 同时给"更多车"留出空间（难度诉求）。
     * <p>
     * <b>改这个数会连带影响求解器的状态编码</b>：锚点 {@code row × COLUMNS + col}
     * 必须能被 {@code ParkingLevelGenerator} 的每车位宽装下，详见该类。
     * <p>
     * 保持 10×10 的取舍：12×12 虽能塞到 48 辆，但格边长降到约 21.7dp，
     * 小屏上车辆过小、辨识与点击都变差；故维持 10×10，车辆数取该尺寸下的实测上限
     * （见 {@link #MAX_FILL_RATIO}）。
     */
    public static final int COLUMNS = 10;

    /** 停车场行数。 */
    public static final int ROWS = 10;

    /** 接客区车位数——全局难度总闸。 */
    public static final int PICKUP_SLOTS = 4;

    /** 乘客颜色数，必须与 parking_passenger_colors 数组长度一致。 */
    public static final int COLOR_COUNT = 8;

    /** 撤销栈上限（只防内存，不防策略）。 */
    public static final int MAX_UNDO = 200;

    /**
     * 每关「移除」道具次数。
     * <p>
     * <b>关卡不再在生成时证明全局可解</b>（只做弱校验），堵死的车靠本道具清路，
     * 故次数从 1 提到 4——它是高密度下的核心解压阀。
     */
    public static final int REMOVE_PER_LEVEL = 4;

    /** 每关「排序」道具次数：队列顺序不再等于解法顺序，排序成为主要调整手段。 */
    public static final int SORT_PER_LEVEL = 2;

    /**
     * 每格载客数：载客量 = 车身长度 × 本值。
     * <p>
     * <b>与 {@code Vehicle#capacity()} 是同一条规则的两处实现</b>——
     * model 层不依赖 engine 层，故 {@code Vehicle.capacity()} 仍是 {@code length}，
     * 等价于本值为 1。若要把本值改成 ≠1，必须同步改 {@code Vehicle.capacity()}。
     * <p>
     * 目标取舍为「<b>车辆数优先</b>」：人数随车数自然增长（48 辆 ≈ 120 人），
     * 不额外放大每格载客，以免单关接客动画过长。
     */
    public static final int PASSENGERS_PER_CELL = 1;

    /** 载客量：车身长度 × {@link #PASSENGERS_PER_CELL}。 */
    public static int capacityOf(int length) {
        return length * PASSENGERS_PER_CELL;
    }

    /**
     * 期望乘客数 = 车辆数 × 平均车长 × 每格载客数。
     * <p>
     * 用于难度预估、文档与 UI；<b>关卡实际总人数</b>仍由该关各车载客量求和得出
     * （守恒量，GDD 机制 5），二者在统计意义上一致。
     */
    public static double expectedPassengers(int vehicleCount) {
        return vehicleCount * AVG_CELLS_PER_VEHICLE * PASSENGERS_PER_CELL;
    }

    /** 每接走 1 位乘客的得分。 */
    public static final int SCORE_PER_PASSENGER = 10;

    /** 通关奖励 = 本值 × 关卡号。 */
    public static final int SCORE_PER_LEVEL_UNIT = 100;

    /**
     * 连击奖励：一次操作同时送走 ≥2 辆车时，每多送 1 辆额外加本值。
     * <p>
     * 例：一次送走 3 辆 → 奖励 {@code SCORE_COMBO_PER_EXTRA × 2}。
     * 「一次操作」指一次 move / 移除 / 排序所触发的整轮接客结算
     * （{@link com.template.app.game.parking.engine.ParkingEngine#resolvePickup}），
     * 只有在该轮里真正开走的车才计入连击。
     */
    public static final int SCORE_COMBO_PER_EXTRA = 50;

    /** 关卡生成：布局重试上限。车多了、棋盘大了，重试成本高，但太多会让最坏耗时失控。 */
    public static final int GENERATOR_MAX_ATTEMPTS = 20;

    /**
     * 关卡生成：单次 BFS 访问状态上限。
     * <p>
     * <b>已不再用于"证明整关可解"</b>——生成器改为弱校验（见
     * {@link #MIN_ESCAPABLE_AT_START}），只做直线可达扫描。本值仅作为可达性检查的
     * 兜底上限，2000 绰绰有余，生成耗时因此从秒级降到毫秒级。
     */
    public static final int GENERATOR_MAX_BFS_STATES = 2000;

    /**
     * 弱校验门槛：开局至少要有这么辆车能<b>沿箭头方向直线开走</b>（到边界无阻挡）。
     * <p>
     * 取代原来的"全局 BFS 求完整解"：只保证开局有得走、接得上客，
     * 后续被堵死由「移除 / 排序」道具兜底（见 {@link #REMOVE_PER_LEVEL}）。
     * 取 3 兼顾"开局不至于全堵"与"不把密度压回去"。
     */
    public static final int MIN_ESCAPABLE_AT_START = 3;

    /**
     * 预热：进入某一关后，预先为<b>后续这么多关</b>生成关卡并入库。
     * <p>
     * 目的是消除"首次进关 / 点刷新"时的等待：关卡不再随包预置池（配置漂移后整池作废），
     * 改为进入第 N 关后在后台把 N 及后续若干关的池子填满，玩家真正走到时直接命中缓存。
     * 关卡号越深难度桶越容易重合（{@link #bucketOf}），实际生成的关卡数通常少于本值。
     */
    public static final int WARMUP_LEVEL_COUNT = 6;

    /** 预热：每个难度桶补齐到这么多条（用于「刷新」时仍有多样性，不至于反复同一张图）。 */
    public static final int WARMUP_PER_BUCKET = 3;

    /** 小汽车长度（占格数）。 */
    public static final int LENGTH_MIN = 2;

    /** 巴士长度（占格数）。车长在 [LENGTH_MIN, LENGTH_MAX] 内等概率随机。 */
    public static final int LENGTH_MAX = 3;

    /**
     * 每辆车平均占格数。与 {@link #LENGTH_MIN}/{@link #LENGTH_MAX} 同源，
     * 生成器也用这两个常量随机车长，故平均值不会漂移。
     */
    public static final double AVG_CELLS_PER_VEHICLE = (LENGTH_MIN + LENGTH_MAX) / 2.0;

    /** 棋盘总格数。 */
    public static final int BOARD_CELLS = ROWS * COLUMNS;

    /**
     * 目标占用率：<b>车辆上限的唯一调节旋钮</b>。
     * <p>
     * 车辆允许紧密相邻停放（引擎只禁止重叠，不要求留缝）。
     * <p>
     * <b>本值不再受求解器约束</b>：生成器已从"全局 BFS 证明可解"改为弱校验
     * （见 {@link #MIN_ESCAPABLE_AT_START}），密度可以一路推高；堵死的车由
     * 「移除 / 排序」道具兜底（这正是参考图那种"满场堵成一团"的观感来源）。
     * <p>
     * 实测偏难时优先下调本值（0.85 → 34 辆），而不是回退机制。
     */
    public static final double MAX_FILL_RATIO = 0.90;

    /**
     * 单关最多车辆数 = 棋盘容量 × 目标占用率 ÷ 每车平均占格。
     * <p>
     * <b>不再硬编码</b>，也不再受状态编码位宽限制（求解器已改为扁平 {@code int[]} 存储，
     * 车辆数无上限）。改棋盘大小或占用率，这个值会自动跟着变。
     */
    public static final int MAX_VEHICLES = Math.max(5,
        (int) (BOARD_CELLS * MAX_FILL_RATIO / AVG_CELLS_PER_VEHICLE));

    /** 首关车辆数：一上来就比原来的 L1（8 辆）满得多。 */
    public static final int START_VEHICLES = 12;

    /** 每关递增车辆数。 */
    public static final int VEHICLES_PER_LEVEL = 2;

    /**
     * 车辆数量：<b>L1 = {@link #START_VEHICLES}，之后每关 +2，到 {@link #MAX_VEHICLES} 封顶</b>。
     * <p>
     * 本作所有车都要接客离场，**没有"只挡路不用管"的车**——
     * 车越多，挡路的越多，要接的乘客也越多，难度随关卡单调上升。
     * <p>
     * 封顶值 = {@code BOARD_CELLS × MAX_FILL_RATIO ÷ AVG_CELLS_PER_VEHICLE}
     * = 100 × 0.90 ÷ 2.5 ≈ 36 辆（10×10 下的实测上限；原为 20 辆）。
     * 难度曲线为 L1=12、每关 +2、L13 起恒定 36。
     */
    public static int vehicleCount(int levelIndex) {
        return clamp(START_VEHICLES + (levelIndex - 1) * VEHICLES_PER_LEVEL, 5, MAX_VEHICLES);
    }

    /**
     * 难度门槛：整关最少需要的操作数。
     * <p>
     * <b>生成器改为弱校验后，本值不再用于"拒绝布局"</b>（那需要求出全局解，
     * 见 {@link #MIN_ESCAPABLE_AT_START}）；它现在只作为关卡<b>分桶</b>的元数据写入缓存
     * （{@code ParkingLevelStore.store}）与难度档标识（{@link #bucketOf}）。
     */
    public static int minSolutionMoves(int levelIndex) {
        return minSolutionMovesFor(vehicleCount(levelIndex), levelIndex);
    }

    /**
     * 难度门槛：整关最少需要的操作数，按<b>实际车数</b>计算。
     * <p>
     * 原用于让生成器拒绝"每辆车都能直接开走"的布局（那种局面操作数 == 车辆数，太简单）；
     * 弱校验之后该用途已取消，本值仅作分桶元数据保留。
     * <p>
     * <b>必须用实际车数而非 {@link #vehicleCount(int)}</b>：
     * 生成器在"降车重试"时车数会低于该关目标值，若门槛仍按目标车数算成绝对步数，
     * 车少了却要求同样多步数 → 几乎必然失败 → 一路掉到保底关。
     */
    public static int minSolutionMovesFor(int vehicleCount, int levelIndex) {
        return vehicleCount + Math.min(3, 1 + levelIndex / 4);
    }

    /**
     * 难度桶：车数与额外步数要求共同决定关卡的「难度档」。
     * <p>
     * 关卡号无限递增，但 {@link #vehicleCount} 在 L13+ 饱和到 {@link #MAX_VEHICLES}、
     * {@link #minSolutionMoves} 在 L21+ 饱和，故桶只有有限几个（docs/13 §3.1）。
     * 同一桶内的关卡可互相替换，这是「有限关卡池服务无限关卡号」的基础。
     */
    public static int bucketOf(int levelIndex) {
        int count = vehicleCount(levelIndex);
        return count * 10 + (minSolutionMoves(levelIndex) - count);
    }

    /**
     * 配置指纹：决定存量关卡是否仍然<b>合法</b>的常量哈希。
     * <p>
     * 关卡落库缓存后，若几何/颜色常量变化，旧布局可能越界崩溃
     * （例：{@code COLUMNS} 缩小 → {@code col + length > COLUMNS}；
     * {@code COLOR_COUNT} 减小 → {@code colorIndex} 越界）。
     * 这些常量参与计算，任一改动都会改变本值，从而使旧池自动失效（docs/13 §5.1）。
     */
    public static int configVersion() {
        int v = 1;
        v = v * 31 + ROWS;
        v = v * 31 + COLUMNS;
        v = v * 31 + COLOR_COUNT;
        v = v * 31 + MAX_VEHICLES;
        v = v * 31 + PICKUP_SLOTS;
        return v;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
