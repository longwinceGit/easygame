package com.template.app.game.tetris.view;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.template.app.game.tetris.engine.TetrisEngine;
import com.template.app.game.tetris.model.Tetromino;

/**
 * 拖拽手势状态机。只读引擎、向 {@link Host} 请求刷新与触觉反馈、向 {@link TetrisView.Listener}
 * 发出落子/旋转请求。不持有任何 Canvas / Paint，与绘制层完全解耦。
 */
class TetrisTouchHandler {
    /** 手势需要反向驱动 View 的能力（刷新、触觉反馈）。 */
    interface Host {
        void invalidate();

        void haptic(int feedbackConstant);
    }

    private static final long TAP_TIMEOUT_MS = 250L;

    private final TetrisGeometry geometry;
    private final DragState drag;
    private final Host host;
    private final float touchSlop;
    private TetrisEngine engine;
    private TetrisView.Listener listener;

    TetrisTouchHandler(TetrisGeometry geometry, DragState drag, Host host, Context context) {
        this.geometry = geometry;
        this.drag = drag;
        this.host = host;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void attach(TetrisEngine engine) {
        this.engine = engine;
        resetDrag();
    }

    void setListener(TetrisView.Listener listener) {
        this.listener = listener;
    }

    boolean onTouchEvent(MotionEvent event) {
        if (!interactive()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return beginDrag(event);
            case MotionEvent.ACTION_MOVE:
                if (drag.dragging) {
                    updateDrag(event.getX(), event.getY());
                    host.invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drag.dragging) {
                    endDrag(event.getX(), event.getY(), event.getEventTime() - event.getDownTime());
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private boolean interactive() {
        return engine != null && engine.getState() == TetrisEngine.State.RUNNING;
    }

    private boolean beginDrag(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        int slot = geometry.traySlotAt(x, y);
        if (slot < 0 || engine.getTrayPiece(slot) == null) {
            return false;
        }
        drag.dragging = true;
        drag.dragSlot = slot;
        drag.moved = false;
        drag.downX = x;
        drag.downY = y;
        drag.dragX = x;
        drag.dragY = y;
        updateTarget(x, y);
        host.haptic(HapticFeedbackConstants.KEYBOARD_TAP);
        host.invalidate();
        return true;
    }

    private void updateDrag(float x, float y) {
        drag.dragX = x;
        drag.dragY = y;
        if (!drag.moved && Math.hypot(x - drag.downX, y - drag.downY) > touchSlop) {
            drag.moved = true;
        }
        updateTarget(x, y);
    }

    private void updateTarget(float x, float y) {
        Tetromino piece = engine.getTrayPiece(drag.dragSlot);
        if (piece == null) {
            drag.ghostRow = -1;
            drag.ghostLeft = -1;
            return;
        }
        int[][] shape = piece.shape();
        int width = shape[0].length;
        int height = shape.length;
        int maxLeft = Math.max(0, engine.getBoardWidth() - width);
        drag.ghostLeft = geometry.columnAt(x, width, maxLeft);

        // 进入行由手指高度决定，而不是写死从顶部进入：
        // 这样玩家才能把方块递到已有方块下方的空腔里，而不总是被上方挡住。
        // 用"方块实际绘制的顶部"换算，保证幽灵与手指下方的方块视觉一致。
        float drawnTop = geometry.draggedTopY(drag.dragY, height);
        drag.ghostDesiredTop = Math.round((drawnTop - geometry.boardTop) / geometry.cell);
        drag.ghostRow = engine.landingRow(drag.dragSlot, drag.ghostLeft, drag.ghostDesiredTop);
    }

    private void endDrag(float x, float y, long duration) {
        if (!drag.moved && duration <= TAP_TIMEOUT_MS) {
            // 视为点击：旋转（GDD 机制 2）
            if (listener != null) {
                listener.onRotateRequested(drag.dragSlot);
            }
        } else {
            boolean inDropZone = y < geometry.trayTop
                && x > geometry.boardLeft - geometry.cell
                && x < geometry.boardRight + geometry.cell;
            if (inDropZone && drag.ghostRow >= 0 && listener != null) {
                listener.onPlaceRequested(drag.dragSlot, drag.ghostLeft, drag.ghostDesiredTop);
            } else {
                // 非法放置：零惩罚回弹（设计支柱 P2）
                host.haptic(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
        resetDrag();
        host.invalidate();
    }

    private void resetDrag() {
        drag.reset();
    }
}
