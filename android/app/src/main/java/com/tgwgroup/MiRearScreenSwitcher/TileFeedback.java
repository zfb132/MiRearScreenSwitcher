package com.tgwgroup.MiRearScreenSwitcher;

import android.os.Build;
import android.service.quicksettings.Tile;

/** Optional tile metadata is unavailable on the oldest supported Android versions. */
final class TileFeedback {
    private TileFeedback() {}

    static void setSubtitle(Tile tile, String subtitle) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(subtitle);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.setStateDescription("");
        }
    }
}
