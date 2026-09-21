package com.template.app.game.parking.engine;

import com.template.app.game.parking.model.Direction;

/**
 * 一次操作中"接客并离场"的一辆车。
 * <p>
 * 引擎在 {@code resolvePickup()} 里把接客的车瞬时置为 {@code GONE}，但为了把"乘客逐个上车、
 * 车一辆辆开走"做成可见动画，需要把<b>发生了什么</b>如实告诉渲染层，而不只是最终的 {@code boarded} 计数。
 * <p>
 * 纯数据载体，零 {@code android.*} 依赖（ADR-003）。屏幕坐标由 View 在播放时按几何换算，
 * 这里只描述语义。
 */
public final class BoardStep {

    /** 接客的车辆 id（离场后已不可在引擎里查到，故把它需要的属性一并带上）。 */
    public final int vehicleId;

    /** 车身颜色下标（与乘客队列同域）。 */
    public final int colorIndex;

    /** 行驶方向，决定车头朝向与离场方向。 */
    public final Direction direction;

    /** 车身长度：2 = 小汽车，3 = 巴士。 */
    public final int length;

    /** 本次上车的乘客数。 */
    public final int count;

    /**
     * 接客位下标；{@code -1} 表示这辆车是"开出即接客离场"（没在接客位停留，
     * 上客点在棋盘出口），例如玩家点了一辆正好对色、直接驶出的车。
     */
    public final int slot;

    /** true = 直接开出接客（上客点在棋盘出口）；false = 先在接客位停着，等队首轮到再接客。 */
    public final boolean straightOut;

    public BoardStep(int vehicleId, int colorIndex, Direction direction, int length,
                     int count, int slot, boolean straightOut) {
        this.vehicleId = vehicleId;
        this.colorIndex = colorIndex;
        this.direction = direction;
        this.length = length;
        this.count = count;
        this.slot = slot;
        this.straightOut = straightOut;
    }
}
