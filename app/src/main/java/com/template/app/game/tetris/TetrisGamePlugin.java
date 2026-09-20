package com.template.app.game.tetris;

import androidx.annotation.NonNull;

import com.template.app.R;
import com.template.app.game.core.GameDescriptor;
import com.template.app.game.core.GamePlugin;
import com.template.app.util.Constants;

/**
 * 拖拽方块在 {@link com.template.app.game.core.GameRegistry} 中的注册入口。
 * <p>
 * 新增一款游戏时，照着这个类写一个自己的 Plugin 即可。
 */
public final class TetrisGamePlugin implements GamePlugin {

    private static final GameDescriptor DESCRIPTOR = new GameDescriptor(
        Constants.GAME_ID_TETRIS,
        R.string.tetris_title,
        R.string.tetris_subtitle,
        R.drawable.ic_tetris,
        R.color.game_tetris_accent,
        R.id.nav_tetris);

    @NonNull
    @Override
    public GameDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
