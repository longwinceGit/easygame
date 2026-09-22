package com.template.app.data.model;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 一条预生成（或运行时生成后回填）的挪车关卡（docs/13）。
 * <p>
 * 存的是<b>已证明可解</b>的关卡：{@code layout} + {@code queue} 由
 * {@code ParkingLevelCodec} 编解码，队列源自生成器 BFS 的求解顺序，
 * 因此读出来即可直接实例化，运行时<b>无需再验证可解性</b>。
 * <p>
 * {@code config_version} 是失效开关：几何/颜色常量一变，旧行自动不再被命中（docs/13 §5.1）。
 */
@Entity(
    tableName = "parking_levels",
    indices = {@Index(value = {"config_version", "bucket"})}
)
public class ParkingLevelEntity {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    private long id;

    @ColumnInfo(name = "config_version")
    private int configVersion;

    /** 难度桶，见 {@code ParkingConfig.bucketOf}。 */
    @ColumnInfo(name = "bucket")
    private int bucket;

    @ColumnInfo(name = "vehicle_count")
    private int vehicleCount;

    /**
     * 车辆布局，格式见 {@code ParkingLevelCodec}。
     * <p>
     * {@code @NonNull} 让 Room 生成 {@code NOT NULL}：这两列是关卡的全部内容，
     * 空值只会产生坏缓存。同时它必须与 {@code AppDatabase} 里手写迁移的 SQL 一致，
     * 否则 Room 运行时校验不通过（升级即崩）。
     */
    @NonNull
    @ColumnInfo(name = "layout")
    private String layout;

    /** 乘客队列，格式见 {@code ParkingLevelCodec}。 */
    @NonNull
    @ColumnInfo(name = "queue")
    private String queue;

    @ColumnInfo(name = "min_moves")
    private int minMoves;

    public ParkingLevelEntity() {
        // Room 需要无参构造
    }

    /**
     * 业务构造入口。
     * <p>
     * 标注 {@code @Ignore} 是<b>必需的</b>：Room 发现多个可用构造器时会发出
     * "multiple good constructors" 警告并自行挑选，行为不可控。
     */
    @Ignore
    public ParkingLevelEntity(int configVersion, int bucket, int vehicleCount,
                              String layout, String queue, int minMoves) {
        this.configVersion = configVersion;
        this.bucket = bucket;
        this.vehicleCount = vehicleCount;
        this.layout = layout;
        this.queue = queue;
        this.minMoves = minMoves;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }

    public int getBucket() {
        return bucket;
    }

    public void setBucket(int bucket) {
        this.bucket = bucket;
    }

    public int getVehicleCount() {
        return vehicleCount;
    }

    public void setVehicleCount(int vehicleCount) {
        this.vehicleCount = vehicleCount;
    }

    public String getLayout() {
        return layout;
    }

    public void setLayout(String layout) {
        this.layout = layout;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public int getMinMoves() {
        return minMoves;
    }

    public void setMinMoves(int minMoves) {
        this.minMoves = minMoves;
    }
}
