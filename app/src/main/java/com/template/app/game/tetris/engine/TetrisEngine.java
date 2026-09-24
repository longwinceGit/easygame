package com.template.app.game.tetris.engine;

import com.template.app.game.tetris.model.Board;
import com.template.app.game.tetris.model.Tetromino;
import com.template.app.game.tetris.model.TetrominoType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 拖拽方块的游戏引擎：状态机 + 计分 + 方块生成 + 结束判定。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>零 android.* 依赖</b>，可被纯 JVM 单测直接覆盖（ADR-003）</li>
 *   <li>支持注入随机种子，便于复现特定局面</li>
 *   <li>渲染层可直接读取棋盘（ADR-004），但所有状态变更必须经由本类的命令方法</li>
 * </ul>
 */
public final class TetrisEngine {

    public enum State {
        /** 未开局 */
        READY,
        /** 进行中，可接受输入 */
        RUNNING,
        /** 已暂停，不接受输入 */
        PAUSED,
        /** 已结束 */
        GAME_OVER
    }

    private final Board board;
    private final Tetromino[] tray;
    private final Random random;
    private final List<TetrominoType> bag = new ArrayList<>();

    private State state = State.READY;
    private int score;
    private int lines;
    private int level = 1;
    private int combo;

    public TetrisEngine() {
        this(System.nanoTime());
    }

    /** 使用固定随机种子构造，用于单测复现。 */
    public TetrisEngine(long seed) {
        this.board = new Board(TetrisConfig.BOARD_WIDTH, TetrisConfig.BOARD_HEIGHT);
        this.tray = new Tetromino[TetrisConfig.TRAY_SIZE];
        this.random = new Random(seed);
    }

    // ==================================================================
    // 命令（产生状态变化）
    // ==================================================================

    /** 开始新的一局。 */
    public void start() {
        board.clear();
        for (int i = 0; i < tray.length; i++) {
            tray[i] = null;
        }
        bag.clear();
        score = 0;
        lines = 0;
        level = 1;
        combo = 0;
        refillTray();
        state = State.RUNNING;
    }

    public void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
        }
    }

    public void resume() {
        if (state == State.PAUSED) {
            state = State.RUNNING;
        }
    }

    /** 重新开始（等价于 start）。 */
    public void restart() {
        start();
    }

    /** 旋转托盘中指定槽位的方块（顺时针 90°）。 */
    public void rotate(int slot) {
        if (!isRunning() || !isValidSlot(slot)) {
            return;
        }
        Tetromino piece = tray[slot];
        if (piece != null) {
            piece.rotate();
        }
    }

    /**
     * 将托盘中指定槽位的方块放置到 {@code left} 列，硬降落到该列最底可落位置。
     *
     * @param slot    托盘槽位
     * @param left    目标列（左侧列号）
     * @param fromTop 期望的进入行（手指高度）。必须与预览用的取值一致，
     *                否则会出现"预览显示能放进去、松手却回弹"的不一致。
     * @return 放置结果；失败时 {@link PlaceResult#success} 为 false，棋盘与托盘均不变
     */
    public PlaceResult place(int slot, int left, int fromTop) {
        if (!isRunning() || !isValidSlot(slot)) {
            return PlaceResult.failure();
        }
        Tetromino piece = tray[slot];
        if (piece == null) {
            return PlaceResult.failure();
        }
        int[][] shape = piece.shape();
        int row = board.landingRow(shape, left, fromTop);
        if (row < 0) {
            return PlaceResult.failure();
        }

        board.put(shape, left, row, piece.paletteIndex() + 1);
        tray[slot] = null;

        int[] cleared = board.clearFullRows();
        int clearedCount = cleared.length;
        int gained = 0;

        if (clearedCount > 0) {
            // 用消行前的等级计分，避免同一次消除内等级跳变造成公式歧义
            int bonusIndex = Math.min(clearedCount, TetrisConfig.MULTI_BONUS.length - 1);
            gained = (clearedCount * TetrisConfig.LINE_SCORE
                + TetrisConfig.MULTI_BONUS[bonusIndex]) * level;
            combo++;
            if (combo >= 2) {
                gained += (combo - 1) * TetrisConfig.COMBO_UNIT * level;
            }
            lines += clearedCount;
            level = lines / TetrisConfig.LEVEL_LINES + 1;
        } else {
            // 未消行：不得分，且打断连击
            combo = 0;
        }
        score += gained;

        // 放一块即补一块：空出的槽位立刻生成新方块，玩家无需等三块都用完。
        // refillTray() 本身就是"只填 null 槽"，故这里无条件调用即可。
        // 顺序不可颠倒：先补充，再判定是否无处可放（GDD 机制 5）。
        refillTray();
        boolean gameOver = !hasAnyMove();
        if (gameOver) {
            state = State.GAME_OVER;
        }
        return PlaceResult.success(cleared, gained, combo, gameOver, row, left);
    }

    // ==================================================================
    // 查询（无副作用）
    // ==================================================================

    /** 棋盘格子数组引用（<b>只读</b>，禁止修改）。 */
    public int[][] getCells() {
        return board.cells();
    }

    public int getBoardWidth() {
        return board.width();
    }

    public int getBoardHeight() {
        return board.height();
    }

    public int traySize() {
        return tray.length;
    }

    /** 托盘中指定槽位的方块；已消耗则为 null。 */
    public Tetromino getTrayPiece(int slot) {
        return isValidSlot(slot) ? tray[slot] : null;
    }

    /**
     * 指定槽位方块在列 {@code left} 上的落点行号。
     *
     * @param fromTop 期望的进入行（手指高度）。不足时会向上顶开，再向下重力吸附。
     * @return 落点行号；该列完全放不下时返回 -1
     */
    public int landingRow(int slot, int left, int fromTop) {
        Tetromino piece = getTrayPiece(slot);
        if (piece == null) {
            return -1;
        }
        return board.landingRow(piece.shape(), left, fromTop);
    }

    /**
     * 判断指定槽位的方块在<b>所有旋转 × 所有列 × 所有行</b>下是否存在合法落点。
     * <p>
     * 这是结束判定的原子操作（GDD 机制 5）。
     * <b>必须与放置规则使用同一套可达性语义</b>：既然放置允许玩家从任意高度进入，
     * 结束判定就不能只看"从棋盘顶部是否可达"，否则会出现"明明还能塞进洞里却已经结束"。
     * 从最底部向上找，等价于"存在任意可放置的行"。
     */
    public boolean canPlaceAnywhere(int slot) {
        Tetromino piece = getTrayPiece(slot);
        if (piece == null) {
            return false;
        }
        TetrominoType type = piece.type();
        for (int rotation = 0; rotation < 4; rotation++) {
            int[][] shape = type.shape(rotation);
            int maxLeft = board.width() - shape[0].length;
            int fromTop = board.height() - shape.length;
            for (int left = 0; left <= maxLeft; left++) {
                if (board.landingRow(shape, left, fromTop) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 托盘中是否还存在任何可落子的方块。 */
    public boolean hasAnyMove() {
        for (int slot = 0; slot < tray.length; slot++) {
            if (tray[slot] != null && canPlaceAnywhere(slot)) {
                return true;
            }
        }
        return false;
    }

    public int getScore() {
        return score;
    }

    public int getLines() {
        return lines;
    }

    public int getLevel() {
        return level;
    }

    public int getCombo() {
        return combo;
    }

    public State getState() {
        return state;
    }

    // ==================================================================
    // 会话存档（退出后继续）
    // ==================================================================

    /**
     * 一局进行中的快照，用于"退出只是暂停、下次进来可继续"（对齐挪车的进度持久化）。
     * <p>
     * 只存<b>可静态描述的数据</b>：棋盘格子、托盘方块（类型 + 旋转态）、分数类字段。
     * 7-bag 的剩余序列刻意不存——继续后从新的一袋开始，对可玩性无影响。
     * <p>
     * <b>纯 Java，不依赖 android.*（ADR-003）。</b>
     */
    public static final class SessionSnapshot {

        /** 棋盘格子：0 = 空，1..7 = 方块调色板索引 + 1。 */
        public final int[][] cells;

        /** 托盘每槽的 {@link TetrominoType} 序号；-1 = 该槽为空。 */
        public final int[] trayTypes;

        /** 托盘每槽的旋转态，与 {@link #trayTypes} 一一对应。 */
        public final int[] trayRotations;

        public final int score;
        public final int lines;
        public final int level;
        public final int combo;

        public SessionSnapshot(int[][] cells, int[] trayTypes, int[] trayRotations,
                               int score, int lines, int level, int combo) {
            this.cells = cells;
            this.trayTypes = trayTypes;
            this.trayRotations = trayRotations;
            this.score = score;
            this.lines = lines;
            this.level = level;
            this.combo = combo;
        }
    }

    /** 导出当前局面快照。棋盘会<b>深拷贝</b>，快照与引擎后续状态互不影响。 */
    public SessionSnapshot snapshot() {
        int[][] src = board.cells();
        int[][] copy = new int[src.length][];
        for (int r = 0; r < src.length; r++) {
            copy[r] = src[r].clone();
        }
        int[] types = new int[tray.length];
        int[] rotations = new int[tray.length];
        for (int i = 0; i < tray.length; i++) {
            Tetromino piece = tray[i];
            types[i] = piece == null ? -1 : piece.type().ordinal();
            rotations[i] = piece == null ? 0 : piece.rotation();
        }
        return new SessionSnapshot(copy, types, rotations, score, lines, level, combo);
    }

    /**
     * 从快照恢复局面（继续上次未完成的局）。
     * <p>
     * 非法数据（棋盘尺寸不符、类型越界）一律返回 {@code false}，由调用方开新局兜底。
     * 空槽会被补满——存档若来自旧版本可能缺槽。
     *
     * @return 是否恢复成功
     */
    public boolean restore(SessionSnapshot snapshot) {
        if (snapshot == null || !board.restore(snapshot.cells)) {
            return false;
        }
        for (int i = 0; i < tray.length; i++) {
            int ordinal = i < snapshot.trayTypes.length ? snapshot.trayTypes[i] : -1;
            if (ordinal < 0 || ordinal >= TetrominoType.TYPES.length) {
                tray[i] = null;
                continue;
            }
            int rotation = i < snapshot.trayRotations.length ? snapshot.trayRotations[i] : 0;
            tray[i] = new Tetromino(TetrominoType.TYPES[ordinal], rotation);
        }
        refillTray();
        score = snapshot.score;
        lines = snapshot.lines;
        level = Math.max(1, snapshot.level);
        combo = Math.max(0, snapshot.combo);
        state = State.RUNNING;
        return true;
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    private boolean isRunning() {
        return state == State.RUNNING;
    }

    private boolean isValidSlot(int slot) {
        return slot >= 0 && slot < tray.length;
    }

    private void refillTray() {
        for (int i = 0; i < tray.length; i++) {
            if (tray[i] == null) {
                tray[i] = new Tetromino(nextType());
            }
        }
    }

    /** 7-bag 随机：每 7 个方块内 7 种类型各出现一次，避免出现死亡序列。 */
    private TetrominoType nextType() {
        if (bag.isEmpty()) {
            Collections.addAll(bag, TetrominoType.TYPES);
            Collections.shuffle(bag, random);
        }
        return bag.remove(bag.size() - 1);
    }
}
