package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Declared Immersive Engineering recipe pages.
 *
 * <p>Evidence: every JSON quoted below was read out of
 * {@code immersiveengineering__ImmersiveEngineering-1.21.1-12.4.2-194.jar}
 * ({@code data/immersiveengineering/recipe/&lt;type&gt;/*.json}) and the JEI slot order
 * out of the runtime dump {@code run/jei-category-dump.txt}. Ingredient fields are
 * written back as {@code {"item":…,"count":…}} and results as
 * {@code {"id":…,"count":…}}, the two shapes Immersive Engineering itself uses.
 *
 * <p>Shapes measured over every shipped sample of the declared types:
 * <ul>
 *   <li>{@code crusher} (106 recipes): {@code input} is {@code {"item":…}} (17) or
 *       {@code {"tag":…}} (89); {@code result} is {@code {"count":…,"id":…}} (15),
 *       {@code {"id":…}} (9), {@code {"tag":…}} (42) or
 *       {@code {"basePredicate":…,"count":…}} (40); 32 of them carry a non-empty
 *       {@code secondaries} array.</li>
 *   <li>{@code metal_press} (96): {@code input} is {@code {"basePredicate":…,"count":…}}
 *       (24), {@code {"tag":…}} (71) or {@code {"item":…}} (1); {@code result} is
 *       {@code {"tag":…}} (46), {@code {"basePredicate":…,"count":…}} (46),
 *       {@code {"count":…,"id":…}} (3) or {@code {"id":…}} (1).</li>
 *   <li>{@code alloy} (8): {@code input0} is {@code {"tag":…}} (3) or
 *       {@code {"basePredicate":…,"count":…}} (5), {@code input1} is {@code {"tag":…}} (7)
 *       or {@code {"item":…}} (1); {@code result} is {@code {"basePredicate":…,"count":…}}
 *       (7) or {@code {"count":…,"id":…}} (1).</li>
 *   <li>{@code arc_furnace} (78): {@code input} is {@code {"tag":…}} (72),
 *       {@code {"basePredicate":…,"count":…}} (5) or {@code {"item":…}} (1),
 *       {@code additives} holds 0 entries (69) or 1 (9), {@code results} always holds
 *       exactly one entry, {@code secondaries} holds 1 entry (30) or is absent (48), and
 *       {@code slag} is present (17) or absent (61); no recipe carries both.</li>
 *   <li>{@code blueprint} (61): {@code inputs} holds 2 (43), 4 (8), 3 (9) or 6 (1)
 *       entries; {@code result} is {@code {"id":…}} (47), {@code {"count":…,"id":…}} (12)
 *       or {@code {"components":…,"count":…,"id":…}} (2).</li>
 *   <li>{@code blast_furnace} (2, both shipped in the jar): {@code input} is
 *       {@code {"tag":…}}; {@code result} and {@code slag} are
 *       {@code {"tag":…}} or {@code {"basePredicate":…,"count":…}}; {@code time} is present.
 *       Its JEI category draws a fixed INPUT + two OUTPUT slants, so both outputs are named.</li>
 *   <li>{@code sawmill} (72): {@code input} is {@code {"item":…}} (62) or an array of
 *       two items (10); {@code result} is {@code {"id":…}} or {@code {"count":…,"id":…}};
 *       {@code stripped} is present in 21 recipes only; {@code secondaryOutputs} holds 0
 *       (29) or 1 (43) entries and {@code strippingSecondaries} holds 0 (22), 1 (49) or
 *       2 (1) entries.</li>
 * </ul>
 *
 * <p>How the field lists cover those shapes: the client builds one editor slot per real
 * INPUT slot of the displayed layout and one per real OUTPUT slot, and the k-th real JEI
 * slot of a role maps to the k-th declared field of that role (the client maps slot
 * ordinals through {@code jei.input.<ordinal>}), so how many candidate stacks a slot
 * holds does not matter. For the alloy sample the ingredient dump lists 26 candidates:
 * {@code input[0]=minecraft:glass … input[23]=ae2:quartz_vibrant_glass} (the 24 members of
 * {@code c:glass_blocks}) followed by
 * {@code input[24]=mekanism:dust_iron, input[25]=immersiveengineering:dust_iron} (the 2
 * members of {@code c:dusts/iron}), yet the layout draws those two tag ingredients as two
 * INPUT slots, which is what the two declared fields name.
 *
 * <p>A declaration whose slot count varies per recipe uses a {@code %d} field, which
 * repeats once per remaining slot: {@code ["input", "additives.%d"]} names the
 * {@code input} field first and then {@code additives.0}, {@code additives.1}, so the
 * 69 zero-additive and the 9 one-additive arc furnace recipes all match.
 *
 * <p>Known limitation of the declared styles: Immersive Engineering decodes ingredient
 * fields as {@code IngredientWithSize}, whose codec is
 * {@code either({basePredicate, count}, <plain Ingredient>)}. A field that carried a size
 * (for example {@code {"basePredicate":{"tag":"c:plates/steel"},"count":3}}) is therefore
 * rewritten as {@code {"item":…,"count":3}}, which decodes through the plain-Ingredient
 * branch, so that input drops back to a size of 1. The declaration API has no
 * {@code basePredicate} shape, and every declared field does occur as a plain
 * {@code {"item":…}} in Immersive Engineering's own recipes, so this is accepted rather
 * than worked around.
 *
 * <p>Every Immersive Engineering page whose recipes have a JSON file to patch is
 * declared, including the ones that needed a shape this file could not state:
 * {@code cloche}, {@code bottling_machine}, {@code fertilizer} and
 * {@code blast_furnace_fuel} are declared through a recipe-derived slot sequence
 * ({@link ImmersiveEngineeringSlotSequences} quotes the JSON, the category's slot
 * order and why a field list cannot name them - a variable-length item list whose
 * key changes per file, an output array whose entries are chance-wrapped, or no
 * output slot at all). The pages that stay read-only are named with
 * {@code withReadOnlyPages} on the declaration of their serializer, because their
 * recipes exist only in memory: {@code arc_recycling}, {@code mixer_potions},
 * {@code bottling_buckets} and {@code bottling_potions}.
 * {@code refinery}, {@code mixer} and {@code coke_oven} are declared field lists;
 * their JSON and slot order are quoted next to their declarations, as the fluid
 * pages {@code fermenter} and {@code squeezer} are.
 *
 * <p>{@code sawmill} and {@code arc_furnace} declare their output as an <em>ordered
 * segment list</em> ({@code withConcatenatedOutputs}) because the recipe decides which
 * JSON field lands at which JEI OUTPUT ordinal - a positional list can only name one field
 * per ordinal, and guessing would write a secondary into {@code slag}. A segment list is
 * concatenated against the recipe JSON, so the k-th OUTPUT slot is the k-th element of the
 * concatenation, and a slot ordinal the concatenation does not reach refuses the whole
 * patch ({@code RecipeFieldMapping.expandSegments}). {@code SawmillRecipeCategory.setRecipe}
 * adds INPUT({@code input}), OUTPUT({@code stripped}) <em>only when {@code stripped} is
 * non-empty</em>, OUTPUT({@code result}) always, one OUTPUT per entry of
 * {@code strippingSecondaries}, then one per entry of {@code secondaryOutputs}; of the 72
 * shipped recipes 21 carry {@code stripped}, so OUTPUT ordinal 0 is {@code stripped} on
 * those 21 and {@code result} on the other 51, and ordinal 1 is {@code result} on the 21,
 * {@code strippingSecondaries.0} on 29 and {@code secondaryOutputs.0} on 22.
 * {@code ArcFurnaceRecipeCategory} likewise draws one OUTPUT per {@code results} entry,
 * then one per {@code secondaries} entry, then one for a non-empty {@code slag}, so its
 * second OUTPUT slot is {@code secondaries.0.output} on 30 of the 78 shipped recipes and
 * {@code slag} on 17. Each page's declaration entry spells out its segments and the
 * measured shapes.
 */
public final class ImmersiveEngineeringRecipeDeclarations {
    public static final List<ModdedRecipeAdapter> ALL;

    static {
        List<ModdedRecipeAdapter> adapters = new ArrayList<ModdedRecipeAdapter>();

        // JEI page immersiveengineering:alloy ("合金窑"), 2 inputs / 1 output.
        // data/immersiveengineering/recipe/alloysmelter/insulating_glass.json:
        //   {"type":"immersiveengineering:alloy",
        //    "input0":{"basePredicate":{"tag":"c:glass_blocks"},"count":2},
        //    "input1":{"tag":"c:dusts/iron"},
        //    "result":{"count":2,"id":"immersiveengineering:insulating_glass"}}
        // data/immersiveengineering/recipe/alloysmelter/electrum.json:
        //   {"input0":{"tag":"c:ingots/gold"},"input1":{"tag":"c:ingots/silver"},
        //    "result":{"basePredicate":{"tag":"c:ingots/electrum"},"count":2}}
        // data/immersiveengineering/recipe/alloysmelter/manyullyn.json:
        //   "input1":{"item":"minecraft:netherite_scrap"} - the plain item form input1 accepts.
        // JEI: the layout draws input0 as one slot holding the whole c:glass_blocks tag and
        // input1 as one slot holding the whole c:dusts/iron tag, output[0]=
        // immersiveengineering:insulating_glass, matching input0 then input1. The ingredient
        // dump lists input[0..23] as the c:glass_blocks members and input[24..25] as the
        // c:dusts/iron members - those are candidates inside the two slots, not slots. Two
        // slots, so two fixed fields.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:alloy",
                Arrays.asList("input0", "input1"), "result",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page immersiveengineering:arc_furnace ("电弧炉"), 1 or 2 inputs / 1..2 outputs.
        // data/immersiveengineering/recipe/arcfurnace/alloy_brass.json:
        //   {"type":"immersiveengineering:arc_furnace","energy":51200,
        //    "additives":[{"tag":"c:ingots/zinc"}],"input":{"tag":"c:ingots/copper"},
        //    "results":[{"basePredicate":{"tag":"c:ingots/brass"},"count":2}],"time":100}
        // data/immersiveengineering/recipe/arcfurnace/netherite_scrap.json:
        //   {"input":{"item":"minecraft:ancient_debris"},"additives":[],
        //    "results":[{"count":2,"id":"minecraft:netherite_scrap"}],
        //    "slag":{"tag":"c:slag"}}
        // JEI: ArcFurnaceRecipeCategory draws one INPUT slot for "input" and one more per
        // entry of "additives", so the layout shows 1 slot when there is no additive
        // (69 of the 78 samples) and 2 slots with one (9). input[0]=minecraft:copper_ingot
        // (input), input[1]=create:zinc_ingot (additives[0]), output[0]=create:brass_ingot
        // (results[0]) - the recipe order. "additives.%d" therefore covers every additive
        // the layout can draw, and the older fixed "additives.0" list, which only matched
        // the one-additive shape, is gone; energy, time and every other key survive.
        // ArcFurnaceRecipeCategory.setRecipe adds OUTPUT(res) for every "results" entry,
        // then OUTPUT(secondaries[i].output) for every valid "secondaries" entry, then
        // OUTPUT(slag) when "slag" is non-empty - three shapes sharing one ordinal
        // sequence, which a positional list cannot name (its second OUTPUT slot is
        // "secondaries.0.output" on some recipes and "slag" on others). The declared
        // output is therefore the ordered segment list
        //   ["results.%d", "secondaries.%d.output", "slag"]
        // concatenated against the recipe JSON, so output.0 is results[0], the following
        // ordinals are secondaries[0].output, secondaries[1].output, ... and the last one
        // is "slag".
        // Measured over the 78 shipped files
        // (data/immersiveengineering/recipe/arcfurnace/*.json): every file carries exactly
        // one "results" entry; 30 carry one "secondaries" entry and no "slag"; 17 carry a
        // "slag" and no "secondaries"; the other 31 carry neither. So 31 recipes draw one
        // OUTPUT slot and 47 draw two, and no recipe ever carries both. Which segment lands
        // at a given ordinal is therefore only decidable from the recipe JSON - the client
        // checks the count bound, the server resolves the list and refuses an ordinal the
        // concatenation does not reach.
        // The arc_recycling page shares this serializer and draws a single INPUT slot for a
        // recipe with no additives at all, so its shape now matches this declaration too.
        // Its recipes are generated from arc_recycling_list.json at reload time (ids like
        // immersiveengineering:arc_recycling_list000), so there is no recipe JSON for the
        // server to patch; the page is named as read-only below and stays exactly as
        // read-only as the old slot-count check made it.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:arc_furnace",
                Arrays.asList("input", "additives.%d"),
                Arrays.asList("results.%d", "secondaries.%d.output", "slag"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withConcatenatedOutputs()
                .withReadOnlyPages("immersiveengineering:arc_recycling"));

        // JEI page immersiveengineering:blast_furnace ("高炉"), 1 input / 2 outputs.
        // data/immersiveengineering/recipe/blastfurnace/steel.json:
        //   {"type":"immersiveengineering:blast_furnace","input":{"tag":"c:ingots/iron"},
        //    "result":{"tag":"c:ingots/steel"},"slag":{"tag":"c:slag"},"time":1200}
        // data/immersiveengineering/recipe/blastfurnace/steel_block.json:
        //   "input":{"tag":"c:storage_blocks/iron"}, "result":{"tag":"c:storage_blocks/steel"},
        //   "slag":{"basePredicate":{"tag":"c:slag"},"count":9}, "time":10800.
        // JEI: input[0]=minecraft:iron_block, output[0]=immersiveengineering:storage_steel,
        // output[1]=immersiveengineering:slag - the "result" then the "slag".
        // BlastFurnaceRecipeCategory.setRecipe adds exactly three slots and no others:
        // INPUT(input), OUTPUT(result), OUTPUT(slag). Both are TagOutput fields whose
        // TagOutput.get() returns a representative stack (a tag output resolves to its first
        // member), so the page is always 1 input / 2 outputs and neither output slot can be
        // empty: there is no shape of this page whose second slot has to refuse an edit.
        // The recipe class field is named "output" but the JSON key is "result" - taken from
        // the shipped JSON, which Immersive Engineering's own datagen writes and the game
        // reloads. "time" survives verbatim; as with crusher's result, an edited "result" or
        // "slag" is written as {"id":…,"count":…}, which decodes through TagOutput's plain
        // ingredient branch and so drops a tag in favour of one chosen item.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:blast_furnace",
                Collections.singletonList("input"), Arrays.asList("result", "slag"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page immersiveengineering:blueprint ("工程师装配台"), 2..6 inputs / 1 output.
        // data/immersiveengineering/recipe/blueprint/mold_wire.json:
        //   {"type":"immersiveengineering:blueprint","category":"molds",
        //    "inputs":[{"basePredicate":{"tag":"c:plates/steel"},"count":3},
        //              {"item":"immersiveengineering:wirecutter"}],
        //    "result":{"id":"immersiveengineering:mold_wire"}}
        // data/immersiveengineering/recipe/blueprint/banner_hammer.json:
        //   {"inputs":[{"tag":"c:paper"},{"item":"immersiveengineering:hammer"}],
        //    "result":{"id":"immersiveengineering:bannerpattern_hammer"}}
        // data/immersiveengineering/recipe/blueprint/robot_wolf.json: six inputs, one of them
        //   {"basePredicate":{"tag":"c:ingots/uranium"},"count":3}.
        // JEI: input[0]=immersiveengineering:thermoelectric_generator, input[1]=radiator,
        // input[2]=mekanism:ingot_uranium, input[3]=immersiveengineering:ingot_uranium,
        // input[4]=plate_steel, input[5]=component_electronic_adv, input[6]=component_steel,
        // output[0]=robot_wolf, i.e. the array order with the uranium tag expanded in place;
        // the blueprint item itself is a catalyst slot, not an input.
        // BlueprintRecipeCategory draws one INPUT slot per entry of "inputs", so the array
        // length 2/3/4/6 is the page's input count: "inputs.%d" covers all four, where the
        // old fixed ["inputs.0", "inputs.1"] only matched the 2-entry shape (43 of the 61
        // samples). "category" survives untouched.
        // Two of the four-input samples (bullet_flare_green / _yellow) write their result with
        // a "components" block carrying the flare colour:
        //   "result":{"components":{"immersiveengineering:flare":{…}},"count":4,
        //             "id":"immersiveengineering:bullet_flare"}
        // RecipeAdapterSupport.simpleStack rejects a stack with a component patch, so JEI's
        // output slot has no representative; the editor shows it empty and refuses to rewrite
        // it (the same treatment horsepowered:bottling is left undeclared for), because a
        // written result would be the whole {"id":…,"count":…} node and drop the colour. The
        // four inputs of those two recipes stay editable.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:blueprint",
                Collections.singletonList("inputs.%d"), "result",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page immersiveengineering:crusher ("粉碎机"), 1 input / 1..N outputs.
        // data/immersiveengineering/recipe/crusher/amethyst.json:
        //   {"type":"immersiveengineering:crusher","energy":3200,
        //    "input":{"item":"minecraft:amethyst_block"},
        //    "result":{"count":4,"id":"minecraft:amethyst_shard"}}
        // data/immersiveengineering/recipe/crusher/blue_dye.json:
        //   {"type":"immersiveengineering:crusher","energy":1600,
        //    "input":{"tag":"c:gems/lapis"},"result":{"count":2,"id":"minecraft:blue_dye"},
        //    "secondaries":[{"chance":0.1,"output":{"item":"minecraft:light_gray_dye"}}]}
        // JEI: input[0]=mekanism:block_raw_uranium, input[1]=immersiveengineering:raw_block_uranium,
        // output[0]=immersiveengineering:dust_uranium for crusher/raw_block_uranium.json. That
        // recipe has a single input slot ({"tag":"c:storage_blocks/raw_uranium"}), so the two
        // entries are the two members of one tag, not two slots: treat input as one field.
        // CrusherRecipeCategory adds output[0]=result, then one OUTPUT slot per valid entry
        // of "secondaries" (an entry whose output is empty or whose chance is <= 0 is
        // skipped), in order, so output.0 is "result" and output.n>0 is
        // "secondaries.(n-1).output". Every one of the 32 shipped samples that carry
        // secondaries has exactly one entry with a non-empty output and a positive chance,
        // so JEI's filtered index equals the array index there; "chance" and "conditions" of
        // a secondary entry survive verbatim. Those 32 recipes now build a model (with two
        // output slots) instead of being refused by the old single-output rule.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:crusher",
                Collections.singletonList("input"),
                Arrays.asList("result", "secondaries.%d.output"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page immersiveengineering:metal_press ("金属冲压机"), 1 input / 1 output.
        // data/immersiveengineering/recipe/metalpress/bullet_casing.json:
        //   {"type":"immersiveengineering:metal_press","energy":2400,
        //    "input":{"tag":"c:ingots/copper"},"mold":"immersiveengineering:mold_bullet_casing",
        //    "result":{"count":2,"id":"immersiveengineering:empty_casing"}}
        // data/immersiveengineering/recipe/metalpress/melon.json:
        //   {"input":{"item":"minecraft:melon"},"mold":"immersiveengineering:mold_unpacking",
        //    "result":{"count":9,"id":"minecraft:melon_slice"}}
        // JEI: input[0]=minecraft:copper_ingot, output[0]=immersiveengineering:empty_casing,
        // catalyst[0]=immersiveengineering:mold_bullet_casing - the mold is a catalyst slot, not
        // an input, so it is not declared and survives verbatim. energy likewise.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:metal_press",
                Collections.singletonList("input"), "result",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // JEI page immersiveengineering:sawmill ("锯木机"), 1 input / 1..4 outputs.
        // data/immersiveengineering/recipe/sawmill/acacia_log.json (with "stripped"):
        //   {"type":"immersiveengineering:sawmill","energy":1600,
        //    "input":{"item":"minecraft:acacia_log"},
        //    "result":{"count":6,"id":"minecraft:acacia_planks"},
        //    "stripped":{"id":"minecraft:stripped_acacia_log"},
        //    "strippingSecondaries":[{"tag":"c:dusts/wood"}],
        //    "secondaryOutputs":[{"tag":"c:dusts/wood"}]}
        // data/immersiveengineering/recipe/sawmill/acacia_slab.json (no "stripped"):
        //   {"type":"immersiveengineering:sawmill","energy":800,
        //    "input":{"item":"minecraft:acacia_planks"},
        //    "result":{"count":2,"id":"minecraft:acacia_slab"},
        //    "strippingSecondaries":[{"tag":"c:dusts/wood"}],"secondaryOutputs":[]}
        // SawmillRecipeCategory.setRecipe adds INPUT(input), then OUTPUT(stripped) only
        // when stripped is non-empty, then OUTPUT(output), then one OUTPUT per entry of
        // "strippingSecondaries", then one per entry of "secondaryOutputs". The declared
        // output is therefore the ordered segment list
        //   ["stripped", "result", "strippingSecondaries.%d", "secondaryOutputs.%d"]
        // concatenated against the recipe JSON: a recipe with "stripped" draws
        // stripped, result, strippingSecondaries[0], secondaryOutputs[0]; one without it
        // draws result, strippingSecondaries[0], ... so the k-th OUTPUT slot is the k-th
        // element of that concatenation.
        // Measured over the 72 shipped files: 21 carry "stripped" (and every one of those
        // also carries one strippingSecondaries and one secondaryOutputs entry, i.e. draws
        // 4 OUTPUT slots); the other 51 do not - 28 carry one strippingSecondaries entry,
        // 22 one secondaryOutputs entry (2 slots each) and 1 carries two
        // strippingSecondaries entries (3 slots). So OUTPUT ordinal 0 is "stripped" on 21
        // recipes and "result" on 51, and ordinal 1 is "result" on the 21 stripped ones,
        // "strippingSecondaries.0" on 29 and "secondaryOutputs.0" on 22 - the same ordinal
        // names different JSON fields depending on the recipe, which no positional list can
        // express and this ordered segment list does.
        // "input" is one field: 62 files carry a single {"item":…} and 10 an array of two
        // items, but Ingredient.getItems() renders either as the page's one INPUT slot.
        // Immersive Engineering decodes the output fields as TagOutput, so an edited one is
        // written as {"id":…,"count":…} and a tag or {"basePredicate":…} result narrows to
        // the chosen item, exactly like the other declared IE pages. "energy" survives.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:sawmill",
                Collections.singletonList("input"),
                Arrays.asList("stripped", "result", "strippingSecondaries.%d", "secondaryOutputs.%d"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withConcatenatedOutputs());

        // JEI page immersiveengineering:refinery ("精炼厂"), 2 fluid inputs / 1 fluid output.
        // data/immersiveengineering/recipe/refinery/resin.json:
        //   {"type":"immersiveengineering:refinery","energy":240,
        //    "input0":{"amount":12,"tag":"c:acetaldehyde"},"input1":{"amount":8,"tag":"c:creosote"},
        //    "result":{"amount":8,"id":"immersiveengineering:phenolic_resin"}}
        // data/immersiveengineering/recipe/refinery/acetaldehyde.json carries input0 only,
        // and input1 is an Optional<SizedFluidIngredient>, so that page draws 1 INPUT slot
        // where the other 3 draw 2. A declaration must describe a page shape exactly, so
        // the one-input recipes build no model; the other 3 are editable.
        // RefineryRecipeCategory.setRecipe adds INPUT(input0), INPUT(input1), a CATALYST
        // for "catalyst" (only when non-empty) and OUTPUT(result), all fluids:
        // input0 and input1 are "SizedFluidIngredient" decoded by
        // SizedFluidIngredient.FLAT_CODEC = FluidIngredient.MAP_CODEC_NONEMPTY + "amount",
        // which accepts {"fluid":id,"amount":n}, and result is a FluidStack decoded by
        // FluidStack.OPTIONAL_CODEC, which is {"amount":n,"id":id}. That is exactly the
        // default fluid input style (FLUID) and the default fluid output style (FLUID_ID),
        // so no style override is needed. "catalyst" and "energy" are not declared and
        // survive verbatim.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:refinery",
                Arrays.asList("input0", "input1"), "result",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withFieldKind("input0", IngredientKind.FLUID)
                .withFieldKind("input1", IngredientKind.FLUID)
                .withFieldKind("result", IngredientKind.FLUID));

        // JEI page immersiveengineering:mixer ("搅拌机"), 1 fluid + N item inputs / 1 fluid output.
        // data/immersiveengineering/recipe/mixer/concrete.json:
        //   {"type":"immersiveengineering:mixer","energy":3200,"fluid":{"amount":500,"tag":"minecraft:water"},
        //    "inputs":[{"basePredicate":{"tag":"c:sands"},"count":2},{"tag":"c:gravels"},{"tag":"c:clay"}],
        //    "result":{"amount":500,"id":"immersiveengineering:concrete"}}
        // data/immersiveengineering/recipe/mixer/redstone_acid.json: "inputs" holds one entry.
        // MixerRecipeCategory.setRecipe adds INPUT(fluid) first, then OUTPUT(result), then one
        // INPUT per entry of "inputs" (in that order), so with the roles separated the fluid is
        // input ordinal 0 and the items are ordinals 1..N: the declaration is
        // ["fluid", "inputs.%d"]. "fluid" is a SizedFluidIngredient and "result" a FluidStack,
        // so the default fluid styles apply. The dump's input[0]=water, input[1]=flowing_water
        // are the two candidates of that one fluid slot; input[2..6] are the candidates of the
        // three item slots (one per "inputs" entry), which is what the item dump shape shows.
        // mekanism:washing-style mixed kinds are declared through withFieldKind.
        //
        // immersiveengineering:mixer_potions is the same category class and the same slot
        // shape, but its 255 recipes are produced at runtime by
        // PotionRecipeGenerators.initPotionRecipes() from
        // data/immersiveengineering/recipe/mixer_potion_list.json (an
        // "immersiveengineering:generated_list" file); there is no per-recipe JSON for the
        // server to patch, so the page is named as read-only exactly like arc_recycling.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:mixer",
                Arrays.asList("fluid", "inputs.%d"), "result",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withFieldKind("fluid", IngredientKind.FLUID)
                .withFieldKind("result", IngredientKind.FLUID)
                .withReadOnlyPages("immersiveengineering:mixer_potions"));

        // JEI page immersiveengineering:coke_oven ("焦炉"), 1 input / 2 outputs.
        // data/immersiveengineering/recipe/cokeoven/charcoal.json:
        //   {"type":"immersiveengineering:coke_oven","creosote":250,
        //    "input":{"basePredicate":{"tag":"minecraft:logs_that_burn"},"count":8},
        //    "result":{"id":"minecraft:charcoal"},"time":3000}
        // data/immersiveengineering/recipe/cokeoven/coke.json:
        //   "input":{"basePredicate":{"item":"minecraft:coal"},"count":16},
        //   "result":{"tag":"c:coal_coke"},"creosote":500,"time":6000
        // CokeOvenRecipeCategory.setRecipe adds exactly three slots, in this order:
        //   INPUT(input)                    unconditional, IngredientWithSize.getMatchingStackList()
        //   OUTPUT(result)                  when output.get() is not empty, TagOutput.get()
        //   OUTPUT(creosote)                when creosoteOutput > 0, a
        //                                   new FluidStack(IEFluids.CREOSOTE.getStill(), creosoteOutput)
        // - so output ordinal 0 is "result" (an item) and ordinal 1 is "creosote" (a fluid),
        // which is the positional list below. The dump's 41 input candidates are the members
        // of the one minecraft:logs_that_burn tag, not 41 slots.
        // "creosote" is a BARE INTEGER (millibuckets), not a fluid object:
        // CokeOvenRecipeSerializer.CODECS decodes "result" as TagOutput.CODECS, "input" as
        // IngredientWithSize.CODECS, "time" with DualCodecs.INT.optionalFieldOf("time", 200)
        // and "creosote" with DualCodecs.INT.fieldOf("creosote") - a plain required int.
        // The field therefore holds the amount alone and its fluid is implied by the field
        // name, which is what ModdedJsonStyle.AMOUNT means; the id is fixed to
        // immersiveengineering:creosote, the still fluid IEFluids.make("creosote", …)
        // registers, so a dragged fluid of any other id is refused by both sides instead of
        // having its amount written into the creosote field. The recipe class keeps the
        // field as creosoteOutput and has no other output, so nothing else is namable -
        // "time" is not declared and survives verbatim.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:coke_oven",
                Collections.singletonList("input"), Arrays.asList("result", "creosote"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withFieldKind("creosote", IngredientKind.FLUID)
                .withFluidStyles(ModdedJsonStyle.FLUID, ModdedJsonStyle.AMOUNT)
                .withFixedFieldIds("creosote", "immersiveengineering:creosote"));

        // JEI page immersiveengineering:squeezer ("工业挤压机"), 1 input / 1..2 outputs.
        // data/immersiveengineering/recipe/squeezer/melon_seeds.json - the fluid shape:
        //   {"type":"immersiveengineering:squeezer","energy":6400,
        //    "fluid":{"amount":20,"id":"immersiveengineering:plantoil"},
        //    "input":{"item":"minecraft:melon_seeds"}}
        // data/immersiveengineering/recipe/squeezer/graphite_dust.json - the item shape:
        //   {"type":"immersiveengineering:squeezer","energy":19200,
        //    "input":{"basePredicate":{"tag":"c:dusts/coal_coke"},"count":8},
        //    "result":{"tag":"c:dusts/hop_graphite"}}
        // Of the 6 shipped files 5 write "fluid" and 1 writes "result"; none writes both.
        // SqueezerRecipeCategory.setRecipe:
        //   addSlot(INPUT).addItemStacks(input.getMatchingStacks())
        //   slot = addSlot(OUTPUT)                        // created unconditionally
        //   if (!itemOutput.get().isEmpty()) slot.addItemStack(itemOutput.get())
        //   if (fluidOutput is not null and not empty)
        //       addSlot(OUTPUT).setFluidRenderer(…).addIngredient(FLUID_STACK, fluidOutput)
        // - the item OUTPUT slot exists on every recipe, so the 5 fluid recipes draw *two*
        // output slots: ordinal 0 is the empty item slot and ordinal 1 the fluid, while the
        // graphite recipe draws one, the item. JEI keeps a slot the category created even
        // when it holds no ingredient (the client test's "[B] leaves the … slot output.0
        // empty" check is what proved it: an ordered segment list would name the fluid for
        // ordinal 0 and leave that empty slot unnameable). The ordinal-to-field mapping is
        // fixed - 0 is "result", 1 is "fluid" - which is exactly what this positional list
        // says, and a drag onto the empty item slot of a fluid recipe writes a "result"
        // the recipe codec accepts next to its "fluid".
        // The kinds are declared so each slot accepts only its own: the fluid is the
        // FluidStack codec {"amount":…,"id":…}, the item output a TagOutput written as
        // {"id":…,"count":…}. "input" is an IngredientWithSize whose plain {"item":…}
        // branch is what the input style writes; "energy" is not declared and survives.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:squeezer",
                Collections.singletonList("input"), Arrays.asList("result", "fluid"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withFluidFields("fluid"));

        // JEI page immersiveengineering:fermenter ("工业发酵机"), 1 input / 1..2 outputs.
        // data/immersiveengineering/recipe/fermenter/potato.json - the fluid shape:
        //   {"type":"immersiveengineering:fermenter","energy":6400,
        //    "fluid":{"amount":80,"id":"immersiveengineering:ethanol"},
        //    "input":{"tag":"c:crops/potato"}}
        // data/immersiveengineering/recipe/fermenter/honey.json - the only file that also
        // carries an item output:
        //   {"fluid":{"amount":250,"id":"immersiveengineering:ethanol"},
        //    "input":{"item":"minecraft:honey_bottle"},
        //    "result":{"id":"minecraft:glass_bottle"}}
        // Of the 8 shipped files all 8 write "fluid" and 1 writes "result" as well.
        // FermenterRecipeCategory.setRecipe adds the three slots in the same order as the
        // squeezer's, so the item slot exists on all 8 recipes and is empty on the 7 that
        // have no "result": the page draws two OUTPUT slots there (ordinal 0 empty, ordinal
        // 1 the fluid) and two on honey.json (ordinal 0 the bottle, ordinal 1 the ethanol).
        // The declared list is the same fixed ordinal mapping the squeezer's is.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:fermenter",
                Collections.singletonList("input"), Arrays.asList("result", "fluid"),
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID)
                .withFluidFields("fluid"));

        // JEI page immersiveengineering:cloche ("园艺玻璃罩"), 2 inputs / 1..2 outputs.
        // Its slots are a recipe-derived sequence, not a field list -
        // ImmersiveEngineeringSlotSequences.Cloche quotes the JSON, the category's slot
        // order and the reason (the "results" array mixes plain outputs with
        // {"chance":…,"output":…} entries in one ordinal sequence, and only the sequence
        // can address the nested object of the latter). The two styles are the mod's own
        // item shapes: the seed and the outputs are plain Ingredient ({"item":…}) and
        // TagOutput ({"id":…,"count":…}) values. The fluid input is an unsized
        // FluidIngredient, so the FLUID style's "amount" key is written but ignored by
        // its codec - see the sequence's note.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:cloche",
                ImmersiveEngineeringSlotSequences.Cloche.INSTANCE,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.FLUID, ModdedJsonStyle.FLUID_ID));

        // JEI page immersiveengineering:bottling_machine ("灌装机"), 2..4 inputs / 1..2 outputs.
        // Also a derived sequence: the item input is a single "input" object in 23 of the
        // 28 shipped files and an "inputs" array in the other 5, which no field list can
        // name (see ImmersiveEngineeringSlotSequences.BottlingMachine).
        // The other two pages of the same serializer stay read-only and are named here so
        // the client refuses to model them: their recipes are synthesised at display time
        // and have no recipe JSON to patch -
        //   immersiveengineering:bottling_buckets has 37 recipes, all with a
        //     "immersiveengineering:jei_bucket_<fluid>" id (JEIHelper builds them from the
        //     fluid registry; there is no bucket list file in the jar), and
        //   immersiveengineering:bottling_potions has 175 with a
        //     "immersiveengineering:potion_bottling_list<nnn>" id, generated by
        //     GeneratedListRecipe from data/immersiveengineering/recipe/potion_bottling_list.json
        //     (an "immersiveengineering:generated_list" file, like mixer_potions' and
        //     arc_recycling's).
        // The fluid input of the declared page is a SizedFluidIngredient
        // ({"amount":…,"tag":…}), so the default amount-bearing fluid styles are the right
        // ones here, unlike the cloche's unsized field.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:bottling_machine",
                ImmersiveEngineeringSlotSequences.BottlingMachine.INSTANCE,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.FLUID, ModdedJsonStyle.FLUID_ID)
                .withReadOnlyPages("immersiveengineering:bottling_buckets",
                        "immersiveengineering:bottling_potions"));

        // JEI pages immersiveengineering:fertilizer ("园艺玻璃罩肥料", 5 recipes) and
        // immersiveengineering:blast_furnace_fuel ("高炉燃料", 4 recipes): one INPUT slot
        // and no output slot at all. Both are declared with the same single-item sequence,
        // which is the only declaration form whose output side can be empty - a field list
        // needs at least one output field. The two files are quoted next to
        // SingleItemInputSequence.INPUT (shared with Silent Gear's and Mekanism's pages of
        // the same shape); the fertilizer's
        // "growthModifier" and the fuel's "time" are undeclared and survive verbatim.
        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:fertilizer",
                SingleItemInputSequence.INPUT,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.FLUID, ModdedJsonStyle.FLUID_ID));

        adapters.add(new ModdedRecipeDeclaration("immersiveengineering:blast_furnace_fuel",
                SingleItemInputSequence.INPUT,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.FLUID, ModdedJsonStyle.FLUID_ID));

        ALL = Collections.unmodifiableList(adapters);
    }

    private ImmersiveEngineeringRecipeDeclarations() {
    }
}
