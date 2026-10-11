package cc.sighs.JEIEditor.platform.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Declared Cobblemon recipe pages.
 *
 * <p>Evidence: every JSON quoted below was read out of
 * {@code cobblemon__Cobblemon-neoforge-1.8.1+1.21.1.jar}
 * ({@code data/cobblemon/recipe/campfire_pot/*.json}) and the slot order out of
 * {@code CampfirePotRecipeCategory.setRecipe}, disassembled with {@code javap -c}.
 */
public final class CobblemonRecipeDeclarations {
    public static final List<ModdedRecipeAdapter> ALL;

    static {
        List<ModdedRecipeAdapter> adapters = new ArrayList<ModdedRecipeAdapter>();

        // JEI page cobblemon:campfire_pot_recipe ("营火锅"), 2..7 inputs / 1 output.
        // The page displays two recipe types; only this one is declared:
        // data/cobblemon/recipe/campfire_pot/antidote_in_campfire_pot.json:
        //   {"type":"cobblemon:cooking_pot_shapeless","category":"medicines",
        //    "ingredients":[{"item":"minecraft:glass_bottle"},{"item":"cobblemon:pecha_berry"}],
        //    "result":{"id":"cobblemon:antidote"},"seasoningProcessors":[],
        //    "seasoningTag":"cobblemon:empty"}
        // data/cobblemon/recipe/campfire_pot/beetroot_soup_in_campfire_pot.json is the same
        // shape with seven ingredients; 90 of the page's 117 files are this type, the other
        // 27 are {"type":"cobblemon:cooking_pot","pattern":[…],"key":{…}}.
        // CampfirePotRecipeCategory.setRecipe adds one INPUT per entry of the ingredient
        // list (in a three-column grid), then - when the recipe names seasoning processors -
        // one INPUT per matching example seasoning, then OUTPUT(getResultItem), which is
        // what CobblemonCampfirePotSlotSequence describes. Those seasoning slots are
        // previews with no field of their own, which is why the page needs a sequence rather
        // than the field list "ingredients.%d": the repeat field would name them too and a
        // drop there would append an ingredient.
        adapters.add(new ModdedRecipeDeclaration("cobblemon:cooking_pot_shapeless",
                CobblemonCampfirePotSlotSequence.INSTANCE,
                ModdedRecipeAdapter.ItemJsonStyle.ITEM,
                ModdedRecipeAdapter.ItemJsonStyle.ID,
                ModdedJsonStyle.FLUID, ModdedJsonStyle.FLUID_ID));

        ALL = Collections.unmodifiableList(adapters);
    }

    private CobblemonRecipeDeclarations() {
    }
}
