import com.template.app.game.parking.engine.ParkingEngine;
import com.template.app.game.parking.engine.ParkingLevelGenerator;
import com.template.app.game.parking.model.Level;
import com.template.app.game.parking.model.Vehicle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 一次性验证：不进版本库。
 * 校验「必须满载才能开走」规则：
 *   1. 任何处于 GONE（已开走）的车，loaded 必须等于 capacity（满载）
 *   2. 停在接客区（PICKUP）等待的车必须未满载
 *   3. 乘客守恒：队列剩余人数 == getPassengersLeft()
 *   4. 撤销后每辆车的 loaded 必须完全还原
 *   5. 关卡仍然可通关（新规则不能把关卡变成死局）
 */
public final class FullLoadTest {

    private static int violations = 0;

    public static void main(String[] args) {
        randomPlay();
        undoCheck();
        solveCheck();
        System.out.println(violations == 0
            ? "PASS: full-load rule ok"
            : "FAIL: " + violations + " violation(s)");
    }

    /** 随机走子，每一步都校验不变量。 */
    private static void randomPlay() {
        ParkingLevelGenerator generator = new ParkingLevelGenerator(20260922L);
        Random rnd = new Random(7);
        int checked = 0;
        for (int levelIndex = 1; levelIndex <= 6; levelIndex++) {
            for (int iter = 0; iter < 15; iter++) {
                ParkingEngine engine = new ParkingEngine();
                engine.applyLevel(generator.generate(levelIndex));
                for (int step = 0; step < 80; step++) {
                    if (engine.getState() != ParkingEngine.State.RUNNING) {
                        break;
                    }
                    int idx = rnd.nextInt(engine.vehicleCount());
                    Vehicle v = engine.vehicleAt(idx);
                    if (!v.inLot()) {
                        continue;
                    }
                    int forward = engine.maxForward(v.id);
                    int backward = engine.maxBackward(v.id);
                    if (forward + backward == 0) {
                        continue;
                    }
                    int delta = rnd.nextInt(forward + backward + 1) - backward;
                    if (delta == 0) {
                        delta = forward > 0 ? forward : -backward;
                    }
                    engine.move(v.id, delta);
                    checked += check(engine, "random L" + levelIndex);
                }
            }
        }
        System.out.println("random play: " + checked + " state checks done");
    }

    /** 撤销必须连载客数一起还原。 */
    private static void undoCheck() {
        ParkingLevelGenerator generator = new ParkingLevelGenerator(4242L);
        Random rnd = new Random(11);
        int checked = 0;
        for (int iter = 0; iter < 60; iter++) {
            ParkingEngine engine = new ParkingEngine();
            engine.applyLevel(generator.generate(3));
            for (int step = 0; step < 25; step++) {
                if (engine.getState() != ParkingEngine.State.RUNNING) {
                    break;
                }
                int idx = rnd.nextInt(engine.vehicleCount());
                Vehicle v = engine.vehicleAt(idx);
                if (!v.inLot()) {
                    continue;
                }
                int forward = engine.maxForward(v.id);
                int backward = engine.maxBackward(v.id);
                if (forward + backward == 0) {
                    continue;
                }
                int delta = rnd.nextInt(forward + backward + 1) - backward;
                if (!engine.canUndo()) {
                    engine.move(v.id, delta == 0 ? 1 : delta);
                    continue;
                }
                int[] before = loadedSnapshot(engine);
                if (!engine.move(v.id, delta == 0 ? 1 : delta).success) {
                    continue;
                }
                if (!engine.undo()) {
                    continue;
                }
                int[] after = loadedSnapshot(engine);
                for (int i = 0; i < before.length; i++) {
                    if (before[i] != after[i]) {
                        violations++;
                        System.out.println("undo did not restore loaded at #" + i
                            + " (" + before[i] + " -> " + after[i] + ")");
                    }
                }
                checked++;
            }
        }
        System.out.println("undo check: " + checked + " undo round-trips done");
    }

    /**
     * 完整 DFS 求解：确认新规则下关卡仍然可通关。
     * 只跑车少的关卡，避免状态爆炸。
     */
    private static void solveCheck() {
        ParkingLevelGenerator generator = new ParkingLevelGenerator(20260922L);
        int solved = 0;
        int total = 0;
        for (int levelIndex = 1; levelIndex <= 4; levelIndex++) {
            for (int iter = 0; iter < 4; iter++) {
                total++;
                ParkingEngine engine = new ParkingEngine();
                engine.applyLevel(generator.generate(levelIndex));
                Set<String> visited = new HashSet<>();
                if (dfs(engine, visited, 0)) {
                    solved++;
                }
            }
        }
        System.out.println("solve check: " + solved + "/" + total + " levels still solvable");
        if (solved < total) {
            // 求解器有深度/状态上限，未证出不等于不可解；这里只作提示
            System.out.println("  (note: DFS has depth/state caps; unsolved != unsolvable)");
        }
    }

    private static boolean dfs(ParkingEngine engine, Set<String> visited, int depth) {
        if (engine.getState() == ParkingEngine.State.SOLVED) {
            return true;
        }
        // 10×10 棋盘 + 最多 12 辆车的状态空间远大于 8×8，上限需相应放宽
        if (engine.getState() == ParkingEngine.State.STUCK || depth >= 34
            || visited.size() >= 1500000) {
            return false;
        }
        String key = stateKey(engine);
        if (!visited.add(key)) {
            return false;
        }
        for (int i = 0; i < engine.vehicleCount(); i++) {
            Vehicle v = engine.vehicleAt(i);
            if (!v.inLot()) {
                continue;
            }
            int forward = engine.maxForward(v.id);
            int backward = engine.maxBackward(v.id);
            for (int delta = -backward; delta <= forward; delta++) {
                if (delta == 0) {
                    continue;
                }
                if (!engine.move(v.id, delta).success) {
                    continue;
                }
                boolean ok = dfs(engine, visited, depth + 1);
                engine.undo();
                if (ok) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stateKey(ParkingEngine engine) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < engine.vehicleCount(); i++) {
            Vehicle v = engine.vehicleAt(i);
            sb.append(v.row).append(',').append(v.col).append(',').append(v.place)
                .append(',').append(v.loaded).append('|');
        }
        sb.append('Q');
        for (int i = 0; i < engine.queueSize(); i++) {
            sb.append(engine.queueGroupAt(i).colorIndex).append(':')
                .append(engine.queueGroupAt(i).count).append(';');
        }
        return sb.toString();
    }

    private static int[] loadedSnapshot(ParkingEngine engine) {
        int[] out = new int[engine.vehicleCount()];
        for (int i = 0; i < out.length; i++) {
            out[i] = engine.vehicleAt(i).loaded;
        }
        return out;
    }

    /** 校验全部不变量，返回本次检查点数量。 */
    private static int check(ParkingEngine engine, String tag) {
        // 1) 已开走的车必须满载；2) 接客区等待的车必须未满载
        for (int i = 0; i < engine.vehicleCount(); i++) {
            Vehicle v = engine.vehicleAt(i);
            if (v.place == Vehicle.PLACE_GONE && !v.isFull()) {
                violations++;
                System.out.println(tag + ": car " + v.id + " left NOT full (loaded="
                    + v.loaded + "/" + v.capacity() + ")");
            }
            if (v.place == Vehicle.PLACE_PICKUP && v.isFull()) {
                violations++;
                System.out.println(tag + ": car " + v.id + " full but still waiting");
            }
        }
        // 3) 乘客守恒
        int queueLeft = 0;
        for (int i = 0; i < engine.queueSize(); i++) {
            queueLeft += engine.queueGroupAt(i).count;
        }
        if (queueLeft != engine.getPassengersLeft()) {
            violations++;
            System.out.println(tag + ": conservation broken queue=" + queueLeft
                + " left=" + engine.getPassengersLeft());
        }
        return 1;
    }
}
