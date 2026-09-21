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

    /** 数据库版本 */
    public static final int DATABASE_VERSION = 1;

    /** 拖拽方块的唯一 ID，同时作为存档表中的 game_id */
    public static final String GAME_ID_TETRIS = "tetris";

    /** 挪车消消消的唯一 ID，同时作为存档表中的 game_id */
    public static final String GAME_ID_PARKING = "parking";

    /** 战绩页展示的最近对局条数 */
    public static final int RECENT_RECORD_LIMIT = 20;
}
