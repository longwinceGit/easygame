package com.template.app.ui.records;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.template.app.data.GameRecordRepository;
import com.template.app.data.model.GameBest;
import com.template.app.data.model.GameRecord;
import com.template.app.util.Constants;

import java.util.List;

/**
 * 战绩页 ViewModel：按游戏聚合最高分 + 最近对局。
 */
public class RecordsViewModel extends AndroidViewModel {

    private final LiveData<List<GameBest>> bestPerGame;
    private final LiveData<List<GameRecord>> recent;

    public RecordsViewModel(@NonNull Application application) {
        super(application);
        GameRecordRepository repository = GameRecordRepository.getInstance(application);
        bestPerGame = repository.observeBestPerGame();
        recent = repository.observeRecent(Constants.RECENT_RECORD_LIMIT);
    }

    public LiveData<List<GameBest>> getBestPerGame() {
        return bestPerGame;
    }

    public LiveData<List<GameRecord>> getRecent() {
        return recent;
    }
}
