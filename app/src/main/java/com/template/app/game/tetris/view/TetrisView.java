package com.template.app.game.tetris.view;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.template.app.R;
import com.template.app.game.tetris.engine.TetrisConfig;
import com.template.app.game.tetris.engine.TetrisEngine;

/**
 * 拖拽方块的游戏画布门面：组装棋盘 + 托盘 + 拖拽手势。
 * <p>
 * <b>为什么棋盘和托盘在同一个 View 里</b>（ADR-002）：若拆成两个 View，跨 View 拖拽
 * 需要 {@code startDragAndDrop}（无法精细控制拖拽影像）或自行转发触摸事件（坐标转换
 * 复杂度高、易出 bug）。合二为一后所有坐标天然统一，是复杂度更低的选择。
 * <p>
 * 内部协作拆分为三个包级私有协作者（均为纯重构，行为不变）：
 * <ul>
 *   <li>{@link TetrisGeometry} —— 布局几何与坐标换算（无 Canvas / Paint）</li>
 *   <li>{@link TetrisRenderer} —— 全部绘制与动画计时</li>
 *   <li>{@link TetrisTouchHandler} —— 手势状态机</li>
 * </ul>
 * 三者共享的拖拽瞬时状态由 {@link DragState} 承载（手势写、绘制读）。
 * <p>
 * <b>性能约定</b>：{@link #onDraw(Canvas)} 内零对象分配 —— 所有 {@link android.graphics.Paint} /
 * {@link android.graphics.RectF} 均为预分配字段并复用。
 */
public class TetrisView extends View implements TetrisTouchHandler.Host {

    /** 与 Fragment 的通信回调。所有状态变更都经由它走 ViewModel（ADR-004）。 */
    public interface Listener {
        /** 请求把托盘 slot 的方块放置到 left 列，进入行为 fromTop。 */
        void onPlaceRequested(int slot, int left, int fromTop);

        /** 请求旋转托盘 slot 的方块。 */
        void onRotateRequested(int slot);
    }

    private static final float MIN_CELL_DP = TetrisGeometry.MIN_CELL_DP;

    @Nullable
    private TetrisEngine engine;
    @Nullable
    private Listener listener;

    private TetrisGeometry geometry;
    private TetrisRenderer renderer;
    private TetrisTouchHandler handler;
    private DragState drag;

    public TetrisView(Context context) {
        super(context);
        init(context);
    }

    public TetrisView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TetrisView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        drag = new DragState();
        geometry = new TetrisGeometry(context.getResources().getDisplayMetrics().density);
        renderer = new TetrisRenderer(context, geometry);
        handler = new TetrisTouchHandler(geometry, drag, this, context);

        setContentDescription(context.getString(R.string.tetris_board_description));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** 绑定引擎。View 只读引擎，所有写入必须经由 {@link Listener}。 */
    public void attach(TetrisEngine engine) {
        this.engine = engine;
        handler.attach(engine);
        invalidate();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
        handler.setListener(listener);
    }

    /** 播放消行闪烁动画。 */
    public void startClearFlash(int[] rows) {
        renderer.startClearFlash(rows);
        invalidate();
    }

    /**
     * 播放得分浮字动画。文字出现在被消除行的中心，向上飘起并淡出。
     *
     * @param gained       本次得分（0 表示未消行，不播放）
     * @param clearedCount 本次消除行数
     * @param combo        本次连击数（< 2 时不展示连击文案）
     * @param clearedRows  被消除的行索引
     */
    public void startScorePopup(int gained, int clearedCount, int combo, int[] clearedRows) {
        renderer.startScorePopup(gained, clearedCount, combo, clearedRows);
        invalidate();
    }

    // ==================================================================
    // 布局
    // ==================================================================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int minHeight = (int) (MIN_CELL_DP * geometry.density * TetrisConfig.BOARD_HEIGHT)
            + getPaddingTop() + getPaddingBottom();
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int height = resolveSize(Math.max(getSuggestedMinimumHeight(), minHeight), heightMeasureSpec);
        setMeasuredDimension(width, height);
        geometry.layout(width, height, getPaddingLeft(), getPaddingTop(), getPaddingRight(), getPaddingBottom());
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
        renderer.draw(canvas, engine, drag);
        // 动画帧续接：闪烁与浮字各自时长不同，统一由门面决定是否需要下一帧
        if (renderer.isAnimating()) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return handler.onTouchEvent(event);
    }

    @Override
    public void haptic(int feedbackConstant) {
        performHapticFeedback(feedbackConstant);
    }
}
