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
import com.template.app.game.parking.model.Direction;
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

    /** 一次"所有动画都播完"的瞬时通知，用于让通关 / 卡住弹窗等到车全开走再弹出。 */
    public interface OnAnimationFinishedListener {
        void onAnimationFinished();
    }

    @Nullable
    private ParkingEngine engine;
    @Nullable
    private Listener listener;
    @Nullable
    private OnAnimationFinishedListener animationFinishedListener;
    private boolean wasAnimating;

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

    /** 注册"所有动画播完"的监听（通关 / 卡住弹窗用它推迟弹出）。传 null 即注销。 */
    public void setOnAnimationFinishedListener(@Nullable OnAnimationFinishedListener listener) {
        this.animationFinishedListener = listener;
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

        // 驶出的车统一先沿车头方向冲出场地，再拐进乘客区的接客位并停住；
        // 上客（接满乘客）由离场编排在接客位播放，车头转正朝右后再驶下马路、自左向右开走。
        if (vehicle != null) {
            // 车先沿自己的车头方向冲出场地，再拐进乘客区的接客位
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

        // 接客离场的车排进离场动画。被当场接走的那辆刚驶出停车场、正在播放驶入接客位的动画，
        // 待其到位后再开始上客，避免车还在半路而乘客已经上车的双重影像。
        // 延迟入队交给动画状态机内部处理，等待期间 isAnimating 仍为真，确保上层能
        // 可靠地等到所有车都开走（见 isBusy / OnAnimationFinishedListener）。
        if (result.boardSteps != null && !result.boardSteps.isEmpty()) {
            enqueueBoardSteps(result.boardSteps, result, ParkingAnimator.EXIT_DURATION_MS);
        }
        invalidate();
    }

    /**
     * 「移除 / 排序」后可能触发一批车接客离场，走与移动相同的离场编排。
     * 这些车都是"在接客位等到队首"的，故全部按车位定位，立即可播。
     */
    public void startBoardSteps(List<BoardStep> steps) {
        enqueueBoardSteps(steps, null, 0);
        invalidate();
    }

    /** 把引擎给的接客清单转成带屏幕坐标的离场车，交给动画状态机排队播放。 */
    private void enqueueBoardSteps(List<BoardStep> steps, MoveResult result, long delayMs) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        List<ParkingAnimator.LeavingCar> cars = new ArrayList<>();
        for (BoardStep step : steps) {
            cars.add(buildLeavingCar(step, result));
        }
        anim.enqueueBoardCars(cars, delayMs);
    }

    /**
     * 为单个接客步骤算好起点 / 上客点 / 离场终点等屏幕坐标。
     * <p>
     * <b>上客就地发生在这辆车自己所在的接客位</b>：车停在哪个车位，乘客就在哪个车位上车，
     * 不再横向滑到最左侧统一上客点——"车在哪，人在哪上"。
     * 接满乘客后<b>直接从该车位垂直驶下马路</b>，再自左向右开走。
     */
    private ParkingAnimator.LeavingCar buildLeavingCar(BoardStep step, MoveResult result) {
        float fromX = step.slot >= 0
            ? geometry.slotCenterX(step.slot)   // 起点：车原本停的接客位
            : geometry.stripLeft + geometry.stripWidth / 2f;
        float fromY = geometry.slotCenterY();
        // 就地上下客：上客点就是它自己所在的接客位（与起点重合，故上客阶段不再横滑）
        float sx = fromX;
        float sy = fromY;
        // 横向车：车头向右，长度方向沿屏幕 x 轴展开（width 为长边）
        float w = geometry.vehicleWidth(step.length, true);
        float h = geometry.vehicleHeight(step.length, true);
        return new ParkingAnimator.LeavingCar(step.vehicleId, step.colorIndex, Direction.RIGHT,
            step.length, step.count, fromX, fromY, sx, sy, 0f,
            sx, geometry.roadCenterY,
            geometry.roadLeft + geometry.roadWidth + w, geometry.roadCenterY, 0f, w, h);
    }

    /** 播放接客浮字。 */
    public void startPopup(int value) {
        anim.startPopup(value);
        invalidate();
    }

    /** 播放连击浮字（一次操作送走 ≥2 辆车时）。 */
    public void startComboPopup(int carCount, int bonus) {
        anim.startCombo(carCount, bonus);
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
        boolean animating = renderer.isAnimating(anim);
        // 动画从"在播"切到"停了"的那一刻，通知上层（弹窗等）。用 wasAnimating
        // 保证只触发一次，避免每帧都回调。
        if (wasAnimating && !animating && animationFinishedListener != null) {
            animationFinishedListener.onAnimationFinished();
        }
        wasAnimating = animating;
        if (animating) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return handler.onTouchEvent(event);
    }
}
