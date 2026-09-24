package com.template.app.game.tetris.model;

/**
 * 一个待放置的方块实例：类型 + 当前旋转态。
 * <p>
 * <b>纯 Java，不得依赖 android.* —— 见 ADR-003。</b>
 */
public final class Tetromino {

    private final TetrominoType type;
    private int rotation;

    public Tetromino(TetrominoType type) {
        this.type = type;
        this.rotation = 0;
    }

    /**
     * 以<b>指定旋转态</b>构造，供"退出后继续"从存档恢复使用。
     * <p>
     * 常规流程只用 {@link #Tetromino(TetrominoType)}（rotation 从 0 开始）；
     * 存档必须保留玩家已经转过的角度，否则继续时方块姿态会变。
     */
    public Tetromino(TetrominoType type, int rotation) {
        this.type = type;
        this.rotation = rotation & 3;
    }

    public TetrominoType type() {
        return type;
    }

    public int rotation() {
        return rotation;
    }

    /** 顺时针旋转 90°。 */
    public void rotate() {
        rotation = (rotation + 1) & 3;
    }

    /** 当前旋转态的形状矩阵（已裁剪包围盒）。 */
    public int[][] shape() {
        return type.shape(rotation);
    }

    /** 形状矩阵的宽度（列数）。 */
    public int width() {
        return shape()[0].length;
    }

    /** 渲染层调色板索引。 */
    public int paletteIndex() {
        return type.paletteIndex();
    }
}
