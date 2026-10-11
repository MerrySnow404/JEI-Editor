package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The slot sequence of Cobblemon's campfire pot page, derived from the recipe the way
 * {@code CampfirePotRecipeCategory.setRecipe} draws it.
 *
 * <p>Evidence (Cobblemon 1.8.1, disassembled with {@code javap -c}): the category draws
 * <ol>
 *   <li>one INPUT slot per entry of {@code CookingPotRecipeBase.getIngredients()}, laid out
 *       in a three-column grid,</li>
 *   <li>and - only when {@code getSeasoningProcessors()} is not empty - one more INPUT slot
 *       per example seasoning that matches the recipe's {@code seasoningTag},</li>
 *   <li>then OUTPUT({@code getResultItem}).</li>
 * </ol>
 *
 * <p>{@code data/cobblemon/recipe/campfire_pot/antidote_in_campfire_pot.json}:
 * <pre>
 *   {"type":"cobblemon:cooking_pot_shapeless","category":"medicines",
 *    "ingredients":[{"item":"minecraft:glass_bottle"},{"item":"cobblemon:pecha_berry"}],
 *    "result":{"id":"cobblemon:antidote"},"seasoningProcessors":[],
 *    "seasoningTag":"cobblemon:empty"}
 * </pre>
 *
 * <p>Those seasoning slots are previews - "you could add one of these" - and hold no field
 * of their own: the recipe names the processors it uses and a tag, not the seasonings. That
 * is why the page cannot be declared as a field list: a repeat field names every remaining
 * ordinal, so a drop on a seasoning slot would be written as {@code ingredients.N} - and for
 * an array of N entries that is the "spare slot", i.e. it would APPEND an ingredient to a
 * recipe the user never meant to change. This sequence names slots only for the entries the
 * JSON really carries, and {@link #fieldBackedInputOrdinals} tells the client where they stop,
 * so the seasoning slots are modelled read-only and a hand-made patch on one of them is
 * refused (the resolved sequence does not reach that ordinal).
 *
 * <p>This declaration covers {@code cobblemon:cooking_pot_shapeless} only. The page's other
 * recipe type, {@code cobblemon:cooking_pot} (27 of its 117 files, a pattern + key shape),
 * is declared nowhere, so the client builds no model for those entries.
 */
final class CobblemonCampfirePotSlotSequence implements DerivedSlotSequence {
    static final CobblemonCampfirePotSlotSequence INSTANCE =
            new CobblemonCampfirePotSlotSequence();

    private static final String INGREDIENTS = "ingredients";
    private static final String RESULT = "result";

    private CobblemonCampfirePotSlotSequence() {
    }

    @Override
    public Set<IngredientKind> inputKinds() {
        return Collections.singleton(IngredientKind.ITEM);
    }

    @Override
    public Set<IngredientKind> outputKinds() {
        return Collections.singleton(IngredientKind.ITEM);
    }

    /** One slot per ingredient entry, plus the seasoning previews; at least one. */
    @Override
    public boolean inputCountFits(int count) {
        return count >= 1;
    }

    /** The category always draws exactly one output slot. */
    @Override
    public boolean outputCountFits(int count) {
        return count == 1;
    }

    @Override
    public Optional<List<DerivedSlot>> inputSlots(JsonObject recipe, Recipe<?> loaded) {
        Optional<JsonArray> entries = array(recipe);
        if (!entries.isPresent() || entries.get().isEmpty()) {
            return Optional.empty();
        }
        // The independent half of the derivation: the JSON's ingredient array has to hold
        // exactly the ingredients the mod's codec built from it. A recipe whose two halves
        // disagree is refused instead of being written with a guessed field.
        if (loaded == null || loaded.getIngredients() == null
                || loaded.getIngredients().size() != entries.get().size()) {
            return Optional.empty();
        }
        List<DerivedSlot> slots = new ArrayList<DerivedSlot>(entries.get().size());
        for (int index = 0; index < entries.get().size(); index++) {
            JsonElement entry = entries.get().get(index);
            if (entry == null || !entry.isJsonObject()) {
                return Optional.empty();
            }
            slots.add(DerivedSlot.of(IngredientKind.ITEM, INGREDIENTS + "." + index));
        }
        return Optional.of(slots);
    }

    @Override
    public Optional<List<DerivedSlot>> outputSlots(JsonObject recipe, Recipe<?> loaded) {
        if (recipe == null) {
            return Optional.empty();
        }
        JsonElement result = recipe.get(RESULT);
        if (result == null || !result.isJsonObject()) {
            return Optional.empty();
        }
        return Optional.of(Collections.singletonList(
                DerivedSlot.of(IngredientKind.ITEM, RESULT)));
    }

    /**
     * How many input slots hold an ingredient entry: the recipe's own ingredient list
     * length. Everything after them is a seasoning preview the page draws and no field
     * names, so the model leaves those slots read-only.
     */
    @Override
    public Set<Integer> fieldBackedInputOrdinals(Recipe<?> loaded, int inputCount) {
        Set<Integer> ordinals = new LinkedHashSet<Integer>();
        int ingredients = inputCount;
        if (loaded != null && loaded.getIngredients() != null) {
            ingredients = Math.min(inputCount, loaded.getIngredients().size());
        }
        for (int ordinal = 0; ordinal < ingredients; ordinal++) {
            ordinals.add(Integer.valueOf(ordinal));
        }
        return ordinals;
    }

    /** The recipe's {@code ingredients} array, or empty when it carries none. */
    private static Optional<JsonArray> array(JsonObject recipe) {
        if (recipe == null) {
            return Optional.empty();
        }
        JsonElement node = recipe.get(INGREDIENTS);
        return node != null && node.isJsonArray()
                ? Optional.of(node.getAsJsonArray()) : Optional.empty();
    }
}
