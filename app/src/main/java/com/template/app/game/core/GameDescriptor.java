package com.template.app.game.core;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

/**
 * 一款游戏的元信息（不可变值对象）。
 * <p>
 * 这是壳（Hub）了解一款游戏的<b>全部</b>内容。壳永远不需要 import 具体游戏的任何类，
 * 从而保证新增游戏只需新增文件，不修改壳层（ADR-001 / PRD Story 5）。
 */
public final class GameDescriptor {

    /** 游戏唯一 ID，同时作为存档表中的 game_id */
    @NonNull
    public final String id;

    /** 游戏名称字符串资源 */
    @StringRes
    public final int titleRes;

    /** 一句话简介字符串资源 */
    @StringRes
    public final int subtitleRes;

    /** 列表图标资源 */
    @DrawableRes
    public final int iconRes;

    /** 该游戏的主题色，用于卡片着色 */
    @ColorRes
    public final int accentRes;

    /** 导航图中该游戏页面的 destination id */
    @IdRes
    public final int navDestinationRes;

    public GameDescriptor(@NonNull String id,
                          @StringRes int titleRes,
                          @StringRes int subtitleRes,
                          @DrawableRes int iconRes,
                          @ColorRes int accentRes,
                          @IdRes int navDestinationRes) {
        this.id = id;
        this.titleRes = titleRes;
        this.subtitleRes = subtitleRes;
        this.iconRes = iconRes;
        this.accentRes = accentRes;
        this.navDestinationRes = navDestinationRes;
    }
}
