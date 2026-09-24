package com.template.app.game.tetris.view;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.template.app.R;
import com.template.app.game.tetris.engine.TetrisConfig;
import com.template.app.game.tetris.engine.TetrisEngine;
import com.template.app.game.tetris.model.Tetromino;

/**
 * 棋盘与托盘的全部绘制 + 两套动画计时（消行闪烁、得分浮字）。
 * 不持有 Canvas / View，由 {@link TetrisView#onDraw} 调用 {@link #draw}。
 */
class TetrisRenderer {
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
    private static final float DRAG_ALPHA = 0.95f;
    /**
     * 需要描边补偿的浅色块下标 = 1（O 块）。
     * 该位置现在取挪车色板的「黄」（原为琥珀，同属浅色），与浅色棋盘底对比度不足 3:1，
     * 仍需描边补偿；若色板顺序调整，此处要跟着改。
     */
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

    private final TetrisGeometry geometry;
    private final Resources resources;
    private final float density;

    private int[] palette = new int[0];
    private int columnHintColor;
    private int ghostValidColor;
    private int ghostInvalidColor;
    private String pausedText;
    private String gameOverText;
    private int flashDurationMs = 160;
    private int popupDurationMs = 900;

    private int[] flashRows;
    private long flashStart;
    private boolean flashing;

    private String popupText;
    private String popupSubText;
    private float popupX;
    private float popupY;
    private long popupStart;
    private boolean popupActive;

    TetrisRenderer(Context context, TetrisGeometry geometry) {
        this.geometry = geometry;
        this.resources = context.getResources();
        this.density = geometry.density;

        palette = resources.getIntArray(R.array.tetris_piece_colors);
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

        flashDurationMs = resources.getInteger(R.integer.duration_clear);
        popupDurationMs = resources.getInteger(R.integer.duration_popup);
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
    }

    void draw(Canvas canvas, TetrisEngine engine, DragState drag) {
        float radius = geometry.boardRadius();
        drawBoard(canvas, radius);
        drawCells(canvas, engine);
        if (drag.dragging) {
            drawColumnHint(canvas, engine, drag);
            drawGhost(canvas, engine, drag);
        }
        drawFlash(canvas, radius);
        drawTray(canvas, engine, drag);
        if (drag.dragging) {
            drawDraggedPiece(canvas, engine, drag);
        }
        drawStateOverlay(canvas, engine);
        drawScorePopup(canvas);
    }

    /** 是否有动画在播放，供门面决定是否请求下一帧。 */
    boolean isAnimating() {
        return flashing || popupActive;
    }

    /** 播放消行闪烁动画。 */
    void startClearFlash(int[] rows) {
        if (rows == null || rows.length == 0) {
            return;
        }
        flashRows = rows;
        flashStart = SystemClock.uptimeMillis();
        flashing = true;
    }

    /**
     * 播放得分浮字动画。文字出现在被消除行的中心，向上飘起并淡出。
     *
     * @param gained       本次得分（0 表示未消行，不播放）
     * @param clearedCount 本次消除行数
     * @param combo        本次连击数（< 2 时不展示连击文案）
     * @param clearedRows  被消除的行索引
     */
    void startScorePopup(int gained, int clearedCount, int combo, int[] clearedRows) {
        if (gained <= 0 || clearedRows == null || clearedRows.length == 0) {
            return;
        }
        popupText = "+" + gained;

        // 副标题只在"值得欢呼"时出现，避免每次消 1 行都刷屏
        StringBuilder sub = new StringBuilder();
        if (clearedCount >= 4) {
            sub.append(resources.getString(R.string.tetris_popup_tetris));
        } else if (clearedCount >= 2) {
            sub.append(resources.getString(R.string.tetris_popup_lines, clearedCount));
        }
        if (combo >= 2) {
            if (sub.length() > 0) {
                sub.append("  ");
            }
            sub.append(resources.getString(R.string.tetris_popup_combo, combo));
        }
        popupSubText = sub.length() > 0 ? sub.toString() : null;

        int midRow = clearedRows[clearedRows.length / 2];
        popupX = (geometry.boardLeft + geometry.boardRight) / 2f;
        popupY = geometry.boardTop + (midRow + 0.5f) * geometry.cell;
        popupStart = SystemClock.uptimeMillis();
        popupActive = true;
    }

    private void drawBoard(Canvas canvas, float radius) {
        rect.set(geometry.boardLeft, geometry.boardTop, geometry.boardRight, geometry.boardBottom);
        canvas.drawRoundRect(rect, radius, radius, paintBoardBg);

        for (int c = 1; c < TetrisConfig.BOARD_WIDTH; c++) {
            float x = geometry.boardLeft + c * geometry.cell;
            canvas.drawLine(x, geometry.boardTop + radius, x, geometry.boardBottom - radius, paintGrid);
        }
        for (int r = 1; r < TetrisConfig.BOARD_HEIGHT; r++) {
            float y = geometry.boardTop + r * geometry.cell;
            canvas.drawLine(geometry.boardLeft + radius, y, geometry.boardRight - radius, y, paintGrid);
        }
        canvas.drawRoundRect(rect, radius, radius, paintBoardFrame);
    }

    private void drawCells(Canvas canvas, TetrisEngine engine) {
        int[][] cells = engine.getCells();
        for (int r = 0; r < cells.length; r++) {
            int[] row = cells[r];
            float y = geometry.boardTop + r * geometry.cell;
            for (int c = 0; c < row.length; c++) {
                if (row[c] == 0) {
                    continue;
                }
                drawCell(canvas, geometry.boardLeft + c * geometry.cell, y, geometry.cell, row[c] - 1, 1f);
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

        // 浅色块（O 黄）与浅色棋盘底对比度不足 3:1，用描边补偿（UI 规格 2.1）
        if (paletteIndex == AMBER_INDEX) {
            rect.set(x + inset, y + inset, x + size - inset, y + size - inset);
            paintAmberStroke.setStrokeWidth(size * AMBER_STROKE_RATIO);
            paintAmberStroke.setAlpha((int) (255 * alpha));
            canvas.drawRoundRect(rect, radius, radius, paintAmberStroke);
        }
    }

    private void drawColumnHint(Canvas canvas, TetrisEngine engine, DragState drag) {
        Tetromino piece = engine.getTrayPiece(drag.dragSlot);
        if (piece == null || drag.ghostLeft < 0) {
            return;
        }
        float from = geometry.boardLeft + drag.ghostLeft * geometry.cell;
        float to = from + piece.width() * geometry.cell;
        // 落点非法时整列变红，给出"这一列放不下"的即时反馈（UI 规格 2.2）
        paintColumn.setColor(drag.ghostRow >= 0 ? columnHintColor : ghostInvalidColor);
        paintColumn.setAlpha((int) (255 * COLUMN_ALPHA));
        canvas.drawRect(from, geometry.boardTop, to, geometry.boardBottom, paintColumn);
    }

    private void drawGhost(Canvas canvas, TetrisEngine engine, DragState drag) {
        Tetromino piece = engine.getTrayPiece(drag.dragSlot);
        if (piece == null || drag.ghostLeft < 0) {
            return;
        }
        boolean valid = drag.ghostRow >= 0;
        int color = valid ? ghostValidColor : ghostInvalidColor;
        // 非法时把轮廓画在手指对应的行，明确"是你指的这个位置放不下"，
        // 而不是画到第 0 行误导玩家
        int row = valid ? drag.ghostRow : Math.max(0, drag.ghostDesiredTop);
        drawShapeOutline(canvas, piece.shape(), geometry.boardLeft + drag.ghostLeft * geometry.cell,
            geometry.boardTop + row * geometry.cell, geometry.cell, color);
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
            rect.set(geometry.boardLeft, geometry.boardTop + row * geometry.cell,
                geometry.boardRight, geometry.boardTop + (row + 1) * geometry.cell);
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

    private void drawTray(Canvas canvas, TetrisEngine engine, DragState drag) {
        float slotGap = TetrisGeometry.TRAY_SLOT_GAP_DP * density;
        float slotRadius = geometry.boardRadius() * 0.75f;
        int traySize = engine.traySize();
        for (int slot = 0; slot < traySize; slot++) {
            float left = geometry.trayLeft + slot * (geometry.slotWidth + slotGap);
            rect.set(left, geometry.trayTop, left + geometry.slotWidth, geometry.trayTop + geometry.slotHeight);
            canvas.drawRoundRect(rect, slotRadius, slotRadius, paintTraySlot);

            Tetromino piece = engine.getTrayPiece(slot);
            if (piece == null) {
                canvas.drawRoundRect(rect, slotRadius, slotRadius, paintTrayEmpty);
                continue;
            }
            if (drag.dragging && slot == drag.dragSlot) {
                // 方块正在跟随手指，槽位留空
                continue;
            }
            int[][] shape = piece.shape();
            float pieceWidth = shape[0].length * geometry.cell;
            float pieceHeight = shape.length * geometry.cell;
            float padding = geometry.slotWidth * 0.14f;
            float scale = Math.min((geometry.slotWidth - 2 * padding) / pieceWidth,
                (geometry.slotHeight - 2 * padding) / pieceHeight);
            scale = Math.min(scale, 1f);
            float drawnWidth = pieceWidth * scale;
            float drawnHeight = pieceHeight * scale;
            drawPiece(canvas, shape, piece.paletteIndex(),
                left + (geometry.slotWidth - drawnWidth) / 2f,
                geometry.trayTop + (geometry.slotHeight - drawnHeight) / 2f,
                geometry.cell * scale, 1f, 0f);
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

    private void drawDraggedPiece(Canvas canvas, TetrisEngine engine, DragState drag) {
        Tetromino piece = engine.getTrayPiece(drag.dragSlot);
        if (piece == null) {
            return;
        }
        int[][] shape = piece.shape();
        float size = geometry.cell * TetrisGeometry.DRAG_SCALE;
        float width = shape[0].length * size;
        float height = shape.length * size;
        // 上移半格，避免方块被指尖完全遮挡
        float originX = drag.dragX - width / 2f;
        float originY = drag.dragY - height / 2f - geometry.cell * TetrisGeometry.DRAG_LIFT;
        drawPiece(canvas, shape, piece.paletteIndex(), originX, originY, size, DRAG_ALPHA, 8f * density);
    }

    private void drawStateOverlay(Canvas canvas, TetrisEngine engine) {
        TetrisEngine.State state = engine.getState();
        if (state != TetrisEngine.State.PAUSED && state != TetrisEngine.State.GAME_OVER) {
            return;
        }
        rect.set(geometry.boardLeft, geometry.boardTop, geometry.boardRight, geometry.boardBottom);
        canvas.drawRoundRect(rect, geometry.boardRadius(), geometry.boardRadius(), paintDim);
        String text = state == TetrisEngine.State.PAUSED ? pausedText : gameOverText;
        canvas.drawText(text, (geometry.boardLeft + geometry.boardRight) / 2f,
            (geometry.boardTop + geometry.boardBottom) / 2f
                - (paintOverlayText.ascent() + paintOverlayText.descent()) / 2f,
            paintOverlayText);
    }
}
