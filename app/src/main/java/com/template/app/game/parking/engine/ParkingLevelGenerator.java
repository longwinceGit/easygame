package com.template.app.game.parking.engine;

import com.template.app.game.parking.model.Direction;
import com.template.app.game.parking.model.Level;
import com.template.app.game.parking.model.PassengerGroup;
import com.template.app.game.parking.model.Vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 关卡生成器：<b>随机布局 + 弱校验</b>。
 * <p>
 * <b>已不再于生成时证明整关可解</b>（原 ADR-008 的"随机布局 + BFS 求解"）：
 * 密度要推到参考图那种"满场堵成一团"（{@code MAX_FILL_RATIO} 0.90、约 36 辆），
 * 全局 BFS 在这个密度下展不开，会退化成大量降车、永远达不到目标车辆数。
 * <p>
 * 现在的做法：
 * <ol>
 *   <li>随机丢车进停车场（只保证不重叠），每辆车随机颜色与四向箭头之一</li>
 *   <li><b>弱校验</b>：只要求开局有 ≥ {@link ParkingConfig#MIN_ESCAPABLE_AT_START}
 *       辆车能沿箭头直线开走——保证"开局有得走"</li>
 *   <li>队列顺序不再等于解法顺序：先排开局能开走的车，其余随机追加</li>
 * </ol>
 * 后续被堵死的车由「移除 / 排序」道具兜底（见
 * {@link ParkingConfig#REMOVE_PER_LEVEL}），这是本作"高密度"的解压阀。
 * <p>
 * 纯 Java：可以在 JVM 上直接断言"生成 N 关，开局均可动"。
 */
public final class ParkingLevelGenerator {

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
     * <b>必须在后台线程调用</b>（ADR-007）：高密度下放车与弱校验仍有可观开销，
     * 但相比原 BFS 方案（数十万次状态展开）已快若干个数量级。
     */
    public Level generate(int levelIndex) {
        int count = ParkingConfig.vehicleCount(levelIndex);

        for (int attempt = 0; attempt < ParkingConfig.GENERATOR_MAX_ATTEMPTS; attempt++) {
            Level level = tryGenerate(levelIndex, count);
            if (level != null) {
                return level;
            }
        }
        // 降级：减车。车越少越容易满足弱校验。
        while (count > 2) {
            count--;
            for (int attempt = 0; attempt < 8; attempt++) {
                Level level = tryGenerate(levelIndex, count);
                if (level != null) {
                    return level;
                }
            }
        }
        // 保底：2 辆车，必然可动。玩家永远有得玩。
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
        // 弱校验：开局至少要有若干辆车能直接开走，否则整关一上来就堵死。
        List<Integer> escapable = escapableVehicles(layout);
        if (escapable.size() < ParkingConfig.MIN_ESCAPABLE_AT_START) {
            return null;
        }
        return new Level(levelIndex, layout, buildQueue(layout, escapable));
    }

    private List<Vehicle> randomLayout(int vehicleCount) {
        boolean[][] occupied = new boolean[ParkingConfig.ROWS][ParkingConfig.COLUMNS];
        List<Vehicle> layout = new ArrayList<>();
        for (int i = 0; i < vehicleCount; i++) {
            // 传入已放置的车辆：新车方向必须避开与同轴既有车"相向"（见 placeVehicle）
            Vehicle v = placeVehicle(occupied, layout, i);
            if (v == null) {
                return null;
            }
            layout.add(v);
        }
        return layout;
    }

    /**
     * 随机放一辆车：随机轴向、随机颜色、随机长度 2 或 3；<b>方向受"禁止相向"约束</b>。
     * <p>
     * <b>方向约束（必须遵守，否则必然死局）</b>：同轴车辆不得"相向"。
     * <ul>
     *   <li>横向车：位于同行某辆横向车<b>左侧</b>时不得向右，位于其<b>右侧</b>时不得向左；</li>
     *   <li>竖直车：位于同列某辆竖直车<b>上方</b>时不得向下，位于其<b>下方</b>时不得向上。</li>
     * </ul>
     * 原因：横向车只能沿行移动。"左车向右、右车向左"时两者互相挡在对方出口路径上，
     * 且都无法让开（同一行没有第三条路可绕），于是<b>谁也开不走</b>——除「移除」道具外无解。
     * 反之"左车向左、右车向右"（<b>背向</b>）合法：各自朝两端开走，互不阻挡。
     * 等价表述：同行横向车按列排序后方向序列必须是"左* 右*"（先左后右）。
     * <p>
     * 实现上<b>不锁定整行方向</b>（那会把背向也一并禁掉、方向多样性损失一半），
     * 而是对每个候选位置判定两个方向各自是否合法，只从合法方向里随机选。
     * <p>
     * <b>先收集全部可放位置再随机取一个</b>，而不是"随机试错 N 次"：
     * 目标密度 0.90 时剩余空位稀少且破碎，随机试错会在放最后几辆车时频繁失败，
     * 使整关反复重来；枚举候选（每辆约 200 个位置，开销可忽略）则只要还有空间必定成功。
     */
    private Vehicle placeVehicle(boolean[][] occupied, List<Vehicle> placed, int id) {
        List<int[]> candidates = new ArrayList<>();
        for (int length = ParkingConfig.LENGTH_MIN; length <= ParkingConfig.LENGTH_MAX; length++) {
            for (int axis = 0; axis < 2; axis++) {
                boolean horizontal = axis == 0;
                int maxRow = horizontal ? ParkingConfig.ROWS : ParkingConfig.ROWS - length + 1;
                int maxCol = horizontal ? ParkingConfig.COLUMNS - length + 1 : ParkingConfig.COLUMNS;
                for (int row = 0; row < maxRow; row++) {
                    for (int col = 0; col < maxCol; col++) {
                        if (!isFree(occupied, row, col, length, horizontal)) {
                            continue;
                        }
                        int dirs = allowedDirections(placed, row, col, horizontal);
                        if (dirs == 0) {
                            continue;   // 两个方向都会与既有车相向，此位置不可用
                        }
                        // {row, col, length, horizontal, dirs}
                        candidates.add(new int[]{row, col, length, horizontal ? 1 : 0, dirs});
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        int[] pick = candidates.get(random.nextInt(candidates.size()));
        int row = pick[0];
        int col = pick[1];
        int length = pick[2];
        boolean horizontal = pick[3] == 1;
        occupy(occupied, row, col, length, horizontal);

        // 只从合法方向里随机选一个
        int dirs = pick[4];
        boolean positive = dirs == (DIR_POSITIVE | DIR_NEGATIVE)
            ? random.nextBoolean()
            : dirs == DIR_POSITIVE;
        Direction direction;
        if (horizontal) {
            direction = positive ? Direction.RIGHT : Direction.LEFT;
        } else {
            direction = positive ? Direction.DOWN : Direction.UP;
        }
        int color = random.nextInt(ParkingConfig.COLOR_COUNT);
        return new Vehicle(id, length, horizontal, direction, color, row, col);
    }

    /** 方向位掩码：正向 = 横向 RIGHT / 竖直 DOWN；负向 = 横向 LEFT / 竖直 UP。 */
    private static final int DIR_POSITIVE = 1;
    private static final int DIR_NEGATIVE = 2;

    /**
     * 该位置允许哪些方向（位掩码）：排除会与同轴既有车<b>相向</b>的方向。
     * <p>
     * 注意只禁"相向"——同向（跟在后面依次开走）与背向（各朝一端）都是可解的。
     */
    private static int allowedDirections(List<Vehicle> placed, int row, int col, boolean horizontal) {
        int allowed = DIR_POSITIVE | DIR_NEGATIVE;
        for (Vehicle other : placed) {
            if (other.horizontal != horizontal) {
                continue;
            }
            if (horizontal) {
                if (other.row != row) {
                    continue;
                }
                // 我在它左侧却要向右 → 与这辆向左的车相向
                if (col < other.col && other.direction == Direction.LEFT) {
                    allowed &= ~DIR_POSITIVE;
                }
                // 我在它右侧却要向左 → 与这辆向右的车相向
                if (col > other.col && other.direction == Direction.RIGHT) {
                    allowed &= ~DIR_NEGATIVE;
                }
            } else {
                if (other.col != col) {
                    continue;
                }
                // 我在它上方却要向下 → 与这辆向上的车相向
                if (row < other.row && other.direction == Direction.UP) {
                    allowed &= ~DIR_POSITIVE;
                }
                // 我在它下方却要向上 → 与这辆向下的车相向
                if (row > other.row && other.direction == Direction.DOWN) {
                    allowed &= ~DIR_NEGATIVE;
                }
            }
        }
        return allowed;
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

    // ==================================================================
    // 弱校验：开局可动性
    // ==================================================================

    /**
     * 找出当前能<b>沿自己的箭头方向直线开走</b>的车（到边界的路径上无其他车阻挡）。
     * <p>
     * 这是取代"全局 BFS 证明可解"的弱校验：只保证开局有得走，
     * 不保证整关无道具通关——后者由「移除 / 排序」道具兜底。
     *
     * @return 可开走的车辆下标（即 {@link Vehicle#id}）
     */
    private List<Integer> escapableVehicles(List<Vehicle> layout) {
        boolean[][] occupied = new boolean[ParkingConfig.ROWS][ParkingConfig.COLUMNS];
        for (Vehicle v : layout) {
            for (int i = 0; i < v.length; i++) {
                int r = v.horizontal ? v.row : v.row + i;
                int c = v.horizontal ? v.col + i : v.col;
                occupied[r][c] = true;
            }
        }
        List<Integer> escapable = new ArrayList<>();
        for (int i = 0; i < layout.size(); i++) {
            if (canEscape(layout.get(i), occupied)) {
                escapable.add(i);
            }
        }
        return escapable;
    }

    /** 该车沿箭头方向到边界是否畅通（不含自身占格）。 */
    private static boolean canEscape(Vehicle v, boolean[][] occupied) {
        if (v.horizontal) {
            int step = v.direction.dCol > 0 ? 1 : -1;
            // 车头前方第一格：向右是 col+length，向左是 col-1
            int first = v.direction.dCol > 0 ? v.col + v.length : v.col - 1;
            for (int c = first; c >= 0 && c < ParkingConfig.COLUMNS; c += step) {
                if (occupied[v.row][c]) {
                    return false;
                }
            }
            return true;
        }
        int step = v.direction.dRow > 0 ? 1 : -1;
        int first = v.direction.dRow > 0 ? v.row + v.length : v.row - 1;
        for (int r = first; r >= 0 && r < ParkingConfig.ROWS; r += step) {
            if (occupied[r][v.col]) {
                return false;
            }
        }
        return true;
    }

    // ==================================================================
    // 乘客队列
    // ==================================================================

    /**
     * 构造乘客队列：<b>不再依赖解法顺序</b>（生成器已不再求全局解）。
     * <p>
     * 顺序策略：
     * <ol>
     *   <li>先放"开局能开走的车"（随机打乱）——保证开局几步一定走得通、接得上客；</li>
     *   <li>再放其余车（随机打乱）——它们的颜色顺序不构成可解性承诺，
     *       被堵死时由「移除 / 排序」道具处理。</li>
     * </ol>
     * 相邻同色合并成一组（与原来一致）。
     */
    private List<PassengerGroup> buildQueue(List<Vehicle> layout, List<Integer> escapable) {
        List<Integer> order = new ArrayList<>(escapable);
        Collections.shuffle(order, random);

        List<Integer> rest = new ArrayList<>();
        for (int i = 0; i < layout.size(); i++) {
            if (!escapable.contains(i)) {
                rest.add(i);
            }
        }
        Collections.shuffle(rest, random);
        order.addAll(rest);

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
}
