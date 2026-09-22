package com.template.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.template.app.data.model.ParkingLevelEntity;

import java.util.List;

/**
 * 关卡池的数据访问对象（docs/13）。
 * <p>
 * <b>所有方法都是同步的，必须在后台线程调用</b>——Room 默认禁止主线程同步查询。
 * 调用方是 {@code ParkingViewModel} 的 {@code parking-level-gen} 单线程执行器，
 * 天然满足；这也让取关保持「一次查询 ≈ 毫秒级」。
 */
@Dao
public interface ParkingLevelDao {

    /**
     * 从指定桶里随机取一条关卡。
     * <p>
     * {@code config_version} 参与过滤：配置漂移后旧行自动不可见，
     * 无需在读取侧做任何额外判断（docs/13 §5.1）。
     */
    @Query("SELECT * FROM parking_levels "
        + "WHERE config_version = :configVersion AND bucket = :bucket "
        + "ORDER BY RANDOM() LIMIT 1")
    ParkingLevelEntity findRandom(int configVersion, int bucket);

    /** 该桶当前缓存了多少条，用于判断是否需要补池。 */
    @Query("SELECT COUNT(*) FROM parking_levels "
        + "WHERE config_version = :configVersion AND bucket = :bucket")
    int countInBucket(int configVersion, int bucket);

    /** 插入一条关卡，返回自增 ID。 */
    @Insert
    long insert(ParkingLevelEntity entity);

    /** 批量插入，用于随包预置池的一次性播种。 */
    @Insert
    long[] insertAll(List<ParkingLevelEntity> entities);

    /** 当前配置版本下的总条数，用于判断预置池是否已经播种过。 */
    @Query("SELECT COUNT(*) FROM parking_levels WHERE config_version = :configVersion")
    int countAll(int configVersion);

    /**
     * 清理配置漂移产生的存量关卡。
     * <p>
     * 这些行已不可能被 {@link #findRandom} 命中，留着只是占空间。
     */
    @Query("DELETE FROM parking_levels WHERE config_version <> :configVersion")
    int deleteStale(int configVersion);
}
