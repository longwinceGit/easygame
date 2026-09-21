package com.template.app.game.parking.view;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.template.app.R;
import com.template.app.game.parking.engine.BoardStep;
import com.template.app.game.parking.engine.MoveResult;
import com.template.app.game.parking.engine.ParkingConfig;
import com.template.app.game.parking.engine.ParkingEngine;
import com.template.app.game.parking.model.Vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * 挪车消消消的画布门面：组装乘客队列 + 接客区 + 倾斜停车场 + 手势。
 * <p>
 * <b>为什么三块画在同一个 View 里</b>（ADR-002）：车辆驶出的动画要从倾斜的车场
 * 一路开进水平的接客位，拆成多个 View 就得做跨 View 的坐标转换与动画接力
 * ——合二为一后所有坐标天然统一。
 * <p>
 * 内部协作拆为四个包级私有协作者：
 * <ul>
 *   <li>{@link ParkingGeometry} —— 布局几何、倾斜变换与坐标换算</li>
 *   <li>{@link ParkingRenderer} —— 全部绘制与动画计时</li>
 *   <li>{@link ParkingTouchHandler} —— 手势状态机</li>
 *   <li>{@link ParkingAnimator} —— 拖动与动画的瞬时状态（手势写、绘制读）</li>
 * </ul>
 */
public class ParkingView extends View implements ParkingTouchHandler.Host {

    /** 与 Fragment 的通信回调。所有状态变更都经由它走 ViewModel（ADR-004）。 */
    public interface Listener {
        void onMoveRequested(int vehicleId, int delta);

        void onTapRequested(int vehicleId);

        void onRemoveRequested(int vehicleId);
    }

    @Nullable
    private ParkingEngine engine;
    @Nullable
    private Listener listener;

    private ParkingGeometry geometry;
    private ParkingRenderer renderer;
    private ParkingTouchHandler handler;
    private ParkingAnimator anim;
    private boolean removeMode;

    public ParkingView(Context context) {
        super(context);
        init(context);
    }

    public ParkingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ParkingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        anim = new ParkingAnimator();
        geometry = new ParkingGeometry(context.getResources().getDisplayMetrics().density);
        renderer = new ParkingRenderer(context, geometry);
        handler = new ParkingTouchHandler(geometry, anim, this);

        setContentDescription(context.getString(R.string.parking_board_description));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** 绑定引擎。View 只读引擎，所有写入必须经由 {@link Listener}。 */
    public void attach(ParkingEngine engine) {
        this.engine = engine;
        handler.attach(engine);
        invalidate();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
        handler.setListener(listener);
    }

    /** 进入 / 退出「移除」选择模式：该模式下车辆被高亮并可被点掉。 */
    public void setRemoveMode(boolean enabled) {
        if (removeMode == enabled) {
            return;
        }
        removeMode = enabled;
        handler.setRemoveMode(enabled);
        invalidate();
    }

    /**
     * 播放一次操作动画。
     * <p>
     * 棋盘内移动走旋转坐标系；驶出则换算成屏幕坐标——车先冲出场地再拐进接客位，
     * 车身同时从棋盘倾角转正。
     */
    public void startMove(MoveResult result) {
        if (engine == null || !result.success) {
            return;
        }
        Vehicle vehicle = engine.vehicleById(result.vehicleId);
        if (!result.exited) {
            if (vehicle == null) {
                return;
            }
            anim.startMove(result, vehicle);
            invalidate();
            return;
        }

        // 被操作的车是否"直接开出接客"（它会出现在 boardSteps 里），这种不播占位驶入动画
        boolean movedBoarded = false;
        if (result.boardSteps != null) {
            for (BoardStep step : result.boardSteps) {
                if (step.straightOut) {
                    movedBoarded = true;
                    break;
                }
            }
        }

        if (!movedBoarded && vehicle != null) {
            // 仅占位：车先沿车头方向冲出场地，再拐进接客位并停住
            geometry.vehicleScreenCenter(result.fromRow, result.fromCol,
                vehicle.length, vehicle.horizontal);
            float fromX = geometry.tmpX;
            float fromY = geometry.tmpY;

            geometry.rotateVector(vehicle.direction.dCol, vehicle.direction.dRow);
            float distance = geometry.cell * 1.5f;
            float outX = fromX + geometry.tmpX * distance;
            float outY = fromY + geometry.tmpY * distance;

            float toX = result.pickupSlot >= 0
                ? geometry.slotCenterX(result.pickupSlot)
                : geometry.stripLeft + geometry.stripWidth / 2f;
            anim.startExit(vehicle, fromX, fromY, ParkingGeometry.ROTATION_DEGREES,
                outX, outY, toX, geometry.slotCenterY(), result.pickupSlot >= 0);
        }

        // 把本次接客离场的车（可能含直接开出的那辆 + 已在接客位等到的车）排进离场动画
        enqueueBoardSteps(result.boardSteps, result);
        invalidate();
    }

    /**
     * 「移除 / 排序」后可能触发一批车接客离场，走与移动相同的离场编排。
     * 这些车都是"在接客位等到队首"的，故全部按车位定位。
     */
    public void startBoardSteps(List<BoardStep> steps) {
        enqueueBoardSteps(steps, null);
        invalidate();
    }

    /** 把引擎给的接客清单转成带屏幕坐标的离场车，交给动画状态机排队播放。 */
    private void enqueueBoardSteps(List<BoardStep> steps, MoveResult result) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        List<ParkingAnimator.LeavingCar> cars = new ArrayList<>();
        for (BoardStep step : steps) {
            cars.add(buildLeavingCar(step, result));
        }
        anim.enqueueBoardCars(cars);
    }

    /** 为单个接客步骤算好上客点 / 离场终点等屏幕坐标。 */
    private ParkingAnimator.LeavingCar buildLeavingCar(BoardStep step, MoveResult result) {
        if (step.straightOut && result != null) {
            // 直接开出接客：上客点在棋盘出口外（沿车头方向 1.5 格处），倾角保持棋盘斜度。
            // 基点是这辆车驶出后的边界格（result.toRow/toCol）。
            geometry.vehicleScreenCenter(result.toRow, result.toCol,
                step.length, !step.direction.isVertical());
            float fromX = geometry.tmpX;
            float fromY = geometry.tmpY;
            geometry.rotateVector(step.direction.dCol, step.direction.dRow);
            float distance = geometry.cell * 1.5f;
            float outX = fromX + geometry.tmpX * distance;
            float outY = fromY + geometry.tmpY * distance;
            float dirX = outX - fromX;
            float dirY = outY - fromY;
            double len = Math.hypot(dirX, dirY);
            float ux = len > 0 ? (float) (dirX / len) : 0f;
            float uy = len > 0 ? (float) (dirY / len) : 0f;
            float far = geometry.cell * 16f;
            boolean horizontal = !step.direction.isVertical();
            float w = geometry.vehicleWidth(step.length, horizontal);
            float h = geometry.vehicleHeight(step.length, horizontal);
            return new ParkingAnimator.LeavingCar(step.colorIndex, step.direction, step.length,
                step.count, outX, outY, ParkingGeometry.ROTATION_DEGREES,
                outX + ux * far, outY + uy * far, 0f, w, h);
        }

        // 在接客位等到队首：上客点即该车车位，离场沿接客带向右驶出屏幕
        float sx = geometry.slotCenterX(step.slot);
        float sy = geometry.slotCenterY();
        float w = geometry.slotWidth * 0.8f;
        float h = geometry.pickupHeight * 0.5f;
        float far = geometry.cell * 16f;
        return new ParkingAnimator.LeavingCar(step.colorIndex, step.direction, step.length,
            step.count, sx, sy, 0f,
            geometry.stripLeft + geometry.stripWidth + w, sy, 0f, w, h);
    }

    /** 播放接客浮字。 */
    public void startPopup(int value) {
        anim.startPopup(value);
        invalidate();
    }

    /** 是否仍在播放任何动画（含接客离场编排）。用于锁住输入，避免动画途中状态错乱。 */
    public boolean isBusy() {
        return anim.isAnimating(SystemClock.uptimeMillis());
    }

    // Host.invalidate() 由 View.invalidate() 天然实现，无需覆写。

    @Override
    public void haptic(int feedbackConstant) {
        performHapticFeedback(feedbackConstant);
    }

    // ==================================================================
    // 布局
    // ==================================================================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int minHeight = (int) geometry.minSceneHeightPx()
            + getPaddingTop() + getPaddingBottom();
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int height = resolveSize(Math.max(getSuggestedMinimumHeight(), minHeight), heightMeasureSpec);
        setMeasuredDimension(width, height);
        geometry.layout(width, height, getPaddingLeft(), getPaddingTop(),
            getPaddingRight(), getPaddingBottom());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        geometry.layout(w, h, getPaddingLeft(), getPaddingTop(), getPaddingRight(), getPaddingBottom());
    }

    // ==================================================================
    // 绘制 / 触摸（委托给协作者）
    // ==================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (engine == null || geometry.cell <= 0) {
            return;
        }
        renderer.draw(canvas, engine, anim, removeMode);
        if (renderer.isAnimating(anim)) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return handler.onTouchEvent(event);
    }
}
