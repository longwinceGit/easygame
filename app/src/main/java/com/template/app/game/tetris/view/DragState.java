package com.template.app.game.tetris.view;

/**
 * 拖拽手势与绘制之间共享的瞬时状态。
 * <p>
 * 手势状态机（{@link TetrisTouchHandler}）负责写入，绘制（{@link TetrisRenderer}）在
 * {@code onDraw} 时只读，二者因此解耦、互不依赖。
 */
class DragState {
    boolean dragging;
    int dragSlot = -1;
    float dragX;
    float dragY;
    float downX;
    float downY;
    boolean moved;
    int ghostLeft = -1;
    int ghostRow = -1;
    int ghostDesiredTop = 0;

    void reset() {
        dragging = false;
        dragSlot = -1;
        ghostLeft = -1;
        ghostRow = -1;
        moved = false;
    }
}
