// Disable normal villager trading for the pack.
//
// Wandering traders are intentionally left untouched.
//
// Important: do NOT mutate VillagerTradesEvent#getTrades() here.
// In NeoForge 1.21/KubeJS, that object is an Int2ObjectMap keyed by villager
// level integers. Treating it like a normal JS/string-keyed object can crash
// world/resource loading with String -> Integer ClassCastException.
//
// Instead, handle only player interaction with normal minecraft:villager
// entities. We try to cancel the interaction through whichever cancellation
// method KubeJS/NeoForge exposes, and we also clear the clicked villager's
// already-created offers as a defensive fallback. All calls are guarded so a
// missing API method cannot crash startup or world loading.

var $Villager = Java.loadClass('net.minecraft.world.entity.npc.Villager')
var $InteractionResult = Java.loadClass('net.minecraft.world.InteractionResult')

function vanillaAdjust$getTarget(event) {
  if (event == null) {
    return null
  }

  try {
    return event.getTarget()
  } catch (ignored) {
  }

  try {
    return event.target
  } catch (ignored) {
  }

  try {
    return event.entity
  } catch (ignored) {
  }

  return null
}

function vanillaAdjust$isNormalVillager(entity) {
  if (entity == null) {
    return false
  }

  try {
    if (entity instanceof $Villager) {
      return true
    }
  } catch (ignored) {
  }

  try {
    var id = String(entity.getType().builtInRegistryHolder().key().location())
    return id === 'minecraft:villager'
  } catch (ignored) {
  }

  try {
    var id = String(entity.type)
    return id === 'minecraft:villager'
  } catch (ignored) {
  }

  return false
}

function vanillaAdjust$clearVillagerOffers(entity) {
  if (entity == null) {
    return
  }

  try {
    var offers = entity.getOffers()
    if (offers != null) {
      offers.clear()
    }
  } catch (ignored) {
  }
}

function vanillaAdjust$cancelInteraction(event) {
  // KubeJS wrapper-style cancellation, if the event is wrapped.
  try {
    event.cancel()
    return true
  } catch (ignored) {
  }

  // NeoForge-style cancellation APIs vary between event classes/versions.
  try {
    event.setCanceled(true)
    return true
  } catch (ignored) {
  }

  try {
    event.setCancellationResult($InteractionResult.SUCCESS)
    return true
  } catch (ignored) {
  }

  try {
    event.setCancellationResult($InteractionResult.CONSUME)
    return true
  } catch (ignored) {
  }

  return false
}

function vanillaAdjust$blockVillagerTrading(event) {
  var target = vanillaAdjust$getTarget(event)
  if (!vanillaAdjust$isNormalVillager(target)) {
    return
  }

  vanillaAdjust$clearVillagerOffers(target)
  vanillaAdjust$cancelInteraction(event)
}

// Do not handle VillagerTradesEvent or WandererTradesEvent.
// Normal villager trades are blocked on interaction; wandering traders remain normal.
NativeEvents.onEvent('net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$EntityInteract', function(event) {
  vanillaAdjust$blockVillagerTrading(event)
})

NativeEvents.onEvent('net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$EntityInteractSpecific', function(event) {
  vanillaAdjust$blockVillagerTrading(event)
})
