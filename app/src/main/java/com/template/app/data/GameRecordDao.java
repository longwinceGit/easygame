package com.template.app.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.template.app.data.model.GameBest;
import com.template.app.data.model.GameRecord;

import java.util.List;

/**
 * 对局记录的数据访问对象。
 */
@Dao
public interface GameRecordDao {

    /**
     * 观察某款游戏的历史最高分。
     * <p>
     * 使用 COALESCE 保证无任何记录时返回 0 而不是 null，
     * 避免上层做空值分支判断。
     */
    @Query("SELECT COALESCE(MAX(score), 0) FROM game_records WHERE game_id = :gameId")
    LiveData<Integer> observeBestScore(String gameId);

    /** 观察每款游戏的最高分与对局数。 */
    @Query("SELECT game_id AS gameId, MAX(score) AS score, COUNT(*) AS plays "
        + "FROM game_records GROUP BY game_id ORDER BY score DESC")
    LiveData<List<GameBest>> observeBestPerGame();

    /** 观察最近的对局记录（跨所有游戏）。 */
    @Query("SELECT * FROM game_records ORDER BY played_at DESC LIMIT :limit")
    LiveData<List<GameRecord>> observeRecent(int limit);

    /** 插入一条对局记录，返回自增 ID。 */
    @Insert
    long insert(GameRecord record);

    /** 清空某款游戏的全部记录。 */
    @Query("DELETE FROM game_records WHERE game_id = :gameId")
    int deleteByGame(String gameId);
}
