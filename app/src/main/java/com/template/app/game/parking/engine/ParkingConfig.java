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

    /** 每关「移除」道具次数。 */
    public static final int REMOVE_PER_LEVEL = 1;

    /** 每关「排序」道具次数。 */
    public static final int SORT_PER_LEVEL = 1;

    /**
     * 每格载客数：载客量 = 车身长度 × 本值。
     * <p>
     * <b>与 {@code Vehicle#capacity()} 是同一条规则的两处实现</b>——
     * model 层不依赖 engine 层，故 {@code Vehicle.capacity()} 仍是 {@code length}，
     * 等价于本值为 1。若要把本值改成 ≠1，必须同步改 {@code Vehicle.capacity()}。
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

    /** 关卡生成：布局重试上限。车多了、棋盘大了，重试成本高，但太多会让最坏耗时失控。 */
    public static final int GENERATOR_MAX_ATTEMPTS = 14;

    /**
     * 关卡生成：单次 BFS 访问状态上限。
     * <p>
     * 5000 对本作目标密度（封顶 {@link #MAX_VEHICLES} 辆）实测足够：20 辆时仍 100% 满车。
     * 超过约 22 辆后车容易被埋住、这个预算展不开，表现为"大量降车"——
     * 届时才需要放宽（代价是生成显著变慢）。
     */
    public static final int GENERATOR_MAX_BFS_STATES = 5000;

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
     * 车辆允许紧密相邻停放（引擎只禁止重叠，不要求留缝），所以"停满"的可动性上限
     * 完全由实测决定——密度太高时 BFS 没有腾挪空间、求不出解，生成就会失败。
     * 取值经 {@code BoardCheck} 密度扫描标定：满车率达标、保底率为 0、耗时可接受。
     */
    public static final double MAX_FILL_RATIO = 0.50;

    /**
     * 单关最多车辆数 = 棋盘容量 × 目标占用率 ÷ 每车平均占格。
     * <p>
     * <b>不再硬编码</b>，也不再受状态编码位宽限制（求解器已改为扁平 {@code int[]} 存储，
     * 车辆数无上限）。改棋盘大小或占用率，这个值会自动跟着变。
     */
    public static final int MAX_VEHICLES = Math.max(5,
        (int) (BOARD_CELLS * MAX_FILL_RATIO / AVG_CELLS_PER_VEHICLE));

    /** 首关车辆数。 */
    public static final int START_VEHICLES = 8;

    /** 每关递增车辆数。 */
    public static final int VEHICLES_PER_LEVEL = 1;

    /**
     * 车辆数量：<b>L1 = {@link #START_VEHICLES}，之后每关 +1，到 {@link #MAX_VEHICLES} 封顶</b>。
     * <p>
     * 本作所有车都要接客离场，**没有"只挡路不用管"的车**——
     * 车越多，挡路的越多，要接的乘客也越多，难度随关卡单调上升。
     * <p>
     * 封顶值由密度实测标定（10×10）：20 辆时仍 100% 满车、单次约 5s 内完成，
     * 故上限取 20。本作难度曲线为 L1=8、之后每关 +1、L13 起恒定 20。
     */
    public static int vehicleCount(int levelIndex) {
        return clamp(START_VEHICLES + (levelIndex - 1) * VEHICLES_PER_LEVEL, 5, MAX_VEHICLES);
    }

    /**
     * 难度门槛：整关最少需要的操作数。
     * <p>
     * 生成器会拒绝"每辆车都能直接开走"的布局（那种局面操作数 == 车辆数，太简单）。
     * 关卡越深，要求的额外步数越多，但上限刻意压低——否则会逼出海量重试拖垮生成耗时。
     */
    public static int minSolutionMoves(int levelIndex) {
        return minSolutionMovesFor(vehicleCount(levelIndex), levelIndex);
    }

    /**
     * 难度门槛：整关最少需要的操作数，按<b>实际车数</b>计算。
     * <p>
     * 生成器拒绝"每辆车都能直接开走"的布局（那种局面操作数 == 车辆数，太简单）。
     * 关卡越深，要求的额外步数越多，但上限刻意压低——否则会逼出海量重试拖垮生成耗时。
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
