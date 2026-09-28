package dev.pk.budspro

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile that toggles the touch lock. */
class LockTileService : TileService() {
    override fun onStartListening() {
        Buds.init(this)
        render()
    }

    override fun onClick() {
        Buds.setLockTouch(!Buds.prefs.lockTouch.value)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val locked = Buds.prefs.lockTouch.value
        tile.state = if (locked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (locked) "Locked" else "Active"
        tile.updateTile()
    }
}
