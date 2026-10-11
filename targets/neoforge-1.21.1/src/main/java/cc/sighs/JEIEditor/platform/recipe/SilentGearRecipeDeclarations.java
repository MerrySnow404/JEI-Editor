package cc.sighs.JEIEditor.platform.recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Declared Silent Gear recipe pages.
 *
 * <p>Evidence: every JSON quoted below was read out of
 * {@code silent-gear__silent-gear-1.21.1-neoforge-4.2.1.1.jar}
 * ({@code data/silentgear/recipe/**}) and every slot order out of the category's own
 * {@code setRecipe}, disassembled with {@code javap -c}. The serializer ids are the
 * {@code type} values of those files, which Silent Gear registers verbatim
 * ({@code silentgear:alloy_making/metal} and the other three alloy kinds,
 * {@code silentgear:tool_action}).
 *
 * <p>Silent Gear pushes the <em>bare recipe objects</em> into JEI instead of
 * {@code RecipeHolder}s, so the dump has no recipe id for its pages and they were
 * listed as unmodelled. The editor resolves that case by identity: the client looks
 * the displayed object up in the recipe manager
 * ({@code JeiRecipeEditorPlugin.resolveRecipeHolder}) and patches the holder it finds,
 * which is the recipe the manager loaded from the JSON below. Those recipes are
 * {@code Ingredient}-based, so the declared item shapes write them back exactly as the
 * mod's own files spell them.
 *
 * <p>Two alloy recipes carry a dynamic output - {@code custom_gem.dimerald.json} and
 * {@code custom_ingot.high_carbon_steel.json} write
 * {@code "result":{"item":"silentgear:custom_gem","material":"silentgear:dimerald"}}.
 * The {@code material} key IS the identity of that result, and the declared writer
 * rebuilds the whole node, so it would drop it. The ingredient whose material is named
 * carries it as a data component, so {@code RecipeAdapterSupport.simpleStack} yields no
 * representative for that slot: the editor shows the output empty and refuses to
 * rewrite it, exactly like the component-carrying outputs of Immersive Engineering's
 * blueprint page. Their inputs stay editable.
 *
 * <p>Coverage is partial per page, and that is the shape of the data: the /metal page
 * draws 8 recipes and /gem 4, while the pack ships 5 and 1 recipe files. The rest are
 * generated from Silent Gear's material data and never enter the recipe manager, so the
 * client cannot resolve a holder for them and only the JSON-backed entries build a
 * model (the client test reports metal 5/8 and gem 1/4). The /fabric and /super pages
 * ship no recipe file at all - their 3 recipes each are generated the same way - so
 * nothing on those two can be patched; they are named as not covered in
 * {@code gen_checklist.py} instead of being declared.
 */
public final class SilentGearRecipeDeclarations {
    public static final List<ModdedRecipeAdapter> ALL;

    static {
        List<ModdedRecipeAdapter> adapters = new ArrayList<ModdedRecipeAdapter>();

        // JEI pages silentgear:alloy_making/metal and /gem ("Metal Alloys",
        // "Gem Alloys"), 2..4 inputs / 1 output.
        // data/silentgear/recipe/alloying/metal/azure_electrum_ingot.json:
        //   {"type":"silentgear:alloy_making/metal",
        //    "ingredients":[{"tag":"c:storage_blocks/azure_silver"},{"tag":"c:ingots/gold"},
        //                   {"tag":"c:ingots/gold"},{"tag":"c:ender_pearls"}],
        //    "result":{"count":3,"item":"silentgear:azure_electrum_ingot"}}
        // data/silentgear/recipe/alloying/metal/blaze_gold_ingot.json is the same shape
        // with three ingredients and no "count".
        // AlloyMakingRecipeCategory.setRecipe adds one INPUT slot per element of
        // AlloyRecipe.getIngredients() (in order, skipping nothing - the count is the
        // array's) and then one OUTPUT slot for AlloyRecipe.getResultItem(): the
        // ingredient array length is the page's input count, which is what
        // "ingredients.%d" names.
        // The input style is the declaration's item shape because those entries are
        // plain Ingredients ({"tag":…} / {"item":…}); the output style is the item shape
        // too, NOT the result-side "id" form every other declared page uses: Silent Gear
        // writes its result as {"item":…,"count":…} (AlloyRecipe.Result's own keys are
        // item, count and material), so an edited output has to keep that key.
        // Only these two kinds are declared. The /fabric and /super pages exist (3
        // recipes each) but not one of their recipes has a recipe JSON: Silent Gear
        // generates them from its material data, and they are display-only even for the
        // manager - the client cannot resolve a holder for them, which is what
        // scripts/jei-client-tests.ps1 showed (0 models for either page, from the
        // serializer alone they would have been marked wired). They are named in
        // gen_checklist.py's NOT_COVERED_UIDS instead.
        for (String kind : Arrays.asList("metal", "gem")) {
            adapters.add(new ModdedRecipeDeclaration("silentgear:alloy_making/" + kind,
                    Collections.singletonList("ingredients.%d"), "result",
                    ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                    ModdedRecipeAdapter.ItemJsonStyle.ITEM));
        }

        // JEI page silentgear:gear_crafting ("Gear Crafting", 113 recipes), 2..3 inputs /
        // 1 output.
        // data/silentgear/recipe/gear/arrow.json - one of the 70 "gear_crafting" files:
        //   {"type":"silentgear:gear_crafting","category":"equipment",
        //    "ingredients":[{"item":"silentgear:arrow_heads"},
        //                   {"type":"silentgear:part_type","part_type":"silentgear:rod"},
        //                   {"type":"silentgear:part_type","part_type":"silentgear:fletching"}],
        //    "result":{"count":1,"id":"silentgear:arrow"}}
        // data/silentgear/recipe/gear/arrow_head.json - one of the 43 "compound_part"
        // files, the other type this one page displays (70 + 43 = the page's 113):
        //   {"type":"silentgear:compound_part","category":"equipment",
        //    "ingredients":[{"type":"silentgear:blueprint","gear_type":"silentgear:arrow",
        //                    "part_type":"silentgear:main"},
        //                   {"type":"silentgear:material","gear_type":"silentgear:arrow",
        //                    "part_type":"silentgear:main"}],
        //    "result":{"count":1,"id":"silentgear:arrow_heads"}}
        // GearCraftingRecipeCategoryJei.setRecipe adds OUTPUT(CraftingRecipe.getResultItem())
        // and then one INPUT slot per element of CraftingRecipe.getIngredients(), so the
        // ingredient array length is the page's input count ("ingredients.%d") and the one
        // output is "result". The "category" key is not declared and survives.
        // Those ingredient entries are Silent Gear's own ingredient types, registered
        // through NeoForge's IngredientType dispatch (SgIngredientTypes), whose codec
        // falls back to the vanilla single/tag form - so the declared item shape
        // ({"item":…,"count":…}) decodes, and the count key is ignored exactly like on
        // every other plain-Ingredient field this editor declares.
        // The result of both types is a *template*: the crafted gear's material comes
        // from the inputs at craft time and the displayed stack carries it as a data
        // component, so RecipeAdapterSupport.simpleStack yields no representative for
        // that slot - the editor shows the output empty and refuses to rewrite it (like
        // the alloy pages' material results and IE's coloured flares), while the inputs
        // stay editable.
        for (String serializer : Arrays.asList("silentgear:gear_crafting",
                "silentgear:compound_part")) {
            adapters.add(new ModdedRecipeDeclaration(serializer,
                    Collections.singletonList("ingredients.%d"), "result",
                    ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                    ModdedRecipeAdapter.ItemJsonStyle.ID));
        }

        // JEI page silentgear:salvaging ("Salvaging", 88 recipes), 1 input / 0..4 outputs.
        // The page draws recipes of three types, all of them "salvage something":
        // data/silentgear/recipe/salvaging/bow.json - the 54 plain "salvaging" files:
        //   {"type":"silentgear:salvaging","ingredient":{"item":"minecraft:bow"},
        //    "results":[{"count":3,"id":"minecraft:string"},{"count":3,"id":"minecraft:stick"}]}
        // data/silentgear/recipe/salvaging/gear/axe.json - the 33 "salvaging/gear" files:
        //   {"type":"silentgear:salvaging/gear","ingredient":{"item":"silentgear:axe"}}
        // data/silentgear/recipe/salvage/compound_part.json - one file, a type with no
        //   fields at all: {"type":"silentgear:salvaging/compound_part"}
        // SalvagingRecipeCategoryJei.setRecipe adds INPUT(ingredient) and then one OUTPUT
        // per entry of SalvagingRecipe.getPossibleResultsForDisplay(), so the output count
        // is the "results" array's length. All 97 result entries of the 54 shipped plain
        // files are {"count":…,"id":…} (1..4 per recipe), which is the result-side item
        // shape; the input is a plain Ingredient, so the ingredient side is the item shape.
        adapters.add(new ModdedRecipeDeclaration("silentgear:salvaging",
                Collections.singletonList("ingredient"), "results.%d",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        // The "salvaging/gear" type draws one slot and no output: its
        // getPossibleResultsForDisplay() returns an empty list for every recipe (the
        // materials come from the gear's own data), so the page has nothing to show on the
        // output side and no field list can name a page with no output at all. See
        // SingleItemInputSequence.INGREDIENT for the shared mechanism (the bytecode evidence is
        // above).
        adapters.add(new ModdedRecipeDeclaration("silentgear:salvaging/gear",
                SingleItemInputSequence.INGREDIENT,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.FLUID, ModdedJsonStyle.FLUID_ID));

        // The third type on that page, silentgear:salvaging/compound_part, is declared
        // nowhere on purpose: its single shipped file has no ingredient and no results
        // ({"type":"silentgear:salvaging/compound_part"}), so the page it produces has an
        // empty input slot and a computed output - there is no field to edit. It is left
        // unmodelled, like every other page the editor cannot name a slot for.

        // JEI page silentgear:tool_action ("Tool Action"), 2 inputs / 1 output.
        // data/silentgear/recipe/azure_electrum_dust.json:
        //   {"type":"silentgear:tool_action","damage_to_tool":1,
        //    "ingredient":{"tag":"c:ingots/azure_electrum"},
        //    "result":{"count":1,"id":"silentgear:azure_electrum_dust"},
        //    "sound":{"pitch":1.0,"pitch_deviation":0.1,
        //             "sound":"silentgear:block.silentgear.stone_anvil.hammer","volume":1.0},
        //    "tool":{"tag":"c:tools/hammer"}}
        // ToolActionRecipeCategory.setRecipe adds the slots in this order:
        //   INPUT(tool)      ToolActionRecipe.getTool()
        //   INPUT(ingredient) ToolActionRecipe.getIngredient()
        //   CATALYST         the stone anvil block, not a recipe field
        //   OUTPUT           ToolActionRecipe.getResult()
        // - so per role the inputs are "tool" then "ingredient" (the tool comes FIRST,
        // which is the reverse of what the page draws attention to) and the output is
        // "result". "damage_to_tool" and "sound" are undeclared and survive verbatim.
        // Here the result IS the result-side item shape ({"id":…,"count":…}), unlike the
        // alloy pages' "item" form, because the mod's own file writes "id".
        adapters.add(new ModdedRecipeDeclaration("silentgear:tool_action",
                Arrays.asList("tool", "ingredient"), "result",
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID));

        ALL = Collections.unmodifiableList(adapters);
    }

    private SilentGearRecipeDeclarations() {
    }
}
