package com.template.app.data.model;

/**
 * 按游戏分组聚合出的"最高分 + 对局数"。
 * <p>
 * 这是 Room 分组查询的结果 POJO，对应 SQL 中的 GROUP BY game_id，
 * 字段名必须与查询结果的列别名一致。
 */
public class GameBest {

    /** 游戏 ID */
    public String gameId;

    /** 该游戏的历史最高分 */
    public int score;

    /** 该游戏的累计对局数 */
    public int plays;

    public GameBest() {
        // Room 需要无参构造
    }
}
