package com.template.app.game.parking.engine;

import com.template.app.game.parking.model.Direction;
import com.template.app.game.parking.model.Level;
import com.template.app.game.parking.model.PassengerGroup;
import com.template.app.game.parking.model.Vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 关卡生成器：<b>随机布局 + BFS 求解</b>（ADR-008）。
 * <p>
 * 关键点在于顺序不是"验"出来的，而是"求"出来的：
 * <ol>
 *   <li>随机丢车进停车场（只保证不重叠），每辆车随机颜色与四向箭头之一</li>
 *   <li>逐辆 BFS 搜索"把这辆车沿箭头开出边界"的最短路，求出一辆就把它移出状态</li>
 *   <li><b>求出的顺序本身就是乘客队列的顺序</b>——于是关卡在生成时就被证明可解</li>
 * </ol>
 * <b>保守性</b>：BFS 中<b>非目标</b>车辆最多只能开到离边界一格的位置，不得到达边界
 * ——因为在真实引擎里"到达边界"等价于<b>驶出停车场</b>，会消耗接客位。
 * 而"停在离边界一格"在真实游戏中由拖动实现，因此 BFS 的可达状态是真实游戏的子集
 * ——BFS 能解，真实游戏一定能解。
 * <p>
 * 纯 Java：这是一个纯函数式的算法资产，可以在 JVM 上直接断言"生成 N 关，全部可解"。
 */
public final class ParkingLevelGenerator {

    private static final int PLACEMENT_ATTEMPTS = 160;

    private final Random random;

    public ParkingLevelGenerator() {
        this(new Random());
    }

    /** 给定种子，便于在测试中复现某一关。 */
    public ParkingLevelGenerator(long seed) {
        this(new Random(seed));
    }

    private ParkingLevelGenerator(Random random) {
        this.random = random;
    }

    /**
     * 生成一个关卡。
     * <p>
     * <b>必须在后台线程调用</b>（ADR-007）：最坏情况是数十万次状态展开。
     * 求解器本身受 {@link ParkingConfig#GENERATOR_MAX_BFS_STATES} 状态数约束，
     * 不再叠加墙钟预算，使测试机与真机的生成行为完全一致。
     */
    public Level generate(int levelIndex) {
        int count = ParkingConfig.vehicleCount(levelIndex);

        for (int attempt = 0; attempt < ParkingConfig.GENERATOR_MAX_ATTEMPTS; attempt++) {
            Level level = tryGenerate(levelIndex, count);
            if (level != null) {
                return level;
            }
        }
        // 降级：减车。所有车都要接客离场，车越少越容易解。
        while (count > 2) {
            count--;
            for (int attempt = 0; attempt < 8; attempt++) {
                Level level = tryGenerate(levelIndex, count);
                if (level != null) {
                    return level;
                }
            }
        }
        // 保底：2 辆车，必然可解。玩家永远有得玩。
        return emergencyLevel(levelIndex);
    }

    // ==================================================================
    // 生成流程
    // ==================================================================

    private Level tryGenerate(int levelIndex, int vehicleCount) {
        List<Vehicle> layout = randomLayout(vehicleCount);
        if (layout == null) {
            return null;
        }
        Solution solution = solve(layout, vehicleCount);
        // 质量门：拒绝"每辆车都能直接开走"的布局——那种局面点 N 下就通关，太简单。
        // 门槛按<b>实际车数</b>算：降车重试时门槛同步下降，否则车少了却仍要求同样的
        // 绝对步数，会让所有降车尝试都失败、直接掉进 2 车保底关。
        if (solution == null
            || solution.totalMoves < ParkingConfig.minSolutionMovesFor(vehicleCount, levelIndex)) {
            return null;
        }
        return new Level(levelIndex, layout, buildQueue(layout, solution.order));
    }

    private List<Vehicle> randomLayout(int vehicleCount) {
        boolean[][] occupied = new boolean[ParkingConfig.ROWS][ParkingConfig.COLUMNS];
        List<Vehicle> layout = new ArrayList<>();
        for (int i = 0; i < vehicleCount; i++) {
            Vehicle v = placeVehicle(occupied, i);
            if (v == null) {
                return null;
            }
            layout.add(v);
        }
        return layout;
    }

    /** 随机放一辆车：随机轴向、随机四向箭头、随机颜色、随机长度 2 或 3。 */
    private Vehicle placeVehicle(boolean[][] occupied, int id) {
        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
            boolean horizontal = random.nextBoolean();
            int length = 2 + random.nextInt(2);
            int row;
            int col;
            if (horizontal) {
                row = random.nextInt(ParkingConfig.ROWS);
                col = random.nextInt(ParkingConfig.COLUMNS - length + 1);
            } else {
                row = random.nextInt(ParkingConfig.ROWS - length + 1);
                col = random.nextInt(ParkingConfig.COLUMNS);
            }
            if (!isFree(occupied, row, col, length, horizontal)) {
                continue;
            }
            occupy(occupied, row, col, length, horizontal);
            Direction direction;
            if (horizontal) {
                direction = random.nextBoolean() ? Direction.LEFT : Direction.RIGHT;
            } else {
                direction = random.nextBoolean() ? Direction.UP : Direction.DOWN;
            }
            int color = random.nextInt(ParkingConfig.COLOR_COUNT);
            return new Vehicle(id, length, horizontal, direction, color, row, col);
        }
        return null;
    }

    private static boolean isFree(boolean[][] occupied, int row, int col, int length, boolean horizontal) {
        for (int i = 0; i < length; i++) {
            int r = horizontal ? row : row + i;
            int c = horizontal ? col + i : col;
            if (occupied[r][c]) {
                return false;
            }
        }
        return true;
    }

    private static void occupy(boolean[][] occupied, int row, int col, int length, boolean horizontal) {
        for (int i = 0; i < length; i++) {
            int r = horizontal ? row : row + i;
            int c = horizontal ? col + i : col;
            occupied[r][c] = true;
        }
    }

    /** 把驶出顺序翻译成乘客队列：相邻同色合并成一组。 */
    private List<PassengerGroup> buildQueue(List<Vehicle> layout, List<Integer> order) {
        List<PassengerGroup> groups = new ArrayList<>();
        for (int id : order) {
            Vehicle v = layout.get(id);
            PassengerGroup last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (last != null && last.colorIndex == v.colorIndex) {
                last.count += v.capacity();
            } else {
                groups.add(new PassengerGroup(v.colorIndex, v.capacity()));
            }
        }
        return groups;
    }

    private Level emergencyLevel(int levelIndex) {
        List<Vehicle> layout = new ArrayList<>();
        layout.add(new Vehicle(0, 2, false, Direction.UP, 0, 2, 1));
        layout.add(new Vehicle(1, 2, true, Direction.RIGHT, 1, 4, 3));
        List<PassengerGroup> queue = new ArrayList<>();
        queue.add(new PassengerGroup(0, 2));
        queue.add(new PassengerGroup(1, 2));
        return new Level(levelIndex, layout, queue);
    }

    // ==================================================================
    // 求解器
    // ==================================================================

    private static final class Solution {

        private final List<Integer> order;
        private final int totalMoves;

        Solution(List<Integer> order, int totalMoves) {
            this.order = order;
            this.totalMoves = totalMoves;
        }
    }

    private Solution solve(List<Vehicle> layout, int vehicleCount) {
        int n = layout.size();
        Board board = new Board(layout);
        boolean[] gone = new boolean[n];
        int[] anchors = new int[n];
        for (int i = 0; i < n; i++) {
            anchors[i] = layout.get(i).row * ParkingConfig.COLUMNS + layout.get(i).col;
        }

        List<Integer> order = new ArrayList<>();
        int totalMoves = 0;
        for (int step = 0; step < vehicleCount; step++) {
            // 收集这一阶段所有"能开出去"的车，随机选一辆。
            // 早期实现固定选最短路的那辆，结果每关都退化成"按最容易的顺序点一遍"——太简单。
            List<Node> goals = new ArrayList<>();
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (gone[i]) {
                    continue;
                }
                Node goal = board.extract(i, anchors, gone);
                if (goal == null) {
                    continue;
                }
                goals.add(goal);
                indices.add(i);
            }
            if (goals.isEmpty()) {
                return null;
            }
            int pick = random.nextInt(goals.size());
            Node chosen = goals.get(pick);
            int chosenIndex = indices.get(pick);

            // 采用求解后的终局：后续阶段的搜索从"这辆车已经开走"的局面继续
            anchors = chosen.anchors;
            gone[chosenIndex] = true;
            totalMoves += chosen.depth();
            order.add(chosenIndex);
        }
        return new Solution(order, totalMoves);
    }

    /** 求解器的静态棋盘描述：车辆的静态属性与一份可变的锚点数组。 */
    private static final class Board {

        private final int size;
        private final int[] lengths;
        private final boolean[] horizontal;
        private final int[] dirRow;
        private final int[] dirCol;

        Board(List<Vehicle> layout) {
            size = layout.size();
            lengths = new int[size];
            horizontal = new boolean[size];
            dirRow = new int[size];
            dirCol = new int[size];
            for (int i = 0; i < size; i++) {
                Vehicle v = layout.get(i);
                lengths[i] = v.length;
                horizontal[i] = v.horizontal;
                dirRow[i] = v.direction.dRow;
                dirCol[i] = v.direction.dCol;
            }
        }

        /** 每辆车锚点占的位数。10×10 棋盘锚点 0..99，取 8 bit（1 字节）对齐便于拆分。 */
        private static final int BITS_PER_VEHICLE = 8;

        private static final int FIELD_MASK = 0xFF;

        /**
         * 低 64 位（lo）放第 0..7 辆；第 8 辆起放高 64 位（hi）。
         * 12 辆车时 hi 只用 32 位，故上限 16 辆，与 {@link ParkingConfig#MAX_VEHICLES} 一致。
         */
        private static final int LOW_VEHICLES = 8;

        /**
         * 搜索"把 target 沿箭头开出边界"的最短操作序列。
         * <p>
         * 热路径<b>零对象分配</b>：访问集用原始 long 开放寻址集合（避免每状态一次
         * {@code Long} 装箱），局面用<b>两个 long</b> 编码，子节点由父局面直接改写对应车辆
         * 的字节字段得到——不再为每辆车克隆 {@code int[]}，也不再新建 {@code Node}。
         * 仅命中目标时解一次码、生成一份终局锚点。这正是压住"整机 CPU/页错误飙升、
         * 主线程被拖垮"的最后一块短板。
         *
         * @return 终局节点；放弃（超限）或无解时返回 null
         */
        Node extract(int target, int[] start, boolean[] gone) {
            int n = size;
            StateSet visited = new StateSet(ParkingConfig.GENERATOR_MAX_BFS_STATES);
            // 数组尺寸按"状态数预算"分配：根 + 至多 GENERATOR_MAX_BFS_STATES 个后继。
            // 真正的预算上限是<b>入队状态数</b>（由下方 tail 守卫强制），而非出队扩展次数，
            // 否则一个状态扩展可生成多个后继，入队总数会远超数组容量导致越界。
            int cap = ParkingConfig.GENERATOR_MAX_BFS_STATES + 1;
            int[] frontier = new int[cap];
            long[] statesLo = new long[cap];
            long[] statesHi = new long[cap];
            int[] depthArr = new int[cap];
            int[] anchors = new int[n];     // 可复用的解码 scratch
            long[] packed = new long[2];    // 可复用的编码 scratch：packed[0]=lo, [1]=hi

            pack(start, packed);
            statesLo[0] = packed[0];
            statesHi[0] = packed[1];
            depthArr[0] = 0;
            visited.add(statesLo[0], statesHi[0]);
            int head = 0, tail = 0;
            frontier[tail++] = 0;

            int expanded = 0;
            while (head < tail) {
                if (++expanded > ParkingConfig.GENERATOR_MAX_BFS_STATES) {
                    return null;
                }
                int cur = frontier[head++];
                long curLo = statesLo[cur];
                long curHi = statesHi[cur];
                unpack(curLo, curHi, anchors);
                if (boundary(target, anchors) == 0) {
                    return new Node(anchors.clone(), depthArr[cur]);
                }
                for (int i = 0; i < n; i++) {
                    if (gone[i]) {
                        continue;
                    }
                    // sign = 1 沿箭头滑到底（点击）；sign = -1 反向滑到底（拖动）
                    for (int sign = 1; sign >= -1; sign -= 2) {
                        int steps = maxSteps(anchors, gone, i, sign, target);
                        if (steps == 0) {
                            continue;
                        }
                        int delta = steps * sign
                            * (dirRow[i] * ParkingConfig.COLUMNS + dirCol[i]);
                        long nextLo = curLo;
                        long nextHi = curHi;
                        if (i < LOW_VEHICLES) {
                            int shift = i * BITS_PER_VEHICLE;
                            int field = ((int) ((curLo >>> shift) & FIELD_MASK) + delta)
                                & FIELD_MASK;
                            nextLo = (curLo & ~((long) FIELD_MASK << shift))
                                | ((long) field << shift);
                        } else {
                            int shift = (i - LOW_VEHICLES) * BITS_PER_VEHICLE;
                            int field = ((int) ((curHi >>> shift) & FIELD_MASK) + delta)
                                & FIELD_MASK;
                            nextHi = (curHi & ~((long) FIELD_MASK << shift))
                                | ((long) field << shift);
                        }
                        if (visited.add(nextLo, nextHi)) {
                            if (tail >= cap) {
                                return null; // 状态数预算耗尽，放弃本次求解
                            }
                            int node = tail;
                            statesLo[node] = nextLo;
                            statesHi[node] = nextHi;
                            depthArr[node] = depthArr[cur] + 1;
                            frontier[tail++] = node;
                        }
                    }
                }
            }
            return null;
        }

        /** 把局面 (lo, hi) 解码进复用缓存 out。 */
        private static void unpack(long lo, long hi, int[] out) {
            for (int i = 0; i < out.length; i++) {
                out[i] = i < LOW_VEHICLES
                    ? (int) ((lo >>> (i * BITS_PER_VEHICLE)) & FIELD_MASK)
                    : (int) ((hi >>> ((i - LOW_VEHICLES) * BITS_PER_VEHICLE)) & FIELD_MASK);
            }
        }

        private int maxSteps(int[] anchors, boolean[] gone, int index, int sign, int target) {
            int row = anchors[index] / ParkingConfig.COLUMNS;
            int col = anchors[index] % ParkingConfig.COLUMNS;
            int steps = 0;
            while (free(row + dirRow[index] * sign, col + dirCol[index] * sign,
                lengths[index], horizontal[index], anchors, gone, index)) {
                row += dirRow[index] * sign;
                col += dirCol[index] * sign;
                steps++;
            }
            // 非目标车不得开出边界：最多停在离边界一格的位置
            if (index != target && sign > 0) {
                int boundary = boundary(index, anchors);
                steps = Math.min(steps, Math.max(0, boundary - 1));
            }
            return steps;
        }

        /** 该车沿箭头开到边界还需要几格。 */
        private int boundary(int index, int[] anchors) {
            int row = anchors[index] / ParkingConfig.COLUMNS;
            int col = anchors[index] % ParkingConfig.COLUMNS;
            if (horizontal[index]) {
                return dirCol[index] < 0
                    ? col
                    : ParkingConfig.COLUMNS - col - lengths[index];
            }
            return dirRow[index] < 0
                ? row
                : ParkingConfig.ROWS - row - lengths[index];
        }

        private boolean free(int row, int col, int length,             boolean horizontal,
                             int[] anchors, boolean[] gone, int self) {
            if (row < 0 || col < 0) {
                return false;
            }
            if (horizontal) {
                if (row >= ParkingConfig.ROWS || col + length > ParkingConfig.COLUMNS) {
                    return false;
                }
            } else {
                if (col >= ParkingConfig.COLUMNS || row + length > ParkingConfig.ROWS) {
                    return false;
                }
            }
            for (int i = 0; i < size; i++) {
                if (i == self || gone[i]) {
                    continue;
                }
                int otherRow = anchors[i] / ParkingConfig.COLUMNS;
                int otherCol = anchors[i] % ParkingConfig.COLUMNS;
                if (overlaps(row, col, length, horizontal,
                    otherRow, otherCol, lengths[i], this.horizontal[i])) {
                    return false;
                }
            }
            return true;
        }

        private static boolean overlaps(int row, int col, int length, boolean horizontal,
                                        int otherRow, int otherCol, int otherLength, boolean otherHorizontal) {
            for (int i = 0; i < length; i++) {
                int r = horizontal ? row : row + i;
                int c = horizontal ? col + i : col;
                for (int j = 0; j < otherLength; j++) {
                    int rr = otherHorizontal ? otherRow : otherRow + j;
                    int cc = otherHorizontal ? otherCol : otherCol + j;
                    if (r == rr && c == cc) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * 把锚点数组打包成两个 long：{@code out[0]=lo}（第 0..7 辆）、{@code out[1]=hi}（第 8 辆起）。
         * <p>
         * <b>为什么不再用单 long</b>：8×8 时锚点 0..63 只要 6 bit，10 车 = 60 bit 恰好塞得下；
         * 但 10×10 棋盘锚点是 0..99，每车最少 7 bit，12 辆车 = 84 bit <b>超出 long 的 64 位</b>。
         * 故改为每车 8 bit（1 字节对齐）+ 两个 long，上限 16 辆车。
         * <p>
         * 单次 extract 内 gone 不变，故无需编码 gone，仅锚点即可唯一确定局面。
         * 相比 StringBuilder+String，热路径上依然<b>零对象分配</b>——
         * 这正是压住"整机 CPU/页错误飙升、主线程被拖垮"的关键。
         */
        private void pack(int[] anchors, long[] out) {
            long lo = 0L;
            long hi = 0L;
            for (int i = 0; i < size; i++) {
                long field = anchors[i] & FIELD_MASK;
                if (i < LOW_VEHICLES) {
                    lo |= field << (i * BITS_PER_VEHICLE);
                } else {
                    hi |= field << ((i - LOW_VEHICLES) * BITS_PER_VEHICLE);
                }
            }
            out[0] = lo;
            out[1] = hi;
        }
    }

    /**
     * 双 long 开放寻址集合（BFS 访问集）：避免热路径上每个状态一次对象分配。
     * <p>
     * 10×10 + 最多 12 辆车的状态需要 {@code (lo, hi)} 两个 long 表示，
     * 所以键是两个 long；空槽用 {@code keysLo[idx] == 0} 表示（真实键翻转了 lo 的最高位，恒非 0）。
     */
    private static final class StateSet {
        private final long[] keysLo;
        private final long[] keysHi;
        private final int mask;

        StateSet(int maxStates) {
            int cap = 1;
            while (cap <= maxStates * 2) {
                cap <<= 1;
            }
            keysLo = new long[cap];
            keysHi = new long[cap];
            mask = cap - 1;
        }

        /** @return true 表示首次加入（之前不存在）。 */
        boolean add(long lo, long hi) {
            long key = lo ^ Long.MIN_VALUE; // 翻转最高位：真实状态永不等于空槽 0
            int idx = (int) (mix(key, hi) & mask);
            while (keysLo[idx] != 0L) {
                if (keysLo[idx] == key && keysHi[idx] == hi) {
                    return false;
                }
                idx = (idx + 1) & mask;
            }
            keysLo[idx] = key;
            keysHi[idx] = hi;
            return true;
        }

        /** 把两个 long 混成一个下标：只按 lo 散列会让分布不均（hi 变化时下标不变）。 */
        private static long mix(long lo, long hi) {
            return lo ^ (hi * 0x9E3779B97F4A7C15L);
        }
    }

    /** BFS 节点：仅持有终局锚点与操作步数（步数由 BFS 数组直接记录，无需回溯父链）。 */
    private static final class Node {

        private final int[] anchors;
        private final int depth;

        Node(int[] anchors, int depth) {
            this.anchors = anchors;
            this.depth = depth;
        }

        int depth() {
            return depth;
        }
    }
}
