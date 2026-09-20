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
            gameOverEvent.setValue(engine.getScore());
        }
    }

    public void clearGameOverEvent() {
        gameOverEvent.setValue(null);
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
