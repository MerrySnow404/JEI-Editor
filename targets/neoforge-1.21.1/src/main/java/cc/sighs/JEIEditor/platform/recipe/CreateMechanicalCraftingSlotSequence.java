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
 * The slot sequence of Create's mechanical crafting page, derived from the recipe the way
 * {@code com.simibubi.create.compat.jei.category.MechanicalCraftingCategory.setRecipe}
 * draws it.
 *
 * <p>Evidence (Create 1.21.1-6.0.10, disassembled with {@code javap -c}): the category adds
 * one OUTPUT slot for {@code getResultItem}, then walks {@code CraftingRecipe.getIngredients()}
 * and adds one INPUT slot per element - no skipping, no reordering. Measured against the
 * client, that list is one entry per <em>pattern cell</em>, empty cells included: the page
 * reports 25 input slots for the 5x5 {@code crushing_wheel} pattern (13 of them non-empty)
 * and 15 for {@code wand_of_symmetry}'s 3x5 one.
 *
 * <pre>
 *   data/create/recipe/mechanical_crafting/wand_of_symmetry.json
 *   {"type":"create:mechanical_crafting","accept_mirrored":true,"category":"misc",
 *    "key":{"B":{"tag":"c:ingots/brass"},"E":{"tag":"c:ender_pearls"},
 *           "G":{"tag":"c:glass_blocks"},"O":{"tag":"c:obsidians"},
 *           "P":{"item":"create:precision_mechanism"}},
 *    "pattern":[" G ","GEG"," P "," B "," O "],
 *    "result":{"count":1,"id":"create:wand_of_symmetry"}}
 * </pre>
 *
 * <p>Two things follow from that shape, and they are why this page needs a sequence:
 *
 * <ul>
 *   <li>A cell that is a <b>space</b> has no ingredient and no field: the slot exists on the
 *       page but holds nothing. It is returned as a {@link DerivedSlot} with no paths, so the
 *       client models it read-only ({@link #fieldBackedInputOrdinals} tells it which ordinals
 *       those are) and the server refuses any patch addressed to it.</li>
 *   <li>A letter used by several cells is <b>one field</b>: the JSON names ingredients per
 *       pattern character, so the k-th non-empty cell's field is {@code key.<character>} and
 *       editing one of those cells changes the ingredient of every cell using that letter
 *       (in {@code crushing_wheel.json} the "A" cells are 13 of the 25). That is the
 *       recipe's own structure - Create's JSON cannot express per-cell ingredients - and the
 *       smoke test pins it on {@code wand_of_symmetry}'s "G", which fills ordinals 1, 3 and 5.</li>
 * </ul>
 *
 * <p>The independent half of the derivation is the check against the recipe the mod parsed:
 * every cell's letter has to decode to exactly the {@code Ingredient} at that ordinal
 * (compared through {@link CreateIngredientShapes#sameItems}), a letter without a
 * {@code key} entry or an empty cell that the mod did not see as empty refuses the whole
 * patch, and the cell count has to equal the recipe's ingredient count. Those checks are
 * what caught an earlier version of this mapping that counted only non-space characters.
 *
 * <p>Measured over the 4 shipped files: 3..5 letters, 10..25 cells, one {@code result}.
 * {@code accept_mirrored}, {@code category} and {@code show_notification} are not declared
 * and survive verbatim.
 */
final class CreateMechanicalCraftingSlotSequence implements DerivedSlotSequence {
    static final CreateMechanicalCraftingSlotSequence INSTANCE =
            new CreateMechanicalCraftingSlotSequence();

    private static final String PATTERN = "pattern";
    private static final String KEY = "key";
    private static final String RESULT = "result";

    private CreateMechanicalCraftingSlotSequence() {
    }

    @Override
    public Set<IngredientKind> inputKinds() {
        return Collections.singleton(IngredientKind.ITEM);
    }

    @Override
    public Set<IngredientKind> outputKinds() {
        return Collections.singleton(IngredientKind.ITEM);
    }

    /** One slot per pattern cell; at least one. */
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
        Optional<List<String>> cells = cells(recipe);
        if (!cells.isPresent() || cells.get().isEmpty()) {
            return Optional.empty();
        }
        if (loaded == null || loaded.getIngredients() == null
                || loaded.getIngredients().size() != cells.get().size()) {
            return Optional.empty();
        }
        JsonObject key = object(recipe, KEY);
        NonNullList<Ingredient> ingredients = loaded.getIngredients();
        List<DerivedSlot> slots = new ArrayList<DerivedSlot>(cells.get().size());
        for (int index = 0; index < cells.get().size(); index++) {
            String letter = cells.get().get(index);
            boolean empty = " ".equals(letter);
            if (empty != ingredients.get(index).isEmpty()) {
                // A cell the JSON leaves blank has to be the empty Ingredient the mod built,
                // and a cell carrying a letter has to be a real one.
                return Optional.empty();
            }
            if (empty) {
                slots.add(new DerivedSlot(IngredientKind.ITEM, Collections.<String>emptyList()));
                continue;
            }
            if (key == null) {
                return Optional.empty();
            }
            JsonElement node = key.get(letter);
            if (node == null || !node.isJsonObject()) {
                return Optional.empty();
            }
            Optional<Ingredient> ingredient = CreateIngredientShapes.decodeIngredient(node);
            if (!ingredient.isPresent() || !CreateIngredientShapes.sameItems(
                    ingredients.get(index).getItems(), ingredient.get().getItems())) {
                return Optional.empty();
            }
            // Two cells with the same letter resolve to the same field on purpose: the
            // recipe names ingredients per pattern character.
            slots.add(DerivedSlot.of(IngredientKind.ITEM, KEY + "." + letter));
        }
        return Optional.of(slots);
    }

    @Override
    public Optional<List<DerivedSlot>> outputSlots(JsonObject recipe, Recipe<?> loaded) {
        if (object(recipe, RESULT) == null) {
            return Optional.empty();
        }
        return Optional.of(Collections.singletonList(
                DerivedSlot.of(IngredientKind.ITEM, RESULT)));
    }

    /**
     * The ordinals that carry a letter, read off the recipe the mod parsed: a blank cell is
     * the empty {@code Ingredient} there. Everything else on the page - the spaces - is
     * modelled read-only.
     */
    @Override
    public Set<Integer> fieldBackedInputOrdinals(Recipe<?> loaded, int inputCount) {
        Set<Integer> ordinals = new LinkedHashSet<Integer>();
        if (loaded == null || loaded.getIngredients() == null) {
            for (int ordinal = 0; ordinal < inputCount; ordinal++) {
                ordinals.add(Integer.valueOf(ordinal));
            }
            return ordinals;
        }
        NonNullList<Ingredient> ingredients = loaded.getIngredients();
        int count = Math.min(inputCount, ingredients.size());
        for (int ordinal = 0; ordinal < count; ordinal++) {
            if (!ingredients.get(ordinal).isEmpty()) {
                ordinals.add(Integer.valueOf(ordinal));
            }
        }
        return ordinals;
    }

    /**
     * One entry per pattern cell, row by row, with {@code " "} for a blank one, or empty when
     * the recipe carries no usable pattern. Rows must all be the same length, as the codec
     * requires.
     */
    private static Optional<List<String>> cells(JsonObject recipe) {
        JsonArray rows = array(recipe, PATTERN);
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        List<String> cells = new ArrayList<String>();
        int width = -1;
        for (JsonElement row : rows) {
            if (row == null || !row.isJsonPrimitive() || !row.getAsJsonPrimitive().isString()) {
                return Optional.empty();
            }
            String line = row.getAsString();
            if (width < 0) {
                width = line.length();
            } else if (line.length() != width) {
                return Optional.empty();
            }
            for (int index = 0; index < line.length(); index++) {
                cells.add(String.valueOf(line.charAt(index)));
            }
        }
        return Optional.of(cells);
    }

    private static JsonArray array(JsonObject recipe, String key) {
        if (recipe == null) {
            return null;
        }
        JsonElement node = recipe.get(key);
        return node != null && node.isJsonArray() ? node.getAsJsonArray() : null;
    }

    private static JsonObject object(JsonObject recipe, String key) {
        if (recipe == null) {
            return null;
        }
        JsonElement node = recipe.get(key);
        return node != null && node.isJsonObject() ? node.getAsJsonObject() : null;
    }
}
