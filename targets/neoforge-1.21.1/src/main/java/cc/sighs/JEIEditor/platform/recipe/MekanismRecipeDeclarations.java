package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Declared Mekanism recipe pages. Inputs and outputs are flat JSON objects with
 * {@code item}/{@code id} plus {@code count}, {@code chemical}/{@code id} plus
 * {@code amount}, or {@code fluid}/{@code id} plus {@code amount}.
 *
 * <p>Every entry was checked against the mod's own recipe JSON
 * ({@code data/mekanism/recipe/...} of mekanism-1.21.1-10.7.19.85.jar) and the
 * JEI slot order reported by {@code run/jei-category-dump.txt}. Item-shaped
 * input fields ({@code ItemStackIngredient}) are written as
 * {@code {"item": id, "count": n}} and item outputs ({@code ItemStack}) as
 * {@code {"id": id, "count": n}}; an unedited input keeps its original tag form
 * and is only narrowed to a single item when that slot is edited.
 *
 * <p>The chemical pages are the ones that need the per-field ingredient kind: a
 * page can hold an item input and a chemical input at the same time, so the
 * declaration names the kind of each field pattern and both sides refuse a
 * mismatch. The exact codecs behind each shape are quoted in the static block.
 */
public final class MekanismRecipeDeclarations {
    public static final List<ModdedRecipeAdapter> ALL;

    static {
        List<ModdedRecipeAdapter> adapters = new ArrayList<ModdedRecipeAdapter>();
        // mekanism:combining -> {"main_input","extra_input","output"}.
        // Observed: {"type":"mekanism:combining","extra_input":{"count":1,"tag":"c:cobblestones/normal"},
        //   "main_input":{"count":8,"tag":"c:raw_materials/gold"},"output":{"count":1,"id":"minecraft:gold_ore"}}
        // JEI draws main_input, extra_input and output in that order
        // (input[0]=raw_gold, input[1]=cobblestone, output[0]=gold_ore);
        // both inputs are written as {"item": id, "count": n} and the output as {"id": id, "count": n}.
        adapters.add(new ModdedRecipeDeclaration("mekanism:combining",
                Arrays.asList("main_input", "extra_input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID));
        // mekanism:crushing -> {"input","output"}, 1 input / 1 output in JEI.
        // Observed: {"type":"mekanism:crushing","input":{"count":1,"item":"minecraft:bone"},
        //   "output":{"count":6,"id":"minecraft:bone_meal"}}
        // Observed tag form: {"type":"mekanism:crushing","input":{"count":1,"tag":"c:rods/blaze"},
        //   "output":{"count":4,"id":"minecraft:blaze_powder"}}
        // JEI shape: input[0]=item_stack:minecraft:cracked_deepslate_bricks,
        // output[0]=item_stack:minecraft:deepslate_tiles.
        adapters.add(new ModdedRecipeDeclaration("mekanism:crushing",
                Arrays.asList("input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID));
        // mekanism:enriching -> {"input","output"}, 1 input / 1 output in JEI
        // (same serializer codec as crushing).
        // Observed: {"type":"mekanism:enriching","input":{"count":1,"item":"minecraft:packed_mud"},
        //   "output":{"count":1,"id":"minecraft:mud_bricks"}}
        // JEI: input[0]=item_stack:minecraft:packed_mud, output[0]=item_stack:minecraft:mud_bricks.
        adapters.add(new ModdedRecipeDeclaration("mekanism:enriching",
                Arrays.asList("input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID));
        // JEI page mekanism:sawing ("精密锯木机"), 1 input / 2 outputs.
        // data/mekanism/recipe/sawing/trapdoor/cherry.json:
        //   {"type":"mekanism:sawing","input":{"count":1,"item":"minecraft:cherry_trapdoor"},
        //    "main_output":{"count":3,"id":"minecraft:cherry_planks"}}
        // data/mekanism/recipe/sawing/bed/black.json:
        //   {"type":"mekanism:sawing","input":{"count":1,"item":"minecraft:black_bed"},
        //    "main_output":{"count":3,"id":"minecraft:oak_planks"},"secondary_chance":1.0,
        //    "secondary_output":{"count":3,"id":"minecraft:black_wool"}}
        // 84 of the 124 shipped recipes carry "secondary_output" (and always together with
        // "secondary_chance"); the other 40 carry only "main_output".
        // JEI: input[0]=minecraft:cherry_trapdoor, output[0]=minecraft:cherry_planks for the
        // first sample - the ingredient dump lists no second output there, but that is the
        // flattened supplier, not the slot view. SawmillRecipeCategory.setRecipe adds two
        // OUTPUT slots unconditionally, in this order: getMainOutputDefinition() at
        // (output.x + 4, output.y + 4) and getSecondaryOutputDefinition() at
        // (output.x + 20, output.y + 4), both through initItem(...) ->
        // addSlot(...).addItemStacks(list). For a recipe without a secondary that list is
        // empty, and JEI keeps the slot: RecipeLayoutBuilder.addSlot puts it in
        // "visibleSlots" unconditionally and RecipeSlotBuilder.build builds a RecipeSlot from
        // it without any emptiness check, so the page always shows two OUTPUT slots and the
        // second one has no ingredient.
        // The editor's contract for an output slot that holds no ingredient is to refuse the
        // edit (ModdedRecipeAdapters.replaceOutput), which is exactly what the client test's
        // assertion C checks, so the empty second slot of the 40 recipes stays read-only
        // instead of writing a "secondary_output" whose "secondary_chance" would stay absent.
        // The writable half is worth it: "main_output" and "secondary_output" are both plain
        // ItemStack fields written as {"id":…,"count":…}, and "secondary_chance" survives
        // verbatim when the secondary is edited.
        adapters.add(new ModdedRecipeDeclaration("mekanism:sawing",
                Arrays.asList("input"), Arrays.asList("main_output", "secondary_output"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID));
        // Not declared:
        // - mekanism:smelting -> the page has no recipe JSON at all; its 284 recipes
        //   are synthesised at runtime from minecraft:smelting recipes
        //   (MekanismRecipeType.getRecipesUncached -> BasicSmeltingRecipe, ids built by
        //   RecipeViewerUtils.synthetic(id, "mekanism_generated")), so the declared
        //   write path has no original JSON to patch.
        //
        // --- chemical pages -----------------------------------------------------
        //
        // Mekanism is the chemical recipe type this editor declares, so it is also
        // where the per-field ingredient kind is spelled out. Two codecs decide every
        // shape below, read out of Mekanism-1.21.1-10.7.19.85.jar:
        //   chemical INPUT  ChemicalStackIngredient.CODEC =
        //     ChemicalIngredient ({"chemical":…} XOR {"tag":…}) + POSITIVE_LONG "amount"
        //     -> {"amount":N,"chemical":"mekanism:x"}  (ModdedJsonStyle.CHEMICAL)
        //   chemical OUTPUT ChemicalStack.MAP_CODEC -> {"amount":N,"id":"mekanism:x"}
        //     (ModdedJsonStyle.CHEMICAL_ID)
        //   item INPUT      ItemStackIngredient.CODEC (SizedIngredient.FLAT_CODEC)
        //     -> {"item"/"tag":…,"count":N}, the declared ITEM shape
        //   item OUTPUT     ItemStack -> {"count":N,"id":…}, the declared ID shape
        //   fluid INPUT     FluidStackIngredient.CODEC (SizedFluidIngredient.FLAT_CODEC)
        //     -> {"fluid"/"tag":…,"amount":N}  (ModdedJsonStyle.FLUID)
        //   fluid OUTPUT    FluidStack.CODEC -> {"amount":N,"id":…}  (ModdedJsonStyle.FLUID_ID)
        // So no declaration below needs a style override; only the field kinds differ.
        // The JEI slot order of every page below was read from its category's setRecipe
        // (mekanism/client/recipe_viewer/jei/machine/*), and the dump corroborates it:
        // its input[i]/output[i] indices are candidate indices, so e.g. washing's
        // input[0]=water,input[1]=flowing_water are the two candidates of ONE fluid slot.
        // The derived CATALYST/RENDER_ONLY slots those categories add (a chemical tank,
        // the ore's displayed item) carry no recipe field and are not modelled.

        // mekanism:activating -> {"input","output"}; ChemicalToChemicalRecipeCategory
        // adds INPUT(input) then OUTPUT(output).
        //   {"type":"mekanism:activating","input":{"amount":10,"chemical":"mekanism:nuclear_waste"},
        //    "output":{"amount":1,"id":"mekanism:polonium"}}
        // JEI: input[0]=chemical:10 nuclear_waste, output[0]=chemical:1 polonium.
        adapters.add(new ModdedRecipeDeclaration("mekanism:activating",
                Collections.singletonList("input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("input", "output"));

        // mekanism:centrifuging -> same fields and same category class as activating.
        //   {"type":"mekanism:centrifuging","input":{"amount":1,"chemical":"mekanism:uranium_hexafluoride"},
        //    "output":{"amount":1,"id":"mekanism:fissile_fuel"}}
        adapters.add(new ModdedRecipeDeclaration("mekanism:centrifuging",
                Collections.singletonList("input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("input", "output"));

        // mekanism:chemical_infusing -> {"left_input","right_input","output"};
        // ChemicalChemicalToChemicalRecipeCategory adds INPUT(left_input),
        // INPUT(right_input), OUTPUT(output), all chemical.
        //   {"type":"mekanism:chemical_infusing","left_input":{"amount":1,"chemical":"mekanism:hydrofluoric_acid"},
        //    "right_input":{"amount":1,"chemical":"mekanism:uranium_oxide"},
        //    "output":{"amount":2,"id":"mekanism:uranium_hexafluoride"}}
        // JEI: input[0]=chemical:1 hydrofluoric_acid, input[1]=chemical:1 uranium_oxide,
        // output[0]=chemical:2 uranium_hexafluoride.
        adapters.add(new ModdedRecipeDeclaration("mekanism:chemical_infusing",
                Arrays.asList("left_input", "right_input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("left_input", "right_input", "output"));

        // mekanism:pigment_mixing -> PigmentMixerRecipeCategory extends the same category
        // without overriding setRecipe, so the slot layout is identical to infusing.
        //   {"type":"mekanism:pigment_mixing","left_input":{"amount":1,"chemical":"mekanism:light_blue"},
        //    "right_input":{"amount":1,"chemical":"mekanism:red"},"output":{"amount":2,"id":"mekanism:magenta"}}
        adapters.add(new ModdedRecipeDeclaration("mekanism:pigment_mixing",
                Arrays.asList("left_input", "right_input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("left_input", "right_input", "output"));

        // mekanism:washing -> {"fluid_input","chemical_input","output"};
        // FluidChemicalToChemicalRecipeCategory adds INPUT(fluid_input) as a fluid,
        // INPUT(chemical_input) as a chemical, OUTPUT(output) as a chemical.
        //   {"type":"mekanism:washing","chemical_input":{"amount":1,"chemical":"mekanism:dirty_lead"},
        //    "fluid_input":{"amount":5,"tag":"minecraft:water"},"output":{"amount":1,"id":"mekanism:clean_lead"}}
        // JEI: input[0]=fluid:5 water, input[1]=fluid:5 flowing_water (slot 0's two
        // candidates), input[2]=chemical:1 dirty_lead (slot 1), output[0]=chemical:1 clean_lead.
        // The fluid comes first, which is the field order below.
        adapters.add(new ModdedRecipeDeclaration("mekanism:washing",
                Arrays.asList("fluid_input", "chemical_input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withFluidFields("fluid_input")
                .withChemicalFields("chemical_input", "output"));

        // mekanism:dissolution -> {"item_input","chemical_input","output"};
        // ChemicalDissolutionRecipeCategory adds INPUT(item_input) as an item,
        // INPUT(chemical_input) as a chemical, OUTPUT(output) as a chemical.
        //   {"type":"mekanism:dissolution","chemical_input":{"amount":1,"chemical":"mekanism:sulfuric_acid"},
        //    "item_input":{"count":1,"tag":"c:ores/osmium"},"output":{"amount":1000,"id":"mekanism:dirty_osmium"},
        //    "per_tick_usage":true}
        // JEI: input[0..1]=the two c:ores/osmium candidates (one item slot),
        // input[2]=chemical:100 sulfuric_acid, output[0]=chemical:1000 dirty_osmium.
        // "per_tick_usage" is not declared and survives verbatim; it only scales the
        // amount the page displays, not the recipe's own field.
        adapters.add(new ModdedRecipeDeclaration("mekanism:dissolution",
                Arrays.asList("item_input", "chemical_input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("chemical_input", "output"));

        // mekanism:oxidizing -> {"input","output"}; ItemStackToChemicalRecipeCategory
        // adds INPUT(input) as an item and OUTPUT(output) as a chemical.
        //   {"type":"mekanism:oxidizing","input":{"count":1,"tag":"c:dusts/lithium"},
        //    "output":{"amount":100,"id":"mekanism:lithium"}}
        // JEI: input[0]=item_stack:mekanism:dust_lithium, output[0]=chemical:100 lithium.
        adapters.add(new ModdedRecipeDeclaration("mekanism:oxidizing",
                Collections.singletonList("input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("output"));

        // mekanism:chemical_conversion -> the same category and fields as oxidizing.
        //   {"type":"mekanism:chemical_conversion","input":{"count":1,"item":"minecraft:flint"},
        //    "output":{"amount":10,"id":"mekanism:oxygen"}}
        adapters.add(new ModdedRecipeDeclaration("mekanism:chemical_conversion",
                Collections.singletonList("input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("output"));

        // mekanism:pigment_extracting -> PigmentExtractingRecipeCategory extends the same
        // category without overriding setRecipe: INPUT(input) item, OUTPUT(output) chemical.
        //   {"type":"mekanism:pigment_extracting","input":{"count":1,"item":"minecraft:brown_banner"},
        //    "output":{"amount":64,"id":"mekanism:brown"}}
        adapters.add(new ModdedRecipeDeclaration("mekanism:pigment_extracting",
                Collections.singletonList("input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("output"));

        // mekanism:crystallizing -> {"input","output"}; ChemicalCrystallizerRecipeCategory
        // adds OUTPUT(output) as an item FIRST and then INPUT(input) as a chemical. The
        // role, not the draw order, decides which declared field a slot is, so the
        // declaration is still input/output. The category also adds a conditional
        // RENDER_ONLY slot with the ore's displayed item, which carries no recipe field.
        //   {"type":"mekanism:crystallizing","input":{"amount":200,"chemical":"mekanism:clean_iron"},
        //    "output":{"count":1,"id":"mekanism:crystal_iron"}}
        adapters.add(new ModdedRecipeDeclaration("mekanism:crystallizing",
                Collections.singletonList("input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("input"));

        // mekanism:evaporating -> {"input","output"}, both fluids;
        // FluidToFluidRecipeCategory adds INPUT(input) then OUTPUT(output).
        //   {"type":"mekanism:evaporating","input":{"amount":10,"tag":"minecraft:water"},
        //    "output":{"amount":1,"id":"mekanism:brine"}}
        // JEI: input[0..1]=water and flowing_water (one slot), output[0]=fluid:1 brine.
        adapters.add(new ModdedRecipeDeclaration("mekanism:evaporating",
                Collections.singletonList("input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withFluidFields("input", "output"));

        // mekanism:separating -> 1 fluid input / 2 chemical outputs;
        // ElectrolysisRecipeCategory adds INPUT(input), OUTPUT(left_chemical_output),
        // OUTPUT(right_chemical_output). "energy_multiplier" is optional and survives.
        //   {"type":"mekanism:separating","input":{"amount":2,"tag":"minecraft:water"},
        //    "left_chemical_output":{"amount":2,"id":"mekanism:hydrogen"},
        //    "right_chemical_output":{"amount":1,"id":"mekanism:oxygen"}}
        // JEI: input[0..1]=one water slot, output[0]=hydrogen, output[1]=oxygen.
        adapters.add(new ModdedRecipeDeclaration("mekanism:separating",
                Collections.singletonList("input"),
                Arrays.asList("left_chemical_output", "right_chemical_output"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withFluidFields("input")
                .withChemicalFields("left_chemical_output", "right_chemical_output"));

        // mekanism:compressing / injecting / metallurgic_infusing / nucleosynthesizing /
        // purifying all use ItemStackChemicalToItemStackRecipeCategory: an item input, a
        // chemical input, an item output, and a derived CATALYST item slot (a chemical
        // tank plus the chemical's solid representations) which carries no recipe field.
        // The category adds INPUT(item_input), INPUT(chemical_input), OUTPUT(output),
        // CATALYST(derived), in that order.
        //   {"type":"mekanism:compressing","chemical_input":{"amount":1,"chemical":"mekanism:osmium"},
        //    "item_input":{"count":1,"tag":"c:dusts/glowstone"},
        //    "output":{"count":1,"id":"mekanism:ingot_refined_glowstone"},"per_tick_usage":true}
        //   {"type":"mekanism:injecting","chemical_input":{"amount":1,"tag":"mekanism:water_vapor"},
        //    "item_input":{"count":1,"item":"minecraft:dirt"},"output":{"count":1,"id":"minecraft:mud"},
        //    "per_tick_usage":true}
        //   {"type":"mekanism:metallurgic_infusing","chemical_input":{"amount":20,"tag":"mekanism:redstone"},
        //    "item_input":{"count":1,"tag":"c:ingots/osmium"},
        //    "output":{"count":1,"id":"mekanism:basic_control_circuit"},"per_tick_usage":false}
        //   {"type":"mekanism:nucleosynthesizing","chemical_input":{"amount":2,"chemical":"mekanism:antimatter"},
        //    "duration":500,"item_input":{"count":1,"tag":"minecraft:small_flowers"},
        //    "output":{"count":1,"id":"minecraft:chorus_flower"},"per_tick_usage":false}
        //   {"type":"mekanism:purifying","chemical_input":{"amount":1,"chemical":"mekanism:oxygen"},
        //    "item_input":{"count":1,"tag":"c:ores/copper"},"output":{"count":3,"id":"mekanism:clump_copper"},
        //    "per_tick_usage":true}
        // JEI (compressing sample): input[0]=item_stack:minecraft:glowstone_dust,
        // input[1]=chemical:200 osmium, output[0]=mekanism:ingot_refined_glowstone,
        // catalyst[0..2]=a chemical tank and two osmium items. 34 of the 91 injecting
        // recipes write their chemical input as a tag and 57 as a concrete chemical;
        // both are the same ChemicalStackIngredient and the editor narrows either to the
        // chosen chemical, exactly like the declared item pages narrow a tag to an item.
        adapters.add(new ModdedRecipeDeclaration("mekanism:compressing",
                Arrays.asList("item_input", "chemical_input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("chemical_input"));
        adapters.add(new ModdedRecipeDeclaration("mekanism:injecting",
                Arrays.asList("item_input", "chemical_input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("chemical_input"));
        adapters.add(new ModdedRecipeDeclaration("mekanism:metallurgic_infusing",
                Arrays.asList("item_input", "chemical_input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("chemical_input"));
        adapters.add(new ModdedRecipeDeclaration("mekanism:nucleosynthesizing",
                Arrays.asList("item_input", "chemical_input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("chemical_input"));
        adapters.add(new ModdedRecipeDeclaration("mekanism:purifying",
                Arrays.asList("item_input", "chemical_input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("chemical_input"));

        // --- mekanism:rotary: one serializer, two pages, opposite directions ---------
        //
        // mekanism:condensentrating and mekanism:decondensentrating are two JEI pages of
        // one Minecraft recipe type and one serializer. MekanismJEI.registerCategories
        // builds "new RotaryCondensentratorRecipeCategory(guiHelper, true)" under the
        // recipe-type uid mekanism:condensentrating and the (guiHelper, false) one under
        // mekanism:decondensentrating, and registerRecipes feeds both lists from the
        // single MekanismRecipeType.ROTARY, so all 17 shipped recipes are shown on both
        // pages (the dump reports 17 for each) and the direction is the category's own
        // boolean:
        //   condensentrating  (true):  if (hasChemicalToFluid())
        //                              INPUT  getChemicalInput()            -> chemical_input
        //                              OUTPUT getFluidOutputDefinition()    -> fluid_output
        //   decondensentrating(false): if (hasFluidToChemical())
        //                              INPUT  getFluidInput()               -> fluid_input
        //                              OUTPUT getChemicalOutputDefinition() -> chemical_output
        // Both directions live in the same JSON
        // (data/mekanism/recipe/rotary/uranium_oxide.json):
        //   {"type":"mekanism:rotary","chemical_input":{"amount":1,"chemical":"mekanism:uranium_oxide"},
        //    "chemical_output":{"amount":1,"id":"mekanism:uranium_oxide"},
        //    "fluid_input":{"amount":1,"tag":"c:uranium_oxide"},
        //    "fluid_output":{"amount":1,"id":"mekanism:uranium_oxide"}}
        // and all 17 shipped files carry all four keys in those two shapes (chemical input
        // {"amount","chemical"} = the declared CHEMICAL style, chemical output
        // {"amount","id"} = CHEMICAL_ID, fluid input {"amount","tag"} = FLUID, fluid output
        // {"amount","id"} = FLUID_ID), so no style override is needed - only the field list
        // and the kinds differ per page.
        // The slot kinds differing while the serializer does not is exactly what a
        // page-scoped declaration is for (withPageUid): each page names its own fields, and
        // mekanism:rotary itself stays undeclared, so neither page can be modelled - or
        // written - with the other direction's fields.
        adapters.add(new ModdedRecipeDeclaration("mekanism:rotary",
                Collections.singletonList("chemical_input"), "fluid_output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("chemical_input")
                .withFluidFields("fluid_output")
                .withPageUid("mekanism:condensentrating"));
        adapters.add(new ModdedRecipeDeclaration("mekanism:rotary",
                Collections.singletonList("fluid_input"), "chemical_output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withFluidFields("fluid_input")
                .withChemicalFields("chemical_output")
                .withPageUid("mekanism:decondensentrating"));

        // JEI page mekanism:reaction ("加压反应室"), 3 inputs / 0..2 outputs.
        // PressurizedReactionRecipeCategory.setRecipe adds its three INPUT slots
        // unconditionally and in this order:
        //   INPUT  getInputSolid().getRepresentations()    -> item_input     (ItemStackIngredient)
        //   INPUT  getInputFluid().getRepresentations()    -> fluid_input    (FluidStackIngredient)
        //   INPUT  getInputChemical().getRepresentations() -> chemical_input (ChemicalStackIngredient)
        // then one OUTPUT slot per non-empty entry of getOutputDefinition(), which is
        // Collections.singletonList(new Output(outputItem, outputChemical)):
        //   if (itemList.stream().allMatch(ITEM_EMPTY)      == false) OUTPUT -> item_output
        //   if (chemicalList.stream().allMatch(CHEMICAL_EMPTY) == false) OUTPUT -> chemical_output
        // There is no fluid output: BasicPressurizedReactionRecipe's fields are
        // inputSolid, inputFluid, inputChemical, energyRequired, duration, outputItem and
        // outputChemical, and its codec declares exactly
        //   item_input (required), fluid_input (required), chemical_input (required),
        //   energy_required (optional, default 0), duration (required),
        //   item_output (optional, default empty), chemical_output (optional, default empty)
        // with the validation "No output specified, must have at least an Item or Chemical
        // output". So the output ordinals are data-dependent: with both fields the page
        // draws item_output then chemical_output, with only item_output it draws one slot
        // (item_output), with only chemical_output one slot (chemical_output). Measured
        // over the 12 shipped files: 7 carry both, 4 omit item_output and 1 omits
        // chemical_output.
        // "item_output" then "chemical_output" is therefore an ordered segment list,
        // concatenated against the recipe JSON; it is also the first one whose segments
        // hold different kinds (an item then a chemical), which is why the client accepts
        // either kind for an output ordinal and the server checks the patch kind against
        // the kind of the field the concatenation really resolved to.
        //   {"type":"mekanism:reaction","chemical_input":{"amount":10,"chemical":"mekanism:oxygen"},
        //    "duration":60,"energy_required":1000,"fluid_input":{"amount":50,"tag":"c:ethene"},
        //    "item_input":{"count":1,"item":"mekanism:substrate"},
        //    "item_output":{"count":1,"id":"mekanism:hdpe_pellet"}}
        //   {"type":"mekanism:reaction","chemical_input":{"amount":25,"chemical":"mekanism:oxygen"},
        //    "chemical_output":{"amount":25,"id":"mekanism:hydrogen"},"duration":37,
        //    "fluid_input":{"amount":25,"tag":"minecraft:water"},
        //    "item_input":{"count":8,"tag":"c:dusts/wood"}}   -- no item_output at all
        // energy_required and duration are never declared and survive verbatim.
        adapters.add(new ModdedRecipeDeclaration("mekanism:reaction",
                Arrays.asList("item_input", "fluid_input", "chemical_input"),
                Arrays.asList("item_output", "chemical_output"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withFluidFields("fluid_input")
                .withChemicalFields("chemical_input", "chemical_output")
                .withConcatenatedOutputs());

        // JEI page mekanism:painting ("喷涂机"), 1 item input / 1 chemical input / 1 output.
        // PaintingRecipeCategory.setRecipe adds INPUT(getItemInput()) as an item,
        // INPUT(getChemicalInput()) as a chemical (guarded by perTickUsage(), which only
        // switches the gauge form) and OUTPUT(getOutputDefinition()) as an item - one slot
        // each, always, so the declaration is the plain pair. The dump's 17 input candidates
        // are the 16 members of the item slot's tag plus the one chemical candidate.
        // data/mekanism/recipe/painting/banner/black.json:
        //   {"type":"mekanism:painting","chemical_input":{"amount":256,"chemical":"mekanism:black"},
        //    "item_input":{"type":"neoforge:difference","base":{"tag":"mekanism:colorable/banners"},
        //                  "count":1,"subtracted":{"item":"minecraft:black_banner"}},
        //    "output":{"count":1,"id":"minecraft:black_banner"},"per_tick_usage":false}
        // 160 of the 176 shipped files write item_input as that nested
        // neoforge:difference, 16 as a plain {"count":…,"item":…}. The difference is not a
        // set the editor could name: the page offers exactly base-minus-subtracted, so the
        // slot is declared with withPreservedIngredientBase, and an edit rewrites only the
        // node's "base" with the chosen item while "type" and "subtracted" survive verbatim
        // (see RecipeEditsApplier.writePreservedIngredientBase). The item the user picks is
        // one of the difference's own members, so the written recipe still contains its own
        // base minus the subtracted item and the page rebuilds the same single-item input
        // rather than dropping the whole difference. The other 16 files - a plain
        // {"count":…,"item":…}, and a basePredicate such as Immersive Engineering's - have no
        // base sub-node, so they are written as the ordinary ITEM field, the same narrowing
        // every other declared item field performs on a tag.
        // The difference's "count" is that SizedIngredient's amount and is deliberately left
        // untouched: all 176 shipped files write 1 there while the page draws a single item,
        // so writing the patch's count would be observationally neutral today and would
        // silently narrow a datapack recipe that asked for more than one.
        // The chemical input is a plain chemical stack and the output a plain ItemStack, so
        // both need no special shape. per_tick_usage survives verbatim.
        adapters.add(new ModdedRecipeDeclaration("mekanism:painting",
                Arrays.asList("item_input", "chemical_input"), "output",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM, ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withChemicalFields("chemical_input")
                .withPreservedIngredientBase("item_input"));

        // JEI page mekanism:energy_conversion ("Energy Conversion", 2 recipes), 1 input /
        // no output slot.
        // data/mekanism/recipe/energy_conversion/redstone.json:
        //   {"type":"mekanism:energy_conversion","input":{"count":1,"tag":"c:dusts/redstone"},
        //    "output":10000}
        // data/mekanism/recipe/energy_conversion/redstone_block.json is the same shape with
        // "output":90000.
        // The page draws exactly one slot - the input ingredient - and shows the energy as
        // text beside it, so there is no output slot to name: the "output" value is a bare
        // energy amount in FE, not an ingredient (and not a fluid or chemical either), so no
        // declared kind can carry it. The page is therefore declared through
        // SingleItemInputSequence (one item input, no output); "output" stays undeclared and
        // survives verbatim, the same way the fertilizer's "growthModifier" does.
        adapters.add(new ModdedRecipeDeclaration("mekanism:energy_conversion",
                SingleItemInputSequence.INPUT,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.FLUID, ModdedJsonStyle.FLUID_ID));

        // Deliberately not declared:
        // - mekanism:smelting -> the page has no recipe JSON at all; its 284 recipes are
        //   synthesised at runtime from minecraft:smelting recipes, so the declared write
        //   path has no original JSON to patch (see the note higher up).
        // - mekanism:crystallizing's RENDER_ONLY slot, and every derived CATALYST slot,
        //   are not recipe fields, so they are neither declared nor modellable.
        ALL = Collections.unmodifiableList(adapters);
    }

    private MekanismRecipeDeclarations() {
    }
}
