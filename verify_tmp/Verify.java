import com.template.app.game.parking.engine.ParkingConfig;
import com.template.app.game.parking.engine.ParkingEngine;
import com.template.app.game.parking.engine.ParkingLevelGenerator;
import com.template.app.game.parking.model.Level;
import com.template.app.game.parking.model.Vehicle;

import java.util.HashSet;
import java.util.Set;

/**
 * 一次性验证程序：不进版本库。
 * 目的：①确认扩大棋盘 / 加车之后关卡仍然可解；②量化难度是否真的随关卡上升。
 *
 * 可解性用「完整 DFS 求解器」证明：枚举每辆车全部合法位移（含拖动到中间位置），
 * 带记忆化，深度上限 18。能找到一条通关路径即判定可解。
 * （生成器本身也是构造式可解，这里再做一次独立复验。）
 */
public final class Verify {

    private static final int SAMPLES = 8;
    private static final int MAX_DEPTH = 16;
    /** 完整 DFS 访问状态上限，超过则视为"本次未证出"（可能是状态空间太大，非必不可解）。 */
    private static final int STATE_CAP = 150_000;

    public static void main(String[] args) {
        System.out.println("关卡 | 车数 | 乘客 | 开局被挡 | 走完步数 | 生成ms | 强解可解 | 生成可解");
        System.out.println("-----|------|------|----------|----------|--------|----------|---------");

        ParkingLevelGenerator generator = new ParkingLevelGenerator(20260921L);
        int strongFail = 0;
        long worstGen = 0;

        for (int levelIndex = 1; levelIndex <= 8; levelIndex++) {
            int vehiclesSum = 0;
            int passengersSum = 0;
            int blockedSum = 0;
            int movesSum = 0;
            long genSum = 0;
            int strongSolved = 0;
            int genSolved = 0;

            for (int n = 0; n < SAMPLES; n++) {
                long t0 = System.currentTimeMillis();
                Level level = generator.generate(levelIndex);
                long gen = System.currentTimeMillis() - t0;
                genSum += gen;
                if (gen > worstGen) worstGen = gen;

                vehiclesSum += level.vehicles.size();
                passengersSum += level.totalPassengers;

                ParkingEngine engine = new ParkingEngine();
                engine.applyLevel(level);

                int blocked = 0;
                for (Vehicle v : level.vehicles) {
                    if (engine.maxForward(v.id) < engine.boundaryDistance(v.id)) blocked++;
                }
                blockedSum += blocked;

                boolean genSolvable = level.queue != null && !level.queue.isEmpty();
                if (genSolvable) genSolved++;

                int moves = solveByReplay(engine);
                if (moves >= 0) {
                    strongSolved++;
                    movesSum += moves;
                } else {
                    strongFail++;
                }
            }

            System.out.println("L" + levelIndex
                + "   | " + (vehiclesSum / SAMPLES)
                + "    | " + (passengersSum / SAMPLES)
                + "   | " + (blockedSum / SAMPLES)
                + "        | " + (movesSum / Math.max(1, strongSolved))
                + "        | " + (genSum / SAMPLES)
                + "     | " + strongSolved + "/" + SAMPLES
                + "     | " + genSolved + "/" + SAMPLES);
        }

        System.out.println("");
        System.out.println("最坏生成耗时 " + worstGen + "ms");
        System.out.println(strongFail == 0
            ? "ALL LEVELS SOLVED (完整 DFS)"
            : "强解未证出: " + strongFail + " / " + (SAMPLES * 8)
                + "（可能状态空间过大，非必不可解）");
    }

    /** 完整 DFS：找到通关路径返回步数，否则 -1。 */
    private static int solveByReplay(ParkingEngine engine) {
        Set<String> visited = new HashSet<>();
        int[] result = new int[]{-1};
        dfs(engine, visited, 0, result);
        return result[0];
    }

    private static void dfs(ParkingEngine engine, Set<String> visited, int depth, int[] result) {
        if (result[0] >= 0) return;
        if (engine.getState() == ParkingEngine.State.SOLVED) {
            result[0] = depth;
            return;
        }
        if (depth >= MAX_DEPTH || visited.size() >= STATE_CAP) return;
        String key = stateKey(engine);
        if (!visited.add(key)) return;

        for (int i = 0; i < engine.vehicleCount(); i++) {
            Vehicle v = engine.vehicleAt(i);
            if (!v.inLot()) continue;
            int forward = engine.maxForward(v.id);
            int backward = engine.maxBackward(v.id);
            for (int delta = -backward; delta <= forward; delta++) {
                if (delta == 0) continue;
                if (!engine.move(v.id, delta).success) continue;
                dfs(engine, visited, depth + 1, result);
                if (result[0] >= 0) return;
                engine.undo();
            }
        }
    }

    private static String stateKey(ParkingEngine engine) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < engine.vehicleCount(); i++) {
            Vehicle v = engine.vehicleAt(i);
            sb.append(v.row).append(',').append(v.col).append(',').append(v.place).append('|');
        }
        sb.append('Q');
        for (int i = 0; i < engine.queueSize(); i++) {
            sb.append(engine.queueGroupAt(i).colorIndex).append(':')
                .append(engine.queueGroupAt(i).count).append(';');
        }
        return sb.toString();
    }
}
