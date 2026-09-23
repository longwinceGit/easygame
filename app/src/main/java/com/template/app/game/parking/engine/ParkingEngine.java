package com.template.app.game.parking.engine;

import com.template.app.game.parking.model.Direction;
import com.template.app.game.parking.model.Level;
import com.template.app.game.parking.model.PassengerGroup;
import com.template.app.game.parking.model.Vehicle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 挪车消消消的引擎：状态机 + 全部规则。
 * <p>
 * <b>纯 Java，零 {@code android.*} 依赖（ADR-003）</b>——它可以被纯 JVM 单测覆盖，
 * 换渲染层时逻辑一行不用动。
 * <p>
 * 由本类守护的不变量（任何命令返回前都必须成立）：
 * <ol>
 *   <li>每辆车只处于停车场 / 接客区 / 已离开三处之一</li>
 *   <li>接客区车辆数 ≤ {@link ParkingConfig#PICKUP_SLOTS}</li>
 *   <li>队列人数 + 已接客数 == 本关总人数（守恒，「移除」会让乘客一并离开，同步扣减总数）</li>
 *   <li>{@code SOLVED} ⟺ 队列为空 <b>且</b> 所有乘客都已上车（队列空但有人没上车 = 死局）</li>
 *   <li>{@code RUNNING} 时至少有一辆车可移动，否则立刻转 {@code STUCK}</li>
 * </ol>
 */
public final class ParkingEngine {

    /** 引擎状态。 */
    public enum State {
        /** 还没套用关卡。 */
        READY,
        /** 进行中。 */
        RUNNING,
        /** 乘客全部接完，本关通过。 */
        SOLVED,
        /** 停车场里已无任何可移动的车辆。 */
        STUCK
    }

    /** 点击/拖动被拒绝的原因：车辆可以动。 */
    public static final int BLOCK_NONE = 0;

    /** 点击/拖动被拒绝的原因：被其他车挡住。 */
    public static final int BLOCK_BY_VEHICLE = 1;

    /** 点击/拖动被拒绝的原因：接客区已满，这辆车开不出去。 */
    public static final int BLOCK_BY_PICKUP = 2;

    private final List<Vehicle> vehicles = new ArrayList<>();
    private final List<PassengerGroup> queue = new ArrayList<>();
    private final int[][] grid = new int[ParkingConfig.ROWS][ParkingConfig.COLUMNS];
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();

    private State state = State.READY;
    private int levelIndex = 1;
    private int totalPassengers;
    private int passengersServed;
    private int score;
    /** 本局累计接走的乘客数（跨关累加，用于「一次游玩记一次分」的战绩）。 */
    private int sessionServedTotal;
    private int removesLeft;
    private int sortsLeft;

    /** 最近一次「移除 / 排序」产生的接客清单，等 ViewModel 来取走播放离场动画。 */
    private List<BoardStep> lastBoardSteps = Collections.emptyList();

    // ==================================================================
    // 关卡装载
    // ==================================================================

    /** 套用一个关卡。分数与关卡号由 {@link #startNewRun()} 单独管理，这里不动。 */
    public void applyLevel(Level level) {
        vehicles.clear();
        for (Vehicle prototype : level.vehicles) {
            Vehicle copy = prototype.copy();
            copy.place = Vehicle.PLACE_LOT;
            copy.loaded = 0;
            copy.slot = -1;
            vehicles.add(copy);
        }
        queue.clear();
        for (PassengerGroup group : level.queue) {
            queue.add(group.copy());
        }
        undoStack.clear();
        levelIndex = level.levelIndex;
        totalPassengers = level.totalPassengers;
        passengersServed = 0;
        removesLeft = ParkingConfig.REMOVE_PER_LEVEL;
        sortsLeft = ParkingConfig.SORT_PER_LEVEL;
        state = State.RUNNING;
        rebuildGrid();
    }

    /** 开新一轮：分数清零、回到第 1 关。 */
    public void startNewRun() {
        score = 0;
        sessionServedTotal = 0;
        levelIndex = 1;
        state = State.READY;
        vehicles.clear();
        queue.clear();
        undoStack.clear();
        rebuildGrid();
    }

    // ==================================================================
    // 只读查询
    // ==================================================================

    public State getState() {
        return state;
    }

    public int getLevelIndex() {
        return levelIndex;
    }

    public int getScore() {
        return score;
    }

    /** 本局累计接走的乘客数（跨关累加）。 */
    public int getSessionServed() {
        return sessionServedTotal;
    }

    /**
     * 从指定累计分接续开一局（「继续」用）。
     * <p>
     * 不重置分数与累计接客数，关卡号由随后套用的关卡决定；状态回到 {@code READY}
     * 等待 {@code applyLevel} 装载第 {@code sessionLevel} 关。
     */
    public void beginRun(int startScore) {
        score = startScore;
        sessionServedTotal = 0;
        levelIndex = 1;
        state = State.READY;
        vehicles.clear();
        queue.clear();
        undoStack.clear();
        rebuildGrid();
    }

    /** 本关还没被接走的乘客数。 */
    public int getPassengersLeft() {
        return totalPassengers - passengersServed;
    }

    public int getPassengersServed() {
        return passengersServed;
    }

    public int getRemovesLeft() {
        return removesLeft;
    }

    public int getSortsLeft() {
        return sortsLeft;
    }

    /** 接客区剩余空位。 */
    public int getFreeSlots() {
        int used = 0;
        for (Vehicle v : vehicles) {
            if (v.inPickup()) {
                used++;
            }
        }
        return ParkingConfig.PICKUP_SLOTS - used;
    }

    public int vehicleCount() {
        return vehicles.size();
    }

    /** 按下标取车。渲染层用下标遍历，避免每帧分配集合。 */
    public Vehicle vehicleAt(int index) {
        return vehicles.get(index);
    }

    public Vehicle vehicleById(int id) {
        for (Vehicle v : vehicles) {
            if (v.id == id) {
                return v;
            }
        }
        return null;
    }

    public int queueSize() {
        return queue.size();
    }

    /** 乘客队列的第 index 组，index 0 是队首。 */
    public PassengerGroup queueGroupAt(int index) {
        return queue.get(index);
    }

    /** (row, col) 上的车辆 id；空格返回 -1。命中测试用。 */
    public int occupantAt(int row, int col) {
        if (row < 0 || row >= ParkingConfig.ROWS || col < 0 || col >= ParkingConfig.COLUMNS) {
            return -1;
        }
        return grid[row][col];
    }

    // ==================================================================
    // 可动性
    // ==================================================================

    /** 这辆车沿箭头方向开到边界还需要几格。 */
    public int boundaryDistance(int vehicleId) {
        Vehicle v = vehicleById(vehicleId);
        if (v == null) {
            return 0;
        }
        return boundaryDistance(v);
    }

    private int boundaryDistance(Vehicle v) {
        if (v.horizontal) {
            return v.direction == Direction.LEFT
                ? v.col
                : ParkingConfig.COLUMNS - v.col - v.length;
        }
        return v.direction == Direction.UP
            ? v.row
            : ParkingConfig.ROWS - v.row - v.length;
    }

    /**
     * 沿箭头方向最多能前进几格。
     * <p>
     * 若这辆车驶出后无法被接客区接纳（颜色不匹配队首且车位已满），
     * 它最多只能开到离边界一格的位置——<b>开不出去</b>，而不是开出去然后死局。
     */
    public int maxForward(int vehicleId) {
        Vehicle v = vehicleById(vehicleId);
        if (v == null || !v.inLot()) {
            return 0;
        }
        int steps = countSteps(v, 1);
        if (!canAdmit(v)) {
            int allowed = Math.max(0, boundaryDistance(v) - 1);
            steps = Math.min(steps, allowed);
        }
        return steps;
    }

    /** 反方向（拖动）最多能退几格。 */
    public int maxBackward(int vehicleId) {
        Vehicle v = vehicleById(vehicleId);
        if (v == null || !v.inLot()) {
            return 0;
        }
        return countSteps(v, -1);
    }

    /** 该车此刻能否沿箭头驶出停车场。 */
    public boolean canExit(int vehicleId) {
        Vehicle v = vehicleById(vehicleId);
        if (v == null || !v.inLot()) {
            return false;
        }
        // canAdmit 必须显式参与：车已贴边时 boundary==0、maxForward==0，
        // 若不看接纳条件会把"开不出去"误判成"可以驶出"
        return canAdmit(v) && maxForward(vehicleId) == boundaryDistance(v);
    }

    /** 停车场里是否还存在任何一种合法移动（含反向拖动）。 */
    public boolean hasAnyMove() {
        for (Vehicle v : vehicles) {
            if (!v.inLot()) {
                continue;
            }
            if (maxForward(v.id) > 0 || maxBackward(v.id) > 0 || canExit(v.id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该车为什么动不了，用于给玩家精确的提示文案。
     *
     * @return {@link #BLOCK_NONE} / {@link #BLOCK_BY_VEHICLE} / {@link #BLOCK_BY_PICKUP}
     */
    public int blockedReason(int vehicleId) {
        Vehicle v = vehicleById(vehicleId);
        if (v == null || !v.inLot()) {
            return BLOCK_BY_VEHICLE;
        }
        if (maxForward(vehicleId) > 0 || canExit(vehicleId)) {
            return BLOCK_NONE;
        }
        if (!canAdmit(v)) {
            return BLOCK_BY_PICKUP;
        }
        return BLOCK_BY_VEHICLE;
    }

    // ==================================================================
    // 命令
    // ==================================================================

    /**
     * 移动一辆车。
     *
     * @param delta 位移格数，正数 = 沿箭头，负数 = 反向（拖动产生）。会被夹紧到合法区间。
     *              位移正好到达边界（且路径畅通）时车辆驶出停车场。
     */
    public MoveResult move(int vehicleId, int delta) {
        if (state != State.RUNNING) {
            return MoveResult.failure();
        }
        Vehicle v = vehicleById(vehicleId);
        if (v == null || !v.inLot()) {
            return MoveResult.failure();
        }

        int boundary = boundaryDistance(v);
        int forward = maxForward(vehicleId);
        int backward = maxBackward(vehicleId);
        delta = Math.max(-backward, Math.min(forward, delta));

        boolean exits = delta >= 0 && delta == boundary && canAdmit(v);
        if (delta == 0 && !exits) {
            return MoveResult.failure();
        }

        pushUndo();
        int fromRow = v.row;
        int fromCol = v.col;
        int newRow = v.row + v.direction.dRow * delta;
        int newCol = v.col + v.direction.dCol * delta;
        v.row = newRow;
        v.col = newCol;

        int pickupSlot = -1;
        int boarded = 0;
        List<BoardStep> boardSteps = Collections.emptyList();
        if (exits) {
            // 先分配固定车位再上客：这样"刚驶出即对色"的车，其接客动画也停在它自己的车位上
            v.slot = nextFreeSlot();
            v.place = Vehicle.PLACE_PICKUP;
            rebuildGrid();
            boardSteps = resolvePickup(v);
            boarded = totalBoarded(boardSteps);
            // 这辆车若被当场接客离场，记下它停进的接客位，
            // 让离场动画把车开进乘客区的对应车位（而非在棋盘出口直接上客）。
            for (BoardStep step : boardSteps) {
                if (step.vehicleId == v.id) {
                    pickupSlot = step.slot;
                    break;
                }
            }
            if (pickupSlot < 0 && v.inPickup()) {
                pickupSlot = v.slot;
            }
        } else {
            rebuildGrid();
        }
        finishTurn();
        return MoveResult.of(v.id, fromRow, fromCol, v.row, v.col,
            exits, pickupSlot, boarded, state == State.SOLVED, state == State.STUCK, boardSteps);
    }

    /** 点击一辆车：沿箭头滑到底（可能驶出）。 */
    public MoveResult tap(int vehicleId) {
        return move(vehicleId, maxForward(vehicleId));
    }

    /**
     * 用「移除」道具清走一辆车。
     * <p>
     * 被移除的车的乘客会一并改乘其他交通工具离开（同步扣减本关总人数），
     * 否则会留下永远等不到车的乘客——那是一个必然的软锁。
     *
     * @return 是否成功
     */
    public boolean removeVehicle(int vehicleId) {
        if (state != State.RUNNING || removesLeft <= 0) {
            return false;
        }
        Vehicle v = vehicleById(vehicleId);
        if (v == null || !v.inLot()) {
            return false;
        }
        pushUndo();
        v.place = Vehicle.PLACE_GONE;
        removesLeft--;
        rebuildGrid();
        dropPassengers(v.colorIndex, v.capacity());
        lastBoardSteps = resolvePickup(null);
        finishTurn();
        return true;
    }

    /**
     * 用「排序」道具重排剩余乘客队列：同色乘客聚合到一起，聚合块按颜色在原队列中
     * 首次出现的先后排列。排序后立刻结算一次接客区——队首变化可能让占位的车自动离开。
     * <p>
     * 重排<b>不保证</b>仍可解；但排序本身会被记入撤销栈，玩家可以一步退回。
     *
     * @return 是否生效（剩余队列不足两组时无效）
     */
    public boolean sortQueue() {
        if (state != State.RUNNING || sortsLeft <= 0 || queue.size() < 2) {
            return false;
        }
        pushUndo();
        sortsLeft--;
        List<PassengerGroup> merged = new ArrayList<>();
        for (PassengerGroup group : queue) {
            PassengerGroup existing = null;
            for (PassengerGroup candidate : merged) {
                if (candidate.colorIndex == group.colorIndex) {
                    existing = candidate;
                    break;
                }
            }
            if (existing == null) {
                merged.add(new PassengerGroup(group.colorIndex, group.count));
            } else {
                existing.count += group.count;
            }
        }
        queue.clear();
        queue.addAll(merged);
        lastBoardSteps = resolvePickup(null);
        finishTurn();
        return true;
    }

    /** 撤销一步。栈空返回 false。 */
    public boolean undo() {
        Snapshot snapshot = undoStack.pollLast();
        if (snapshot == null) {
            return false;
        }
        snapshot.restore(this);
        rebuildGrid();
        return true;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    // ==================================================================
    // 内部：规则
    // ==================================================================

    /**
     * 这辆车驶出后能否被接纳。
     * <p>
     * 守卫的不变量：<b>接客区车辆数 ≤ {@link ParkingConfig#PICKUP_SLOTS}</b>。
     * <ul>
     *   <li>有空位 —— 放行，它最多停一个车位。</li>
     *   <li>已满 —— 只允许"队首对色<b>且能被这一组装满</b>"的车驶出：它上完客立即开走，
     *       全程不占车位。若只上了一部分客，它就得停下来等，那会突破车位上限，
     *       因此这种情况不允许驶出。</li>
     * </ul>
     * 早期实现在"对色"时无条件放行，是因为当时上客即离场；
     * 加了"必须满载才开走"之后，半载停留成为可能，这里必须补上车位约束。
     */
    private boolean canAdmit(Vehicle v) {
        if (getFreeSlots() > 0) {
            return true;
        }
        return !queue.isEmpty()
            && queue.get(0).colorIndex == v.colorIndex
            && queue.get(0).count >= v.remainingCapacity();
    }

    /**
     * 反复结算接客区：只要队首颜色能在接客区里找到车，就让它上客。
     * <p>
     * <b>核心规则：必须满载才驶离。</b>每次上客只填到 {@link Vehicle#remainingCapacity()}；
     * 填满（{@link Vehicle#isFull()}）才置为 {@code PLACE_GONE} 并释放车位，
     * <b>没装满就继续停在接客区等客</b>，绝不半载开走。
     * <p>
     * 这是本作最主要的「解套」路径——先派上去占位，等队首轮到它的颜色，它自己就走了。
     * <p>
     * 每辆<b>驶离</b>的车会记一笔 {@link BoardStep} 交给渲染层，
     * 让"乘客逐个上车、车一辆辆开走"能按发生顺序播放出来；
     * 只上了一部分客、仍在等客的车不产生 step（它留在接客区，画面上本就可见）。
     * <p>
     * 循环必然终止：每轮要么消耗掉一个队列分组，要么让某辆车的 {@code loaded} 增加至少 1
     * （上界是它的容量），两者都是有限量。
     *
     * @param moved 本次刚驶出停车场的车（可能直接对色上客）；其余情况是 {@code null}
     * @return 本次接客<b>离场</b>的车辆清单，按离场顺序
     */
    private List<BoardStep> resolvePickup(Vehicle moved) {
        List<BoardStep> steps = new ArrayList<>();
        while (!queue.isEmpty()) {
            PassengerGroup front = queue.get(0);
            Vehicle match = null;
            // 本次刚驶出的车<b>优先</b>上客。canAdmit 在"接客区已满"时正是据此判断
            // "它会被这一组装满、随即开走、不占车位"；若被同色的等待车辆抢先，
            // 它就可能半载停下，从而突破车位上限。
            if (moved != null && moved.inPickup()
                && moved.colorIndex == front.colorIndex && !moved.isFull()) {
                match = moved;
            } else {
                for (Vehicle v : vehicles) {
                    // 已满载的车必然已经驶离，这里再判一次是防御：不重复给它上客
                    if (v.inPickup() && v.colorIndex == front.colorIndex && !v.isFull()) {
                        match = v;
                        break;
                    }
                }
            }
            if (match == null) {
                break;
            }
            int take = Math.min(match.remainingCapacity(), front.count);
            front.count -= take;
            match.loaded += take;
            passengersServed += take;
            sessionServedTotal += take;
            score += take * ParkingConfig.SCORE_PER_PASSENGER;
            if (front.count == 0) {
                queue.remove(0);
            }
            if (!match.isFull()) {
                // 未满载：留在接客区继续等客，不生成离场步骤
                continue;
            }
            // 所有被接客离场的车都在<b>自己那个固定车位</b>上客后开走：
            // 车位在进入时分配、离开前不变，故这里直接用它自己的车位，不存在横移。
            steps.add(new BoardStep(match.id, match.colorIndex, match.direction,
                match.length, match.loaded, match.slot, false));
            match.place = Vehicle.PLACE_GONE;
            match.slot = -1;   // 释放车位，供后续进入的车使用
        }
        return steps;
    }

    /** 把接客清单里的乘客数加起来。 */
    private static int totalBoarded(List<BoardStep> steps) {
        int total = 0;
        for (BoardStep step : steps) {
            total += step.count;
        }
        return total;
    }

    /** 取出并清空最近一次「移除 / 排序」操作产生的接客清单，供 ViewModel 触发离场动画。 */
    public List<BoardStep> takeBoardSteps() {
        List<BoardStep> steps = lastBoardSteps;
        lastBoardSteps = Collections.emptyList();
        return steps;
    }

    /** 从队列里扣掉 amount 位指定颜色的乘客（被「移除」的车的乘客改乘其他交通工具）。 */
    private void dropPassengers(int colorIndex, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int index = -1;
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).colorIndex == colorIndex) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return;
            }
            PassengerGroup group = queue.get(index);
            int take = Math.min(remaining, group.count);
            group.count -= take;
            remaining -= take;
            totalPassengers -= take;
            if (group.count == 0) {
                queue.remove(index);
            }
        }
    }

    /** 一步结束后的收尾：判定通关 / 死局。 */
    private void finishTurn() {
        if (queue.isEmpty()) {
            // 队列空还不够：跨组抢客可能让某辆车永远装不满（人已被别的车接走），
            // 此时队列虽空但仍有乘客没上车。那不是过关，而是死局——
            // 否则玩家会看到"过关"提示却还剩人没接完。
            if (passengersServed == totalPassengers) {
                state = State.SOLVED;
                score += ParkingConfig.SCORE_PER_LEVEL_UNIT * levelIndex;
            } else {
                state = State.STUCK;
            }
            return;
        }
        if (!hasAnyMove()) {
            state = State.STUCK;
        }
    }

    /**
     * 分配一个空闲接客位：取<b>最小可用下标</b>，因此车辆按进入顺序从左到右排开。
     * <p>
     * 车位一旦分配就固定不变（存在 {@link Vehicle#slot}），直到该车驶离才释放。
     * 这取代了早先"按 id 顺序实时重算"的做法——那种做法会让任何一辆车离开后
     * 它右边的所有车整体左移一格，既打乱排列，也让上客动画出现横移。
     *
     * @return 车位下标；接客区已满时返回 {@code -1}（调用方只在 {@link #canAdmit} 为真时驶出，故不应发生）
     */
    private int nextFreeSlot() {
        boolean[] used = new boolean[ParkingConfig.PICKUP_SLOTS];
        for (Vehicle other : vehicles) {
            if (other.inPickup() && other.slot >= 0 && other.slot < ParkingConfig.PICKUP_SLOTS) {
                used[other.slot] = true;
            }
        }
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return -1;
    }

    /** 沿 sign 方向数出最多能走几格（sign=1 沿箭头，-1 反向）。 */
    private int countSteps(Vehicle v, int sign) {
        int steps = 0;
        int row = v.row;
        int col = v.col;
        while (fits(row + v.direction.dRow * sign, col + v.direction.dCol * sign, v)) {
            row += v.direction.dRow * sign;
            col += v.direction.dCol * sign;
            steps++;
        }
        return steps;
    }

    /** (row, col) 放这辆车是否合法：不出界，且不与别的车重叠。 */
    private boolean fits(int row, int col, Vehicle v) {
        if (row < 0 || col < 0) {
            return false;
        }
        if (v.horizontal) {
            if (row >= ParkingConfig.ROWS || col + v.length > ParkingConfig.COLUMNS) {
                return false;
            }
        } else {
            if (col >= ParkingConfig.COLUMNS || row + v.length > ParkingConfig.ROWS) {
                return false;
            }
        }
        for (int i = 0; i < v.length; i++) {
            int r = v.horizontal ? row : row + i;
            int c = v.horizontal ? col + i : col;
            int occupant = grid[r][c];
            if (occupant >= 0 && occupant != v.id) {
                return false;
            }
        }
        return true;
    }

    private void rebuildGrid() {
        for (int r = 0; r < ParkingConfig.ROWS; r++) {
            for (int c = 0; c < ParkingConfig.COLUMNS; c++) {
                grid[r][c] = -1;
            }
        }
        for (Vehicle v : vehicles) {
            if (!v.inLot()) {
                continue;
            }
            for (int i = 0; i < v.length; i++) {
                int r = v.horizontal ? v.row : v.row + i;
                int c = v.horizontal ? v.col + i : v.col;
                grid[r][c] = v.id;
            }
        }
    }

    // ==================================================================
    // 内部：撤销快照（ADR-009：全量快照而非逆向操作）
    // ==================================================================

    private void pushUndo() {
        if (undoStack.size() >= ParkingConfig.MAX_UNDO) {
            undoStack.pollFirst();
        }
        undoStack.addLast(capture());
    }

    private Snapshot capture() {
        int n = vehicles.size();
        int[] rows = new int[n];
        int[] cols = new int[n];
        int[] places = new int[n];
        int[] loaded = new int[n];
        int[] slots = new int[n];
        for (int i = 0; i < n; i++) {
            Vehicle v = vehicles.get(i);
            rows[i] = v.row;
            cols[i] = v.col;
            places[i] = v.place;
            loaded[i] = v.loaded;
            slots[i] = v.slot;
        }
        int q = queue.size();
        int[] colors = new int[q];
        int[] counts = new int[q];
        for (int i = 0; i < q; i++) {
            colors[i] = queue.get(i).colorIndex;
            counts[i] = queue.get(i).count;
        }
        return new Snapshot(rows, cols, places, loaded, slots, colors, counts, score,
            passengersServed, removesLeft, sortsLeft, totalPassengers, levelIndex, state);
    }

    private static final class Snapshot {

        private final int[] rows;
        private final int[] cols;
        private final int[] places;
        private final int[] loaded;
        private final int[] slots;
        private final int[] queueColors;
        private final int[] queueCounts;
        private final int score;
        private final int passengersServed;
        private final int removesLeft;
        private final int sortsLeft;
        private final int totalPassengers;
        private final int levelIndex;
        private final State state;

        Snapshot(int[] rows, int[] cols, int[] places, int[] loaded, int[] slots,
                 int[] queueColors, int[] queueCounts,
                 int score, int passengersServed, int removesLeft, int sortsLeft,
                 int totalPassengers, int levelIndex, State state) {
            this.rows = rows;
            this.cols = cols;
            this.places = places;
            this.loaded = loaded;
            this.slots = slots;
            this.queueColors = queueColors;
            this.queueCounts = queueCounts;
            this.score = score;
            this.passengersServed = passengersServed;
            this.removesLeft = removesLeft;
            this.sortsLeft = sortsLeft;
            this.totalPassengers = totalPassengers;
            this.levelIndex = levelIndex;
            this.state = state;
        }

        void restore(ParkingEngine engine) {
            int n = Math.min(rows.length, engine.vehicles.size());
            for (int i = 0; i < n; i++) {
                Vehicle v = engine.vehicles.get(i);
                v.row = rows[i];
                v.col = cols[i];
                v.place = places[i];
                v.loaded = loaded[i];
                v.slot = slots[i];
            }
            engine.queue.clear();
            for (int i = 0; i < queueColors.length; i++) {
                engine.queue.add(new PassengerGroup(queueColors[i], queueCounts[i]));
            }
            engine.score = score;
            engine.passengersServed = passengersServed;
            engine.removesLeft = removesLeft;
            engine.sortsLeft = sortsLeft;
            engine.totalPassengers = totalPassengers;
            engine.levelIndex = levelIndex;
            engine.state = state;
        }
    }
}
