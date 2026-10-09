package com.knobnext

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class KnobTileService : TileService() {

    override fun onStartListening() = render()

    override fun onClick() {
        val prefs = Prefs(this)
        prefs.enabled = !prefs.enabled
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val enabled = Prefs(this).enabled
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (enabled) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }
}
