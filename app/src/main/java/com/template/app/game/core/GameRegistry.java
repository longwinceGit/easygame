package com.template.app.game.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.template.app.game.tetris.TetrisGamePlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 游戏插件注册表。
 * <p>
 * <b>新增一款游戏的完整步骤</b>：
 * <ol>
 *   <li>新建 {@code game/<gameId>/} 包，实现自己的 UI 与逻辑</li>
 *   <li>实现 {@link GamePlugin}</li>
 *   <li>在 {@code nav_graph.xml} 中加一个 destination</li>
 *   <li>在本类的静态块中加一行 {@code register(new XxxGamePlugin())}</li>
 * </ol>
 * 除第 4 步这一行外，壳层（Hub / Records / MainActivity / nav_graph 根）无需任何改动。
 */
public final class GameRegistry {

    private static final List<GamePlugin> PLUGINS = new ArrayList<>();

    static {
        // ---- 在此注册游戏（新增游戏只加这一行）----
        register(new TetrisGamePlugin());
    }

    private GameRegistry() {
        // 工具类禁止实例化
    }

    /** 注册一款游戏。重复 ID 会被忽略，避免误注册导致大厅出现重复卡片。 */
    public static void register(@NonNull GamePlugin plugin) {
        String id = plugin.descriptor().id;
        for (GamePlugin existing : PLUGINS) {
            if (existing.descriptor().id.equals(id)) {
                return;
            }
        }
        PLUGINS.add(plugin);
    }

    /** 全部已注册游戏（不可修改视图）。 */
    @NonNull
    public static List<GamePlugin> plugins() {
        return Collections.unmodifiableList(PLUGINS);
    }

    /** 按 ID 查找游戏，未找到返回 null。 */
    @Nullable
    public static GamePlugin find(@NonNull String id) {
        for (GamePlugin plugin : PLUGINS) {
            if (plugin.descriptor().id.equals(id)) {
                return plugin;
            }
        }
        return null;
    }
}
