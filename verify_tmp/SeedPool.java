import com.template.app.game.parking.engine.ParkingConfig;
import com.template.app.game.parking.engine.ParkingLevelCodec;
import com.template.app.game.parking.engine.ParkingLevelGenerator;
import com.template.app.game.parking.model.Level;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线预生成关卡池（docs/13 第 5 步）。一次性工具，不进版本库的源码树。
 * <p>
 * <b>纯 Java，直接跑在 JVM 上</b>——不需要模拟器或真机，这正是把
 * ParkingLevelGenerator 设计成零 android 依赖（ADR-003）换来的红利。
 * <p>
 * 产出 TSV（制表符分隔；布局/队列里只含 , | ; : 不会与之冲突），随包作为 assets 播种。
 * 这样运行时首次进入各难度桶即可命中缓存，不必再付 0.2~3.7s 的 BFS 求解。
 */
public final class SeedPool {

    /** 每桶预生成条数。池越大越不容易重复暴露，代价只是包体几十 KB。 */
    private static final int PER_BUCKET = 40;

    /**
     * 每个桶取一个代表关卡号。
     * 桶由 (车数, 最少步数) 决定，桶内这些值一致，故用哪个关卡号生成都合法。
     */
    private static final int[] REPRESENTATIVE_LEVELS = {1, 2, 3, 4, 5, 6, 8};

    public static void main(String[] args) throws Exception {
        String out = args.length > 0
            ? args[0]
            : "app/src/main/assets/parking_levels.tsv";
        int configVersion = ParkingConfig.configVersion();
        List<String> lines = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        for (int levelIndex : REPRESENTATIVE_LEVELS) {
            ParkingLevelGenerator generator =
                new ParkingLevelGenerator(20260922L + levelIndex);
            int bucket = ParkingConfig.bucketOf(levelIndex);
            long start = System.currentTimeMillis();
            for (int i = 0; i < PER_BUCKET; i++) {
                Level level = generator.generate(levelIndex);
                lines.add(configVersion
                    + "\t" + bucket
                    + "\t" + level.vehicles.size()
                    + "\t" + ParkingLevelCodec.encodeLayout(level.vehicles)
                    + "\t" + ParkingLevelCodec.encodeQueue(level.queue)
                    + "\t" + ParkingConfig.minSolutionMoves(levelIndex));
            }
            System.out.println("L" + levelIndex + " bucket=" + bucket
                + " -> " + PER_BUCKET + " levels in "
                + (System.currentTimeMillis() - start) + "ms");
        }

        File file = new File(out);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
        System.out.println("wrote " + lines.size() + " levels to " + out
            + " (" + file.length() + " bytes) in "
            + (System.currentTimeMillis() - totalStart) + "ms");
    }
}
