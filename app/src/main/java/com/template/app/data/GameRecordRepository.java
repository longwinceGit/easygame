package com.template.app.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

import com.template.app.data.model.GameBest;
import com.template.app.data.model.GameRecord;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对局记录仓库（单例 + 线程池）。
 * <p>
 * 沿用模板的 Repository 模式：读操作返回 {@link LiveData}（Room 自行在后台线程执行），
 * 写操作投递到单线程池，保证主线程不被阻塞且写入串行。
 */
public class GameRecordRepository {

    private static volatile GameRecordRepository instance;

    private final GameRecordDao dao;

    /**
     * 写入线程池。本类是进程级单例，线程池生命周期与应用进程一致，
     * 因此不提供 shutdown —— 主动关闭反而会在后续写入时抛 RejectedExecutionException。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private GameRecordRepository(Context context) {
        this.dao = AppDatabase.getInstance(context).gameRecordDao();
    }

    /**
     * 获取仓库单例（DCL 模式）。
     */
    public static GameRecordRepository getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (GameRecordRepository.class) {
                if (instance == null) {
                    instance = new GameRecordRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** 观察某款游戏的历史最高分。 */
    public LiveData<Integer> observeBestScore(String gameId) {
        return dao.observeBestScore(gameId);
    }

    /** 观察每款游戏的最高分与对局数。 */
    public LiveData<List<GameBest>> observeBestPerGame() {
        return dao.observeBestPerGame();
    }

    /** 观察最近的对局记录。 */
    public LiveData<List<GameRecord>> observeRecent(int limit) {
        return dao.observeRecent(limit);
    }

    /** 异步保存一条对局记录。 */
    public void save(@NonNull GameRecord record) {
        executor.execute(() -> dao.insert(record));
    }

    /** 异步清空某款游戏的记录。 */
    public void clearByGame(@NonNull String gameId) {
        executor.execute(() -> dao.deleteByGame(gameId));
    }
}
