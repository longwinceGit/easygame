package com.template.app.data;

import android.content.Context;

import com.template.app.data.model.ParkingLevelEntity;
import com.template.app.game.parking.engine.ParkingConfig;
import com.template.app.game.parking.engine.ParkingLevelCodec;
import com.template.app.game.parking.model.Level;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 关卡池的门面（docs/13 第 4 步）：把 Room DAO 与纯 Java 编解码器粘起来，
 * 对上层只暴露「取一条关卡 / 存一条关卡」两个动作，
 * 让 {@code ParkingViewModel} 不必感知表结构与序列化格式。
 * <p>
 * <b>必须在后台线程调用</b>（Room 同步查询禁止主线程）。
 */
public final class ParkingLevelStore {

    /** 随包预置池（由离线工具 {@code SeedPool} 生成）：configVersion,bucket,车辆数,布局,队列,最少步数。 */
    private static final String ASSET_NAME = "parking_levels.tsv";

    private final ParkingLevelDao dao;

    public ParkingLevelStore(ParkingLevelDao dao) {
        this.dao = dao;
    }

    /**
     * 用随包预置池播种（docs/13 §4.3 路径 C 的底座）。
     * <p>
     * <b>为什么必须有</b>：只靠运行时懒生成，每个桶首次只能攒下 1 条，
     * 玩家点「刷新」会反复拿到同一张图——比改动前更糟。预置池让首次进入即命中，
     * 且保证刷新有足够多样性。
     * <p>
     * 只在当前 {@code configVersion} 下池为空时执行；预置池里版本不匹配的整行跳过
     * （配置漂移时预置池自动作废，退回运行时生成）。
     * 读取失败一律静默降级——运行时生成仍会兜底，播种不是必经路径。
     */
    public void seedFromAsset(Context context) {
        int configVersion = ParkingConfig.configVersion();
        if (dao.countAll(configVersion) > 0) {
            return;
        }
        List<ParkingLevelEntity> batch = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(ASSET_NAME), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t");
                if (f.length != 6 || Integer.parseInt(f[0]) != configVersion) {
                    continue;
                }
                batch.add(new ParkingLevelEntity(
                    configVersion,
                    Integer.parseInt(f[1]),
                    Integer.parseInt(f[2]),
                    f[3],
                    f[4],
                    Integer.parseInt(f[5])));
            }
        } catch (IOException | RuntimeException e) {
            // NumberFormatException 等均属 RuntimeException，已被覆盖。
            // 预置池不可用（缺文件/格式坏）一律静默降级到运行时生成。
            return;
        }
        if (!batch.isEmpty()) {
            dao.insertAll(batch);
        }
    }

    /**
     * 随机取一条已缓存关卡。
     *
     * @return 命中返回关卡；池空<b>或数据损坏</b>返回 null ——
     *         坏缓存等价于未命中，由调用方回退到在线生成
     */
    public Level fetch(int levelIndex) {
        ParkingLevelEntity entity = dao.findRandom(
            ParkingConfig.configVersion(), ParkingConfig.bucketOf(levelIndex));
        if (entity == null) {
            return null;
        }
        return ParkingLevelCodec.decode(levelIndex, entity.getLayout(), entity.getQueue());
    }

    /**
     * 当前配置版本下、该难度桶已缓存的条数。
     * <p>
     * 预热用它判断某桶还差几条（见 {@code ParkingConfig#WARMUP_PER_BUCKET}）。
     * 注意桶是"难度档"而非关卡号：深关的关卡号不同但桶可能相同，
     * 因此预热按桶去重后补齐，避免为同一桶重复生成。
     */
    public int countInBucket(int bucket) {
        return dao.countInBucket(ParkingConfig.configVersion(), bucket);
    }

    /**
     * 把在线生成的关卡写回池。
     * <p>
     * 这既是<b>自愈</b>（池空时补齐，下一次同桶取关即可命中），
     * 也是长期<b>补池</b>——玩得越久池越大，重复暴露概率越低。
     */
    public void store(Level level) {
        dao.insert(new ParkingLevelEntity(
            ParkingConfig.configVersion(),
            ParkingConfig.bucketOf(level.levelIndex),
            level.vehicles.size(),
            ParkingLevelCodec.encodeLayout(level.vehicles),
            ParkingLevelCodec.encodeQueue(level.queue),
            ParkingConfig.minSolutionMoves(level.levelIndex)));
    }

    /**
     * 清理配置漂移产生的存量关卡（docs/13 §5.1）。
     * best-effort：这些行已不可能被命中，清不掉也不影响正确性。
     */
    public void pruneStale() {
        dao.deleteStale(ParkingConfig.configVersion());
    }
}
