package com.template.app.game.parking.view;

import com.template.app.game.parking.engine.ParkingConfig;

/**
 * 布局几何与坐标换算。
 * <p>
 * <b>本作棋盘是倾斜的</b>（对应参考截图：停车场是一块旋转过的菱形场地，
 * 车头朝着各个方向斜着停）。绘制时对棋盘整体做一次刚体旋转，
 * 触摸时做一次逆旋转——纯旋转不改变长度，所以描边粗细、命中区都不需要额外补偿。
 * <p>
 * 乘客队列与接客区<b>不参与旋转</b>，它们是水平的两条横带。
 * <p>
 * 刻意不持有任何 {@code Canvas} / {@code Paint}——它是一份纯计算。
 */
class ParkingGeometry {

    /** 棋盘旋转角，负值为逆时针。参考截图约为 -25°，取 -22° 兼顾观感与可读性。 */
    static final float ROTATION_DEGREES = -22f;

    /**
     * 棋盘旋转后外接盒：8×8 约 10.4 格，10×10 约 13.0 格。
     * 格子下限放宽到 22dp——棋盘变大后若仍用 26dp，小屏（约 360dp 宽）上
     * 外接盒会超出可用宽度而被裁切；22dp 保证 13 格 × 22dp ≈ 286dp 仍能放下。
     */
    static final float MIN_CELL_DP = 22f;

    static final float QUEUE_HEIGHT_DP = 46f;
    static final float PICKUP_HEIGHT_DP = 62f;
    static final float ROAD_HEIGHT_DP = 44f;
    static final float GAP_DP = 10f;
    static final float SLOT_GAP_DP = 6f;

    /** 车身在格子内的内缩量：越大车看起来越小、格子间的缝隙越明显。 */
    static final float VEHICLE_INSET_DP = 3.2f;

    static final float MIN_TOUCH_DP = 44f;

    final float density;

    /** 单格边长。 */
    float cell;

    /** 棋盘旋转中心 = 棋盘几何中心。 */
    float centerX;
    float centerY;

    /** 未旋转坐标系下棋盘的左上角。 */
    float boardLeft;
    float boardTop;

    /** 水平横带（队列 / 接客区）的范围，与旋转后棋盘的外接盒等宽。 */
    float stripLeft;
    float stripWidth;

    float queueTop;
    float queueHeight;
    float pickupTop;
    float pickupHeight;
    float slotWidth;

    /** 接客区与停车场之间的横向马路：左右贯通，与队列/接客带等宽。 */
    float roadTop;
    float roadHeight;
    float roadLeft;
    float roadWidth;
    float roadCenterY;

    /** 复用的输出槽：几何方法把计算结果写到这里，避免在绘制路径上分配对象。 */
    float tmpX;
    float tmpY;

    ParkingGeometry(float density) {
        this.density = density;
    }

    void layout(int width, int height, int padLeft, int padTop, int padRight, int padBottom) {
        float contentWidth = width - padLeft - padRight;
        float contentHeight = height - padTop - padBottom;
        queueHeight = QUEUE_HEIGHT_DP * density;
        pickupHeight = PICKUP_HEIGHT_DP * density;
        float gap = GAP_DP * density;
        queueTop = padTop;
        pickupTop = queueTop + queueHeight + gap;
        // 注意：必须写入同名字段（不要写成局部变量），否则 roadTop/roadHeight 始终为 0，
        // 会让 drawRoad 的雪佛龙箭头循环 step=0 陷入死循环 → 主线程 ANR。
        roadTop = pickupTop + pickupHeight + gap;
        roadHeight = ROAD_HEIGHT_DP * density;
        float lotTop = roadTop + roadHeight + gap;
        float availableHeight = contentHeight - (lotTop - padTop);

        double radians = Math.toRadians(Math.abs(ROTATION_DEGREES));
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        // 旋转后外接盒（以格数为单位）：W·cos + H·sin / W·sin + H·cos
        float spanWidth = ParkingConfig.COLUMNS * cos + ParkingConfig.ROWS * sin;
        float spanHeight = ParkingConfig.COLUMNS * sin + ParkingConfig.ROWS * cos;
        cell = Math.min(contentWidth / spanWidth, availableHeight / spanHeight);
        float minCell = MIN_CELL_DP * density;
        if (cell < minCell) {
            cell = minCell;
        }

        float boardWidth = ParkingConfig.COLUMNS * cell;
        float boardHeight = ParkingConfig.ROWS * cell;
        float bboxWidth = boardWidth * cos + boardHeight * sin;
        float bboxHeight = boardWidth * sin + boardHeight * cos;

        centerX = padLeft + contentWidth / 2f;
        centerY = lotTop + bboxHeight / 2f;
        boardLeft = centerX - boardWidth / 2f;
        boardTop = centerY - boardHeight / 2f;

        stripLeft = centerX - bboxWidth / 2f;
        stripWidth = bboxWidth;
        slotWidth = (stripWidth - SLOT_GAP_DP * density * (ParkingConfig.PICKUP_SLOTS - 1))
            / ParkingConfig.PICKUP_SLOTS;

        roadLeft = stripLeft;
        roadWidth = stripWidth;
        roadCenterY = roadTop + roadHeight / 2f;
    }

    // ==================================================================
    // 坐标换算（结果写入 tmpX / tmpY）
    // ==================================================================

    /** 棋盘坐标 → 屏幕坐标。 */
    void toScreen(float boardX, float boardY) {
        double radians = Math.toRadians(ROTATION_DEGREES);
        float dx = boardX - centerX;
        float dy = boardY - centerY;
        tmpX = (float) (centerX + dx * Math.cos(radians) - dy * Math.sin(radians));
        tmpY = (float) (centerY + dx * Math.sin(radians) + dy * Math.cos(radians));
    }

    /** 屏幕坐标 → 棋盘坐标（旋转的逆变换）。 */
    void toBoard(float screenX, float screenY) {
        double radians = Math.toRadians(-ROTATION_DEGREES);
        float dx = screenX - centerX;
        float dy = screenY - centerY;
        tmpX = (float) (centerX + dx * Math.cos(radians) - dy * Math.sin(radians));
        tmpY = (float) (centerY + dx * Math.sin(radians) + dy * Math.cos(radians));
    }

    /** 计算一辆车的中心在屏幕上的位置。 */
    void vehicleScreenCenter(float row, float col, int length, boolean horizontal) {
        float halfWidth = (horizontal ? length : 1) * cell / 2f;
        float halfHeight = (horizontal ? 1 : length) * cell / 2f;
        toScreen(boardLeft + col * cell + halfWidth, boardTop + row * cell + halfHeight);
    }

    // ==================================================================
    // 布局换算
    // ==================================================================

    float boardWidth() {
        return ParkingConfig.COLUMNS * cell;
    }

    /**
     * 场景（队列带 + 接客带 + 间隙 + 倾斜棋盘外接盒）在最小格子下的高度下限，单位像素。
     * 供 {@code onMeasure} 估算最小高度。
     */
    float minSceneHeightPx() {
        double radians = Math.toRadians(Math.abs(ROTATION_DEGREES));
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float spanHeight = (ParkingConfig.COLUMNS * sin + ParkingConfig.ROWS * cos)
            * MIN_CELL_DP * density;
        return (QUEUE_HEIGHT_DP + PICKUP_HEIGHT_DP + ROAD_HEIGHT_DP + GAP_DP * 3f) * density + spanHeight;
    }

    float boardHeight() {
        return ParkingConfig.ROWS * cell;
    }

    float slotLeft(int index) {
        return stripLeft + index * (slotWidth + SLOT_GAP_DP * density);
    }

    /** 第 index 个接客位中心的横坐标。 */
    float slotCenterX(int index) {
        return slotLeft(index) + slotWidth / 2f;
    }

    /** 接客位中心的纵坐标（所有车位同一行）。 */
    float slotCenterY() {
        return pickupTop + pickupHeight / 2f;
    }

    /** 把棋盘坐标系下的方向向量旋转到屏幕坐标系，结果写入 tmpX / tmpY。 */
    void rotateVector(float dx, float dy) {
        double radians = Math.toRadians(ROTATION_DEGREES);
        tmpX = (float) (dx * Math.cos(radians) - dy * Math.sin(radians));
        tmpY = (float) (dx * Math.sin(radians) + dy * Math.cos(radians));
    }

    float vehicleLeft(float col) {
        return boardLeft + col * cell;
    }

    float vehicleTop(float row) {
        return boardTop + row * cell;
    }

    float vehicleWidth(int length, boolean horizontal) {
        return (horizontal ? length : 1) * cell;
    }

    float vehicleHeight(int length, boolean horizontal) {
        return (horizontal ? 1 : length) * cell;
    }

    int rowAt(float boardY) {
        return (int) ((boardY - boardTop) / cell);
    }

    int colAt(float boardX) {
        return (int) ((boardX - boardLeft) / cell);
    }
}
