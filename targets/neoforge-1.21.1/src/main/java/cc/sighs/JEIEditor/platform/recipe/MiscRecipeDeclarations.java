package cc.sighs.JEIEditor.platform.recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Declared recipe pages from the remaining mods (Horse Powered, Rack It Up, NNP
 * Easy Farming).
 *
 * <p>Evidence: every JSON quoted below was read out of the mod jar in
 * {@code run/mods} ({@code data/&lt;ns&gt;/recipe/&lt;type&gt;/*.json}); the serializer id out of the
 * mod's own {@code DeferredRegister} call plus the recipe class's
 * {@code getSerializer()} ({@code javap -p -c}); the slot layout out of the JEI
 * category's {@code setRecipe} bytecode; and the page uids and the displayed slot
 * order out of the runtime dump {@code run/jei-category-dump.txt} (the dump's
 * {@code shape} column is a flattened candidate list, so it is used for the order
 * of the slots only, never for their count).
 *
 * <p>Ingredient and result shapes measured over every shipped sample of the
 * declared types: Horse Powered writes {@code {"ingredient":{"item"|"tag":…}}},
 * {@code {"result":{"count":…,"id":…}}} and a {@code "time"}; Rack It Up writes
 * {@code {"ingredient":{"item":…}}}, {@code {"result":{"id":…,"count":…}}} and
 * {@code {"time":…}}; NNP Easy Farming writes {@code {"input":{"item":…}}} and
 * {@code {"result":{"count":…,"id":…}}}. Inputs are therefore written back as
 * {@code {"item":…,"count":…}} (NeoForge builds {@code Ingredient.Value} from the
 * item-only {@code ItemValue} codec, which ignores a surplus {@code count}) and
 * outputs as {@code {"id":…,"count":…}}, the two shapes all three mods use
 * themselves.
 *
 * <p>The JEI uid is not the serializer id: Horse Powered registers its serializers
 * as {@code horsepowered:chopping}, so the pages {@code horsepowered:chopping} and
 * {@code horsepowered:manual_chopping} share one declaration, and NNP Easy Farming
 * registers {@code nnp_easy_farming:grindstone_serializer} for the page
 * {@code nnp_easy_farming:grindstone_recipe}.
 */
public final class MiscRecipeDeclarations {
    public static final List<ModdedRecipeAdapter> ALL;

    static {
        List<ModdedRecipeAdapter> adapters = new ArrayList<ModdedRecipeAdapter>();

        // JEI pages horsepowered:chopping ("Horse Chopping") and
        // horsepowered:manual_chopping ("Manual Chopping"), 1 input / 1 output.
        // Both pages display com.breakinblocks.horsepowered.recipes.ChoppingRecipe, whose
        // getSerializer() returns HPRecipes.CHOPPING_SERIALIZER, registered as
        // DeferredRegister.create(RECIPE_SERIALIZER, "horsepowered").register("chopping", …).
        // data/horsepowered/recipe/chopping/melon_to_slices.json:
        //   {"type":"horsepowered:chopping","ingredient":{"item":"minecraft:melon"},
        //    "result":{"count":9,"id":"minecraft:melon_slice"},"time":1}
        // data/horsepowered/recipe/chopping/acacia_log_to_planks.json:
        //   {"type":"horsepowered:chopping","ingredient":{"tag":"minecraft:acacia_logs"},
        //    "result":{"count":4,"id":"minecraft:acacia_planks"},"time":1}
        // Measured over all 25 samples: ingredient is {"tag":…} (21) or {"item":…} (4),
        // result is always {"count":…,"id":…}, time is always present and never declared, so
        // it survives verbatim.
        // HorsePowerChoppingCategory.setRecipe:
        //   addSlot(INPUT).addIngredients(getIngredient())
        //   addSlot(OUTPUT).addItemStack(getResult())
        // - one slot each, nothing conditional.
        // HorsePowerManualChoppingCategory.setRecipe adds a CATALYST slot holding the axes
        // before that pair, so the INPUT ordinal is still 0 and the JSON shape is identical.
        // The dump's 17 candidates for both pages are the 17 members of the fence-gate tag in
        // its sample recipe, i.e. candidates inside that one slot, not 17 slots.
        adapters.add(new ModdedRecipeDeclaration("horsepowered:chopping",
                Arrays.asList("ingredient"), "result",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page horsepowered:crushing ("Crushing"), 1 input / 1 output.
        // CrushingRecipe.getSerializer() returns HPRecipes.CRUSHING_SERIALIZER, registered as
        // DeferredRegister.create(RECIPE_SERIALIZER, "horsepowered").register("crushing", …).
        // data/horsepowered/recipe/crushing/stone_to_cobblestone.json:
        //   {"type":"horsepowered:crushing","ingredient":{"item":"minecraft:stone"},
        //    "result":{"count":1,"id":"minecraft:cobblestone"},"time":1}
        // data/horsepowered/recipe/crushing/tuff_to_gravel.json:
        //   {"type":"horsepowered:crushing","ingredient":{"item":"minecraft:tuff"},
        //    "result":{"count":1,"id":"minecraft:gravel"},"time":2}
        // All 10 samples: ingredient {"item":…}, result {"count":…,"id":…}, time present.
        // HorsePowerCrushingCategory.setRecipe:
        //   addSlot(CATALYST).addItemStacks(pickaxes)
        //   addSlot(INPUT).addIngredients(getIngredient())
        //   addSlot(OUTPUT).addItemStack(getResult())
        // - the catalyst slot carries no declared field and is ignored by the model, so
        // exactly one input and one output remain. The dump sample input[0]=minecraft:basalt
        // and output[0]=minecraft:smooth_basalt match ingredient / result of
        // crushing/basalt_to_smooth_basalt.json.
        adapters.add(new ModdedRecipeDeclaration("horsepowered:crushing",
                Arrays.asList("ingredient"), "result",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page horsepowered:drying ("Drying"), 1 input / 1 output.
        // DryingRackRecipe.getSerializer() returns HPRecipes.DRYING_SERIALIZER, registered as
        // DeferredRegister.create(RECIPE_SERIALIZER, "horsepowered").register("drying", …).
        // data/horsepowered/recipe/drying/mud_to_dirt.json:
        //   {"type":"horsepowered:drying","ingredient":{"item":"minecraft:mud"},
        //    "result":{"count":1,"id":"minecraft:dirt"},"time":1000}
        // data/horsepowered/recipe/drying/saplings_to_dead_bush.json:
        //   {"type":"horsepowered:drying","ingredient":{"tag":"minecraft:saplings"},
        //    "result":{"count":1,"id":"minecraft:dead_bush"},"time":1500}
        // All 6 samples: ingredient {"item":…} (5) / {"tag":…} (1), result always
        // {"count":…,"id":…}, time present.
        // HorsePowerDryingCategory.setRecipe:
        //   addSlot(INPUT).addIngredients(getIngredient())
        //   addSlot(OUTPUT).addItemStack(getResult())
        // - unconditional, so the dump sample input[0]=minecraft:mud / output[0]=minecraft:dirt
        // for drying/mud_to_dirt.json is the declared pair.
        adapters.add(new ModdedRecipeDeclaration("horsepowered:drying",
                Arrays.asList("ingredient"), "result",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI pages horsepowered:grinding ("Horse Grinding") and
        // horsepowered:manual_grinding ("Manual Grinding"), both backed by
        // HPRecipes.GRINDING_SERIALIZER, so one entry covers both pages.
        // data/horsepowered/recipe/grinding/cobblestone_to_gravel.json:
        //   {"type":"horsepowered:grinding","ingredient":{"item":"minecraft:cobblestone"},
        //    "result":{"count":1,"id":"minecraft:gravel"}}
        // 22 of the 28 samples carry exactly one output; the other 6 add
        //   {"secondary":{"count":1,"id":…},"secondaryChance":…},
        // which makes HorsePowerGrindingCategory register a second OUTPUT slot:
        //   addSlot(INPUT).addIngredients(getIngredient())
        //   addSlot(OUTPUT).addItemStack(getResult())
        //   if (!getSecondary().isEmpty()) addSlot(OUTPUT).addItemStack(getSecondary())
        // The output list is therefore ["result", "secondary"]: output.0 is always
        // "result" and the optional trailing "secondary" is used only when the page
        // draws that second slot, so all 28 samples build a model. "secondaryChance"
        // is never declared and survives verbatim - the editor does not offer to change
        // a chance, and an untouched chance keeps the mod's own drop rate.
        adapters.add(new ModdedRecipeDeclaration("horsepowered:grinding",
                Arrays.asList("ingredient"), Arrays.asList("result", "secondary"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page horsepowered:pressing ("Horse Press"), 1 input / 1 output.
        // PressRecipe.getSerializer() returns HPRecipes.PRESSING_SERIALIZER, registered as
        // DeferredRegister.create(RECIPE_SERIALIZER, "horsepowered").register("pressing", …).
        // data/horsepowered/recipe/pressing/allium_to_dye.json - the item shape:
        //   {"type":"horsepowered:pressing","ingredient":{"item":"minecraft:allium"},
        //    "result":{"count":4,"id":"minecraft:magenta_dye"}}
        // data/horsepowered/recipe/pressing/ice_to_water.json - the fluid shape:
        //   {"type":"horsepowered:pressing","fluidResult":{"amount":1000,"id":"minecraft:water"},
        //    "ingredient":{"item":"minecraft:ice"}}
        // Of the 37 shipped files 27 write "result" and 10 write "fluidResult"; 3 of the
        // latter also carry an "inputCount" that this declaration never names and therefore
        // preserves. HorsePowerPressCategory.setRecipe:
        //   addSlot(INPUT).addItemStacks(getIngredient().getItems())
        //   if (hasFluidOutput()) addSlot(OUTPUT).addFluidStack(getFluidResult())
        //   else                  addSlot(OUTPUT).addItemStack(getResult())
        // - one OUTPUT slot either way (the branch that does not run adds none), so the page
        // draws a single output slot whose field is "fluidResult" on the ten fluid recipes and
        // "result" on the other 27. A single output field cannot name that: naming "result"
        // leaves the ten fluid slots showing a kind the declaration does not name, which the
        // client test reports as a declaration that can never edit them (and it is right - the
        // server would refuse the patch), and naming "fluidResult" would do the same to the 27.
        // The ordered segment list is the mechanism for exactly this: the k-th OUTPUT slot is
        // the k-th element of the concatenation of the fields the recipe actually carries, so
        // each shape resolves to its own field and the declaration never guesses.
        adapters.add(new ModdedRecipeDeclaration("horsepowered:pressing",
                Arrays.asList("ingredient"), Arrays.asList("result", "fluidResult"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withFluidFields("fluidResult")
                .withConcatenatedOutputs());

        // JEI page rackitup:drying ("晾干"), 1 input / 1 output.
        // The serializer is registered by MRecipes as
        // DeferredRegister.create(RECIPE_SERIALIZER, "rackitup").register("drying", …) and
        // DryingRecipe.getSerializer() returns MRecipes.DRYING_SERIALISER, i.e. rackitup:drying.
        // data/rackitup/recipe/salmon_jerky.json:
        //   {"type":"rackitup:drying","ingredient":{"item":"minecraft:salmon"},
        //    "result":{"id":"rackitup:salmon_jerky","count":1},"time":12000}
        // data/rackitup/recipe/pufferfish_jerky.json:
        //   {"type":"rackitup:drying","ingredient":{"item":"minecraft:pufferfish"},
        //    "result":{"id":"rackitup:pufferfish_jerky","count":1},"time":12000}
        // All 10 samples: ingredient {"item":…}, result {"id":…,"count":…}, time present.
        // me.zoranz.rackitup.integration.jei.Drying.setRecipe:
        //   addSlot(INPUT).addIngredients(getIngredient())
        //   addSlot(OUTPUT).addItemStack(getResultItem(HolderLookup.Provider))
        //   addSlot(CATALYST).addIngredients(campfire)   // only when the rack allows one
        // - the campfire is a CATALYST slot, which the model ignores, so it is not declared
        // and survives verbatim.
        adapters.add(new ModdedRecipeDeclaration("rackitup:drying",
                Arrays.asList("ingredient"), "result",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page nnp_easy_farming:grindstone_recipe ("Grinding Recipes"), 1 input / 1 output.
        // The page uid ends in _recipe, the serializer does not: GrindstoneRecipe
        // .getSerializer() returns NoNameProvidedRecipeSerializers.GRINDSTONE_SERIALIZER,
        // registered under the namespace nnp_easy_farming as "grindstone_serializer", which is
        // exactly the "type" the mod's own recipes carry.
        // data/nnp_easy_farming/recipe/grindstone/salt_from_block.json:
        //   {"type":"nnp_easy_farming:grindstone_serializer",
        //    "input":{"item":"nnp_easy_farming:salt_block"},
        //    "result":{"count":8,"id":"nnp_easy_farming:salt"}}
        // data/nnp_easy_farming/recipe/grindstone/sand_from_gravel.json:
        //   {"type":"nnp_easy_farming:grindstone_serializer","input":{"item":"minecraft:gravel"},
        //    "result":{"count":1,"id":"minecraft:sand"}}
        // All 6 samples: input {"item":…}, result {"count":…,"id":…}.
        // GrindingRecipeCategory.setRecipe:
        //   addSlot(INPUT).addIngredients(getInputItem())
        //   addSlot(OUTPUT).addItemStack(getResult())
        // - unconditional; the dump sample input[0]=minecraft:andesite,
        // output[0]=nnp_easy_farming:salt is salt_from_andesite.json. The mod's other
        // grindstone-adjacent recipe (salt_food.json) uses a different serializer and a
        // different page, so it is untouched.
        adapters.add(new ModdedRecipeDeclaration("nnp_easy_farming:grindstone_serializer",
                Arrays.asList("input"), "result",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // Deliberately not declared. Each candidate below was dumped from its jar and
        // disassembled; none of the remaining three has a field list that names what the
        // page shows, which is what the declared field lists can address.
        //
        // horsepowered:pressing - 37 samples with two different output fields: 27 write
        //   data/horsepowered/recipe/pressing/magma_block_to_cream.json:
        //     {"type":"horsepowered:pressing","ingredient":{"item":"minecraft:magma_block"},
        //      "result":{"count":4,"id":"minecraft:magma_cream"}}
        // while 10 write a fluid and no "result" at all:
        //   data/horsepowered/recipe/pressing/ice_to_water.json:
        //     {"type":"horsepowered:pressing","fluidResult":{"amount":1000,"id":"minecraft:water"},
        //      "ingredient":{"item":"minecraft:ice"}}
        // HorsePowerPressCategory.setRecipe registers exactly one INPUT slot
        // (addItemStacks(getIngredient())) and exactly one OUTPUT slot, but the output slot
        // holds an item or a fluid depending on hasFluidOutput():
        //   if (hasFluidOutput()) addSlot(OUTPUT).addFluidStack(getFluidResult())
        //   else                 addSlot(OUTPUT).addItemStack(getResult())
        // PressRecipe's codec keeps both as independent optional fields
        // (Ingredient.CODEC_NONEMPTY.fieldOf("ingredient"),
        // ItemStack.OPTIONAL_CODEC.optionalFieldOf("result"),
        // FluidStack.OPTIONAL_CODEC.optionalFieldOf("fluidResult"), plus optional "inputCount"
        // and "priority"), so a fluid recipe accepts a stray "result" next to "fluidResult".
        // Since the OUTPUT count is 1 either way the slot-count check cannot tell the two
        // shapes apart, CraftingSlotMapper.slotKey answers "output" for any OUTPUT-role slot
        // view, and RecipeAdapterSupport.simpleStack yields no representative for a fluid slot
        // (ITypedIngredient.getItemStack() only maps VanillaTypes.ITEM_STACK) - the editor would
        // show that output as an empty, droppable slot and a drop there would append
        // {"id":…,"count":…} as a second output field next to "fluidResult". The recipe's codec
        // accepts the pair and PressBlockEntity.canProcess validates only the output tank on the
        // hasFluidOutput() path, so the edit is written rather than refused while the page keeps
        // showing the fluid - the "patched wrongly" case the other declaration files refuse. The
        // page is therefore left read-only even though its 27 item-result recipes are exactly the
        // declared shape, which ("ingredient", "result", ITEM, ID) would serve.
        //
        // horsepowered:bottling - the single sample writes a component-carrying output:
        //   data/horsepowered/recipe/bottling/water_bottle.json:
        //     {"type":"horsepowered:bottling","container":{"item":"minecraft:glass_bottle"},
        //      "fluid":{"amount":250,"id":"minecraft:water"},
        //      "result":{"components":{"minecraft:potion_contents":{"potion":"minecraft:water"}},
        //                "count":1,"id":"minecraft:potion"}}
        // HorsePowerBottlingCategory.setRecipe registers INPUT(container), then a CATALYST slot
        // for the fluid, then OUTPUT(result) - so the slot shape itself is 1/1 and the fluid is
        // not the obstacle. The output is: RecipeAdapterSupport.simpleStack rejects any ItemStack
        // with a component patch, so the OUTPUT slot has no representative and the editor shows
        // it empty, while RecipeEditsApplier.declaredItemJson rebuilds the whole "result" node as
        // {"id":…,"count":…} - for a count-only scroll too - which drops "components" and turns
        // the water bottle into a plain potion. The output cannot be round-tripped, and this is
        // the mod's only bottling recipe, so the page stays read-only.
        //
        // horsepowered:trapping - the output slot is not an item field:
        //   data/horsepowered/recipe/trapping/cow.json:
        //     {"type":"horsepowered:trapping","bait":{"item":"minecraft:wheat"},
        //      "baitConsumeChance":10.0,"baitConsumed":true,"entity":"minecraft:cow"}
        // HorsePowerTrappingCategory.setRecipe:
        //   addSlot(CATALYST).addItemStack(animal_trap)
        //   addSlot(INPUT).addIngredients(getBait())
        //   addSlot(OUTPUT).addItemStack(getDisplayIcon())
        // - the displayed output is a spawn egg derived from the "entity" string, and the recipe
        // JSON has no item output. Declaring "entity" as the output field would make the editor
        // write {"id":…,"count":…} where the codec expects a plain entity id, so the bait is the
        // only writable field and a declaration must name an output too.

        ALL = Collections.unmodifiableList(adapters);
    }

    private MiscRecipeDeclarations() {
    }
}
