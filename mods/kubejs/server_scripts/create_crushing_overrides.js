// Create crushing recipe overrides.
// Requires Create KubeJS integration.
// Minecraft 1.21 Create chance outputs use CreateItem.of(item, chance).
//
// Important:
// Do not pass item tags directly as createCrushing inputs in this pack.
// KubeJS/Create 1.21 can emit invalid tag ingredients for Create crushing.
// Instead, expand tags into explicit item IDs and create one recipe per real item.
//
// This version filters minecraft:barrier because KubeJS may use it as a placeholder
// when an optional/unknown tag does not resolve.

function ukgeoSafeRecipeIdPart(value) {
  return String(value)
      .toLowerCase()
      .replace(/:/g, '_')
      .replace(/[^a-z0-9_./-]/g, '_')
}

function ukgeoExpandItemInputs(inputs) {
  var result = []
  var seen = {}

  function add(id) {
    var key = String(id)

    if (!key) {
      return
    }

    // KubeJS can return minecraft:barrier as a placeholder for missing/empty tags.
    // Never generate processing recipes for it.
    if (key === 'minecraft:barrier' || key === 'minecraft:air') {
      return
    }

    if (seen[key]) {
      return
    }

    seen[key] = true
    result.push(key)
  }

  for (var i = 0; i < inputs.length; i++) {
    var inputValue = String(inputs[i])

    if (!inputValue.startsWith('#')) {
      add(inputValue)
      continue
    }

    var beforeCount = result.length
    var ingredient = Ingredient.of(inputValue)

    ingredient.itemIds.forEach(function(id) {
      add(String(id))
    })

    if (result.length === beforeCount) {
      console.warn('[kubejs] Crushing override tag did not resolve to real item IDs, skipping: ' + inputValue)
    }
  }

  return result
}

function ukgeoRemoveCrushingForInputs(event, inputs) {
  var expanded = ukgeoExpandItemInputs(inputs)

  for (var i = 0; i < expanded.length; i++) {
    event.remove({
      type: 'create:crushing',
      input: expanded[i]
    })
  }
}

function ukgeoReplaceCrushingForInputs(event, inputs, outputFactory, idPrefix) {
  var expanded = ukgeoExpandItemInputs(inputs)

  for (var i = 0; i < expanded.length; i++) {
    var inputValue = expanded[i]

    event.remove({
      type: 'create:crushing',
      input: inputValue
    })

    event.recipes.createCrushing(outputFactory(), inputValue)
        .id('kubejs:create_crushing/' + idPrefix + '_' + ukgeoSafeRecipeIdPart(inputValue))
  }
}

ServerEvents.recipes(function(event) {
  // Remove prismarine crystals crushing.
  event.remove({ id: 'create:crushing/prismarine_crystals' })
  event.remove({
    type: 'create:crushing',
    input: 'minecraft:prismarine_crystals'
  })

  // Crimsite: 5% crushed raw iron.
  event.remove({ id: 'create:crushing/crimsite' })
  event.remove({ id: 'create:crushing/crimsite_recycling' })
  ukgeoReplaceCrushingForInputs(event, [
    'create:crimsite',
    '#create:stone_types/crimsite'
  ], function() {
    return [
      CreateItem.of('create:crushed_raw_iron', 0.05)
    ]
  }, 'crimsite_to_trace_iron')

  // Tuff: no crushing recipe.
  event.remove({ id: 'create:crushing/tuff' })
  event.remove({ id: 'create:crushing/tuff_recycling' })
  ukgeoRemoveCrushingForInputs(event, [
    'minecraft:tuff',
    '#create:stone_types/tuff',
    '#c:stones/tuff',
    '#c:tuffs'
  ])

  // Asurine: 5% crushed raw zinc.
  event.remove({ id: 'create:crushing/asurine' })
  event.remove({ id: 'create:crushing/asurine_recycling' })
  ukgeoReplaceCrushingForInputs(event, [
    'create:asurine',
    '#create:stone_types/asurine'
  ], function() {
    return [
      CreateItem.of('create:crushed_raw_zinc', 0.05)
    ]
  }, 'asurine_to_trace_zinc')

  // Veridium: 5% crushed raw copper.
  event.remove({ id: 'create:crushing/veridium' })
  event.remove({ id: 'create:crushing/veridium_recycling' })
  ukgeoReplaceCrushingForInputs(event, [
    'create:veridium',
    '#create:stone_types/veridium'
  ], function() {
    return [
      CreateItem.of('create:crushed_raw_copper', 0.05)
    ]
  }, 'veridium_to_trace_copper')

  // Ochrum: 1% crushed raw gold.
  event.remove({ id: 'create:crushing/ochrum' })
  event.remove({ id: 'create:crushing/ochrum_recycling' })
  ukgeoReplaceCrushingForInputs(event, [
    'create:ochrum',
    '#create:stone_types/ochrum'
  ], function() {
    return [
      CreateItem.of('create:crushed_raw_gold', 0.01)
    ]
  }, 'ochrum_to_trace_gold')

  // Diorite: 1% quartz.
  event.remove({ id: 'create:crushing/diorite' })
  event.remove({ id: 'create:crushing/diorite_recycling' })
  ukgeoReplaceCrushingForInputs(event, [
    'minecraft:diorite',
    '#create:stone_types/diorite',
    '#c:stones/diorite',
    '#c:diorites'
  ], function() {
    return [
      CreateItem.of('minecraft:quartz', 0.01)
    ]
  }, 'diorite_to_trace_quartz')
})