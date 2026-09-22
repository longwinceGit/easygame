package com.template.app.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.template.app.data.model.GameRecord;
import com.template.app.data.model.ParkingLevelEntity;
import com.template.app.util.Constants;

/**
 * Room 数据库单例（DCL 双重检查锁定模式）。
 * <p>
 * 数据库在首次访问时创建，后续返回同一实例，避免资源浪费。
 */
@Database(
    entities = {GameRecord.class, ParkingLevelEntity.class},
    version = Constants.DATABASE_VERSION
)
public abstract class AppDatabase extends RoomDatabase {

    /**
     * v1 → v2：新增挪车消消消的关卡池表（docs/13）。
     * <p>
     * <b>必须显式提供</b>：本库没有 {@code fallbackToDestructiveMigration}，
     * 缺迁移会让已装用户在升级时直接抛 {@code IllegalStateException}。
     * SQL 需与 Room 为 {@link ParkingLevelEntity} 生成的 schema 完全一致，
     * 否则运行时校验不通过。
     */
    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `parking_levels` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                + "`config_version` INTEGER NOT NULL, "
                + "`bucket` INTEGER NOT NULL, "
                + "`vehicle_count` INTEGER NOT NULL, "
                + "`layout` TEXT NOT NULL, "
                + "`queue` TEXT NOT NULL, "
                + "`min_moves` INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS "
                + "`index_parking_levels_config_version_bucket` "
                + "ON `parking_levels` (`config_version`, `bucket`)");
        }
    };

    private static volatile AppDatabase instance;

    public abstract GameRecordDao gameRecordDao();

    public abstract ParkingLevelDao parkingLevelDao();

    /**
     * 获取数据库单例（DCL 模式）。
     *
     * @param context 应用上下文
     * @return 数据库实例
     */
    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            Constants.DATABASE_NAME)
                        .addMigrations(MIGRATION_1_2)
                        .build();
                }
            }
        }
        return instance;
    }
}
