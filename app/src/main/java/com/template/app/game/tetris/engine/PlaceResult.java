package com.template.app.game.tetris.engine;

/**
 * 一次放置操作的不可变结果。
 * <p>
 * <b>纯 Java，不得依赖 android.* —— 见 ADR-003。</b>
 */
public final class PlaceResult {

    private static final PlaceResult FAILURE = new PlaceResult(false, new int[0], 0, 0, false, -1, -1);

    /** 是否放置成功（false 表示目标列无处可放，方块应回弹托盘） */
    public final boolean success;

    /** 本次被消除的行索引（升序），供渲染层播放闪烁动画 */
    public final int[] clearedRows;

    /** 本次放置获得的总分（= 消行分 + 连击奖励；放置本身不得分，见 TetrisConfig.LINE_SCORE） */
    public final int gained;

    /** 本次放置后的连击数（未消行时为 0） */
    public final int combo;

    /** 本次放置后是否已进入结束状态 */
    public final boolean gameOver;

    /** 实际落点行号，失败时为 -1 */
    public final int row;

    /** 实际落点列号，失败时为 -1 */
    public final int left;

    private PlaceResult(boolean success, int[] clearedRows, int gained,
                        int combo, boolean gameOver, int row, int left) {
        this.success = success;
        this.clearedRows = clearedRows;
        this.gained = gained;
        this.combo = combo;
        this.gameOver = gameOver;
        this.row = row;
        this.left = left;
    }

    /** 放置失败（不可变单例，避免为一次回弹分配对象）。 */
    public static PlaceResult failure() {
        return FAILURE;
    }

    public static PlaceResult success(int[] clearedRows, int gained, int combo,
                                      boolean gameOver, int row, int left) {
        return new PlaceResult(true, clearedRows, gained, combo, gameOver, row, left);
    }
}
