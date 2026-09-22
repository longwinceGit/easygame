import com.template.app.game.parking.engine.ParkingConfig;
import com.template.app.game.parking.engine.ParkingLevelGenerator;
import com.template.app.game.parking.model.Level;

/**
 * 一次性验证：不进版本库。
 * 复现崩溃场景——关卡 2（6 辆车）在后台线程（parking-level-gen）上大量生成。
 * 之前必崩于 ParkingLevelGenerator$Board.extract 的数组越界；
 * 修复后应零异常，且绝大多数关卡为满车数（非全部退化成 2 车保底关）。
 */
public final class Stress {

    private static final int LEVEL = 2;          // 6 辆车
    private static final int ITER = 400;         // 总次数
    private static final int THREADS = 4;        // 模拟后台线程并发

    public static void main(String[] args) throws Exception {
        int per = ITER / THREADS;
        int target = ParkingConfig.vehicleCount(LEVEL);
        System.out.println("generate(" + LEVEL + ") = " + target + " cars, " + ITER
            + " iterations, " + THREADS + " background threads (parking-level-gen)");

        long t0 = System.nanoTime();
        Thread[] ts = new Thread[THREADS];
        final int[] errors = {0};
        final int[] emergency = {0};   // degraded to 2-car fallback
        final int[] degraded = {0};    // fewer than target cars but >2
        final int[] full = {0};        // full vehicle-count normal level
        final long[] worstNs = {0};
        final StringBuilder errMsg = new StringBuilder();

        for (int t = 0; t < THREADS; t++) {
            final long seed = 20260921L + t * 1000L;
            ts[t] = new Thread(() -> {
                ParkingLevelGenerator g = new ParkingLevelGenerator(seed);
                for (int i = 0; i < per; i++) {
                    long s = System.nanoTime();
                    try {
                        Level level = g.generate(LEVEL);
                        long dt = System.nanoTime() - s;
                        synchronized (worstNs) { if (dt > worstNs[0]) worstNs[0] = dt; }
                        int v = level.vehicles.size();
                        if (v <= 2) emergency[0]++;
                        else if (v < target) degraded[0]++;
                        else full[0]++;
                    } catch (Throwable e) {
                        errors[0]++;
                        synchronized (errMsg) {
                            if (errMsg.length() < 3000) {
                                errMsg.append("SEED=").append(seed).append(" i=").append(i)
                                    .append(" -> ").append(e).append("\n");
                            }
                        }
                    }
                }
            }, "parking-level-gen-" + t);
        }
        for (Thread th : ts) th.start();
        for (Thread th : ts) th.join();

        long dt = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("total " + dt + "ms, worst single "
            + (worstNs[0] / 1_000_000) + "ms");
        System.out.println("crash(errors)   = " + errors[0]);
        System.out.println("full normal     = " + full[0]);
        System.out.println("degraded(<" + target + ") = " + degraded[0]);
        System.out.println("2-car fallback  = " + emergency[0]);
        System.out.println(errors[0] == 0
            ? "PASS: zero exceptions"
            : "FAIL: still crashing\n" + errMsg);
    }
}
