package com.template.app.util;

/**
 * 全局常量定义。
 */
public final class Constants {

    private Constants() {
        // 工具类禁止实例化
    }

    /** 数据库名称 */
    public static final String DATABASE_NAME = "game_hub.db";

    /**
     * 数据库版本。
     * v2：新增挪车消消消的关卡池表 {@code parking_levels}（docs/13）。
     * 升版必须同时在 {@code AppDatabase} 提供对应 Migration，否则已装用户会崩溃。
     */
    public static final int DATABASE_VERSION = 2;

    /** 拖拽方块的唯一 ID，同时作为存档表中的 game_id */
    public static final String GAME_ID_TETRIS = "tetris";

    /** 挪车消消消的唯一 ID，同时作为存档表中的 game_id */
    public static final String GAME_ID_PARKING = "parking";

    /** 战绩页展示的最近对局条数 */
    public static final int RECENT_RECORD_LIMIT = 20;
}
