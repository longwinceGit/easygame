package com.template.app.game.parking.view;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;

import com.template.app.game.parking.engine.ParkingEngine;
import com.template.app.game.parking.model.Vehicle;

/**
 * 手势状态机：点击 = 沿箭头滑到底；拖动 = 沿轴精确停位（可反向）。
 * <p>
 * 两种操作合一是刻意的（GDD 机制 2 / 3）：点击让结果可预判，拖动保留华容道必需的"停在中途"。
 */
class ParkingTouchHandler {

    interface Host {
        void invalidate();

        void haptic(int feedbackConstant);

        /** 是否仍在播放动画（含接客离场编排）。动画途中不接受新的手势，避免状态错乱。 */
        boolean isBusy();
    }

    private final ParkingGeometry geometry;
    private final ParkingAnimator anim;
    private final Host host;

    private ParkingEngine engine;

    /** 回调复用 {@link ParkingView.Listener}：它是本包对外的公开契约，不重复定义一份。 */
    private ParkingView.Listener listener;

    private boolean removeMode;

    ParkingTouchHandler(ParkingGeometry geometry, ParkingAnimator anim, Host host) {
        this.geometry = geometry;
        this.anim = anim;
        this.host = host;
    }

    void attach(ParkingEngine engine) {
        this.engine = engine;
    }

    void setListener(ParkingView.Listener listener) {
        this.listener = listener;
    }

    void setRemoveMode(boolean enabled) {
        this.removeMode = enabled;
        anim.resetDrag();
    }

    boolean onTouchEvent(MotionEvent event) {
        if (engine == null || engine.getState() != ParkingEngine.State.RUNNING) {
            anim.resetDrag();
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(event);
            case MotionEvent.ACTION_MOVE:
                return onMove(event);
            case MotionEvent.ACTION_UP:
                return onUp(event);
            case MotionEvent.ACTION_CANCEL:
                anim.resetDrag();
                host.invalidate();
                return true;
            default:
                return false;
        }
    }

    // ==================================================================
    // 手势
    // ==================================================================

    private boolean onDown(MotionEvent event) {
        if (host.isBusy()) {
            anim.resetDrag();
            return false;
        }
        int vehicleId = vehicleIdAt(event.getX(), event.getY());
        if (vehicleId < 0) {
            anim.resetDrag();
            return false;
        }
        if (removeMode) {
            if (listener != null) {
                listener.onRemoveRequested(vehicleId);
            }
            host.haptic(HapticFeedbackConstants.KEYBOARD_TAP);
            return true;
        }
        anim.resetDrag();
        anim.dragging = true;
        anim.dragVehicleId = vehicleId;
        anim.dragStartX = event.getX();
        anim.dragStartY = event.getY();
        anim.dragCells = 0f;
        host.invalidate();
        return true;
    }

    private boolean onMove(MotionEvent event) {
        if (!anim.dragging || anim.dragVehicleId < 0) {
            return false;
        }
        Vehicle v = engine.vehicleById(anim.dragVehicleId);
        if (v == null || !v.inLot()) {
            return true;
        }
        // 只取沿车身轴向的分量；横向车看 x，竖直车看 y
        float along = v.horizontal
            ? event.getX() - anim.dragStartX
            : event.getY() - anim.dragStartY;
        int forwardSign = v.horizontal ? v.direction.dCol : v.direction.dRow;

        float units = (along / geometry.cell) * forwardSign;
        float maxForward = engine.maxForward(v.id);
        float maxBackward = engine.maxBackward(v.id);
        units = Math.max(-maxBackward, Math.min(maxForward, units));

        anim.dragCells = units;
        host.invalidate();
        return true;
    }

    private boolean onUp(MotionEvent event) {
        if (!anim.dragging || anim.dragVehicleId < 0) {
            anim.resetDrag();
            return false;
        }
        int vehicleId = anim.dragVehicleId;
        int delta = Math.round(anim.dragCells);
        anim.resetDrag();
        if (listener != null) {
            if (delta != 0) {
                listener.onMoveRequested(vehicleId, delta);
            } else {
                listener.onTapRequested(vehicleId);
            }
        }
        return true;
    }

    // ==================================================================
    // 命中测试
    // ==================================================================

    /**
     * 找出 (x, y) 下的车辆。
     * <p>
     * 棋盘是倾斜的，所以先把屏幕坐标逆旋转回棋盘坐标再命中。
     * 格子可能小于 44dp，因此命中区按 {@link ParkingGeometry#MIN_TOUCH_DP} 外扩
     * （UI 规格 §2.3）。外扩量取"最小触控目标与格子边长之差的一半"。
     */
    private int vehicleIdAt(float x, float y) {
        geometry.toBoard(x, y);
        float boardX = geometry.tmpX;
        float boardY = geometry.tmpY;
        if (boardX < geometry.boardLeft || boardX > geometry.boardLeft + geometry.boardWidth()
            || boardY < geometry.boardTop || boardY > geometry.boardTop + geometry.boardHeight()) {
            return -1;
        }
        float padding = Math.max(0f,
            ParkingGeometry.MIN_TOUCH_DP * geometry.density - geometry.cell) * 0.5f;
        for (int i = 0; i < engine.vehicleCount(); i++) {
            Vehicle v = engine.vehicleAt(i);
            if (!v.inLot()) {
                continue;
            }
            float left = geometry.vehicleLeft(v.col) - padding;
            float top = geometry.vehicleTop(v.row) - padding;
            float right = left + geometry.vehicleWidth(v.length, v.horizontal) + padding * 2f;
            float bottom = top + geometry.vehicleHeight(v.length, v.horizontal) + padding * 2f;
            if (boardX >= left && boardX <= right && boardY >= top && boardY <= bottom) {
                return v.id;
            }
        }
        return -1;
    }
}
