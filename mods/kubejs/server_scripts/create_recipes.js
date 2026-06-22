// Adds heated Create mixer recipes for gunpowder.
// Requires Create's KubeJS integration; use createMixing rather than legacy nested recipe helpers.
ServerEvents.recipes(event => {
  event.recipes.createMixing('2x minecraft:gunpowder', [
    'minecraft:redstone',
    'minecraft:coal',
    'minecraft:sugar',
    'minecraft:calcite',
    'minecraft:bone_meal'
  ]).heated().id('kubejs:create_mixing/gunpowder_from_coal')

  event.recipes.createMixing('2x minecraft:gunpowder', [
    'minecraft:redstone',
    'minecraft:charcoal',
    'minecraft:sugar',
    'minecraft:calcite',
    'minecraft:bone_meal'
  ]).heated().id('kubejs:create_mixing/gunpowder_from_charcoal')
})
