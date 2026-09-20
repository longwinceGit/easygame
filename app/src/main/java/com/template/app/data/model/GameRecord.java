package com.template.app.data.model;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * 一局游戏的成绩记录。
 * <p>
 * 一张表服务所有游戏，通过 {@code gameId} 区分来源，新增游戏无需建表。
 */
@Entity(tableName = "game_records")
public class GameRecord {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    private long id;

    @ColumnInfo(name = "game_id")
    private String gameId;

    @ColumnInfo(name = "score")
    private int score;

    @ColumnInfo(name = "lines")
    private int lines;

    @ColumnInfo(name = "level")
    private int level;

    @ColumnInfo(name = "played_at")
    private long playedAt;

    public GameRecord() {
        // Room 需要无参构造
    }

    /**
     * 业务构造入口。
     * <p>
     * 标注 {@code @Ignore} 是<b>必需的</b>：Room 发现多个可用构造器时会发出
     * "multiple good constructors" 警告并自行挑选，行为不可控。
     */
    @Ignore
    public GameRecord(String gameId, int score, int lines, int level, long playedAt) {
        this.gameId = gameId;
        this.score = score;
        this.lines = lines;
        this.level = level;
        this.playedAt = playedAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getGameId() {
        return gameId;
    }

    public void setGameId(String gameId) {
        this.gameId = gameId;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public int getLines() {
        return lines;
    }

    public void setLines(int lines) {
        this.lines = lines;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public long getPlayedAt() {
        return playedAt;
    }

    public void setPlayedAt(long playedAt) {
        this.playedAt = playedAt;
    }
}
