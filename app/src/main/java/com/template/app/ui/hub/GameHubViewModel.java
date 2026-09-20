package com.template.app.ui.hub;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.template.app.game.core.GamePlugin;
import com.template.app.game.core.GameRegistry;

import java.util.List;

/**
 * 游戏大厅 ViewModel：数据来源是 {@link GameRegistry}，新增游戏无需改动本类。
 */
public class GameHubViewModel extends ViewModel {

    private final MutableLiveData<List<GamePlugin>> games =
        new MutableLiveData<>(GameRegistry.plugins());

    public LiveData<List<GamePlugin>> getGames() {
        return games;
    }

    /** 注册表发生变化时（例如调试期动态注册）刷新列表。 */
    public void refresh() {
        games.setValue(GameRegistry.plugins());
    }
}
