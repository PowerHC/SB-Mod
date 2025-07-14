package at.hannibal2.skyhanni.features.foraging

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.ConfigUpdaterMigrator
import at.hannibal2.skyhanni.data.IslandType
import at.hannibal2.skyhanni.events.MobEvent
import at.hannibal2.skyhanni.mixins.hooks.RenderLivingEntityHelper
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.ColorUtils.toColor

@SkyHanniModule
object CoralotHighlighter {

    private val config get() = SkyHanniMod.feature.foraging.mobHighlight.coralot

    @HandleEvent(onlyOnIsland = IslandType.GALATEA)
    fun onMob(event: MobEvent.Spawn.SkyblockMob) {
        if (event.mob.name != "Coralot") return
        RenderLivingEntityHelper.setEntityColor(
            event.mob.baseEntity, config.color.toColor()
        ) { isEnabled() }
    }

    fun isEnabled() = config.enabled

    @HandleEvent
    fun onConfigFixEvent(event: ConfigUpdaterMigrator.ConfigFixEvent) {
        event.move(91, "foraging.coralotHighlight", "foraging.mobHighlight.coralot")
    }
}
