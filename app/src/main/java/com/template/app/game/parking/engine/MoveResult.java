package com.template.app.game.parking.engine;

import java.util.Collections;
import java.util.List;

/**
 * 一次操作的结果（不可变）。
 * <p>
 * 视图靠它决定播什么动画；失败返回不可变单例，避免在每帧调用路径上分配对象。
 */
public final class MoveResult {

    private static final MoveResult FAILURE = new MoveResult(
        false, -1, 0, 0, 0, 0, false, -1, 0, false, false, Collections.emptyList());

    /** 操作是否改变了局面。 */
    public final boolean success;

    /** 被操作的车辆。 */
    public final int vehicleId;

    /** 动画起点。 */
    public final int fromRow;

    /** 动画起点。 */
    public final int fromCol;

    /** 动画终点。 */
    public final int toRow;

    /** 动画终点。 */
    public final int toCol;

    /** 本次操作是否让车辆驶出了停车场。 */
    public final boolean exited;

    /**
     * 驶出后停进的接客位下标；{@code -1} 表示它匹配队首、直接接客离开了
     * （不占车位，动画末端淡出）。
     */
    public final int pickupSlot;

    /** 本次接走的乘客数（0 表示只是占位或挪动）。 */
    public final int boarded;

    /** 本关是否已通关。 */
    public final boolean solved;

    /** 是否已判定为死局。 */
    public final boolean stuck;

    /**
     * 本次操作中接客离场的车辆清单，供渲染层播放"乘客逐个上车、车一辆辆开走"的动画。
     * 顺序即播放顺序：先被接走的车先播。可能为空（仅挪动 / 仅占位）。
     */
    public final List<BoardStep> boardSteps;

    private MoveResult(boolean success, int vehicleId, int fromRow, int fromCol,
                       int toRow, int toCol, boolean exited, int pickupSlot, int boarded,
                       boolean solved, boolean stuck, List<BoardStep> boardSteps) {
        this.success = success;
        this.vehicleId = vehicleId;
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.toRow = toRow;
        this.toCol = toCol;
        this.exited = exited;
        this.pickupSlot = pickupSlot;
        this.boarded = boarded;
        this.solved = solved;
        this.stuck = stuck;
        this.boardSteps = boardSteps;
    }

    /** 非法 / 无效果的操作。 */
    public static MoveResult failure() {
        return FAILURE;
    }

    static MoveResult of(int vehicleId, int fromRow, int fromCol, int toRow, int toCol,
                         boolean exited, int pickupSlot, int boarded, boolean solved,
                         boolean stuck, List<BoardStep> boardSteps) {
        return new MoveResult(true, vehicleId, fromRow, fromCol, toRow, toCol,
            exited, pickupSlot, boarded, solved, stuck, boardSteps);
    }
}
