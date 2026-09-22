import com.template.app.game.parking.engine.ParkingConfig;
import com.template.app.game.parking.engine.ParkingLevelCodec;
import com.template.app.game.parking.model.Level;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 一次性验证：不进版本库。
 * 端到端校验随包预置池：解析 TSV -> 逐条解码，确认配置版本、桶、车数、可解性元数据自洽。
 * 等价于把 App 运行时的 seedFromAsset + fetch 路径在 JVM 上先跑一遍。
 */
public final class SeedCheck {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0
            ? args[0]
            : "app/src/main/assets/parking_levels.tsv";
        int expectedVersion = ParkingConfig.configVersion();
        int fail = 0;
        int rows = 0;
        Map<Integer, Integer> perBucket = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                rows++;
                String[] f = line.split("\t");
                if (f.length != 6) {
                    fail++;
                    System.out.println("bad field count: " + line);
                    continue;
                }
                int version = Integer.parseInt(f[0]);
                int bucket = Integer.parseInt(f[1]);
                int vehicleCount = Integer.parseInt(f[2]);
                int minMoves = Integer.parseInt(f[5]);
                if (version != expectedVersion) {
                    fail++;
                    System.out.println("stale config_version: " + version);
                    continue;
                }
                // 用该桶的代表关卡号解码（桶内车数/步数一致，关卡号只影响 Level.levelIndex 字段）
                Level level = ParkingLevelCodec.decode(1, f[3], f[4]);
                if (level == null) {
                    fail++;
                    System.out.println("decode failed: " + line);
                    continue;
                }
                if (level.vehicles.size() != vehicleCount) {
                    fail++;
                    System.out.println("vehicleCount mismatch: " + line);
                }
                // 队列人数必须等于全部车辆容量之和（守恒量，GDD 机制 5）
                int capacity = 0;
                for (int i = 0; i < level.vehicles.size(); i++) {
                    capacity += level.vehicles.get(i).capacity();
                }
                if (capacity != level.totalPassengers) {
                    fail++;
                    System.out.println("passenger conservation broken: " + line);
                }
                perBucket.merge(bucket, 1, Integer::sum);
                if (minMoves <= 0) {
                    fail++;
                }
            }
        }

        System.out.println("rows = " + rows + ", buckets = " + perBucket);
        System.out.println(fail == 0
            ? "PASS: seed asset valid (" + rows + " levels)"
            : "FAIL: " + fail + " problem(s)");
    }
}
