package com.template.app.game.core;

import androidx.annotation.NonNull;

/**
 * 游戏插件契约。
 * <p>
 * <b>刻意只保留 2 个方法</b>——这是对抗"过早抽象"的关键决策（ADR-001）。
 * 壳真正需要知道的只有"展示什么"和"是否可见"；游戏的生命周期、渲染、
 * 状态机全部由游戏自己自治。等到第二款游戏接入时如果确实需要新的钩子，
 * 那时再加（加方法比删方法安全得多）。
 */
public interface GamePlugin {

    /** 游戏元信息：大厅列表渲染所需的全部数据。 */
    @NonNull
    GameDescriptor descriptor();

    /** 是否对玩家可见。false 时大厅可显示"敬请期待"占位卡。 */
    boolean isEnabled();
}
