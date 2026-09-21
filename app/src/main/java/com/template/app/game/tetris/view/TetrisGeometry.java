package com.template.app.game.tetris.view;

import com.template.app.game.tetris.engine.TetrisConfig;

/**
 * 棋盘/托盘的布局几何与坐标换算。
 * <p>
 * 不持有任何 Canvas / Paint / View，纯计算，可被 JVM 单测直接覆盖。
 * 布局矩形在 {@link #layout(int, int, int, int, int, int)} 中计算，
 * 坐标换算方法（{@link #columnAt}、{@link #traySlotAt} 等）均读取这些矩形。
 */
class TetrisGeometry {
    static final float MIN_CELL_DP = 14f;
    static final float TRAY_ROW_SPAN = 3.5f;
    static final float TRAY_GAP_DP = 12f;
    static final float TRAY_SLOT_GAP_DP = 8f;
    static final float BOARD_RADIUS_DP = 8f;
    static final float MIN_TOUCH_DP = 48f;
    static final float DRAG_SCALE = 1.08f;
    static final float DRAG_LIFT = 0.5f;

    float density = 1f;

    float cell;
    float boardLeft;
    float boardTop;
    float boardRight;
    float boardBottom;
    float trayTop;
    float trayLeft;
    float slotWidth;
    float slotHeight;

    TetrisGeometry(float density) {
        this.density = density;
    }

    void layout(int w, int h, int padLeft, int padTop, int padRight, int padBottom) {
        float availWidth = w - padLeft - padRight;
        float availHeight = h - padTop - padBottom;
        if (availWidth <= 0 || availHeight <= 0) {
            return;
        }
        float trayGap = TRAY_GAP_DP * density;
        float cellByHeight = (availHeight - trayGap) / (TetrisConfig.BOARD_HEIGHT + TRAY_ROW_SPAN);
        float cellByWidth = availWidth / TetrisConfig.BOARD_WIDTH;
        cell = Math.max(Math.min(cellByWidth, cellByHeight), MIN_CELL_DP * density);

        float boardWidth = cell * TetrisConfig.BOARD_WIDTH;
        float boardHeight = cell * TetrisConfig.BOARD_HEIGHT;
        boardLeft = padLeft + (availWidth - boardWidth) / 2f;
        boardTop = padTop;
        boardRight = boardLeft + boardWidth;
        boardBottom = boardTop + boardHeight;

        trayTop = boardBottom + trayGap;
        float slotGap = TRAY_SLOT_GAP_DP * density;
        slotWidth = (boardWidth - slotGap * (TetrisConfig.TRAY_SIZE - 1)) / TetrisConfig.TRAY_SIZE;
        slotHeight = TRAY_ROW_SPAN * cell;
        trayLeft = boardLeft;
    }

    float boardRadius() {
        return BOARD_RADIUS_DP * density;
    }

    /**
     * 由手指 x 推算应落入的列，并 clamp 到合法范围。
     * 与绘制拖拽方块的原点使用同一套 cell/boardLeft，保证预览与手指一致。
     */
    int columnAt(float x, int shapeWidth, int maxLeft) {
        int left = Math.round((x - boardLeft) / cell - shapeWidth / 2f);
        return Math.max(0, Math.min(maxLeft, left));
    }

    /** 拖拽中方块"实际绘制顶部"的 y 坐标，用于把手指高度换算为进入行。 */
    float draggedTopY(float dragY, int shapeHeight) {
        return dragY - shapeHeight * cell * DRAG_SCALE / 2f - cell * DRAG_LIFT;
    }

    /**
     * 命中的托盘槽位；未命中返回 -1。
     * 命中区按 {@link #MIN_TOUCH_DP} 最小触控目标向外扩展，但不改变绘制尺寸。
     */
    int traySlotAt(float x, float y) {
        float slotGap = TRAY_SLOT_GAP_DP * density;
        float minHalf = MIN_TOUCH_DP * density / 2f;
        int traySize = TetrisConfig.TRAY_SIZE;
        for (int slot = 0; slot < traySize; slot++) {
            float left = trayLeft + slot * (slotWidth + slotGap);
            float centerX = left + slotWidth / 2f;
            float centerY = trayTop + slotHeight / 2f;
            float halfWidth = Math.max(slotWidth / 2f, minHalf);
            float halfHeight = Math.max(slotHeight / 2f, minHalf);
            if (Math.abs(x - centerX) <= halfWidth && Math.abs(y - centerY) <= halfHeight) {
                return slot;
            }
        }
        return -1;
    }
}
