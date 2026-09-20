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
