package com.template.app.game.parking;

import androidx.annotation.NonNull;

import com.template.app.R;
import com.template.app.game.core.GameDescriptor;
import com.template.app.game.core.GamePlugin;
import com.template.app.util.Constants;

/**
 * 挪车消消消在 {@link com.template.app.game.core.GameRegistry} 中的注册入口。
 * <p>
 * 这是壳层唯一会 import 的本游戏类——那行 import 就是注册本身（ADR-001）。
 */
public final class ParkingGamePlugin implements GamePlugin {

    private static final GameDescriptor DESCRIPTOR = new GameDescriptor(
        Constants.GAME_ID_PARKING,
        R.string.parking_title,
        R.string.parking_subtitle,
        R.drawable.ic_parking,
        R.color.game_parking_accent,
        R.id.nav_parking);

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
