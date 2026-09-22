package com.template.app.game.parking.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.template.app.R;
import com.template.app.game.parking.engine.ParkingConfig;
import com.template.app.game.parking.engine.ParkingEngine;
import com.template.app.game.parking.model.Direction;
import com.template.app.game.parking.model.PassengerGroup;
import com.template.app.game.parking.model.Vehicle;

/**
 * 全部绘制与动画计时。
 * <p>
 * 视觉基调来自参考截图：明快的卡通配色、<b>倾斜的菱形车场</b>、
 * 带投影和车顶高光的俯视车辆、顶部一排彩色小人 + "N LEFT" 计数牌 + 虚线接客位。
 * <p>
 * <b>性能约定</b>：{@code onDraw} 内零对象分配——所有 {@link Paint} / {@link RectF} /
 * {@link Path} 都是预分配字段并复用；颜色全部来自资源，Java 中不出现色值字面量。
 */
class ParkingRenderer {

    /** 乘客队列最多画多少个头像，超出的用 "+N" 表示。关卡越深乘客越多，靠 "+N" 兜底。 */
    private static final int MAX_PASSENGER_ICONS = 16;

    private final ParkingGeometry geometry;
    private final int[] passengerColors;
    private final int accentColor;

    private final Paint platformPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signCaptionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint passengerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overflowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slotDashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roofPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint popupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seatTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seatTextOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final RectF inner = new RectF();
    private final Path arrowPath = new Path();
    private final DashPathEffect dash;

    ParkingRenderer(Context context, ParkingGeometry geometry) {
        this.geometry = geometry;
        this.passengerColors = context.getResources().getIntArray(R.array.parking_passenger_colors);
        this.accentColor = ContextCompat.getColor(context, R.color.game_parking_accent);

        float density = geometry.density;
        float dashSize = 4f * density;
        this.dash = new DashPathEffect(new float[]{dashSize, dashSize}, 0f);

        platformPaint.setStyle(Paint.Style.FILL);
        platformPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_queue_bg));

        signPaint.setStyle(Paint.Style.FILL);
        signPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_sign_bg));

        signTextPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_sign_text));
        signTextPaint.setTextAlign(Paint.Align.CENTER);
        signTextPaint.setTextSize(17f * density);
        signTextPaint.setFakeBoldText(true);

        signCaptionPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_sign_text_dim));
        signCaptionPaint.setTextAlign(Paint.Align.CENTER);
        signCaptionPaint.setTextSize(8f * density);
        signCaptionPaint.setFakeBoldText(true);

        overflowPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_sign_text_dim));
        overflowPaint.setTextAlign(Paint.Align.LEFT);
        overflowPaint.setTextSize(12f * density);
        overflowPaint.setFakeBoldText(true);

        slotPaint.setStyle(Paint.Style.FILL);
        slotPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_slot_bg));

        slotDashPaint.setStyle(Paint.Style.STROKE);
        slotDashPaint.setStrokeWidth(1.5f * density);
        slotDashPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_slot_empty));
        slotDashPaint.setPathEffect(dash);

        groundPaint.setStyle(Paint.Style.FILL);
        groundPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_lot_bg));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * density);
        gridPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_lot_grid));

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(1.5f * density);
        framePaint.setColor(ContextCompat.getColor(context, R.color.game_parking_lot_frame));

        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_car_shadow));

        fillPaint.setStyle(Paint.Style.FILL);
        roofPaint.setStyle(Paint.Style.FILL);
        glassPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.2f * density);
        strokePaint.setColor(ContextCompat.getColor(context, R.color.game_parking_vehicle_stroke));

        arrowFillPaint.setStyle(Paint.Style.FILL);
        arrowFillPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_arrow_fill));
        arrowOutlinePaint.setStyle(Paint.Style.STROKE);
        arrowOutlinePaint.setStrokeWidth(1.6f * density);
        arrowOutlinePaint.setColor(ContextCompat.getColor(context, R.color.game_parking_arrow_outline));

        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeWidth(2.5f * density);
        accentPaint.setColor(accentColor);

        popupPaint.setColor(accentColor);
        popupPaint.setTextAlign(Paint.Align.CENTER);
        popupPaint.setTextSize(16f * density);
        popupPaint.setFakeBoldText(true);

        roadPaint.setStyle(Paint.Style.FILL);
        roadPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_road_bg));

        roadLinePaint.setStyle(Paint.Style.STROKE);
        roadLinePaint.setStrokeWidth(2f * density);
        roadLinePaint.setColor(ContextCompat.getColor(context, R.color.game_parking_road_line));
        roadLinePaint.setPathEffect(dash);

        roadArrowPaint.setStyle(Paint.Style.STROKE);
        roadArrowPaint.setStrokeWidth(2.4f * density);
        roadArrowPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_road_arrow));
        roadArrowPaint.setStrokeJoin(Paint.Join.ROUND);

        // 剩余座位数：白字 + 深描边双绘，与方向箭头同一套可辨识策略，
        // 保证在任意车身配色（含琥珀、黄等浅色车）上都读得清。
        seatTextOutlinePaint.setStyle(Paint.Style.STROKE);
        seatTextOutlinePaint.setStrokeWidth(3f * density);
        seatTextOutlinePaint.setColor(
            ContextCompat.getColor(context, R.color.game_parking_vehicle_stroke));
        seatTextOutlinePaint.setTextAlign(Paint.Align.CENTER);
        seatTextOutlinePaint.setTextSize(11f * density);
        seatTextOutlinePaint.setFakeBoldText(true);

        seatTextPaint.setStyle(Paint.Style.FILL);
        seatTextPaint.setColor(ContextCompat.getColor(context, R.color.game_parking_arrow_fill));
        seatTextPaint.setTextAlign(Paint.Align.CENTER);
        seatTextPaint.setTextSize(11f * density);
        seatTextPaint.setFakeBoldText(true);
    }

    // ==================================================================
    // 入口
    // ==================================================================

    void draw(Canvas canvas, ParkingEngine engine, ParkingAnimator anim, boolean removeMode) {
        long now = SystemClock.uptimeMillis();
        if (!anim.isMoveAnimating(now)) {
            anim.clearMove();
        }
        if (!anim.isExitAnimating(now)) {
            anim.clearExit();
        }
        anim.tickChoreography(now);
        drawQueue(canvas, engine, anim, now);
        drawPickup(canvas, engine, anim);
        drawRoad(canvas);
        drawGround(canvas);
        drawVehicles(canvas, engine, anim, removeMode);
        drawExitingVehicle(canvas, anim, now);
        drawBoardingCar(canvas, anim, now);
        drawPopup(canvas, anim, now);
    }

    boolean isAnimating(ParkingAnimator anim) {
        return anim.isAnimating(SystemClock.uptimeMillis());
    }

    // ==================================================================
    // 乘客队列
    // ==================================================================

    private void drawQueue(Canvas canvas, ParkingEngine engine, ParkingAnimator anim, long now) {
        float top = geometry.queueTop;
        float height = geometry.queueHeight;
        float radius = 12f * geometry.density;
        rect.set(geometry.stripLeft, top, geometry.stripLeft + geometry.stripWidth, top + height);
        canvas.drawRoundRect(rect, radius, radius, platformPaint);

        // 左侧的 "N LEFT" 计数牌
        float signWidth = 54f * geometry.density;
        float signLeft = geometry.stripLeft + 6f * geometry.density;
        rect.set(signLeft, top + 6f * geometry.density,
            signLeft + signWidth, top + height - 6f * geometry.density);
        canvas.drawRoundRect(rect, 8f * geometry.density, 8f * geometry.density, signPaint);
        float signCenterX = rect.centerX();
        canvas.drawText(String.valueOf(engine.getPassengersLeft()), signCenterX,
            rect.centerY() - 1f * geometry.density, signTextPaint);
        canvas.drawText("LEFT", signCenterX, rect.bottom - 5f * geometry.density, signCaptionPaint);

        // 乘客：圆头 + 彩色身体，同色成团
        float icon = (height - 16f * geometry.density) * 0.62f;
        float step = icon + 3f * geometry.density;
        float groupGap = 9f * geometry.density;
        float baseline = top + height * 0.56f;
        float startX = signLeft + signWidth + 10f * geometry.density;

        // 正在上客：把"还没上车的乘客"作为半透明小人放在<b>队首（左边）</b>，
        // 随上车从左到右一个个消失——视觉上就是"队列最前的人正在上车"。
        int pending = 0;
        int pendingColor = 0;
        int boardCount = 0;
        if (anim.isBoardingActive() && !anim.isLeaving(now)) {
            boardCount = anim.boardCount();
            int boarded = anim.boardedSoFar(now);
            pending = boardCount - boarded;
            pendingColor = anim.boardColorIndex();
        }
        if (pending > 0) {
            // 固定占用队首 [startX, startX + boardCount*step] 这一段；
            // 已上车的留在最左，未上车的排在右侧并向中心收缩，最靠近车的先被接走。
            float clusterRight = startX + boardCount * step;
            float px = clusterRight - pending * step;
            passengerPaint.setColor(passengerColors[pendingColor]);
            passengerPaint.setAlpha(178);
            for (int k = 0; k < pending; k++) {
                drawPassenger(canvas, px, baseline, icon);
                px += step;
            }
            passengerPaint.setAlpha(255);
        }

        float x = startX + (boardCount > 0 ? boardCount * step + groupGap : 0f);
        int drawn = 0;
        int frontDrawn = 0;
        for (int i = 0; i < engine.queueSize() && drawn < MAX_PASSENGER_ICONS; i++) {
            PassengerGroup group = engine.queueGroupAt(i);
            int before = drawn;
            for (int k = 0; k < group.count && drawn < MAX_PASSENGER_ICONS; k++) {
                passengerPaint.setColor(passengerColors[group.colorIndex]);
                drawPassenger(canvas, x, baseline, icon);
                x += step;
                drawn++;
            }
            if (i == 0) {
                frontDrawn = drawn - before;
            }
            x += groupGap;
        }

        // 队首高亮：覆盖"正在上客的小人 + 真实队首"，明确"现在要接的是它"
        int highlightCount = (pending > 0 ? boardCount : 0) + frontDrawn;
        if (highlightCount > 0) {
            float lineY = top + height - 5f * geometry.density;
            canvas.drawLine(startX, lineY, startX + highlightCount * step, lineY, accentPaint);
        }

        int remaining = engine.getPassengersLeft() - drawn;
        if (remaining > 0) {
            canvas.drawText("+" + remaining, Math.min(x,
                    geometry.stripLeft + geometry.stripWidth - 22f * geometry.density),
                baseline + icon * 0.3f, overflowPaint);
        }
    }

    /** 一个乘客：圆头 + 圆角身体，同色成团；形状与颜色无关，去色后依然可数。 */
    private void drawPassenger(Canvas canvas, float left, float baseline, float size) {
        float radius = size * 0.5f;
        canvas.drawCircle(left + radius, baseline - radius * 0.95f, radius * 0.42f, passengerPaint);
        rect.set(left + radius * 0.24f, baseline - radius * 0.5f,
            left + size - radius * 0.24f, baseline + radius * 0.8f);
        canvas.drawRoundRect(rect, radius * 0.45f, radius * 0.45f, passengerPaint);
    }

    // ==================================================================
    // 接客区
    // ==================================================================

    private void drawPickup(Canvas canvas, ParkingEngine engine, ParkingAnimator anim) {
        float top = geometry.pickupTop;
        float height = geometry.pickupHeight;
        float radius = 10f * geometry.density;
        int exitVehicleId = anim.exitVehicle == null ? -1 : anim.exitVehicle.id;

        for (int i = 0; i < ParkingConfig.PICKUP_SLOTS; i++) {
            float left = geometry.slotLeft(i);
            rect.set(left, top, left + geometry.slotWidth, top + height);
            canvas.drawRoundRect(rect, radius, radius, slotPaint);
            canvas.drawRoundRect(rect, radius, radius, slotDashPaint);
        }

        // 每辆车画在<b>它自己的固定车位</b>上（进入时分配、离开前不变），
        // 不再按顺序紧凑排列——否则前面的车一走，后面的车就会集体左移。
        for (int i = 0; i < engine.vehicleCount(); i++) {
            Vehicle v = engine.vehicleAt(i);
            if (!v.inPickup() || v.id == exitVehicleId) {
                continue;
            }
            if (v.slot < 0 || v.slot >= ParkingConfig.PICKUP_SLOTS) {
                continue;
            }
            float left = geometry.slotLeft(v.slot);
            float centerX = left + geometry.slotWidth / 2f;
            float centerY = top + height / 2f;
            float carWidth = geometry.slotWidth * 0.8f;
            float carHeight = height * 0.5f;
            drawCarShape(canvas, Direction.RIGHT, v.colorIndex, centerX, centerY,
                0f, 1f, 1f, carWidth, carHeight);
            // 未满载：标出还差几位。车必须装满才开走，这个数字就是它此刻还在等的人数。
            if (!v.isFull()) {
                drawSeatBadge(canvas, v.remainingCapacity(), centerX, centerY,
                    carWidth, carHeight, 0f);
            }
        }

        // 已接客、但动画还没轮到播放的车：继续画在它们各自的接客位上。
        // 引擎一次性把它们置为 GONE，而离场动画是排队逐辆播放的——
        // 不补画这一段，没轮到的车就会凭空消失，等轮到自己才突然冒出来。
        // 尺寸与"停放时"一致（接客位尺度），避免轮到播放时出现大小跳变。
        for (int i = 0; i < anim.waitingBoardCount(); i++) {
            ParkingAnimator.LeavingCar waiting = anim.waitingBoardAt(i);
            // 刚驶出、正在播放"飞向接客位"的那辆由 drawExitingVehicle 绘制，
            // 这里跳过它，否则会同时出现一个静止影像和一个飞行动影。
            if (waiting.vehicleId == exitVehicleId) {
                continue;
            }
            drawCarShape(canvas, waiting.direction, waiting.colorIndex,
                waiting.fromX, waiting.fromY, waiting.spotAngle, 1f, 1f,
                geometry.slotWidth * 0.8f, height * 0.5f);
        }
    }

    /**
     * 在车身右上角标出<b>剩余座位数</b>（还差几位才能开走）。
     * <p>
     * 两处都会画：接客区里没装满的车（它还在等客，玩家要一眼看出"还差几个"），
     * 以及停车场里的车（尚未上客，剩余座位 == 它的座位数，等于把"小汽车 2 座 / 巴士 3 座"显式化）。
     * 满载的车不画。
     *
     * @param angleDegrees 车身倾角，角标随之旋转——否则停车场（整体倾斜）里的车
     *                     角标会浮在车身之外。
     */
    private void drawSeatBadge(Canvas canvas, int remaining, float centerX, float centerY,
                               float width, float height, float angleDegrees) {
        canvas.save();
        canvas.rotate(angleDegrees, centerX, centerY);
        float bx = centerX + width * 0.28f;
        float by = centerY - height * 0.30f;
        // 基线垂直居中：ascent 为负、descent 为正，取二者中值抵消
        float baseline = by - (seatTextPaint.ascent() + seatTextPaint.descent()) / 2f;
        String text = String.valueOf(remaining);
        canvas.drawText(text, bx, baseline, seatTextOutlinePaint);
        canvas.drawText(text, bx, baseline, seatTextPaint);
        canvas.restore();
    }

    // ==================================================================
    // 横向马路（接客区与停车场之间）
    // ==================================================================

    private void drawRoad(Canvas canvas) {
        float left = geometry.roadLeft;
        float top = geometry.roadTop;
        float right = left + geometry.roadWidth;
        float bottom = top + geometry.roadHeight;
        float radius = 10f * geometry.density;
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, roadPaint);

        // 中线：虚线车道分隔线
        float midY = top + (bottom - top) / 2f;
        canvas.drawLine(left + 6f * geometry.density, midY,
            right - 6f * geometry.density, midY, roadLinePaint);

        // 向右的雪佛龙箭头，明确"出口在右"
        float arrowSize = geometry.roadHeight * 0.22f;
        float step = arrowSize * 2.6f;
        for (float x = left + geometry.roadWidth * 0.5f; x < right - arrowSize; x += step) {
            canvas.drawLine(x, midY - arrowSize, x + arrowSize, midY, roadArrowPaint);
            canvas.drawLine(x + arrowSize, midY, x, midY + arrowSize, roadArrowPaint);
        }
    }

    // ==================================================================
    // 停车场（倾斜）
    // ==================================================================

    private void drawGround(Canvas canvas) {
        canvas.save();
        canvas.rotate(ParkingGeometry.ROTATION_DEGREES, geometry.centerX, geometry.centerY);
        float radius = 16f * geometry.density;
        rect.set(geometry.boardLeft, geometry.boardTop,
            geometry.boardLeft + geometry.boardWidth(),
            geometry.boardTop + geometry.boardHeight());
        canvas.drawRoundRect(rect, radius, radius, groundPaint);
        canvas.drawRoundRect(rect, radius, radius, framePaint);

        for (int c = 1; c < ParkingConfig.COLUMNS; c++) {
            float x = geometry.vehicleLeft(c);
            canvas.drawLine(x, geometry.boardTop, x,
                geometry.boardTop + geometry.boardHeight(), gridPaint);
        }
        for (int r = 1; r < ParkingConfig.ROWS; r++) {
            float y = geometry.vehicleTop(r);
            canvas.drawLine(geometry.boardLeft, y,
                geometry.boardLeft + geometry.boardWidth(), y, gridPaint);
        }
        canvas.restore();
    }

    private void drawVehicles(Canvas canvas, ParkingEngine engine,
                              ParkingAnimator anim, boolean removeMode) {
        for (int i = 0; i < engine.vehicleCount(); i++) {
            Vehicle v = engine.vehicleAt(i);
            if (!v.inLot() || anim.moveVehicleId() == v.id) {
                continue;
            }
            float row = v.row;
            float col = v.col;
            if (anim.dragging && anim.dragVehicleId == v.id) {
                row += anim.dragCells * v.direction.dRow;
                col += anim.dragCells * v.direction.dCol;
            }
            geometry.vehicleScreenCenter(row, col, v.length, v.horizontal);
            float cx = geometry.tmpX;
            float cy = geometry.tmpY;
            float vw = geometry.vehicleWidth(v.length, v.horizontal);
            float vh = geometry.vehicleHeight(v.length, v.horizontal);
            drawCarShape(canvas, v.direction, v.colorIndex, cx, cy,
                ParkingGeometry.ROTATION_DEGREES, 1f, removeMode ? 1.06f : 1f, vw, vh);
            // 停车场里的车尚未上客，剩余座位即它的座位数：小汽车 2、巴士 3。
            // 角标必须跟着棋盘倾角旋转，否则会浮在车身之外。
            if (!v.isFull()) {
                drawSeatBadge(canvas, v.remainingCapacity(), cx, cy, vw, vh,
                    ParkingGeometry.ROTATION_DEGREES);
            }
        }
    }

    /**
     * 驶出动画：车先沿车头方向冲出场地（途经点），再拐进接客位，
     * 同时车身从棋盘倾角转正——"车开走了"的关键观感。
     */
    private void drawExitingVehicle(Canvas canvas, ParkingAnimator anim, long now) {
        if (anim.exitVehicle == null) {
            return;
        }
        float progress = Math.min(1f, (now - anim.exitStart) / (float) ParkingAnimator.EXIT_DURATION_MS);
        float eased = easeInOutCubic(progress);
        float inverse = 1f - eased;

        // 二次贝塞尔：起点 S → 途经点 C（冲出场地）→ 终点 E（接客位）
        float x = inverse * inverse * anim.exitFromX
            + 2f * inverse * eased * anim.exitFromX2
            + eased * eased * anim.exitToX;
        float y = inverse * inverse * anim.exitFromY
            + 2f * inverse * eased * anim.exitFromY2
            + eased * eased * anim.exitToY;
        float angle = anim.exitFromAngle * (1f - eased);
        float alpha = anim.exitParked ? 1f : (progress < 0.55f ? 1f : 1f - (progress - 0.55f) / 0.45f);

        Vehicle v = anim.exitVehicle;
        drawCarShape(canvas, v.direction, v.colorIndex, x, y, angle, alpha, 1f,
            geometry.vehicleWidth(v.length, v.horizontal),
            geometry.vehicleHeight(v.length, v.horizontal));
    }

    /**
     * 接客离场编排的当前车：先在上客点"乘客逐个上车"（车里的小人一个个多起来），
     * 再"一辆辆开走"（驶向屏幕外并转正淡出）。
     * <p>
     * 引擎里这辆车早已是 {@code GONE}，这里完全由动画状态机驱动，是一个"影子"实体。
     */
    private void drawBoardingCar(Canvas canvas, ParkingAnimator anim, long now) {
        if (!anim.isBoardingActive()) {
            return;
        }
        int phase = anim.boardPhase(now);
        float x, y, angle, alpha;
        if (phase == 0) {
            // 上客：就地在这辆车自己的接客位进行（起点与上客点重合，不再横滑）
            float fx = anim.boardFromX();
            float fy = anim.boardFromY();
            float tx = anim.boardSpotX();
            float ty = anim.boardSpotY();
            float slide = easeInOutCubic(anim.boardSlideProgress(now));
            x = lerp(fx, tx, slide);
            y = lerp(fy, ty, slide);
            angle = anim.boardSpotAngle();
            alpha = 1f;
        } else if (phase == 1) {
            // 驶下马路：从接客位垂直落到车道
            float t = easeInOutCubic(anim.roadEnterProgress(now));
            x = lerp(anim.boardSpotX(), anim.boardRoadX(), t);
            y = lerp(anim.boardSpotY(), anim.boardRoadY(), t);
            angle = lerp(anim.boardSpotAngle(), 0f, t);
            alpha = 1f;
        } else {
            // 马路上自左向右开走并淡出
            float t = easeInOutCubic(anim.exitProgress(now));
            x = lerp(anim.boardRoadX(), anim.boardLeaveX(), t);
            y = lerp(anim.boardRoadY(), anim.boardLeaveY(), t);
            angle = anim.boardLeaveAngle();
            alpha = t < 0.6f ? 1f : 1f - (t - 0.6f) / 0.4f;
        }

        drawCarShape(canvas, anim.boardDirection(), anim.boardColorIndex(), x, y,
            angle, alpha, 1f, anim.boardWidth(), anim.boardHeight());

        // 已上车的乘客：车内沿长轴排开的小人（驶下与开走阶段为满载）
        int aboard = anim.boardedSoFar(now);
        if (aboard > 0) {
            drawPassengersInCar(canvas, x, y, angle, aboard,
                anim.boardWidth(), anim.boardHeight());
        }
    }

    /** 在车厢内沿长轴画 count 个白色小人，表示已上车的乘客。 */
    private void drawPassengersInCar(Canvas canvas, float cx, float cy, float angle,
                                     int count, float width, float height) {
        canvas.save();
        canvas.rotate(angle, cx, cy);
        boolean horizontal = width >= height;
        float span = (horizontal ? width : height) * 0.46f;
        float r = Math.min(width, height) * 0.13f;
        passengerPaint.setColor(0xFFFFFFFF);
        passengerPaint.setAlpha(235);
        for (int i = 0; i < count; i++) {
            float t = count == 1 ? 0f : (i / (float) (count - 1) - 0.5f);
            float px = cx + (horizontal ? t * span : 0f);
            float py = cy + (horizontal ? 0f : t * span);
            canvas.drawCircle(px, py, r, passengerPaint);
        }
        passengerPaint.setAlpha(255);
        canvas.restore();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ==================================================================
    // 车辆
    // ==================================================================

    /**
     * 画一辆车：投影 + 车身 + 车顶高光 + 前挡风 + 描边 + 白色箭头。
     * 以 (centerX, centerY) 为中心，整体旋转 angleDegrees。
     *
     * @param width  车身总宽（横向车 = 长，竖直车 = 短）
     * @param height 车身总高
     */
    private void drawCarShape(Canvas canvas, Direction direction, int colorIndex,
                              float centerX, float centerY, float angleDegrees,
                              float alpha, float scale, float width, float height) {
        float inset = ParkingGeometry.VEHICLE_INSET_DP * geometry.density;
        float bodyWidth = (width - inset * 2f) * scale;
        float bodyHeight = (height - inset * 2f) * scale;
        float left = centerX - bodyWidth / 2f;
        float top = centerY - bodyHeight / 2f;
        float radius = Math.min(bodyWidth, bodyHeight) * 0.3f;

        int color = passengerColors[colorIndex];

        canvas.save();
        canvas.rotate(angleDegrees, centerX, centerY);

        // 投影：往右下偏一点，给车辆"放在地上"的体积感
        float shadowDrop = 3f * geometry.density * scale;
        rect.set(left + shadowDrop * 0.6f, top + shadowDrop,
            left + bodyWidth + shadowDrop * 0.6f, top + bodyHeight + shadowDrop);
        shadowPaint.setAlpha((int) (255f * alpha));
        canvas.drawRoundRect(rect, radius, radius, shadowPaint);

        // 车身
        rect.set(left, top, left + bodyWidth, top + bodyHeight);
        fillPaint.setColor(color);
        fillPaint.setAlpha((int) (255f * alpha));
        canvas.drawRoundRect(rect, radius, radius, fillPaint);

        // 沿车身长轴的量 / 短轴的量
        boolean horizontal = bodyWidth >= bodyHeight;
        float along = horizontal ? bodyWidth : bodyHeight;
        float across = horizontal ? bodyHeight : bodyWidth;
        float sign = forwardSign(direction);
        float roofAlong = along * 0.60f;
        float roofAcross = across * 0.64f;
        float roofStart = sign >= 0 ? along - roofAlong : 0f;

        // 车顶高光：往车头方向偏移的浅色块，形成顶光
        roofPaint.setColor(ColorUtils.blendARGB(color, 0xFFFFFFFF, 0.30f));
        roofPaint.setAlpha((int) (235f * alpha));
        placeAlong(horizontal, left, top, across, roofStart, roofAlong, roofAcross);
        canvas.drawRoundRect(inner, radius * 0.7f, radius * 0.7f, roofPaint);

        // 前挡风：车顶靠车头一侧的深色横带，读出"车头在哪"
        glassPaint.setColor(ColorUtils.blendARGB(color, 0xFF000000, 0.32f));
        glassPaint.setAlpha((int) (220f * alpha));
        float bandAlong = along * 0.14f;
        float bandStart = sign >= 0
            ? Math.max(0f, roofStart - bandAlong * 1.1f)
            : Math.min(along - bandAlong, roofAlong + bandAlong * 0.1f);
        placeAlong(horizontal, left, top, across, bandStart, bandAlong, across * 0.74f);
        canvas.drawRoundRect(inner, bandAlong * 0.35f, bandAlong * 0.35f, glassPaint);

        // 描边
        strokePaint.setAlpha((int) (255f * alpha));
        rect.set(left, top, left + bodyWidth, top + bodyHeight);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);

        // 方向箭头：白填充 + 深描边双绘，保证在任意色块上可辨识
        float size = Math.min(bodyWidth, bodyHeight) * 0.46f;
        drawArrow(canvas, centerX, centerY, size, direction, alpha);

        fillPaint.setAlpha(255);
        roofPaint.setAlpha(255);
        glassPaint.setAlpha(255);
        strokePaint.setAlpha(255);
        shadowPaint.setAlpha(255);
        canvas.restore();
    }

    /** 在 (left, top) 起点的车身矩形内，沿长轴放置一个 [start, start+alongSize] 的子矩形。 */
    private void placeAlong(boolean horizontal, float left, float top,
                            float across, float start, float alongSize, float acrossSize) {
        if (horizontal) {
            inner.set(left + start, top + (across - acrossSize) / 2f,
                left + start + alongSize, top + (across + acrossSize) / 2f);
        } else {
            inner.set(left + (across - acrossSize) / 2f, top + start,
                left + (across + acrossSize) / 2f, top + start + alongSize);
        }
    }

    /** 车顶高光往车头方向偏移：+1 = 朝正方向，-1 = 朝负方向。 */
    private static float forwardSign(Direction direction) {
        return (direction.dRow + direction.dCol) >= 0 ? 1f : -1f;
    }

    /**
     * 画方向箭头：白色填充 + 深色描边双绘。
     * <p>
     * 双绘是为了浅色车（如琥珀、黄）——描边保证白箭头在任何色块上都可辨识。
     */
    private void drawArrow(Canvas canvas, float cx, float cy, float size,
                           Direction direction, float alpha) {
        canvas.save();
        canvas.rotate(direction.arrowDegrees(), cx, cy);
        arrowPath.rewind();

        float shaft = size * 0.11f;
        arrowPath.moveTo(cx - shaft, cy + size * 0.5f);
        arrowPath.lineTo(cx - shaft, cy - size * 0.05f);
        arrowPath.lineTo(cx + shaft, cy - size * 0.05f);
        arrowPath.lineTo(cx + shaft, cy + size * 0.5f);
        arrowPath.close();

        arrowPath.moveTo(cx - size * 0.3f, cy - size * 0.02f);
        arrowPath.lineTo(cx, cy - size * 0.48f);
        arrowPath.lineTo(cx + size * 0.3f, cy - size * 0.02f);
        arrowPath.close();

        arrowFillPaint.setAlpha((int) (255f * alpha));
        arrowOutlinePaint.setAlpha((int) (255f * alpha));
        canvas.drawPath(arrowPath, arrowFillPaint);
        canvas.drawPath(arrowPath, arrowOutlinePaint);
        arrowFillPaint.setAlpha(255);
        arrowOutlinePaint.setAlpha(255);
        canvas.restore();
    }

    // ==================================================================
    // 接客浮字
    // ==================================================================

    private void drawPopup(Canvas canvas, ParkingAnimator anim, long now) {
        if (!anim.isPopupAnimating(now)) {
            return;
        }
        float progress = (now - anim.popupStart) / (float) ParkingAnimator.POPUP_DURATION_MS;
        float alpha = Math.max(0f, 1f - progress);
        float rise = 26f * geometry.density * easeOutCubic(progress);
        popupPaint.setAlpha((int) (255f * alpha));
        canvas.drawText("+" + anim.popupValue,
            geometry.stripLeft + geometry.stripWidth / 2f,
            geometry.pickupTop + geometry.pickupHeight * 0.62f - rise,
            popupPaint);
        popupPaint.setAlpha(255);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static float easeOutCubic(float t) {
        float inverse = 1f - t;
        return 1f - inverse * inverse * inverse;
    }

    private static float easeInOutCubic(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3f) / 2f;
    }
}
