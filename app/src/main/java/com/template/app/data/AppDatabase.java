package com.template.app.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.template.app.data.model.GameRecord;
import com.template.app.util.Constants;

/**
 * Room 数据库单例（DCL 双重检查锁定模式）。
 * <p>
 * 数据库在首次访问时创建，后续返回同一实例，避免资源浪费。
 */
@Database(entities = {GameRecord.class}, version = Constants.DATABASE_VERSION)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract GameRecordDao gameRecordDao();

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
                        .build();
                }
            }
        }
        return instance;
    }
}
