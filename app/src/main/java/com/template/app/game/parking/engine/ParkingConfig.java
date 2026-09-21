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
     * 8×8：格子更多 → 单个格子更小 → 车辆图标随之变小（视觉诉求），
     * 同时给"更多车"留出空间（难度诉求）。
     */
    public static final int COLUMNS = 8;

    /** 停车场行数。 */
    public static final int ROWS = 8;

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

    /** 每接走 1 位乘客的得分。 */
    public static final int SCORE_PER_PASSENGER = 10;

    /** 通关奖励 = 本值 × 关卡号。 */
    public static final int SCORE_PER_LEVEL_UNIT = 100;

    /** 关卡生成：布局重试上限。车多了、棋盘大了，重试成本高，但太多会让最坏耗时失控。 */
    public static final int GENERATOR_MAX_ATTEMPTS = 14;

    /** 关卡生成：单次 BFS 访问状态上限。8×8 棋盘下每辆车一次提取的上限。 */
    public static final int GENERATOR_MAX_BFS_STATES = 5000;

    /** 单关最多车辆数。求解器用 String 编码状态，不受 64 bit 限制。 */
    public static final int MAX_VEHICLES = 10;

    /**
     * 车辆数量：L1=5 → L2=6 → L3=7 → L4=8 → L5=9 → L6+=10。
     * <p>
     * 本作所有车都要接客离场，**没有"只挡路不用管"的车**——
     * 车越多，挡路的越多，要接的乘客也越多，难度随关卡单调上升。
     * 上限 10 是在"8×8 棋盘下生成耗时可控"与"难度随关卡上升"之间取的平衡点：
     * 10 辆已明显难于旧版的 7 辆，继续加到 12 会让生成最坏耗时冲到数秒。
     */
    public static int vehicleCount(int levelIndex) {
        return clamp(4 + levelIndex, 5, MAX_VEHICLES);
    }

    /**
     * 难度门槛：整关最少需要的操作数。
     * <p>
     * 生成器会拒绝"每辆车都能直接开走"的布局（那种局面操作数 == 车辆数，太简单）。
     * 关卡越深，要求的额外步数越多，但上限刻意压低——否则会逼出海量重试拖垮生成耗时。
     */
    public static int minSolutionMoves(int levelIndex) {
        return vehicleCount(levelIndex) + Math.min(3, 1 + levelIndex / 4);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
