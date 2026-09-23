import com.template.app.game.parking.engine.ParkingConfig;
import com.template.app.game.parking.engine.ParkingLevelCodec;
import com.template.app.game.parking.engine.ParkingLevelGenerator;
import com.template.app.game.parking.model.Level;
import com.template.app.game.parking.model.PassengerGroup;
import com.template.app.game.parking.model.Vehicle;

/**
 * 一次性验证：不进版本库。
 * 校验 ParkingLevelCodec 的 round-trip 无损性 + 防御性 + 桶映射/配置指纹。
 * 编解码是关卡缓存的正确性基石：错了要么静默回退，要么把坏关卡塞进引擎。
 */
public final class CodecTest {

    private static int fail = 0;

    public static void main(String[] args) {
        roundTrip();
        defensive();
        buckets();
        System.out.println(fail == 0 ? "PASS: codec ok" : "FAIL: " + fail + " problem(s)");
    }

    /** 生成 -> 编码 -> 解码 -> 必须完全一致。 */
    private static void roundTrip() {
        ParkingLevelGenerator g = new ParkingLevelGenerator(20260922L);
        int checked = 0;
        for (int lvl = 1; lvl <= 10; lvl++) {
            for (int i = 0; i < 20; i++) {
                Level src = g.generate(lvl);
                String layout = ParkingLevelCodec.encodeLayout(src.vehicles);
                String queue = ParkingLevelCodec.encodeQueue(src.queue);
                Level back = ParkingLevelCodec.decode(lvl, layout, queue);
                checked++;
                if (back == null) {
                    fail++;
                    System.out.println("decode null at L" + lvl + " #" + i);
                    continue;
                }
                if (!same(src, back)) {
                    fail++;
                    System.out.println("MISMATCH at L" + lvl + " #" + i
                        + "\n  src layout=" + layout + " queue=" + queue);
                }
            }
        }
        System.out.println("round-trip checked " + checked + " levels");
    }

    private static boolean same(Level a, Level b) {
        if (a.levelIndex != b.levelIndex || a.totalPassengers != b.totalPassengers) {
            return false;
        }
        if (a.vehicles.size() != b.vehicles.size() || a.queue.size() != b.queue.size()) {
            return false;
        }
        for (int i = 0; i < a.vehicles.size(); i++) {
            Vehicle x = a.vehicles.get(i);
            Vehicle y = b.vehicles.get(i);
            if (x.id != y.id || x.length != y.length || x.horizontal != y.horizontal
                || x.direction != y.direction || x.colorIndex != y.colorIndex
                || x.row != y.row || x.col != y.col) {
                return false;
            }
        }
        for (int i = 0; i < a.queue.size(); i++) {
            PassengerGroup x = a.queue.get(i);
            PassengerGroup y = b.queue.get(i);
            if (x.colorIndex != y.colorIndex || x.count != y.count) {
                return false;
            }
        }
        return true;
    }

    /** 损坏/越界输入必须返回 null（让调用方回退在线生成），而不是抛异常或产出坏关卡。 */
    private static void defensive() {
        String[] badLayouts = {
            null, "", "abc", "0,0,2", "0,0,2,0,0",                 // 字段数不对
            "0,0,2,0,0,99",                                          // colorIndex 越界
            "-1,0,2,0,0,0",                                          // row 越界
            "0,9,2,1,3,0",                                           // 横向 col+len 越界（10 列）
            "9,0,2,0,1,0",                                           // 纵向 row+len 越界（10 行）
            "0,0,9,0,0,0",                                           // length 非法
        };
        for (String bad : badLayouts) {
            Level l = ParkingLevelCodec.decode(1, bad, "0:2");
            if (l != null) {
                fail++;
                System.out.println("bad layout not rejected: " + bad);
            }
        }
        String[] badQueues = {null, "", "x", "0", "99:2", "0:0", "0:-1"};
        for (String bad : badQueues) {
            Level l = ParkingLevelCodec.decode(1, "0,0,2,0,0,0", bad);
            if (l != null) {
                fail++;
                System.out.println("bad queue not rejected: " + bad);
            }
        }
        System.out.println("defensive cases done");
    }

    /** 难度桶必须在 L8+ 饱和（有限池服务无限关卡号的前提）。 */
    private static void buckets() {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        int changes = 0;
        for (int lvl = 1; lvl <= 30; lvl++) {
            int b = ParkingConfig.bucketOf(lvl);
            if (b != prev) {
                changes++;
                prev = b;
                sb.append(" L").append(lvl).append("->").append(b);
            }
        }
        System.out.println("distinct buckets over L1..L30 = " + changes + ":" + sb);
        // 桶数应等于车数爬坡段长度（首关 → 封顶，每关 +1），超过说明没有饱和
        int ramp = ParkingConfig.MAX_VEHICLES - ParkingConfig.START_VEHICLES + 1;
        if (changes > ramp) {
            fail++;
            System.out.println("bucket count " + changes + " exceeds ramp " + ramp);
        }
        System.out.println("configVersion = " + ParkingConfig.configVersion());
    }
}
