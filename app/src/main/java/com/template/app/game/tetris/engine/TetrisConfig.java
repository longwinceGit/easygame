package com.template.app.game.tetris.engine;

/**
 * 拖拽方块全部可调数值的唯一出处。
 * <p>
 * 这里的每个常量都对应 GDD（docs/02-gdd-drag-tetris.md）调参表中的一行，
 * 调平衡时只改这里，不要把魔法数字散落到逻辑代码中。
 * <p>
 * <b>纯 Java，不得依赖 android.* —— 见 ADR-003。</b>
 */
public final class TetrisConfig {

    private TetrisConfig() {
        // 常量类禁止实例化
    }

    /** 棋盘列数 */
    public static final int BOARD_WIDTH = 10;

    /** 棋盘行数 */
    public static final int BOARD_HEIGHT = 20;

    /** 托盘槽位数（全部用完才补充，见 GDD 机制 3） */
    public static final int TRAY_SIZE = 3;

    /**
     * 每消除一行的基础分。
     * <p>
     * <b>注意：放置方块本身不得分。</b>分数只来源于消行，
     * 这样"消行"是玩家唯一的目标，核心循环不被稀释。
     */
    public static final int LINE_SCORE = 100;

    /**
     * 一次消除多行的<b>额外奖励</b>，下标 = 本次消除行数。
     * <p>
     * 单次消 n 行的合计 = {@code n × LINE_SCORE + MULTI_BONUS[n]}（再 × level）：
     * <pre>
     *   1 行 → 100
     *   2 行 → 200 + 200 = 400
     *   3 行 → 300 + 500 = 800
     *   4 行 → 400 + 900 = 1300
     * </pre>
     * 对比"分 n 次各消 1 行"（n × 100），激励倍率为 1 / 2.0 / 2.67 / 3.25。
     * 倍率足够让玩家愿意为了凑 4 行而冒险留坑，但不至于让非 4 行玩法失去价值
     * （凑 4 行需长期空出一列，风险高，理应获得高回报）。
     */
    public static final int[] MULTI_BONUS = {0, 0, 200, 500, 900};

    /** 连击奖励单位（第 n 连击奖励 = (n-1) × COMBO_UNIT × level） */
    public static final int COMBO_UNIT = 50;

    /** 每消除多少行升 1 级 */
    public static final int LEVEL_LINES = 10;
}
