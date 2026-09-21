package com.template.app.game.parking.engine;

import com.template.app.game.parking.model.Direction;
import com.template.app.game.parking.model.Level;
import com.template.app.game.parking.model.PassengerGroup;
import com.template.app.game.parking.model.Vehicle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
        // 质量门：拒绝"每辆车都能直接开走"的布局——那种局面点 N 下就通关，太简单
        if (solution == null
            || solution.totalMoves < ParkingConfig.minSolutionMoves(levelIndex)) {
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

        /** 状态编码的复用缓冲，避免在 BFS 热路径上反复分配。 */
        private final StringBuilder keyBuilder = new StringBuilder(ParkingConfig.MAX_VEHICLES);

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
         *
         * @return 终局节点；放弃（超限）或无解时返回 null
         */
        Node extract(int target, int[] start, boolean[] gone) {
            Set<String> visited = new HashSet<>();
            Deque<Node> frontier = new ArrayDeque<>();
            Node root = new Node(start.clone(), null);
            visited.add(key(root.anchors, gone));
            frontier.add(root);

            int expanded = 0;
            while (!frontier.isEmpty()) {
                if (++expanded > ParkingConfig.GENERATOR_MAX_BFS_STATES) {
                    return null;
                }
                Node current = frontier.pollFirst();
                if (boundary(target, current.anchors) == 0) {
                    return current;
                }
                for (int i = 0; i < size; i++) {
                    if (gone[i]) {
                        continue;
                    }
                    // sign = 1 沿箭头滑到底（点击）；sign = -1 反向滑到底（拖动）
                    for (int sign = 1; sign >= -1; sign -= 2) {
                        int steps = maxSteps(current.anchors, gone, i, sign, target);
                        if (steps == 0) {
                            continue;
                        }
                        int[] next = current.anchors.clone();
                        next[i] += steps * sign * (dirRow[i] * ParkingConfig.COLUMNS + dirCol[i]);
                        if (visited.add(key(next, gone))) {
                            frontier.addLast(new Node(next, current));
                        }
                    }
                }
            }
            return null;
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
         * 状态编码：每车一个 char（锚点 0..63，已驶出记 0xFFFF）。
         * <p>
         * 早期用 long 打包（6 bit/车）把车辆数卡在 9 辆以内，
         * 换成 String 后不再受 64 bit 限制——车数才能随关卡涨到 12 辆。
         * StringBuilder 复用，只有 toString() 会分配。
         */
        private String key(int[] anchors, boolean[] gone) {
            keyBuilder.setLength(0);
            for (int i = 0; i < size; i++) {
                keyBuilder.append((char) (gone[i] ? 0xFFFF : anchors[i]));
            }
            return keyBuilder.toString();
        }
    }

    /** BFS 节点：持有完整状态与父指针，用于回溯操作序列长度。 */
    private static final class Node {

        private final int[] anchors;
        private final Node parent;

        Node(int[] anchors, Node parent) {
            this.anchors = anchors;
            this.parent = parent;
        }

        int depth() {
            int depth = 0;
            Node cursor = this;
            while (cursor.parent != null) {
                depth++;
                cursor = cursor.parent;
            }
            return depth;
        }
    }
}
