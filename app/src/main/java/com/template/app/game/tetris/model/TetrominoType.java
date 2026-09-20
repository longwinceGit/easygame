package com.template.app.game.tetris.model;

/**
 * 七种四连块（Tetromino）类型。
 * <p>
 * 每种类型预计算 4 个旋转态，并在读取时裁剪到最小包围盒，
 * 保证放置与碰撞判定不会出现"矩阵空白行导致方块悬空"的问题。
 * <p>
 * <b>本类是纯 Java，不得依赖任何 android.* —— 见 ADR-003。</b>
 */
public enum TetrominoType {

    I(new int[][]{
        {0, 0, 0, 0},
        {1, 1, 1, 1},
        {0, 0, 0, 0},
        {0, 0, 0, 0}}),

    O(new int[][]{
        {1, 1},
        {1, 1}}),

    T(new int[][]{
        {0, 1, 0},
        {1, 1, 1},
        {0, 0, 0}}),

    S(new int[][]{
        {0, 1, 1},
        {1, 1, 0},
        {0, 0, 0}}),

    Z(new int[][]{
        {1, 1, 0},
        {0, 1, 1},
        {0, 0, 0}}),

    J(new int[][]{
        {1, 0, 0},
        {1, 1, 1},
        {0, 0, 0}}),

    L(new int[][]{
        {0, 0, 1},
        {1, 1, 1},
        {0, 0, 0}});

    /** 缓存 values()，避免每次调用产生新数组。 */
    public static final TetrominoType[] TYPES = values();

    private final int[][][] trimmed;

    TetrominoType(int[][] base) {
        trimmed = new int[4][][];
        int[][] current = base;
        for (int i = 0; i < 4; i++) {
            trimmed[i] = trim(current);
            current = rotateClockwise(current);
        }
    }

    /**
     * 该类型在渲染层调色板中的索引（0–6）。
     * 领域层只持有索引，不持有颜色，以保持对 Android 资源的零依赖。
     */
    public int paletteIndex() {
        return ordinal();
    }

    /**
     * 获取指定旋转态的形状矩阵，已裁剪到最小包围盒。
     *
     * @param rotation 旋转序号（0–3，超出范围会按 &amp; 3 归一化）
     * @return 0 表示空格，非 0 表示占用
     */
    public int[][] shape(int rotation) {
        return trimmed[rotation & 3];
    }

    /** 顺时针旋转 90°（要求输入为方阵）。 */
    private static int[][] rotateClockwise(int[][] src) {
        int n = src.length;
        int[][] dst = new int[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                dst[r][c] = src[n - 1 - c][r];
            }
        }
        return dst;
    }

    /** 裁剪掉全空的外围行列，得到最小包围盒。 */
    private static int[][] trim(int[][] src) {
        int n = src.length;
        int minRow = n;
        int maxRow = -1;
        int minCol = n;
        int maxCol = -1;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (src[r][c] != 0) {
                    if (r < minRow) {
                        minRow = r;
                    }
                    if (r > maxRow) {
                        maxRow = r;
                    }
                    if (c < minCol) {
                        minCol = c;
                    }
                    if (c > maxCol) {
                        maxCol = c;
                    }
                }
            }
        }
        int h = maxRow - minRow + 1;
        int w = maxCol - minCol + 1;
        int[][] dst = new int[h][w];
        for (int r = 0; r < h; r++) {
            System.arraycopy(src[minRow + r], minCol, dst[r], 0, w);
        }
        return dst;
    }
}
