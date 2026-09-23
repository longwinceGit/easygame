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
            // 车长必须取自 ParkingConfig：容量推导用的 AVG_CELLS_PER_VEHICLE 依赖同一组常量
            int length = ParkingConfig.LENGTH_MIN
                + random.nextInt(ParkingConfig.LENGTH_MAX - ParkingConfig.LENGTH_MIN + 1);
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

        /**
         * 搜索"把 target 沿箭头开出边界"的最短操作序列。
         * <p>
         * <b>通用化状态编码</b>：局面不再位打包，而是直接把 {@code n} 个锚点存进扁平
         * {@code int[] anchorsStore}（节点 i 占 {@code [i*n, (i+1)*n)}），
         * 访问集是"哈希定位 + 整段锚点比对"的开放寻址索引表。
         * <p>
         * 相比位打包（单 long 6bit/车、双 long 8bit/车）：
         * <ul>
         *   <li><b>车辆数无上限</b>——不再受 64/128 bit 宽度约束，密度可以一直往上调；</li>
         *   <li>仍然<b>零对象分配</b>（没有 {@code Long} 装箱、没有 {@code String}、没有节点对象），
         *       这正是压住"整机 CPU/页错误飙升、主线程被拖垮"的关键；</li>
         *   <li>代价是每状态一次 {@code arraycopy} 与整段比对，略慢于位改写——
         *       但生成已离线（关卡池），运行时几乎不再走这条路径。</li>
         * </ul>
         *
         * @return 终局节点；放弃（超限）或无解时返回 null
         */
        Node extract(int target, int[] start, boolean[] gone) {
            int n = size;
            // 数组尺寸按"状态数预算"分配：根 + 至多 GENERATOR_MAX_BFS_STATES 个后继。
            // 真正的预算上限是<b>入队状态数</b>（由下方 tail 守卫强制），而非出队扩展次数，
            // 否则一个状态扩展可生成多个后继，入队总数会远超数组容量导致越界。
            int cap = ParkingConfig.GENERATOR_MAX_BFS_STATES + 1;
            int[] anchorsStore = new int[cap * n];  // 每个节点一段连续 n 个锚点
            int[] depthArr = new int[cap];
            int[] frontier = new int[cap];
            int[] anchors = new int[n];             // 可复用的"当前局面"scratch
            int[] next = new int[n];                // 可复用的"候选局面"scratch

            // 开放寻址表：存 node+1，0 表示空槽。容量取 > 2×cap 的 2 的幂，控制装载率。
            int tableCap = 1;
            while (tableCap <= cap * 2) {
                tableCap <<= 1;
            }
            int[] visited = new int[tableCap];
            int mask = tableCap - 1;

            // 根节点：起点局面
            System.arraycopy(start, 0, anchorsStore, 0, n);
            depthArr[0] = 0;
            frontier[0] = 0;
            visited[hash(start, n) & mask] = 1;
            int head = 0, tail = 1;

            int expanded = 0;
            while (head < tail) {
                if (++expanded > ParkingConfig.GENERATOR_MAX_BFS_STATES) {
                    return null;
                }
                int cur = frontier[head++];
                int base = cur * n;
                System.arraycopy(anchorsStore, base, anchors, 0, n);
                if (boundary(target, anchors) == 0) {
                    int[] result = new int[n];
                    System.arraycopy(anchorsStore, base, result, 0, n);
                    return new Node(result, depthArr[cur]);
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
                        // 候选局面 = 父局面，只把第 i 辆的锚点平移 delta
                        System.arraycopy(anchors, 0, next, 0, n);
                        next[i] = anchors[i] + delta;

                        int slot = hash(next, n) & mask;
                        while (visited[slot] != 0) {
                            int other = visited[slot] - 1;
                            if (sameAnchors(anchorsStore, other * n, next, n)) {
                                break; // 已访问过
                            }
                            slot = (slot + 1) & mask;
                        }
                        if (visited[slot] == 0) {
                            if (tail >= cap) {
                                return null; // 状态数预算耗尽，放弃本次求解
                            }
                            int node = tail;
                            System.arraycopy(next, 0, anchorsStore, node * n, n);
                            depthArr[node] = depthArr[cur] + 1;
                            frontier[tail++] = node;
                            visited[slot] = node + 1;
                        }
                    }
                }
            }
            return null;
        }

        /** 局面哈希：只用于定位槽位，冲突由 {@link #sameAnchors} 兜底判等。 */
        private static int hash(int[] anchors, int n) {
            int h = 1;
            for (int i = 0; i < n; i++) {
                h = h * 31 + anchors[i];
            }
            return h;
        }

        /** 两段锚点是否完全相同。 */
        private static boolean sameAnchors(int[] store, int base, int[] other, int n) {
            for (int i = 0; i < n; i++) {
                if (store[base + i] != other[i]) {
                    return false;
                }
            }
            return true;
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
