package cc.sighs.JEIEditor.platform.recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Declared Create recipe pages.
 *
 * <p>Create serialises ingredient lists as arrays, so the declared fields are
 * JSON paths ({@code ingredients.0}, {@code results.0}) rather than plain keys.
 *
 * <p>Evidence: every sample below was read out of
 * {@code create__create-1.21.1-6.0.10.jar} (and, for the compat recipes other
 * mods ship, out of {@code data/<ns>/recipe/} in those jars), and the JEI slot
 * order was taken from the runtime dump {@code run/jei-category-dump.txt}.
 * Across all shipped samples of these types Create always writes ingredients as
 * {@code {"item":…}} / {@code {"tag":…}} inside an {@code ingredients} array and
 * results as {@code {"id":…, "count":…, "chance":…}} inside a {@code results}
 * array, so inputs are written back as {@code {"item":…,"count":…}} and the
 * output as {@code {"id":…,"count":…}}.
 *
 * <p>Counts per recipe type (measured over every sample, not just the two quoted
 * ones): {@code create:deploying} 2 ingredients / 1 result,
 * {@code create:haunting} 1 / 1 (one compat recipe has 2 results),
 * {@code create:item_application} 2 / 1, {@code create:pressing} 1 / 1,
 * {@code create:sandpaper_polishing} 1 / 1, {@code create:splashing} 1 / 1
 * (nine compat recipes have 2 results), {@code create:crushing} 1 / 1..5,
 * {@code create:milling} 1 / 1..3 and {@code create:cutting} 1 / 1.
 * MillingCategory and CrushingCategory both draw one OUTPUT slot per entry of
 * {@code ProcessingRecipe.getRollableResults()},
 * which returns the {@code results} NonNullList verbatim - no entry is filtered
 * out and every entry carries a non-empty stack - so one OUTPUT slot of those
 * pages is exactly one {@code results} element, in array order, and no output
 * slot is ever empty. FanProcessingCategory's multi-output layout draws one
 * OUTPUT slot per entry of the {@code results} array as well, so
 * {@code create:haunting}, {@code create:splashing}, {@code create:crushing} and
 * {@code create:milling} declare {@code "results.%d"}: that covers their
 * one-result recipes as {@code results.0} and every longer shape as
 * {@code results.0} plus {@code results.1} and so on, where the old fixed
 * {@code "results.0"} refused them.
 *
 * <p>Known limitation of the multi-result shapes, which only became reachable
 * with that repeating field: a written result is the declaration's whole
 * {@code {"id":…,"count":…}} node, so an edited result that carried a
 * {@code "chance"} loses it and becomes a guaranteed drop. The sibling result of
 * the same recipe is untouched and keeps its own chance. The declaration API has
 * no chance shape, and every Create result is written in the same shape, so this
 * is accepted rather than worked around. It matters most for
 * {@code create:crushing}, whose second and later results are usually the
 * {@code 0.75}/{@code 0.5}-chance by-products: editing one of those entries makes
 * it a guaranteed drop.
 */
public final class CreateRecipeDeclarations {
    public static final List<ModdedRecipeAdapter> ALL;

    static {
        List<ModdedRecipeAdapter> adapters = new ArrayList<ModdedRecipeAdapter>();

        // JEI page create:sawing ("切削"), 1 input / 1 output.
        // data/create/recipe/cutting/andesite_alloy.json:
        //   {"type":"create:cutting","ingredients":[{"item":"create:andesite_alloy"}],
        //    "processing_time":200,"results":[{"count":6,"id":"create:shaft"}]}
        // data/create/recipe/cutting/bamboo_planks.json: same keys, processing_time 20.
        // JEI: input[0]=minecraft:jungle_log, output[0]=minecraft:stripped_jungle_log,
        // matching ingredients[0] / results[0]. processing_time and neoforge:conditions survive untouched.
        adapters.add(new ModdedRecipeDeclaration("create:cutting",
                Arrays.asList("ingredients.0"), "results.0",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page create:deploying ("使用"), 2 inputs / 1 output.
        // data/create/recipe/deploying/waxed_exposed_copper_tile_stairs_from_adding_wax.json:
        //   {"type":"create:deploying",
        //    "ingredients":[{"item":"create:exposed_copper_tile_stairs"},{"item":"minecraft:honeycomb_block"}],
        //    "keep_held_item":true,"results":[{"id":"create:waxed_exposed_copper_tile_stairs"}]}
        // data/create/recipe/deploying/copper_grate_from_deoxidising.json: ingredients[1]={"tag":"minecraft:axes"}
        // data/create/recipe/deploying/cogwheel.json: ingredients[1]={"tag":"minecraft:planks"}, no keep_held_item.
        // JEI: input[0]=create:exposed_copper_tile_stairs, input[1]=minecraft:honeycomb_block,
        // output[0]=create:waxed_exposed_copper_tile_stairs, i.e. the same order as the array.
        // keep_held_item is never declared, so it survives verbatim.
        adapters.add(new ModdedRecipeDeclaration("create:deploying",
                Arrays.asList("ingredients.0", "ingredients.1"), "results.0",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page create:fan_haunting ("批量缠魂"), 1 input / 1 output.
        // The JEI uid is create:fan_haunting but the recipe type is create:haunting.
        // data/cobblemon/recipe/mod_compatibility/create/haunting/upgrade.json:
        //   {"ingredients":[{"item":"cobblemon:upgrade"}],"neoforge:conditions":[...],
        //    "results":[{"id":"cobblemon:dubious_disc"}],"type":"create:haunting"}
        // data/create/recipe/haunting/infested_stone.json:
        //   {"type":"create:haunting","ingredients":[{"item":"minecraft:stone"}],
        //    "results":[{"id":"minecraft:infested_stone"}]}
        // JEI: input[0]=cobblemon:upgrade, output[0]=cobblemon:dubious_disc.
        // MultiOutput.setRecipe adds one OUTPUT slot per rollable result in array order, so
        // "results.%d" names output.0 as results.0 and output.1 as results.1. The single
        // compat recipe with two results (data/cobblemon/... in the pack) now builds a
        // model as well; the old fixed "results.0" refused it.
        adapters.add(new ModdedRecipeDeclaration("create:haunting",
                Arrays.asList("ingredients.0"), Arrays.asList("results.%d"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page create:item_application ("手动物品使用"), 2 inputs / 1 output.
        // data/create/recipe/item_application/brass_casing_from_log.json:
        //   {"type":"create:item_application",
        //    "ingredients":[{"tag":"c:stripped_logs"},{"tag":"c:ingots/brass"}],
        //    "results":[{"id":"create:brass_casing"}]}
        // data/create/recipe/item_application/railway_casing.json:
        //   {"type":"create:item_application",
        //    "ingredients":[{"item":"create:brass_casing"},{"tag":"c:plates/obsidian"}],
        //    "results":[{"id":"create:railway_casing"}]}
        // JEI: the layout draws ingredients[0] and ingredients[1] as two INPUT slots; the
        // ingredient dump lists input[0..12] = the c:stripped_logs members and
        // input[13]=create:brass_ingot for the sample, i.e. 14 candidates spread over those
        // two slots, array slot 0 first and slot 1 last, so the order matches.
        adapters.add(new ModdedRecipeDeclaration("create:item_application",
                Arrays.asList("ingredients.0", "ingredients.1"), "results.0",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page create:pressing ("冲压"), 1 input / 1 output.
        // data/create/recipe/pressing/iron_ingot.json:
        //   {"type":"create:pressing","ingredients":[{"tag":"c:ingots/iron"}],
        //    "results":[{"id":"create:iron_sheet"}]}
        // data/create/recipe/pressing/cardboard.json:
        //   {"type":"create:pressing","ingredients":[{"item":"create:pulp"}],
        //    "results":[{"id":"create:cardboard"}]}
        // data/create/recipe/pressing/path.json: ingredients[0] is a raw array of item values,
        // and the *_dirt_path compat recipes use {"type":"neoforge:compound","ingredients":[...]};
        // both are still a single element of ingredients, so ingredients.0 addresses them.
        // JEI: input[0]=mekanism:ingot_lead, input[1]=immersiveengineering:ingot_lead,
        // output[0]=immersiveengineering:plate_lead for the c:ingots/lead sample: one array
        // element, one INPUT slot of two tag candidates, one output - the declared shape.
        adapters.add(new ModdedRecipeDeclaration("create:pressing",
                Arrays.asList("ingredients.0"), "results.0",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page create:sandpaper_polishing ("砂纸打磨"), 1 input / 1 output.
        // data/create/recipe/sandpaper_polishing/rose_quartz.json:
        //   {"type":"create:sandpaper_polishing","ingredients":[{"item":"create:rose_quartz"}],
        //    "results":[{"id":"create:polished_rose_quartz"}]}
        // JEI: input[0]=create:rose_quartz, output[0]=create:polished_rose_quartz.
        adapters.add(new ModdedRecipeDeclaration("create:sandpaper_polishing",
                Arrays.asList("ingredients.0"), "results.0",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page create:fan_washing ("批量洗涤"), 1 input / 1 output.
        // The JEI uid is create:fan_washing but the recipe type is create:splashing.
        // data/create/recipe/splashing/magma_block.json:
        //   {"type":"create:splashing","ingredients":[{"item":"minecraft:magma_block"}],
        //    "results":[{"id":"minecraft:obsidian"}]}
        // data/create/recipe/splashing/crushed_raw_iron.json:
        //   {"type":"create:splashing","ingredients":[{"item":"create:crushed_raw_iron"}],
        //    "results":[{"count":9,"id":"minecraft:iron_nugget"},{"chance":0.75,"id":"minecraft:redstone"}]}
        // JEI sample: input[0]=minecraft:purple_concrete_powder, output[0]=minecraft:purple_concrete.
        // "results.%d" covers the two-result recipes above (output.1 = results.1) as well as the
        // one-result ones; the old fixed "results.0" refused the two-result shape.
        adapters.add(new ModdedRecipeDeclaration("create:splashing",
                Arrays.asList("ingredients.0"), Arrays.asList("results.%d"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page create:milling ("研磨"), 1 input / 1..3 outputs.
        // data/create/recipe/milling/charcoal.json:
        //   {"type":"create:milling","ingredients":[{"item":"minecraft:charcoal"}],
        //    "processing_time":100,"results":[{"id":"minecraft:black_dye"},
        //                                    {"chance":0.1,"count":2,"id":"minecraft:gray_dye"}]}
        // data/create/recipe/milling/allium.json: three results - {"count":2,…},
        //   {"chance":0.1,"count":2,…}, {"chance":0.1,…} - so the array length is not fixed.
        // JEI: input[0]=minecraft:charcoal, output[0]=minecraft:black_dye,
        // output[1]=minecraft:gray_dye for the first sample, i.e. the results order.
        // MillingCategory.setRecipe adds one OUTPUT slot per entry of getRollableResults(),
        // which is the "results" NonNullList itself, so "results.%d" names output.0 as
        // results.0 and output.n as results.n. Counted over the 47 milling samples Create
        // ships (compat mods add more): 13 hold one result, 24 hold two, 10 hold three.
        // Every entry carries a non-empty stack, so no milling output slot is ever empty.
        adapters.add(new ModdedRecipeDeclaration("create:milling",
                Arrays.asList("ingredients.0"), Arrays.asList("results.%d"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page create:crushing ("粉碎"), 1 input / 1..5 outputs.
        // data/create/recipe/crushing/amethyst_block.json:
        //   {"type":"create:crushing","ingredients":[{"item":"minecraft:amethyst_block"}],
        //    "processing_time":150,"results":[{"count":3,"id":"minecraft:amethyst_shard"},
        //                                     {"chance":0.5,"id":"minecraft:amethyst_shard"}]}
        // data/create/recipe/crushing/aluminum_ore.json adds "neoforge:conditions" and holds
        //   three results; data/create/recipe/crushing/asurine.json is an all-optional pair
        //   ({"chance":0.3,…} twice).
        // JEI: input[0]=create:asurine … the sample in the dump is
        //   cobblemon:water_gem_block -> cobblemon:water_gem twice (2 results, 2 outputs);
        //   CrushingCategory.layoutOutput walks the same getRollableResults() list and adds
        //   one OUTPUT slot per entry at a computed position, so the slot ordinal is the
        //   results index again ("results.%d"). Counted over the 82 crushing samples Create
        //   ships: 3 hold one result, 42 hold two, 13 hold three, 20 hold four, 4 hold five.
        // "neoforge:conditions" and "processing_time" survive verbatim; as with milling, an
        // edited entry loses its "chance" (see the class comment).
        adapters.add(new ModdedRecipeDeclaration("create:crushing",
                Arrays.asList("ingredients.0"), Arrays.asList("results.%d"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page create:mixing ("混合搅拌") and create:packing ("塑形"), whose
        // serializers are create:mixing and create:compacting. Both draw
        // com.simibubi.create.compat.jei.category.MixingCategory /
        // PackingCategory, which extend BasinCategory and (for the COMPACTING
        // PackingType, i.e. the create:packing page) call its setRecipe:
        // one INPUT slot per entry of ItemHelper.condenseIngredients(getIngredients())
        // - entries with equal Ingredient.getItems() merged into one slot carrying
        // their count - followed by one per entry of getFluidIngredients(), and one
        // OUTPUT slot per item result followed by one per fluid result. The slot
        // sequence is therefore derived from the recipe data and not from the JSON
        // layout, so neither a positional field pattern nor an ordered segment list
        // can name it: see CreateBasinSlotSequence for the bytecode evidence and for
        // how the sequence is resolved while the patch is written.
        //   data/create/recipe/compacting/ice.json: nine {"item":"minecraft:snow_block"}
        //     entries, one drawn slot (the merged group), results[0] the output.
        //   data/create/recipe/mixing/cardboard_pulp.json: four identical
        //     {"tag":"create:pulpifiable"} entries plus a water fluid ingredient, so
        //     input.0 is the four-entry group and input.1 the fluid; editing input.0
        //     rewrites all four entries, which is what keeps the group (and the slot
        //     count of the rebuilt page) the same.
        //   data/create/recipe/mixing/chocolate_melting.json: one item, one fluid
        //     result - the page's only output slot holds a fluid, so the output kind
        //     is the recipe's business and the declaration states both.
        // The fluid ingredient is written as {"type":"neoforge:single","fluid":…,
        // "amount":…}: Create's SizedFluidIngredient codec dispatches on that
        // explicit type (ModdedJsonStyle.TYPED_FLUID), and the fluid result as
        // {"amount":…,"id":…}. Item ingredients keep the ITEM shape every other
        // Create page uses and item results the ID shape, so the two item halves
        // read exactly like create:crushing's.
        adapters.add(new ModdedRecipeDeclaration("create:mixing", CreateBasinSlotSequence.INSTANCE,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.TYPED_FLUID,
                ModdedJsonStyle.FLUID_ID)
                // The JEI page create:automatic_brewing ("自动酿造") shares this
                // serializer and this category class, but its 286 recipes are
                // synthesised at load time from Level.potionBrewing() (their ids are
                // create:potion_mixing_vanilla_<n>) and have no recipe JSON to patch,
                // so the page stays read-only: the client builds no model for it and
                // the checklist counts it as a gap, exactly like
                // immersiveengineering:arc_recycling.
                .withReadOnlyPages("create:automatic_brewing"));
        adapters.add(new ModdedRecipeDeclaration("create:compacting", CreateBasinSlotSequence.INSTANCE,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.TYPED_FLUID,
                ModdedJsonStyle.FLUID_ID));

        // JEI page create:spout_filling ("注液器"), 2 inputs / 1 output.
        // data/create/recipe/filling/compat/immersiveengineering/treated_wood_in_spout.json:
        //   {"type":"create:filling","ingredients":[{"tag":"minecraft:planks"},
        //     {"type":"neoforge:tag","amount":125,"tag":"c:creosote"}],
        //    "results":[{"id":"immersiveengineering:treated_wood_horizontal"}]}
        // SpoutCategory.setRecipe adds exactly three slots:
        //   INPUT  FillingRecipe.getIngredients().get(0)   // the item entry
        //   INPUT  FillingRecipe.getRequiredFluid()        // the fluid entry
        //   OUTPUT getResultItem(recipe)                   // the item result
        // Because the two accesses are `.get(0)` and "the first fluid", the item and the
        // fluid can sit at any index of the JSON array - Create's codec partitions one
        // "ingredients" array into items and fluids - so the page is declared through
        // CreateSpoutSlotSequence, which resolves each slot's path by the entry's own kind
        // and refuses a recipe with more than one of either (the extras would be invisible
        // on the page). The fluid input is written in Create's sized-fluid shape
        // ({"type":"neoforge:single"/"neoforge:tag","amount":…}), which is what those files
        // use, and the item result in the result-side item shape.
        // The page lists 50 entries; 19 files ship under data/create/recipe/filling/** and
        // the provenance probe documented above measured 12 of them in the manager with a
        // JSON resource (the rest of the files are gated by neoforge:conditions whose mods
        // are not loaded). A declared page models every entry whose layout fits - this one
        // reports 40/40 sampled - so an entry Create synthesises at display time
        // (SpoutCategory#consumeRecipes) gets a model too, and its patch is then refused by
        // the server, which finds no recipe JSON for that id (scripts/jei-declared-smoke.ps1
        // pins that refusal for the draining page's synthesised ids, where ALL sampled
        // entries are of that kind). The 12 reachable entries are editable; the page is
        // reported as partially covered for exactly this reason.
        adapters.add(new ModdedRecipeDeclaration("create:filling",
                CreateSpoutSlotSequence.INSTANCE,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.TYPED_FLUID,
                ModdedJsonStyle.FLUID_ID));

        // JEI page create:mechanical_crafting ("机械合成"), 7..13 inputs / 1 output.
        // data/create/recipe/mechanical_crafting/crushing_wheel.json:
        //   {"type":"create:mechanical_crafting","accept_mirrored":false,"category":"misc",
        //    "key":{"A":{"item":"create:andesite_alloy"},"P":{"tag":"minecraft:planks"},
        //           "S":{"tag":"c:stones"}},
        //    "pattern":[" AAA ","AAPAA","APSPA","AAPAA"," AAA "],
        //    "result":{"count":2,"id":"create:crushing_wheel"},"show_notification":false}
        // MechanicalCraftingCategory.setRecipe adds OUTPUT(getResultItem) and then one INPUT
        // slot per element of CraftingRecipe.getIngredients() - a shaped pattern's
        // non-empty cells in reading order - so the page is declared through
        // CreateMechanicalCraftingSlotSequence, which maps the k-th slot to the k-th
        // non-space character of the pattern and its field "key.<character>", and checks
        // each letter against the Ingredient the mod built for that ordinal.
        // The consequence of that mapping is the recipe's own structure: a letter used by
        // several cells is ONE field, so editing a cell changes the ingredient of every cell
        // using that letter (in crushing_wheel.json the "A" cells are 13 of 25; the smoke
        // case pins it on wand_of_symmetry's "G"). Create's own JSON cannot express
        // per-cell ingredients, so this is what "edit this recipe" means there.
        // "accept_mirrored", "category" and "show_notification" are undeclared and survive.
        adapters.add(new ModdedRecipeDeclaration("create:mechanical_crafting",
                CreateMechanicalCraftingSlotSequence.INSTANCE,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.FLUID, ModdedJsonStyle.FLUID_ID));

        // JEI page create:sequenced_assembly ("序列组装"), 2..4 inputs / 1 output.
        // data/create/recipe/sequenced_assembly/sturdy_sheet.json:
        //   {"type":"create:sequenced_assembly","ingredient":{"tag":"c:dusts/obsidian"},
        //    "loops":5,"transitional_item":{"id":"create:unprocessed_obsidian_sheet"},
        //    "results":[{"id":"create:sturdy_sheet"}],
        //    "sequence":[{"type":"create:filling","ingredients":[
        //                    {"item":"create:unprocessed_obsidian_sheet"},
        //                    {"type":"neoforge:single","fluid":"minecraft:lava","amount":500}],
        //                  "results":[{"id":"create:unprocessed_obsidian_sheet"}]},
        //                {"type":"create:pressing","ingredients":[...],"results":[...]}, ...]}
        // SequencedAssemblyCategory.setRecipe adds one INPUT for getIngredient(), one OUTPUT
        // for getResultItem(), and then one slot per step that its JEI sub-category draws:
        // "create:deploying" draws the step's SECOND item ingredient, "create:filling" draws
        // its first fluid one, and "create:pressing"/"create:cutting" draw none. The slots
        // therefore follow the steps, at per-step paths, so the page is declared through
        // CreateSequencedAssemblySlotSequence instead of a field list. Its steps' fluid
        // entries are written as {"type":"neoforge:single","fluid":…,"amount":…}, which the
        // SizedFluidIngredient codec rejects without that explicit type, so the fluid input
        // style is TYPED_FLUID (a written node replaces the entry wholesale, keeping the type
        // and nothing else the old node had).
        // "loops", "transitional_item" and every step's own "results"/"type" are undeclared
        // and survive.
        adapters.add(new ModdedRecipeDeclaration("create:sequenced_assembly",
                CreateSequencedAssemblySlotSequence.INSTANCE,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.TYPED_FLUID, ModdedJsonStyle.FLUID_ID));

        // Deliberately not declared: create:mystery_conversion ("神秘转化"), whose
        // serializer is create:conversion. Its recipes are not datapack recipes at all -
        // no "create:conversion" JSON exists in any jar or datapack, and
        // com.simibubi.create.compat.jei.ConversionRecipe builds the id create:conversion_0
        // as a JEI-only pseudo recipe, so the server would never find the recipe or its
        // source JSON to patch.
        ALL = Collections.unmodifiableList(adapters);
    }

    private CreateRecipeDeclarations() {
    }
}
