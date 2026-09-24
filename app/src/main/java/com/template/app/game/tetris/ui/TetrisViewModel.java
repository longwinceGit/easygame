package com.template.app.game.tetris.ui;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.template.app.data.GameRecordRepository;
import com.template.app.data.model.GameRecord;
import com.template.app.game.tetris.engine.PlaceResult;
import com.template.app.game.tetris.engine.TetrisConfig;
import com.template.app.game.tetris.engine.TetrisEngine;
import com.template.app.util.Constants;

/**
 * 拖拽方块的 ViewModel：持有引擎，向 View 暴露可观察状态，接收 View 的输入。
 * <p>
 * 所有引擎状态变更都必须经过这里，View 不得直接写入引擎（ADR-004）。
 */
public class TetrisViewModel extends AndroidViewModel {

    private static final String PREF_HINT = "tetris_hint";
    private static final String KEY_DRAG_HINT_SHOWN = "drag_hint_shown";

    /**
     * 未完成局的进度存档："退出只是暂停"，下次进入可继续。
     * 与提示分文件存放，便于本局结束时整份清除。
     */
    private static final String PREF_SESSION = "tetris_session";
    private static final String KEY_CELLS = "cells";
    private static final String KEY_TRAY = "tray";
    private static final String KEY_SCORE = "score";
    private static final String KEY_LINES = "lines";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_COMBO = "combo";

    /**
     * 是否"真的玩过"——对齐挪车：空局（没落子、0 分、0 行）不提供继续，
     * 否则一进游戏就退出也会留下存档，导致每次进入都弹「继续 0 分」。
     */
    private static final String KEY_ACTIVE = "active";

    private final TetrisEngine engine = new TetrisEngine();
    private final GameRecordRepository repository;

    private final MutableLiveData<Integer> score = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> lines = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> level = new MutableLiveData<>(1);
    private final MutableLiveData<TetrisEngine.State> state =
        new MutableLiveData<>(TetrisEngine.State.READY);
    private final MutableLiveData<PlaceResult> lastPlacement = new MutableLiveData<>();
    private final MutableLiveData<Integer> revision = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> gameOverEvent = new MutableLiveData<>(null);
    private final LiveData<Integer> best;

    /** 本局成绩是否已写入存档，防止重复保存。 */
    private boolean recordSaved;

    public TetrisViewModel(@NonNull Application application) {
        super(application);
        repository = GameRecordRepository.getInstance(application);
        best = repository.observeBestScore(Constants.GAME_ID_TETRIS);
    }

    /** 引擎实例，供 {@link com.template.app.game.tetris.view.TetrisView} 只读绑定。 */
    public TetrisEngine getEngine() {
        return engine;
    }

    // ---- 输出 ----

    public LiveData<Integer> getScore() {
        return score;
    }

    public LiveData<Integer> getLines() {
        return lines;
    }

    public LiveData<Integer> getLevel() {
        return level;
    }

    public LiveData<Integer> getBest() {
        return best;
    }

    public LiveData<TetrisEngine.State> getState() {
        return state;
    }

    /** 最近一次成功放置的结果，用于触发消行动画与触觉反馈。 */
    public LiveData<PlaceResult> getLastPlacement() {
        return lastPlacement;
    }

    /** 引擎数据版本号，递增即代表需要重绘。 */
    public LiveData<Integer> getRevision() {
        return revision;
    }

    /** 结束时携带本局分数的一次性事件；已消费则为 null。 */
    public LiveData<Integer> getGameOverEvent() {
        return gameOverEvent;
    }

    // ---- 输入 ----

    public void start() {
        engine.start();
        recordSaved = false;
        publish();
    }

    public void pause() {
        engine.pause();
        publish();
    }

    public void resume() {
        engine.resume();
        publish();
    }

    public void restart() {
        start();
    }

    public void rotate(int slot) {
        engine.rotate(slot);
        publish();
    }

    public void place(int slot, int left, int fromTop) {
        PlaceResult result = engine.place(slot, left, fromTop);
        if (!result.success) {
            // 非法放置：引擎未发生任何变化，也无需重绘
            return;
        }
        publish();
        lastPlacement.setValue(result);
        if (result.gameOver) {
            saveRecord();
            // 本局已结束：清除进度存档，否则下次进入会"继续"一个已经死掉的局
            clearSession();
            gameOverEvent.setValue(engine.getScore());
        } else {
            // 与挪车一致：每次操作后落盘，任何时刻退出都不丢进度
            persistProgress();
        }
    }

    public void clearGameOverEvent() {
        gameOverEvent.setValue(null);
    }

    // ---- 会话存档（退出后继续，对齐挪车的进度持久化）----

    /**
     * 是否存在可继续的未完成局。
     * <p>
     * <b>不能只看"存档是否存在"</b>：进入游戏后立刻退出也会落盘（空棋盘 + 0 分），
     * 那样每次进入都会弹「继续 0 分」。对齐挪车：只有真的玩过
     * （棋盘有落子 / 有得分 / 有消行）才算可继续。
     */
    public boolean hasResumableSession() {
        return sessionPrefs().getBoolean(KEY_ACTIVE, false);
    }

    /** 存档中的分数，供「继续」对话框显示。 */
    public int getSavedScore() {
        return sessionPrefs().getInt(KEY_SCORE, 0);
    }

    /**
     * 持久化当前局面：每次放置后与退出时调用。
     * <p>
     * 尚未开始（READY）或已结束（GAME_OVER）的局<b>不写档</b>，
     * 否则会把空局或死局存档成"可继续"。
     */
    public void persistProgress() {
        TetrisEngine.State current = engine.getState();
        if (current == TetrisEngine.State.READY || current == TetrisEngine.State.GAME_OVER) {
            return;
        }
        TetrisEngine.SessionSnapshot snapshot = engine.snapshot();
        StringBuilder cells = new StringBuilder(
            TetrisConfig.BOARD_HEIGHT * TetrisConfig.BOARD_WIDTH);
        for (int[] row : snapshot.cells) {
            for (int value : row) {
                cells.append((char) ('0' + Math.max(0, Math.min(9, value))));
            }
        }
        StringBuilder tray = new StringBuilder();
        for (int i = 0; i < snapshot.trayTypes.length; i++) {
            if (i > 0) {
                tray.append(';');
            }
            tray.append(snapshot.trayTypes[i]).append(',').append(snapshot.trayRotations[i]);
        }
        // 与挪车同一套推导：有落子 / 有得分 / 有消行 才算"有进度"
        boolean active = snapshot.score > 0 || snapshot.lines > 0 || hasAnyCell(snapshot.cells);
        sessionPrefs().edit()
            .putString(KEY_CELLS, cells.toString())
            .putString(KEY_TRAY, tray.toString())
            .putInt(KEY_SCORE, snapshot.score)
            .putInt(KEY_LINES, snapshot.lines)
            .putInt(KEY_LEVEL, snapshot.level)
            .putInt(KEY_COMBO, snapshot.combo)
            .putBoolean(KEY_ACTIVE, active)
            .apply();
    }

    /**
     * 继续上次的未完成局。
     * <p>
     * 恢复失败（棋盘尺寸变更、数据损坏）时退回开新局并清除存档——玩家永远有得玩。
     */
    public void continueRun() {
        String cells = sessionPrefs().getString(KEY_CELLS, null);
        String tray = sessionPrefs().getString(KEY_TRAY, null);
        int expected = TetrisConfig.BOARD_HEIGHT * TetrisConfig.BOARD_WIDTH;
        if (cells == null || tray == null || cells.length() != expected) {
            // 存档与当前棋盘尺寸不符（BOARD_WIDTH / BOARD_HEIGHT 改过）：丢弃
            clearSession();
            start();
            return;
        }
        int[] types = new int[TetrisConfig.TRAY_SIZE];
        int[] rotations = new int[TetrisConfig.TRAY_SIZE];
        decodeTray(tray, types, rotations);
        TetrisEngine.SessionSnapshot snapshot = new TetrisEngine.SessionSnapshot(
            decodeCells(cells), types, rotations,
            sessionPrefs().getInt(KEY_SCORE, 0),
            sessionPrefs().getInt(KEY_LINES, 0),
            sessionPrefs().getInt(KEY_LEVEL, 1),
            sessionPrefs().getInt(KEY_COMBO, 0));
        if (!engine.restore(snapshot)) {
            clearSession();
            start();
            return;
        }
        recordSaved = false;
        publish();
    }

    /** 放弃进度，从头开始。 */
    public void abandonAndRestart() {
        clearSession();
        start();
    }

    /** 清除进度存档（本局结束或玩家放弃后）。 */
    private void clearSession() {
        sessionPrefs().edit().clear().apply();
    }

    private SharedPreferences sessionPrefs() {
        return getApplication().getSharedPreferences(
            PREF_SESSION, android.content.Context.MODE_PRIVATE);
    }

    /** 棋盘上是否已落下任何方块。 */
    private static boolean hasAnyCell(int[][] cells) {
        for (int[] row : cells) {
            for (int value : row) {
                if (value != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 每格一个字符（'0'..'9'）还原为棋盘；长度已在 {@link #continueRun()} 校验过。 */
    private static int[][] decodeCells(String text) {
        int[][] grid = new int[TetrisConfig.BOARD_HEIGHT][TetrisConfig.BOARD_WIDTH];
        int index = 0;
        for (int r = 0; r < TetrisConfig.BOARD_HEIGHT; r++) {
            for (int c = 0; c < TetrisConfig.BOARD_WIDTH; c++) {
                char ch = index < text.length() ? text.charAt(index) : '0';
                grid[r][c] = ch >= '0' && ch <= '9' ? ch - '0' : 0;
                index++;
            }
        }
        return grid;
    }

    /** 解析 "type,rotation;type,rotation;..."；解析失败或缺失的槽位记为 -1（由引擎补新块）。 */
    private static void decodeTray(String text, int[] types, int[] rotations) {
        String[] parts = text.split(";");
        for (int i = 0; i < types.length; i++) {
            if (i >= parts.length) {
                types[i] = -1;
                continue;
            }
            String[] pair = parts[i].split(",");
            try {
                types[i] = Integer.parseInt(pair[0]);
                rotations[i] = pair.length > 1 ? Integer.parseInt(pair[1]) : 0;
            } catch (NumberFormatException e) {
                types[i] = -1;
            }
        }
    }

    // ---- 新手引导 ----

    /** 拖拽提示是否已经展示过（只展示一次，见 GDD 第 5 节）。 */
    public boolean isDragHintShown() {
        return hintPrefs().getBoolean(KEY_DRAG_HINT_SHOWN, false);
    }

    public void markDragHintShown() {
        hintPrefs().edit().putBoolean(KEY_DRAG_HINT_SHOWN, true).apply();
    }

    private SharedPreferences hintPrefs() {
        return getApplication().getSharedPreferences(PREF_HINT, android.content.Context.MODE_PRIVATE);
    }

    // ---- 内部实现 ----

    private void saveRecord() {
        if (recordSaved) {
            return;
        }
        recordSaved = true;
        repository.save(new GameRecord(
            Constants.GAME_ID_TETRIS,
            engine.getScore(),
            engine.getLines(),
            engine.getLevel(),
            System.currentTimeMillis()));
    }

    private void publish() {
        score.setValue(engine.getScore());
        lines.setValue(engine.getLines());
        level.setValue(engine.getLevel());
        state.setValue(engine.getState());
        Integer current = revision.getValue();
        revision.setValue(current == null ? 1 : current + 1);
    }
}
