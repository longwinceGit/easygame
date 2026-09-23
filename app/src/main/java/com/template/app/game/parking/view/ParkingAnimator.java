package com.template.app.game.parking.view;

import android.os.SystemClock;

import com.template.app.game.parking.engine.MoveResult;
import com.template.app.game.parking.model.Direction;
import com.template.app.game.parking.model.Vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * 交互与动画的瞬时状态：手势写、绘制读。
 * <p>
 * 三类动画：
 * <ul>
 *   <li><b>棋盘内移动</b>——在旋转坐标系里插值，随棋盘一起被旋转</li>
 *   <li><b>驶入接客位</b>——在屏幕坐标系里插值：车先沿自己的车头方向冲出场地，
 *       再拐进接客位，同时车身从棋盘的倾斜角转正（仅"占位"时播放）</li>
 *   <li><b>接客离场编排</b>——依次播放每辆车"乘客逐个上车 → 一辆辆开走"的序列，
 *       由 {@link #enqueueBoardCars(List)} 入队、{@link #tickChoreography(long)} 推进</li>
 * </ul>
 */
class ParkingAnimator {

    /** 每移动一格的动画时长，速度恒定，长距离自然更久。 */
    static final long MOVE_MS_PER_CELL = 95L;
    static final long MIN_MOVE_MS = 130L;
    static final long MAX_MOVE_MS = 460L;
    static final long EXIT_DURATION_MS = 560L;
    static final long POPUP_DURATION_MS = 900L;

    /** 每位乘客上车的时长。上客一辆辆来，所以一辆车的上客总时长 = 人数 × 该值。 */
    static final long BOARD_MS_PER_PAX = 160L;
    /** 单车上客总时长的上下限（避免 1 人太短、3 人太长）。 */
    static final long BOARD_MIN_MS = 280L;
    static final long BOARD_MAX_MS = 820L;
    /** 占位 / 稍后接走的车从上客位滑到左侧上客点的时长（上客阶段前半段完成）。 */
    static final long BOARD_SLIDE_MS = 240L;
    /** 上完客后整车开走的时长。 */
    static final long LEAVE_DURATION_MS = 480L;
    /** 上完客后整车驶下马路、落到车道上的时长。 */
    static final long ROAD_ENTER_MS = 300L;
    /** 车在马路上自左向右开到右端屏幕外的时长。 */
    static final long ROAD_EXIT_MS = 760L;

    // ---- 拖动 ----

    boolean dragging;
    int dragVehicleId = -1;
    float dragStartX;
    float dragStartY;

    /** 当前拖动位移，单位是"格"，已夹紧到合法区间，可正可负。 */
    float dragCells;

    // ---- 棋盘内位移动画 ----

    int moveVehicleId = -1;
    int fromRow;
    int fromCol;
    int toRow;
    int toCol;
    long moveStart;
    long moveDuration;
    Vehicle moveVehicle;

    // ---- 驶出动画（屏幕坐标）----

    Vehicle exitVehicle;
    float exitFromX;
    float exitFromY;
    float exitToX;
    float exitToY;
    float exitFromAngle;
    float exitFromX2;
    float exitFromY2;
    long exitStart;
    boolean exitParked;

    // ---- 接客浮字 ----

    int popupValue;
    long popupStart;

    // ---- 连击浮字（一次操作送走 ≥2 辆车时弹出）----

    String comboText;
    long comboStart;

    // ---- 接客离场编排（乘客逐个上车 → 车一辆辆开走）----

    /**
     * 待播放的离场车序列。
     * <p>
     * 用 {@code List + 游标} 而非队列：绘制层要按索引访问"还没轮到播放的车"
     * （把它们继续画在原车位上，见 {@link #waitingBoardAt(int)}），
     * 而 {@code ArrayDeque} 的迭代会在 {@code onDraw} 里每次分配一个迭代器。
     */
    private final List<LeavingCar> boardQueue = new ArrayList<>();
    private int boardCursor;

    /**
     * 已入队但还没到播放时刻的离场车（例如"刚驶出停车场的车先开进接客位、
     * 到位后再上客"，需要延迟 {@link #EXIT_DURATION_MS} 才开始）。
     * 放在动画状态机内部而非用 {@code postDelayed}，可避免"等待期 isAnimating 误判为
     * 已结束"的一帧空隙，从而让上层能可靠地等到所有车开走。
     */
    private final List<LeavingCar> pendingBoardQueue = new ArrayList<>();
    private int pendingCursor;
    private long pendingBoardStart;

    /** 正在播放的那辆车；为 null 时若队列非空则下一帧取出。 */
    private LeavingCar activeBoard;

    /** 当前阶段：0=上客，1=驶下马路，2=马路上自左向右开走。 */
    private int boardPhase;

    /** 当前阶段起始时刻。 */
    private long boardPhaseStart;

    /** 一辆离场车：语义 + 屏幕坐标（起点 / 上客点 / 离场终点）。全部由 View 在入队时算好。 */
    static final class LeavingCar {
        /** 车辆 id，用于与"正在播放驶入接客位动画的那辆"比对，避免画出双重影像。 */
        final int vehicleId;

        final int colorIndex;
        final Direction direction;
        final int length;
        final int count;
        final float fromX, fromY;              // 起点：车原本停在的接客位
        final float spotX, spotY, spotAngle;   // 上客点：就地上下客，即车自己所在的接客位
        final float roadX, roadY;              // 下到马路后的落点（angle 固定 0）
        final float leaveX, leaveY, leaveAngle; // 离场终点：马路上自左向右开到右端屏幕外（angle=0）
        final float width, height;             // 车身尺寸（按上客点场景取棋盘/接客位尺度）

        LeavingCar(int vehicleId, int colorIndex, Direction direction, int length, int count,
                   float fromX, float fromY,
                   float spotX, float spotY, float spotAngle,
                   float roadX, float roadY,
                   float leaveX, float leaveY, float leaveAngle,
                   float width, float height) {
            this.vehicleId = vehicleId;
            this.colorIndex = colorIndex;
            this.direction = direction;
            this.length = length;
            this.count = count;
            this.fromX = fromX;
            this.fromY = fromY;
            this.spotX = spotX;
            this.spotY = spotY;
            this.spotAngle = spotAngle;
            this.roadX = roadX;
            this.roadY = roadY;
            this.leaveX = leaveX;
            this.leaveY = leaveY;
            this.leaveAngle = leaveAngle;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 把一批离场车加入播放队列（顺序即入队顺序，依次播放）。
     *
     * @param delayMs 延迟多久才开始播放；&gt;0 时先进入 pending 队列，
     *                {@link #tickChoreography(long)} 到点后转进正式队列，
     *                等待期间 {@link #hasBoardQueue()} 仍为真。
     */
    void enqueueBoardCars(List<LeavingCar> cars, long delayMs) {
        if (cars == null) {
            return;
        }
        if (delayMs <= 0) {
            boardQueue.addAll(cars);
            return;
        }
        pendingBoardQueue.addAll(cars);
        pendingBoardStart = now() + delayMs;
    }

    /** 播放是否仍在进行（含队列里还没轮到的车，以及已入队但还没到播放时刻的车）。 */
    boolean hasBoardQueue() {
        return activeBoard != null
            || pendingCursor < pendingBoardQueue.size()
            || boardCursor < boardQueue.size();
    }

    /**
     * 已接客、但<b>还没轮到播放</b>的离场车数量（含延迟等待期的）。
     * <p>
     * 这些车在引擎里已经是 {@code GONE}，但玩家还没看到它们上客开走，
     * 绘制层必须把它们继续画在原来的接客位上——否则一次操作带走多辆时，
     * 没轮到的车会凭空消失，等轮到自己才突然冒出来。
     */
    int waitingBoardCount() {
        return (pendingBoardQueue.size() - pendingCursor)
            + (boardQueue.size() - boardCursor);
    }

    /** 取第 i 辆还没轮到播放的离场车：先延迟等待期的，再正式队列的。 */
    LeavingCar waitingBoardAt(int i) {
        int pending = pendingBoardQueue.size() - pendingCursor;
        return i < pending
            ? pendingBoardQueue.get(pendingCursor + i)
            : boardQueue.get(boardCursor + (i - pending));
    }

    /**
     * 每帧推进编排状态机。
     * <p>
     * 单辆车分三阶段：先"上客" {@code count×BOARD_MS_PER_PAX}（夹紧到上限），
     * 再"驶下马路" {@link #ROAD_ENTER_MS}，最后"马路上自左向右开走" {@link #ROAD_EXIT_MS}。
     * 队列里有多辆时依次播放——这就是"一辆辆开走"。
     */
    void tickChoreography(long now) {
        // 等待期结束的离场车转进正式队列，保证"一辆辆开走"的接力不断档。
        if (pendingCursor < pendingBoardQueue.size() && now >= pendingBoardStart) {
            boardQueue.addAll(pendingBoardQueue);
            pendingBoardQueue.clear();
            pendingCursor = 0;
        }
        if (activeBoard == null) {
            advanceBoard(now);
            return;
        }
        if (boardPhase == 0) {
            long duration = boardDuration(activeBoard.count);
            if (now - boardPhaseStart >= duration) {
                boardPhase = 1;
                boardPhaseStart = now;
            }
        } else if (boardPhase == 1) {
            if (now - boardPhaseStart >= ROAD_ENTER_MS) {
                boardPhase = 2;
                boardPhaseStart = now;
            }
        } else if (now - boardPhaseStart >= ROAD_EXIT_MS) {
            activeBoard = null;
            boardPhase = 0;
            advanceBoard(now);
        }
    }

    /** 取出下一辆开始播放；队列播完则整体清空，让游标归位以便下次入队。 */
    private void advanceBoard(long now) {
        if (boardCursor < boardQueue.size()) {
            activeBoard = boardQueue.get(boardCursor++);
            boardPhaseStart = now;
            boardPhase = 0;
        } else if (!boardQueue.isEmpty()) {
            boardQueue.clear();
            boardCursor = 0;
        }
    }

    private static long boardDuration(int count) {
        return Math.max(BOARD_MIN_MS, Math.min(BOARD_MAX_MS, count * BOARD_MS_PER_PAX));
    }

    /** 当前是否有车正在上客 / 开走（供绘制层判断）。 */
    boolean isBoardingActive() {
        return activeBoard != null;
    }

    /** 当前车已上车的乘客数（0..count），按阶段进度取整，实现"一个个上车"。 */
    int boardedSoFar(long now) {
        if (activeBoard == null) {
            return 0;
        }
        if (boardPhase != 0) {
            return activeBoard.count;
        }
        int n = (int) ((now - boardPhaseStart) * activeBoard.count / boardDuration(activeBoard.count));
        return Math.max(0, Math.min(activeBoard.count, n));
    }

    /** 当前车处于"开走"阶段（而非"上客"）。 */
    boolean isLeaving(long now) {
        return activeBoard != null && boardPhase != 0;
    }

    /** 当前处于哪个阶段：0=上客，1=驶下马路，2=马路上自左向右开走。 */
    int boardPhase(long now) {
        return activeBoard == null ? 0 : boardPhase;
    }

    /** 驶下马路阶段的进度 0..1。 */
    float roadEnterProgress(long now) {
        if (activeBoard == null) {
            return 0f;
        }
        return Math.min(1f, (now - boardPhaseStart) / (float) ROAD_ENTER_MS);
    }

    /** 马路上自左向右开走阶段的进度 0..1。 */
    float exitProgress(long now) {
        if (activeBoard == null) {
            return 0f;
        }
        return Math.min(1f, (now - boardPhaseStart) / (float) ROAD_EXIT_MS);
    }

    int boardColorIndex() {
        return activeBoard == null ? 0 : activeBoard.colorIndex;
    }

    Direction boardDirection() {
        return activeBoard.direction;
    }

    int boardLength() {
        return activeBoard.length;
    }

    int boardCount() {
        return activeBoard == null ? 0 : activeBoard.count;
    }

    float boardSpotX() {
        return activeBoard.spotX;
    }

    float boardSpotY() {
        return activeBoard.spotY;
    }

    float boardSpotAngle() {
        return activeBoard.spotAngle;
    }

    /** 上客前车停的起点（原接客位）。与 spot 不同则表示需要滑向左侧上客点。 */
    float boardFromX() {
        return activeBoard.fromX;
    }

    float boardFromY() {
        return activeBoard.fromY;
    }

    /** 上客阶段内从起点滑到左侧上客点的进度 0..1（用于车头对齐前先就位）。 */
    float boardSlideProgress(long now) {
        if (activeBoard == null || boardPhase != 0) {
            return 1f;
        }
        return Math.min(1f, (now - boardPhaseStart) / (float) BOARD_SLIDE_MS);
    }

    float boardLeaveX() {
        return activeBoard.leaveX;
    }

    float boardLeaveY() {
        return activeBoard.leaveY;
    }

    float boardLeaveAngle() {
        return activeBoard.leaveAngle;
    }

    float boardRoadX() {
        return activeBoard.roadX;
    }

    float boardRoadY() {
        return activeBoard.roadY;
    }

    float boardWidth() {
        return activeBoard.width;
    }

    float boardHeight() {
        return activeBoard.height;
    }

    void resetDrag() {
        dragging = false;
        dragVehicleId = -1;
        dragStartX = 0f;
        dragStartY = 0f;
        dragCells = 0f;
    }

    /** 开始一次棋盘内移动动画。 */
    void startMove(MoveResult result, Vehicle vehicle) {
        moveVehicleId = result.vehicleId;
        fromRow = result.fromRow;
        fromCol = result.fromCol;
        toRow = result.toRow;
        toCol = result.toCol;
        moveVehicle = vehicle;
        moveStart = now();
        int cells = Math.abs(result.toRow - result.fromRow) + Math.abs(result.toCol - result.fromCol);
        moveDuration = Math.max(MIN_MOVE_MS,
            Math.min(MAX_MOVE_MS, cells * MOVE_MS_PER_CELL));
    }

    /**
     * 开始一次驶出动画。
     *
     * @param fromX       车离开场地那一刻的屏幕位置
     * @param fromAngle   车身此刻的倾角（= 棋盘倾角）
     * @param outX        沿车头方向冲出场地后的途经点
     * @param toX         终点：接客位中心，或接客区中央（直接接客离开时）
     */
    void startExit(Vehicle vehicle, float fromX, float fromY, float fromAngle,
                   float outX, float outY, float toX, float toY, boolean parked) {
        exitVehicle = vehicle;
        exitFromX = fromX;
        exitFromY = fromY;
        exitFromAngle = fromAngle;
        exitFromX2 = outX;
        exitFromY2 = outY;
        exitToX = toX;
        exitToY = toY;
        exitParked = parked;
        exitStart = now();
    }

    void startPopup(int value) {
        popupValue = value;
        popupStart = now();
    }

    void startCombo(int carCount, int bonus) {
        comboText = carCount + "连击 +" + bonus;
        comboStart = now();
    }

    void clearMove() {
        moveVehicleId = -1;
        moveVehicle = null;
    }

    void clearExit() {
        exitVehicle = null;
    }

    int moveVehicleId() {
        return moveVehicleId;
    }

    boolean isMoveAnimating(long now) {
        return moveVehicleId >= 0 && now - moveStart < moveDuration;
    }

    boolean isExitAnimating(long now) {
        return exitVehicle != null && now - exitStart < EXIT_DURATION_MS;
    }

    boolean isPopupAnimating(long now) {
        return popupValue > 0 && now - popupStart < POPUP_DURATION_MS;
    }

    boolean isComboAnimating(long now) {
        return comboText != null && now - comboStart < POPUP_DURATION_MS;
    }

    /** 只要还有任一动画在跑，就需要下一帧。 */
    boolean isAnimating(long now) {
        return isMoveAnimating(now) || isExitAnimating(now)
            || isPopupAnimating(now) || isComboAnimating(now) || hasBoardQueue();
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }
}
