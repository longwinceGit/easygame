package com.template.app.game.parking.ui;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.template.app.R;
import com.template.app.data.AppDatabase;
import com.template.app.data.GameRecordRepository;
import com.template.app.data.ParkingLevelStore;
import com.template.app.data.model.GameRecord;
import com.template.app.game.parking.engine.BoardStep;
import com.template.app.game.parking.engine.MoveResult;
import com.template.app.game.parking.engine.ParkingEngine;
import com.template.app.game.parking.engine.ParkingLevelGenerator;
import com.template.app.game.parking.model.Level;
import com.template.app.util.Constants;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 挪车消消消的 ViewModel：持有引擎，向 View 暴露可观察状态，接收 View 的输入。
 * <p>
 * 所有引擎状态变更都必须经过这里，View 不得直接写入引擎（ADR-004）。
 * <p>
 * <b>关卡生成走后台线程</b>（ADR-007）：生成器是纯函数，产出的 {@link Level} 不可变，
 * 主线程只负责把它套进引擎。这样最坏情况下玩家也只是看到一次 loading。
 */
public class ParkingViewModel extends AndroidViewModel {

    private static final String PREF_HINT = "parking_hint";
    private static final String KEY_HINT_SHOWN = "hint_shown";

    private final ParkingEngine engine = new ParkingEngine();
    private final ParkingLevelGenerator generator = new ParkingLevelGenerator();
    private final GameRecordRepository repository;

    /**
     * 关卡池门面。<b>懒创建</b>：Room 的打开不能发生在主线程，
     * 因此只在后台线程首次取关时才初始化（DCL）。
     */
    private volatile ParkingLevelStore levelStore;

    /**
     * 关卡生成线程池。生成器最坏要跑数十万次状态展开，不能占用主线程。
     * {@link #onCleared()} 中 shutdown —— shutdown 不打断已在跑的任务，
     * 而它对一个已销毁的 LiveData postValue 是安全的空操作。
     */
    /**
     * 关卡生成线程池。生成器最坏要跑数十万次状态展开，不能占用主线程。
     * 线程显式降权到 {@link android.os.Process#THREAD_PRIORITY_BACKGROUND}，
     * 避免其在模拟器/低端机上吃满单核、引发剧烈 GC 而饿死主线程（ANR）。
     * {@link #onCleared()} 中 shutdown —— shutdown 不打断已在跑的任务，
     * 而它对一个已销毁的 LiveData postValue 是安全的空操作。
     */
    private final ExecutorService generatorExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "parking-level-gen"));

    private final MutableLiveData<Integer> score = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> level = new MutableLiveData<>(1);
    private final MutableLiveData<Integer> passengersLeft = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> removesLeft = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> sortsLeft = new MutableLiveData<>(0);
    private final MutableLiveData<Boolean> canUndo = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> removeMode = new MutableLiveData<>(false);
    private final MutableLiveData<ParkingEngine.State> state =
        new MutableLiveData<>(ParkingEngine.State.READY);
    private final MutableLiveData<Integer> revision = new MutableLiveData<>(0);

    /** 后台生成完毕的关卡；被消费后置 null。 */
    private final MutableLiveData<Level> levelEvent = new MutableLiveData<>(null);

    /** 最近一次成功操作，用于触发动画。 */
    private final MutableLiveData<MoveResult> moveEvent = new MutableLiveData<>(null);

    /** 一次性提示事件，值是字符串资源 id。 */
    private final MutableLiveData<Integer> messageEvent = new MutableLiveData<>(null);

    /** 一次性结局事件：true = 通关，false = 卡住。 */
    private final MutableLiveData<Boolean> finishEvent = new MutableLiveData<>(null);

    /** 「移除 / 排序」触发的接客离场清单，用于播放"乘客上车、车开走"的动画。 */
    private final MutableLiveData<List<BoardStep>> boardEvent = new MutableLiveData<>(null);

    private final LiveData<Integer> best;

    /** 本关成绩是否已写入存档，防止重复保存。 */
    private boolean recordSaved;

    public ParkingViewModel(@NonNull Application application) {
        super(application);
        repository = GameRecordRepository.getInstance(application);
        best = repository.observeBestScore(Constants.GAME_ID_PARKING);
    }

    /** 引擎实例，供 {@link com.template.app.game.parking.view.ParkingView} 只读绑定。 */
    public ParkingEngine getEngine() {
        return engine;
    }

    @Override
    protected void onCleared() {
        generatorExecutor.shutdown();
        super.onCleared();
    }

    // ---- 输出 ----

    public LiveData<Integer> getScore() {
        return score;
    }

    public LiveData<Integer> getLevel() {
        return level;
    }

    public LiveData<Integer> getPassengersLeft() {
        return passengersLeft;
    }

    public LiveData<Integer> getRemovesLeft() {
        return removesLeft;
    }

    public LiveData<Integer> getSortsLeft() {
        return sortsLeft;
    }

    public LiveData<Boolean> getCanUndo() {
        return canUndo;
    }

    public LiveData<Boolean> getLoading() {
        return loading;
    }

    public LiveData<Boolean> getRemoveMode() {
        return removeMode;
    }

    public LiveData<Integer> getBest() {
        return best;
    }

    public LiveData<ParkingEngine.State> getState() {
        return state;
    }

    /** 引擎数据版本号，递增即代表需要重绘。 */
    public LiveData<Integer> getRevision() {
        return revision;
    }

    public LiveData<Level> getLevelEvent() {
        return levelEvent;
    }

    public LiveData<MoveResult> getMoveEvent() {
        return moveEvent;
    }

    public LiveData<Integer> getMessageEvent() {
        return messageEvent;
    }

    public LiveData<Boolean> getFinishEvent() {
        return finishEvent;
    }

    public LiveData<List<BoardStep>> getBoardEvent() {
        return boardEvent;
    }

    // ---- 输入 ----

    /** 开新一轮：分数清零，从第 1 关开始。 */
    public void startRun() {
        engine.startNewRun();
        requestLevel(1);
    }

    /**
     * 请求第 levelIndex 关。全程在后台线程进行。
     * <p>
     * <b>优先命中关卡池</b>（docs/13）：一次 SELECT + 解析，毫秒级，
     * 完全避开 BFS 求解——这是消除「过关/刷新必卡 0.2~3.7 秒」的关键。
     * 池未命中（首次进入某难度桶）才回退在线生成，并把结果写回池完成自愈。
     */
    public void requestLevel(int levelIndex) {
        if (Boolean.TRUE.equals(loading.getValue())) {
            return;
        }
        loading.setValue(true);
        generatorExecutor.execute(() -> {
            // 生成器是 CPU/GC 大户：降权到后台优先级，确保主线程输入永远优先于生成，
            // 从根本上消除"生成拖垮 UI 导致 Input dispatching ANR"的风险。
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            Level cached = levelStore().fetch(levelIndex);
            if (cached != null) {
                levelEvent.postValue(cached);
                return;
            }
            Level generated = generator.generate(levelIndex);
            levelStore().store(generated);
            levelEvent.postValue(generated);
        });
    }

    /**
     * 懒创建关卡池门面。
     * <p>
     * 必须在<b>后台线程</b>调用：{@link AppDatabase#getInstance} 会触发数据库打开，
     * 放在主线程有卡顿风险。首次创建顺带清理配置漂移的存量关卡，并播种随包预置池。
     */
    private ParkingLevelStore levelStore() {
        if (levelStore == null) {
            synchronized (this) {
                if (levelStore == null) {
                    ParkingLevelStore store = new ParkingLevelStore(
                        AppDatabase.getInstance(getApplication()).parkingLevelDao());
                    store.pruneStale();
                    store.seedFromAsset(getApplication());
                    levelStore = store;
                }
            }
        }
        return levelStore;
    }

    /** 把后台生成的关卡套进引擎。必须在主线程调用。 */
    public void applyLevel(@NonNull Level generated) {
        engine.applyLevel(generated);
        levelEvent.setValue(null);
        removeMode.setValue(false);
        loading.setValue(false);
        recordSaved = false;
        publish();
    }

    /** 进入下一关。 */
    public void nextLevel() {
        requestLevel(engine.getLevelIndex() + 1);
    }

    /** 刷新：重新随机生成本关（关卡号不变，本关进度清零）。 */
    public void refreshLevel() {
        requestLevel(engine.getLevelIndex());
    }

    public void undo() {
        if (!engine.undo()) {
            return;
        }
        removeMode.setValue(false);
        publish();
    }

    public void tap(int vehicleId) {
        handleResult(engine.tap(vehicleId), vehicleId);
    }

    public void move(int vehicleId, int delta) {
        handleResult(engine.move(vehicleId, delta), vehicleId);
    }

    /** 移除模式下点了一辆车。 */
    public void removeVehicle(int vehicleId) {
        if (engine.getRemovesLeft() <= 0) {
            messageEvent.setValue(R.string.parking_remove_exhausted_msg);
            return;
        }
        if (!engine.removeVehicle(vehicleId)) {
            messageEvent.setValue(R.string.parking_remove_failed);
            return;
        }
        removeMode.setValue(false);
        publish();
        publishBoardSteps();
        publishFinish();
    }

    /** 用「排序」道具重排剩余乘客队列。 */
    public void sortQueue() {
        if (engine.getSortsLeft() <= 0) {
            messageEvent.setValue(R.string.parking_sort_exhausted_msg);
            return;
        }
        if (!engine.sortQueue()) {
            messageEvent.setValue(R.string.parking_sort_useless);
            return;
        }
        publish();
        publishBoardSteps();
        publishFinish();
    }

    public void toggleRemoveMode() {
        removeMode.setValue(!Boolean.TRUE.equals(removeMode.getValue()));
    }

    public void clearMessageEvent() {
        messageEvent.setValue(null);
    }

    /** 消费掉移动事件，避免 Fragment 重建时重播上一次的动画。 */
    public void clearMoveEvent() {
        moveEvent.setValue(null);
    }

    public void clearFinishEvent() {
        finishEvent.setValue(null);
    }

    public void clearBoardEvent() {
        boardEvent.setValue(null);
    }

    // ---- 新手引导 ----

    /** 首屏提示是否已展示过（只展示一次，见 GDD 第 5 节）。 */
    public boolean isHintShown() {
        return hintPrefs().getBoolean(KEY_HINT_SHOWN, false);
    }

    public void markHintShown() {
        hintPrefs().edit().putBoolean(KEY_HINT_SHOWN, true).apply();
    }

    private SharedPreferences hintPrefs() {
        return getApplication().getSharedPreferences(PREF_HINT, android.content.Context.MODE_PRIVATE);
    }

    // ---- 内部实现 ----

    private void handleResult(MoveResult result, int vehicleId) {
        if (!result.success) {
            int reason = engine.blockedReason(vehicleId);
            messageEvent.setValue(reason == ParkingEngine.BLOCK_BY_PICKUP
                ? R.string.parking_pickup_full
                : R.string.parking_cannot_move);
            return;
        }
        publish();
        moveEvent.setValue(result);
        publishFinish();
    }

    private void publishFinish() {
        if (engine.getState() == ParkingEngine.State.SOLVED) {
            saveRecord();
            finishEvent.setValue(Boolean.TRUE);
        } else if (engine.getState() == ParkingEngine.State.STUCK) {
            finishEvent.setValue(Boolean.FALSE);
        }
    }

    /** 把「移除 / 排序」顺带接走的车交给视图，播放"乘客上车、车开走"的离场动画。 */
    private void publishBoardSteps() {
        List<BoardStep> steps = engine.takeBoardSteps();
        if (steps != null && !steps.isEmpty()) {
            boardEvent.setValue(steps);
        }
    }

    private void publish() {
        score.setValue(engine.getScore());
        level.setValue(engine.getLevelIndex());
        passengersLeft.setValue(engine.getPassengersLeft());
        removesLeft.setValue(engine.getRemovesLeft());
        sortsLeft.setValue(engine.getSortsLeft());
        canUndo.setValue(engine.canUndo());
        state.setValue(engine.getState());
        Integer current = revision.getValue();
        revision.setValue(current == null ? 1 : current + 1);
    }

    private void saveRecord() {
        if (recordSaved) {
            return;
        }
        recordSaved = true;
        repository.save(new GameRecord(
            Constants.GAME_ID_PARKING,
            engine.getScore(),
            engine.getPassengersServed(),
            engine.getLevelIndex(),
            System.currentTimeMillis()));
    }
}
