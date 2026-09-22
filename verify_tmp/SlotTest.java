import com.template.app.game.parking.engine.BoardStep;
import com.template.app.game.parking.engine.MoveResult;
import com.template.app.game.parking.engine.ParkingConfig;
import com.template.app.game.parking.engine.ParkingEngine;
import com.template.app.game.parking.engine.ParkingLevelGenerator;
import com.template.app.game.parking.model.Level;
import com.template.app.game.parking.model.Vehicle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 一次性验证：不进版本库。
 * 校验接客区"固定车位"规则：
 *   1. 同一时刻车位不重复、且在 [0, PICKUP_SLOTS) 内
 *   2. 车一旦进入接客区，车位<b>不再变动</b>（这是本次修复的核心：以前会整体左移）
 *   3. 离场车辆的 BoardStep.slot == 它自己进入时分配的那个车位（不再横移）
 *   4. 不在接客区的车必须已释放车位（slot == -1）
 *   5. 撤销后车位完全还原
 */
public final class SlotTest {

    private static int violations = 0;

    public static void main(String[] args) {
        randomPlay();
        undoCheck();
        System.out.println(violations == 0
            ? "PASS: pickup slot assignment ok"
            : "FAIL: " + violations + " violation(s)");
    }

    private static void randomPlay() {
        ParkingLevelGenerator generator = new ParkingLevelGenerator(20260922L);
        Random rnd = new Random(3);
        int checked = 0;
        for (int levelIndex = 1; levelIndex <= 6; levelIndex++) {
            for (int iter = 0; iter < 15; iter++) {
                ParkingEngine engine = new ParkingEngine();
                engine.applyLevel(generator.generate(levelIndex));
                Map<Integer, Integer> assigned = new HashMap<>();
                for (int step = 0; step < 100; step++) {
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
                    // 移动前记录各车车位，用于核对离场步骤是否横移
                    Map<Integer, Integer> before = snapshotSlots(engine);
                    MoveResult result = engine.move(v.id, delta);
                    if (result.success && result.boardSteps != null) {
                        for (BoardStep bs : result.boardSteps) {
                            Integer prev = before.get(bs.vehicleId);
                            // prev 为 null/-1 表示这辆车移动前还在停车场（尚未分配车位），
                            // 它是"刚驶出即对色上客"，车位是本次才分配的，不参与比对。
                            if (prev != null && prev >= 0 && prev != bs.slot) {
                                violations++;
                                System.out.println("board step slot mismatch: car "
                                    + bs.vehicleId + " parked at " + prev
                                    + " but boards at " + bs.slot);
                            }
                        }
                    }
                    check(engine, assigned);
                    checked++;
                }
            }
        }
        System.out.println("random play: " + checked + " moves checked");
    }

    private static void undoCheck() {
        ParkingLevelGenerator generator = new ParkingLevelGenerator(555L);
        Random rnd = new Random(9);
        int roundTrips = 0;
        for (int iter = 0; iter < 40; iter++) {
            ParkingEngine engine = new ParkingEngine();
            engine.applyLevel(generator.generate(4));
            for (int step = 0; step < 30; step++) {
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
                Map<Integer, Integer> before = snapshotSlots(engine);
                if (!engine.move(v.id, delta == 0 ? 1 : delta).success) {
                    continue;
                }
                if (!engine.undo()) {
                    continue;
                }
                Map<Integer, Integer> after = snapshotSlots(engine);
                if (!before.equals(after)) {
                    violations++;
                    System.out.println("undo did not restore slots: " + before + " -> " + after);
                }
                roundTrips++;
            }
        }
        System.out.println("undo check: " + roundTrips + " undo round-trips done");
    }

    private static Map<Integer, Integer> snapshotSlots(ParkingEngine engine) {
        Map<Integer, Integer> out = new HashMap<>();
        for (int i = 0; i < engine.vehicleCount(); i++) {
            Vehicle v = engine.vehicleAt(i);
            out.put(v.id, v.slot);
        }
        return out;
    }

    /** 校验车位分配的即时不变量。 */
    private static void check(ParkingEngine engine, Map<Integer, Integer> assigned) {
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < engine.vehicleCount(); i++) {
            Vehicle v = engine.vehicleAt(i);
            if (v.inPickup()) {
                if (v.slot < 0 || v.slot >= ParkingConfig.PICKUP_SLOTS) {
                    violations++;
                    System.out.println("car " + v.id + " in pickup with invalid slot " + v.slot);
                    continue;
                }
                if (!used.add(v.slot)) {
                    violations++;
                    System.out.println("slot " + v.slot + " occupied twice (car " + v.id + ")");
                }
                Integer prev = assigned.get(v.id);
                if (prev != null && prev != v.slot) {
                    violations++;
                    System.out.println("car " + v.id + " slot moved " + prev
                        + " -> " + v.slot + " (must stay fixed)");
                }
                assigned.put(v.id, v.slot);
            } else {
                if (v.slot != -1) {
                    violations++;
                    System.out.println("car " + v.id + " left pickup but slot not released: "
                        + v.slot);
                }
                assigned.remove(v.id);
            }
        }
    }
}
