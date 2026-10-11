package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The slot sequence of Create's spout page, derived from the recipe exactly the way
 * {@code com.simibubi.create.compat.jei.category.SpoutCategory.setRecipe} draws it.
 *
 * <p>Evidence (Create 1.21.1-6.0.10, disassembled with {@code javap -c}): the category
 * adds exactly three slots and no others:
 *
 * <pre>
 *   INPUT (27,51)  FillingRecipe.getIngredients().get(0)     // the first ITEM entry
 *   INPUT (27,32)  FillingRecipe.getRequiredFluid()          // the first FLUID entry
 *   OUTPUT(132,51) getResultItem(recipe)                     // the first item result
 * </pre>
 *
 * so the page is always 2 inputs and 1 output, the item before the fluid. Neither
 * accessor loops: `.get(0)` and the first fluid ingredient are the whole page, which is
 * why the derivation below refuses a recipe with more than one item entry, more than one
 * fluid entry or more than one result - the extra entries would be invisible on the page
 * and therefore unreachable, and a slot ordinal could not be mapped to them.
 *
 * <p>Which JSON entry is the item and which the fluid is the <em>codec's</em> decision:
 * Create's {@code ProcessingRecipeParams} partitions one {@code ingredients} array into
 * item ingredients and fluid ingredients, keeping each part's relative order. The
 * indices therefore come from {@link CreateIngredientShapes#isFluidIngredient}, and the
 * item half is cross-checked against the {@code Ingredient} objects the mod itself
 * parsed ({@code FillingRecipe.getIngredients()}); a recipe whose split disagrees is
 * refused rather than written with a guessed field.
 *
 * <p>Measured over the 19 shipped files of {@code data/create/recipe/filling/**}: every
 * one is {@code ingredients:[<item>, <fluid>]} plus one item result, the fluid written as
 * {@code {"type":"neoforge:single"/"neoforge:tag"/"neoforge:components","amount":…}}.
 * The page itself lists 50 entries; the rest are Create's runtime-generated spout
 * variants, which never enter the recipe manager and so have no JSON to patch (they build
 * no model either, which is why the client test reports this page as partially covered).
 */
final class CreateSpoutSlotSequence implements DerivedSlotSequence {
    static final CreateSpoutSlotSequence INSTANCE = new CreateSpoutSlotSequence();

    private static final String INGREDIENTS = "ingredients";
    private static final String RESULTS = "results";
    private static final int INPUT_COUNT = 2;
    private static final int OUTPUT_COUNT = 1;

    private CreateSpoutSlotSequence() {
    }

    @Override
    public Set<IngredientKind> inputKinds() {
        return Collections.unmodifiableSet(new LinkedHashSet<IngredientKind>(
                Arrays.asList(IngredientKind.ITEM, IngredientKind.FLUID)));
    }

    @Override
    public Set<IngredientKind> outputKinds() {
        return Collections.singleton(IngredientKind.ITEM);
    }

    /** One item and one fluid: the category adds exactly those two slots. */
    @Override
    public boolean inputCountFits(int count) {
        return count == INPUT_COUNT;
    }

    /** One item result: the category adds exactly that one slot. */
    @Override
    public boolean outputCountFits(int count) {
        return count == OUTPUT_COUNT;
    }

    @Override
    public Optional<List<DerivedSlot>> inputSlots(JsonObject recipe, Recipe<?> loaded) {
        Optional<CreateIngredientShapes.Split> split =
                CreateIngredientShapes.split(recipe, loaded, INGREDIENTS);
        if (!split.isPresent()
                || split.get().itemIndices.size() != 1
                || split.get().fluidIndices.size() != 1) {
            return Optional.empty();
        }
        List<DerivedSlot> slots = new ArrayList<DerivedSlot>(INPUT_COUNT);
        slots.add(DerivedSlot.of(IngredientKind.ITEM,
                INGREDIENTS + "." + split.get().itemIndices.get(0)));
        slots.add(DerivedSlot.of(IngredientKind.FLUID,
                INGREDIENTS + "." + split.get().fluidIndices.get(0)));
        return Optional.of(slots);
    }

    @Override
    public Optional<List<DerivedSlot>> outputSlots(JsonObject recipe, Recipe<?> loaded) {
        Optional<JsonArray> results = CreateIngredientShapes.array(recipe, RESULTS);
        if (!results.isPresent() || results.get().size() != OUTPUT_COUNT) {
            return Optional.empty();
        }
        JsonElement only = results.get().get(0);
        if (CreateIngredientShapes.isFluidResult(only)) {
            // getResultItem() returns the first ITEM result; a fluid one would leave the
            // page's output slot without a field this declaration can name.
            return Optional.empty();
        }
        return Optional.of(Collections.singletonList(
                DerivedSlot.of(IngredientKind.ITEM, RESULTS + ".0")));
    }

}
