package com.template.app.game.tetris.model;

import java.util.Arrays;

/**
 * 棋盘：负责格子存储、碰撞判定、落点计算与消行。
 * <p>
 * 格子取值约定：<b>0 表示空格，1..7 表示"调色板索引 + 1"</b>。
 * 领域层只存索引，颜色映射由渲染层完成。
 * <p>
 * <b>纯 Java，不得依赖 android.* —— 见 ADR-003。</b>
 */
public final class Board {

    private static final int[] NO_ROWS = new int[0];

    private final int width;
    private final int height;
    private int[][] cells;

    public Board(int width, int height) {
        this.width = width;
        this.height = height;
        this.cells = new int[height][width];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * 返回内部格子数组的引用（<b>只读</b>）。
     * <p>
     * 渲染层每帧需要读取 200 个格子，返回引用是为了避免每帧复制带来的 GC 抖动（ADR-004）。
     * 调用方<b>禁止修改</b>返回数组的内容。
     */
    public int[][] cells() {
        return cells;
    }

    /**
     * 用外部格子数据覆盖棋盘，供"退出后继续"从存档恢复。
     * <p>
     * <b>尺寸不符时一律拒绝</b>：存档可能来自棋盘尺寸不同的版本（如 {@code BOARD_WIDTH} 变更），
     * 此时丢弃存档、由调用方开新局，比画出错位棋盘或崩溃好。
     *
     * @return 是否恢复成功
     */
    public boolean restore(int[][] source) {
        if (source == null || source.length != height) {
            return false;
        }
        for (int r = 0; r < height; r++) {
            if (source[r] == null || source[r].length != width) {
                return false;
            }
        }
        for (int r = 0; r < height; r++) {
            System.arraycopy(source[r], 0, cells[r], 0, width);
        }
        return true;
    }

    /** 清空棋盘。 */
    public void clear() {
        for (int r = 0; r < height; r++) {
            Arrays.fill(cells[r], 0);
        }
    }

    /**
     * 判断形状能否放在 (left, top) 处。
     */
    public boolean canPlace(int[][] shape, int left, int top) {
        int h = shape.length;
        int w = shape[0].length;
        if (left < 0 || left + w > width || top < 0) {
            return false;
        }
        for (int r = 0; r < h; r++) {
            int row = top + r;
            if (row >= height) {
                return false;
            }
            int[] shapeRow = shape[r];
            int[] boardRow = cells[row];
            for (int c = 0; c < w; c++) {
                if (shapeRow[c] != 0 && boardRow[left + c] != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 计算形状在列 {@code left} 上的落点行号。
     * <p>
     * <b>关键：进入行由 {@code fromTop} 指定（来自手指高度），而不是写死为 0。</b>
     * 若写死为 0，则方块必须"从棋盘顶部一路畅通"才能落到底，
     * 玩家将无法把方块递进被上方方块遮挡的空腔（表现为"放不进去，只能停在上方"）。
     *
     * @param fromTop 期望的进入行（会被 clamp 到合法区间）
     * @return 落点行号；若该列自上而下均无可进入的位置则返回 -1
     */
    public int landingRow(int[][] shape, int left, int fromTop) {
        int h = shape.length;
        int top = Math.max(0, Math.min(fromTop, height - h));

        // 手指所在行放不下（与已有方块重叠）时，向上找到第一个能进入的行
        if (!canPlace(shape, left, top)) {
            while (top > 0 && !canPlace(shape, left, top)) {
                top--;
            }
            if (!canPlace(shape, left, top)) {
                return -1;
            }
        }

        // 重力吸附：从进入行继续下落到不能再落为止
        while (canPlace(shape, left, top + 1)) {
            top++;
        }
        return top;
    }

    /**
     * 将形状写入棋盘。
     *
     * @param value 写入值（调色板索引 + 1）
     */
    public void put(int[][] shape, int left, int top, int value) {
        int h = shape.length;
        int w = shape[0].length;
        for (int r = 0; r < h; r++) {
            int[] shapeRow = shape[r];
            int[] boardRow = cells[top + r];
            for (int c = 0; c < w; c++) {
                if (shapeRow[c] != 0) {
                    boardRow[left + c] = value;
                }
            }
        }
    }

    /**
     * 消除所有满行，上方行整体下移。
     *
     * @return 被消除的行索引（<b>升序</b>，为消除前的位置）；无满行时返回空数组
     */
    public int[] clearFullRows() {
        boolean[] full = new boolean[height];
        int count = 0;
        for (int r = 0; r < height; r++) {
            full[r] = isRowFull(r);
            if (full[r]) {
                count++;
            }
        }
        if (count == 0) {
            return NO_ROWS;
        }

        int[][] next = new int[height][width];
        int[] cleared = new int[count];
        int write = height - 1;
        int ci = 0;
        for (int r = height - 1; r >= 0; r--) {
            if (full[r]) {
                cleared[ci++] = r;
                continue;
            }
            next[write--] = cells[r];
        }
        cells = next;

        // 由降序反转为升序，便于渲染层按行绘制闪烁动画
        for (int i = 0; i < cleared.length / 2; i++) {
            int tmp = cleared[i];
            cleared[i] = cleared[cleared.length - 1 - i];
            cleared[cleared.length - 1 - i] = tmp;
        }
        return cleared;
    }

    private boolean isRowFull(int row) {
        int[] line = cells[row];
        for (int cell : line) {
            if (cell == 0) {
                return false;
            }
        }
        return true;
    }
}
