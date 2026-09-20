package com.template.app.game.tetris.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.template.app.R;
import com.template.app.game.tetris.engine.TetrisConfig;
import com.template.app.game.tetris.engine.TetrisEngine;
import com.template.app.game.tetris.model.Tetromino;

/**
 * 拖拽方块的游戏画布：棋盘 + 托盘 + 拖拽手势。
 * <p>
 * <b>为什么棋盘和托盘在同一个 View 里</b>（ADR-002）：若拆成两个 View，跨 View 拖拽
 * 需要 {@code startDragAndDrop}（无法精细控制拖拽影像）或自行转发触摸事件（坐标转换
 * 复杂度高、易出 bug）。合二为一后所有坐标天然统一，是复杂度更低的选择。
 * <p>
 * <b>性能约定</b>：{@link #onDraw(Canvas)} 内零对象分配 —— 所有 {@link Paint} /
 * {@link RectF} 均为预分配字段并复用。
 */
public class TetrisView extends View {

    /** 与 Fragment 的通信回调。所有状态变更都经由它走 ViewModel（ADR-004）。 */
    public interface Listener {
        /** 请求把托盘 slot 的方块放置到 left 列，进入行为 fromTop。 */
        void onPlaceRequested(int slot, int left, int fromTop);

        /** 请求旋转托盘 slot 的方块。 */
        void onRotateRequested(int slot);
    }

    private static final float CELL_INSET_RATIO = 0.06f;
    private static final float CELL_RADIUS_RATIO = 0.18f;
    private static final float INNER_INSET_RATIO = 0.24f;
    private static final float INNER_STROKE_RATIO = 0.07f;
    private static final float INNER_ALPHA = 0.38f;
    private static final float AMBER_STROKE_RATIO = 0.09f;
    private static final float GHOST_FILL_ALPHA = 0.28f;
    private static final float GHOST_STROKE_ALPHA = 0.90f;
    private static final float COLUMN_ALPHA = 0.35f;
    private static final float FLASH_ALPHA = 0.90f;
    private static final float DRAG_SCALE = 1.08f;
    private static final float DRAG_ALPHA = 0.95f;
    private static final float DRAG_LIFT = 0.5f;
    private static final float MIN_CELL_DP = 14f;
    private static final float TRAY_ROW_SPAN = 3.5f;
    private static final float TRAY_GAP_DP = 12f;
    private static final float TRAY_SLOT_GAP_DP = 8f;
    private static final float BOARD_RADIUS_DP = 8f;
    private static final float MIN_TOUCH_DP = 48f;
    private static final long TAP_TIMEOUT_MS = 250L;

    /** 琥珀色（索引 1）需要额外描边补偿对比度，见 UI 规格 2.1。 */
    private static final int AMBER_INDEX = 1;

    private final Paint paintBoardBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintBoardFrame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintCell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintInner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintAmberStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGhostFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGhostStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintColumn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTraySlot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTrayEmpty = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintFlash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintOverlayText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintPopup = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintPopupSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int[] palette = new int[0];
    private int columnHintColor;
    private int ghostValidColor;
    private int ghostInvalidColor;
    private String pausedText;
    private String gameOverText;
    private float density = 1f;
    private float touchSlop = 8f;
    private int flashDurationMs = 160;

    @Nullable
    private TetrisEngine engine;
    @Nullable
    private Listener listener;

    // ---- 布局几何（onSizeChanged 中计算）----
    private float cell;
    private float boardLeft;
    private float boardTop;
    private float boardRight;
    private float boardBottom;
    private float trayTop;
    private float trayLeft;
    private float slotWidth;
    private float slotHeight;

    // ---- 拖拽状态 ----
    private boolean dragging;
    private int dragSlot = -1;
    private float dragX;
    private float dragY;
    private float downX;
    private float downY;
    private boolean moved;
    private int ghostLeft = -1;
    private int ghostRow = -1;
    private int ghostDesiredTop = 0;

    // ---- 消行闪烁动画 ----
    private int[] flashRows;
    private long flashStart;
    private boolean flashing;

    // ---- 得分浮字动画 ----
    private String popupText;
    private String popupSubText;
    private float popupX;
    private float popupY;
    private long popupStart;
    private boolean popupActive;
    private int popupDurationMs = 900;

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
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        flashDurationMs = context.getResources().getInteger(R.integer.duration_clear);

        palette = context.getResources().getIntArray(R.array.tetris_piece_colors);
        columnHintColor = ContextCompat.getColor(context, R.color.game_column_hint);
        ghostValidColor = ContextCompat.getColor(context, R.color.game_ghost_valid);
        ghostInvalidColor = ContextCompat.getColor(context, R.color.game_ghost_invalid);
        pausedText = context.getString(R.string.tetris_paused);
        gameOverText = context.getString(R.string.tetris_game_over_overlay);

        paintBoardBg.setColor(ContextCompat.getColor(context, R.color.game_board_bg));
        paintBoardFrame.setColor(ContextCompat.getColor(context, R.color.game_board_frame));
        paintBoardFrame.setStyle(Paint.Style.STROKE);
        paintBoardFrame.setStrokeWidth(density);
        paintGrid.setColor(ContextCompat.getColor(context, R.color.game_board_grid));
        paintGrid.setStrokeWidth(0.5f * density);
        paintInner.setColor(Color.WHITE);
        paintInner.setStyle(Paint.Style.STROKE);
        paintAmberStroke.setColor(ContextCompat.getColor(context, R.color.game_piece_stroke_amber));
        paintAmberStroke.setStyle(Paint.Style.STROKE);
        paintGhostStroke.setStyle(Paint.Style.STROKE);
        paintTraySlot.setColor(ContextCompat.getColor(context, R.color.game_tray_slot));
        paintTrayEmpty.setColor(ContextCompat.getColor(context, R.color.game_tray_slot_empty));
        paintTrayEmpty.setStyle(Paint.Style.STROKE);
        paintTrayEmpty.setStrokeWidth(density);
        paintTrayEmpty.setPathEffect(new DashPathEffect(new float[]{4 * density, 4 * density}, 0f));
        paintFlash.setColor(ContextCompat.getColor(context, R.color.game_flash));
        paintDim.setColor(ContextCompat.getColor(context, R.color.game_dim));
        paintDim.setAlpha((int) (255 * 0.6f));
        paintOverlayText.setColor(ContextCompat.getColor(context, R.color.text_title));
        paintOverlayText.setTextSize(18f * density);
        paintOverlayText.setTextAlign(Paint.Align.CENTER);

        popupDurationMs = context.getResources().getInteger(R.integer.duration_popup);
        int popupColor = ContextCompat.getColor(context, R.color.text_accent);
        paintPopup.setColor(popupColor);
        paintPopup.setTextSize(22f * density);
        paintPopup.setTextAlign(Paint.Align.CENTER);
        paintPopup.setTypeface(Typeface.DEFAULT_BOLD);
        // 棋盘底是纯色块，文字加投影保证任何主题下都可读
        paintPopup.setShadowLayer(4f * density, 0f, 2f * density, Color.BLACK);
        paintPopupSub.setColor(popupColor);
        paintPopupSub.setTextSize(12f * density);
        paintPopupSub.setTextAlign(Paint.Align.CENTER);
        paintPopupSub.setTypeface(Typeface.DEFAULT_BOLD);
        paintPopupSub.setShadowLayer(3f * density, 0f, 1.5f * density, Color.BLACK);

        setContentDescription(context.getString(R.string.tetris_board_description));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** 绑定引擎。View 只读引擎，所有写入必须经由 {@link Listener}。 */
    public void attach(TetrisEngine engine) {
        this.engine = engine;
        resetDrag();
        invalidate();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** 播放消行闪烁动画。 */
    public void startClearFlash(int[] rows) {
        if (rows == null || rows.length == 0) {
            return;
        }
        flashRows = rows;
        flashStart = SystemClock.uptimeMillis();
        flashing = true;
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
        if (gained <= 0 || clearedRows == null || clearedRows.length == 0) {
            return;
        }
        popupText = "+" + gained;

        // 副标题只在"值得欢呼"时出现，避免每次消 1 行都刷屏
        StringBuilder sub = new StringBuilder();
        if (clearedCount >= 4) {
            sub.append(getResources().getString(R.string.tetris_popup_tetris));
        } else if (clearedCount >= 2) {
            sub.append(getResources().getString(R.string.tetris_popup_lines, clearedCount));
        }
        if (combo >= 2) {
            if (sub.length() > 0) {
                sub.append("  ");
            }
            sub.append(getResources().getString(R.string.tetris_popup_combo, combo));
        }
        popupSubText = sub.length() > 0 ? sub.toString() : null;

        int midRow = clearedRows[clearedRows.length / 2];
        popupX = (boardLeft + boardRight) / 2f;
        popupY = boardTop + (midRow + 0.5f) * cell;
        popupStart = SystemClock.uptimeMillis();
        popupActive = true;
        invalidate();
    }

    // ==================================================================
    // 布局
    // ==================================================================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int minHeight = (int) (MIN_CELL_DP * density * TetrisConfig.BOARD_HEIGHT)
            + getPaddingTop() + getPaddingBottom();
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int height = resolveSize(Math.max(getSuggestedMinimumHeight(), minHeight), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float padLeft = getPaddingLeft();
        float padRight = getPaddingRight();
        float padTop = getPaddingTop();
        float padBottom = getPaddingBottom();
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

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (engine == null || cell <= 0) {
            return;
        }
        float radius = BOARD_RADIUS_DP * density;

        drawBoard(canvas, radius);
        drawCells(canvas);
        if (dragging) {
            drawColumnHint(canvas);
            drawGhost(canvas);
        }
        drawFlash(canvas, radius);
        drawTray(canvas, radius);
        if (dragging) {
            drawDraggedPiece(canvas);
        }
        drawStateOverlay(canvas, radius);
        drawScorePopup(canvas);

        // 动画帧续接：闪烁与浮字各自时长不同，统一由 onDraw 决定是否需要下一帧
        if (flashing || popupActive) {
            postInvalidateOnAnimation();
        }
    }

    private void drawBoard(Canvas canvas, float radius) {
        rect.set(boardLeft, boardTop, boardRight, boardBottom);
        canvas.drawRoundRect(rect, radius, radius, paintBoardBg);

        for (int c = 1; c < TetrisConfig.BOARD_WIDTH; c++) {
            float x = boardLeft + c * cell;
            canvas.drawLine(x, boardTop + radius, x, boardBottom - radius, paintGrid);
        }
        for (int r = 1; r < TetrisConfig.BOARD_HEIGHT; r++) {
            float y = boardTop + r * cell;
            canvas.drawLine(boardLeft + radius, y, boardRight - radius, y, paintGrid);
        }
        canvas.drawRoundRect(rect, radius, radius, paintBoardFrame);
    }

    private void drawCells(Canvas canvas) {
        int[][] cells = engine.getCells();
        for (int r = 0; r < cells.length; r++) {
            int[] row = cells[r];
            float y = boardTop + r * cell;
            for (int c = 0; c < row.length; c++) {
                if (row[c] == 0) {
                    continue;
                }
                drawCell(canvas, boardLeft + c * cell, y, cell, row[c] - 1, 1f);
            }
        }
    }

    private void drawCell(Canvas canvas, float x, float y, float size, int paletteIndex, float alpha) {
        int color = paletteIndex >= 0 && paletteIndex < palette.length ? palette[paletteIndex] : Color.GRAY;
        float inset = size * CELL_INSET_RATIO;
        float radius = size * CELL_RADIUS_RATIO;

        rect.set(x + inset, y + inset, x + size - inset, y + size - inset);
        paintCell.setColor(color);
        paintCell.setAlpha((int) (255 * alpha));
        canvas.drawRoundRect(rect, radius, radius, paintCell);

        // 内框：形状的第二识别通道，灰度/色觉障碍场景下仍可辨认每格的边界
        float inner = size * INNER_INSET_RATIO;
        rect.set(x + inner, y + inner, x + size - inner, y + size - inner);
        paintInner.setStrokeWidth(size * INNER_STROKE_RATIO);
        paintInner.setAlpha((int) (255 * alpha * INNER_ALPHA));
        canvas.drawRoundRect(rect, radius * 0.6f, radius * 0.6f, paintInner);

        // 琥珀块与浅色棋盘底对比度不足 3:1，用描边补偿（UI 规格 2.1）
        if (paletteIndex == AMBER_INDEX) {
            rect.set(x + inset, y + inset, x + size - inset, y + size - inset);
            paintAmberStroke.setStrokeWidth(size * AMBER_STROKE_RATIO);
            paintAmberStroke.setAlpha((int) (255 * alpha));
            canvas.drawRoundRect(rect, radius, radius, paintAmberStroke);
        }
    }

    private void drawColumnHint(Canvas canvas) {
        Tetromino piece = engine.getTrayPiece(dragSlot);
        if (piece == null || ghostLeft < 0) {
            return;
        }
        float from = boardLeft + ghostLeft * cell;
        float to = from + piece.width() * cell;
        // 落点非法时整列变红，给出"这一列放不下"的即时反馈（UI 规格 2.2）
        paintColumn.setColor(ghostRow >= 0 ? columnHintColor : ghostInvalidColor);
        paintColumn.setAlpha((int) (255 * COLUMN_ALPHA));
        canvas.drawRect(from, boardTop, to, boardBottom, paintColumn);
    }

    private void drawGhost(Canvas canvas) {
        Tetromino piece = engine.getTrayPiece(dragSlot);
        if (piece == null || ghostLeft < 0) {
            return;
        }
        boolean valid = ghostRow >= 0;
        int color = valid ? ghostValidColor : ghostInvalidColor;
        // 非法时把轮廓画在手指对应的行，明确"是你指的这个位置放不下"，
        // 而不是画到第 0 行误导玩家
        int row = valid ? ghostRow : Math.max(0, ghostDesiredTop);
        drawShapeOutline(canvas, piece.shape(), boardLeft + ghostLeft * cell,
            boardTop + row * cell, cell, color);
    }

    private void drawShapeOutline(Canvas canvas, int[][] shape, float originX, float originY,
                                  float size, int color) {
        float inset = size * CELL_INSET_RATIO;
        float radius = size * CELL_RADIUS_RATIO;
        paintGhostFill.setColor(color);
        paintGhostFill.setAlpha((int) (255 * GHOST_FILL_ALPHA));
        paintGhostStroke.setColor(color);
        paintGhostStroke.setAlpha((int) (255 * GHOST_STROKE_ALPHA));
        paintGhostStroke.setStrokeWidth(size * INNER_STROKE_RATIO);
        for (int r = 0; r < shape.length; r++) {
            for (int c = 0; c < shape[r].length; c++) {
                if (shape[r][c] == 0) {
                    continue;
                }
                float x = originX + c * size;
                float y = originY + r * size;
                rect.set(x + inset, y + inset, x + size - inset, y + size - inset);
                canvas.drawRoundRect(rect, radius, radius, paintGhostFill);
                canvas.drawRoundRect(rect, radius, radius, paintGhostStroke);
            }
        }
    }

    private void drawFlash(Canvas canvas, float radius) {
        if (!flashing || flashRows == null) {
            return;
        }
        long elapsed = SystemClock.uptimeMillis() - flashStart;
        float progress = Math.min(1f, elapsed / (float) flashDurationMs);
        paintFlash.setAlpha((int) (255 * FLASH_ALPHA * (1f - progress)));
        for (int row : flashRows) {
            rect.set(boardLeft, boardTop + row * cell, boardRight, boardTop + (row + 1) * cell);
            canvas.drawRoundRect(rect, radius, radius, paintFlash);
        }
        if (progress >= 1f) {
            flashing = false;
        }
    }

    /** 得分浮字：从消除行中心向上飘起，先弹出再淡出。 */
    private void drawScorePopup(Canvas canvas) {
        if (!popupActive) {
            return;
        }
        long elapsed = SystemClock.uptimeMillis() - popupStart;
        float p = Math.min(1f, elapsed / (float) popupDurationMs);
        if (p >= 1f) {
            popupActive = false;
            return;
        }

        float y = popupY - 44f * density * easeOutCubic(p);
        // 前 12% 淡入，其余淡出
        float alpha = p < 0.12f ? p / 0.12f : 1f - (p - 0.12f) / 0.88f;
        // 先弹出到 1.2 倍再回落到 1.0
        float scale = p < 0.18f
            ? 0.72f + 0.48f * (p / 0.18f)
            : 1.20f - 0.20f * ((p - 0.18f) / 0.82f);

        canvas.save();
        canvas.translate(popupX, y);
        canvas.scale(scale, scale);
        paintPopup.setAlpha((int) (255 * alpha));
        canvas.drawText(popupText, 0f, 0f, paintPopup);
        if (popupSubText != null) {
            paintPopupSub.setAlpha((int) (255 * alpha));
            canvas.drawText(popupSubText, 0f, 20f * density, paintPopupSub);
        }
        canvas.restore();
    }

    private static float easeOutCubic(float t) {
        float inverse = 1f - t;
        return 1f - inverse * inverse * inverse;
    }

    private void drawTray(Canvas canvas, float radius) {
        float slotGap = TRAY_SLOT_GAP_DP * density;
        float slotRadius = radius * 0.75f;
        int traySize = engine.traySize();
        for (int slot = 0; slot < traySize; slot++) {
            float left = trayLeft + slot * (slotWidth + slotGap);
            rect.set(left, trayTop, left + slotWidth, trayTop + slotHeight);
            canvas.drawRoundRect(rect, slotRadius, slotRadius, paintTraySlot);

            Tetromino piece = engine.getTrayPiece(slot);
            if (piece == null) {
                canvas.drawRoundRect(rect, slotRadius, slotRadius, paintTrayEmpty);
                continue;
            }
            if (dragging && slot == dragSlot) {
                // 方块正在跟随手指，槽位留空
                continue;
            }
            int[][] shape = piece.shape();
            float pieceWidth = shape[0].length * cell;
            float pieceHeight = shape.length * cell;
            float padding = slotWidth * 0.14f;
            float scale = Math.min((slotWidth - 2 * padding) / pieceWidth,
                (slotHeight - 2 * padding) / pieceHeight);
            scale = Math.min(scale, 1f);
            float drawnWidth = pieceWidth * scale;
            float drawnHeight = pieceHeight * scale;
            drawPiece(canvas, shape, piece.paletteIndex(),
                left + (slotWidth - drawnWidth) / 2f,
                trayTop + (slotHeight - drawnHeight) / 2f,
                cell * scale, 1f, 0f);
        }
    }

    private void drawPiece(Canvas canvas, int[][] shape, int paletteIndex,
                           float originX, float originY, float size, float alpha, float shadow) {
        if (shadow > 0f) {
            paintCell.setShadowLayer(shadow, 0f, shadow * 0.5f, Color.BLACK);
        }
        for (int r = 0; r < shape.length; r++) {
            for (int c = 0; c < shape[r].length; c++) {
                if (shape[r][c] == 0) {
                    continue;
                }
                drawCell(canvas, originX + c * size, originY + r * size, size, paletteIndex, alpha);
            }
        }
        if (shadow > 0f) {
            paintCell.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        }
    }

    private void drawDraggedPiece(Canvas canvas) {
        Tetromino piece = engine.getTrayPiece(dragSlot);
        if (piece == null) {
            return;
        }
        int[][] shape = piece.shape();
        float size = cell * DRAG_SCALE;
        float width = shape[0].length * size;
        float height = shape.length * size;
        // 上移半格，避免方块被指尖完全遮挡
        float originX = dragX - width / 2f;
        float originY = dragY - height / 2f - cell * DRAG_LIFT;
        drawPiece(canvas, shape, piece.paletteIndex(), originX, originY, size, DRAG_ALPHA, 8f * density);
    }

    private void drawStateOverlay(Canvas canvas, float radius) {
        TetrisEngine.State state = engine.getState();
        if (state != TetrisEngine.State.PAUSED && state != TetrisEngine.State.GAME_OVER) {
            return;
        }
        rect.set(boardLeft, boardTop, boardRight, boardBottom);
        canvas.drawRoundRect(rect, radius, radius, paintDim);
        String text = state == TetrisEngine.State.PAUSED ? pausedText : gameOverText;
        canvas.drawText(text, (boardLeft + boardRight) / 2f,
            (boardTop + boardBottom) / 2f - (paintOverlayText.ascent() + paintOverlayText.descent()) / 2f,
            paintOverlayText);
    }

    // ==================================================================
    // 触摸
    // ==================================================================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!interactive()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return beginDrag(event);
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    updateDrag(event.getX(), event.getY());
                    invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
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
        int slot = traySlotAt(x, y);
        if (slot < 0 || engine.getTrayPiece(slot) == null) {
            return false;
        }
        dragging = true;
        dragSlot = slot;
        moved = false;
        downX = x;
        downY = y;
        dragX = x;
        dragY = y;
        updateTarget(x, y);
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        invalidate();
        return true;
    }

    private void updateDrag(float x, float y) {
        dragX = x;
        dragY = y;
        if (!moved && Math.hypot(x - downX, y - downY) > touchSlop) {
            moved = true;
        }
        updateTarget(x, y);
    }

    private void updateTarget(float x, float y) {
        Tetromino piece = engine.getTrayPiece(dragSlot);
        if (piece == null) {
            ghostRow = -1;
            ghostLeft = -1;
            return;
        }
        int[][] shape = piece.shape();
        int width = shape[0].length;
        int height = shape.length;
        int maxLeft = Math.max(0, engine.getBoardWidth() - width);
        int left = Math.round((x - boardLeft) / cell - width / 2f);
        ghostLeft = Math.max(0, Math.min(maxLeft, left));

        // 进入行由手指高度决定，而不是写死从顶部进入：
        // 这样玩家才能把方块递到已有方块下方的空腔里，而不总是被上方挡住。
        // 用"方块实际绘制的顶部"换算，保证幽灵与手指下方的方块视觉一致。
        float drawnTop = dragY - height * cell * DRAG_SCALE / 2f - cell * DRAG_LIFT;
        ghostDesiredTop = Math.round((drawnTop - boardTop) / cell);
        ghostRow = engine.landingRow(dragSlot, ghostLeft, ghostDesiredTop);
    }

    private void endDrag(float x, float y, long duration) {
        if (!moved && duration <= TAP_TIMEOUT_MS) {
            // 视为点击：旋转（GDD 机制 2）
            if (listener != null) {
                listener.onRotateRequested(dragSlot);
            }
        } else {
            boolean inDropZone = y < trayTop
                && x > boardLeft - cell
                && x < boardRight + cell;
            if (inDropZone && ghostRow >= 0 && listener != null) {
                listener.onPlaceRequested(dragSlot, ghostLeft, ghostDesiredTop);
            } else {
                // 非法放置：零惩罚回弹（设计支柱 P2）
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
        resetDrag();
        invalidate();
    }

    private void resetDrag() {
        dragging = false;
        dragSlot = -1;
        ghostLeft = -1;
        ghostRow = -1;
        moved = false;
    }

    /**
     * 命中的托盘槽位；未命中返回 -1。
     * <p>
     * 命中区按 48dp 最小触控目标向外扩展（UI 规格第 6 条），
     * 但只影响命中判定，不改变绘制尺寸。
     */
    private int traySlotAt(float x, float y) {
        float slotGap = TRAY_SLOT_GAP_DP * density;
        float minHalf = MIN_TOUCH_DP * density / 2f;
        int traySize = engine.traySize();
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
