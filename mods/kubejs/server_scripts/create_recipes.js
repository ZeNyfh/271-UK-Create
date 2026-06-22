// Adds and overrides Create recipes for the pack.
// Requires Create's KubeJS integration.
// Use createMixing/createSplashing helpers rather than legacy nested recipe helpers.
// Minecraft 1.21 Create chance outputs use CreateItem.of(item, chance).

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

  event.remove({ id: 'create:splashing/crushed_raw_zinc' })
  event.remove({
    type: 'create:splashing',
    input: 'create:crushed_raw_zinc'
  })
  event.recipes.createSplashing('9x create:zinc_nugget', 'create:crushed_raw_zinc')
      .id('kubejs:create_splashing/crushed_raw_zinc_to_zinc_nuggets')

  event.remove({ id: 'create:filling/gunpowder' })

  event.remove({ id: 'create:splashing/red_sand' })
  event.remove({
    type: 'create:splashing',
    input: 'minecraft:red_sand'
  })

  event.remove({ id: 'create:splashing/gravel' })
  event.remove({
    type: 'create:splashing',
    input: 'minecraft:gravel'
  })
  event.recipes.createSplashing('minecraft:flint', 'minecraft:gravel')
      .id('kubejs:create_splashing/gravel_to_flint')

  event.remove({ id: 'create:splashing/crushed_raw_gold' })
  event.remove({
    type: 'create:splashing',
    input: 'create:crushed_raw_gold'
  })
  event.recipes.createSplashing('9x minecraft:gold_nugget', 'create:crushed_raw_gold')
      .id('kubejs:create_splashing/crushed_raw_gold_to_gold_nuggets')

  event.remove({ id: 'create:splashing/soul_sand' })
  event.remove({
    type: 'create:splashing',
    input: 'minecraft:soul_sand'
  })

  event.remove({ id: 'create:splashing/crushed_raw_iron' })
  event.remove({
    type: 'create:splashing',
    input: 'create:crushed_raw_iron'
  })
  event.recipes.createSplashing([
    '9x minecraft:iron_nugget',
    CreateItem.of('minecraft:redstone', 0.10)
  ], 'create:crushed_raw_iron')
      .id('kubejs:create_splashing/crushed_raw_iron_to_iron_nuggets_redstone')

  event.remove({ id: 'create:splashing/magma_block' })
  event.remove({
    type: 'create:splashing',
    input: 'minecraft:magma_block'
  })
})