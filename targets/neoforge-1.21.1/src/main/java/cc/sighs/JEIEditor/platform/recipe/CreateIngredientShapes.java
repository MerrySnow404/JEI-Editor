package cc.sighs.JEIEditor.platform.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The recipe-JSON questions Create's slot derivations ask: which array a recipe carries,
 * whether an entry is a fluid, whether a result is a fluid result, and whether a decoded
 * ingredient is the one the mod itself built.
 *
 * <p>Shared by {@link CreateBasinSlotSequence} and {@link CreateSpoutSlotSequence} so the
 * two derivations agree on what "a fluid entry" means. Every answer here is the mod's own
 * rule, not the editor's opinion: the fluid test is "the {@code type} names a registered
 * fluid ingredient type", which is what Create's {@code SIZED_FLUID_INGREDIENT} dispatches
 * on.
 */
final class CreateIngredientShapes {
    private static final String TYPE = "type";
    private static final String ID = "id";
    private static final String FLUID = "fluid";

    private CreateIngredientShapes() {
    }

    /** The array at {@code key}, or empty when the recipe does not carry one. */
    static Optional<JsonArray> array(JsonObject recipe, String key) {
        if (recipe == null) {
            return Optional.empty();
        }
        JsonElement node = recipe.get(key);
        return node != null && node.isJsonArray()
                ? Optional.of(node.getAsJsonArray()) : Optional.empty();
    }

    /**
     * Whether a recipe JSON entry is a fluid ingredient: its {@code type} names a
     * registered fluid ingredient type. Create's {@code SIZED_FLUID_INGREDIENT}
     * requires that {@code type} on both of its branches, so no item ingredient can
     * carry one, and {@code neoforge:block_tag} - an item ingredient type - is
     * registered for items only.
     */
    static boolean isFluidIngredient(JsonElement entry) {
        if (entry == null || !entry.isJsonObject()) {
            return false;
        }
        JsonElement type = entry.getAsJsonObject().get(TYPE);
        if (type == null || !type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(type.getAsString());
        return id != null && NeoForgeRegistries.FLUID_INGREDIENT_TYPES.containsKey(id);
    }

    /** Whether a {@code results} entry decodes as a fluid stack, as the mod's codec decides. */
    static boolean isFluidResult(JsonElement entry) {
        if (entry == null || !entry.isJsonObject()) {
            return false;
        }
        JsonObject object = entry.getAsJsonObject();
        String id = primitive(object, ID);
        if (id == null) {
            id = primitive(object, FLUID);
        }
        ResourceLocation parsed = id == null ? null : ResourceLocation.tryParse(id);
        return parsed != null && BuiltInRegistries.FLUID.containsKey(parsed);
    }

    static Optional<Ingredient> decodeIngredient(JsonElement entry) {
        try {
            return Ingredient.CODEC.parse(JsonOps.INSTANCE, entry).result();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** A primitive JSON value of {@code key}, or null. */
    static String primitive(JsonObject json, String key) {
        if (json == null || key == null) {
            return null;
        }
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * The item and fluid entry indices of a Create transfer recipe's {@code ingredients}
     * array, cross-checked against the item {@code Ingredient}s the mod itself parsed.
     *
     * <p>Create's {@code ProcessingRecipeParams} splits that one array into item
     * ingredients and fluid ingredients, keeping each part's relative order, so which
     * index belongs to which half is the codec's decision - the entry's own {@code type}
     * decides it ({@link #isFluidIngredient}). The item half is then compared with
     * {@code Recipe#getIngredients()} of the recipe the mod loaded: a recipe whose split
     * disagrees is refused rather than written with a guessed field.
     */
    static Optional<Split> split(JsonObject recipe, Recipe<?> loaded, String ingredientsKey) {
        if (loaded == null) {
            return Optional.empty();
        }
        Optional<JsonArray> entries = array(recipe, ingredientsKey);
        Optional<Split> split = entries.isPresent()
                ? split(entries.get()) : Optional.<Split>empty();
        if (!split.isPresent()) {
            return Optional.empty();
        }
        NonNullList<Ingredient> loadedIngredients = loaded.getIngredients();
        if (loadedIngredients == null || loadedIngredients.size() != split.get().itemIndices.size()) {
            return Optional.empty();
        }
        for (int index = 0; index < split.get().itemIndices.size(); index++) {
            if (!sameItems(loadedIngredients.get(index).getItems(),
                    split.get().itemIngredients.get(index).getItems())) {
                return Optional.empty();
            }
        }
        return split;
    }

    /**
     * The item and fluid entry indices of an {@code ingredients} array, decided by each
     * entry's own {@code type} - the codec's own rule ({@link #isFluidIngredient}) - with
     * every entry required to classify: one that is neither a fluid ingredient nor a
     * decodable item ingredient refuses the derivation instead of being guessed at.
     *
     * <p>This is the half of {@link #split(JsonObject, Recipe, String)} that works without
     * the recipe the mod loaded, for a recipe that reports no ingredient list of its own:
     * Create's sequenced assembly keeps every ingredient inside its {@code sequence} steps
     * and answers {@code getIngredients()} with the empty default, so its steps can only be
     * classified structurally. The shape is the codec's, so a misclassification is a
     * malformed recipe rather than a plausible one.
     */
    static Optional<Split> split(JsonArray entries) {
        if (entries == null) {
            return Optional.empty();
        }
        List<Integer> itemIndices = new ArrayList<Integer>();
        List<Integer> fluidIndices = new ArrayList<Integer>();
        List<Ingredient> itemIngredients = new ArrayList<Ingredient>();
        for (int index = 0; index < entries.size(); index++) {
            JsonElement entry = entries.get(index);
            if (isFluidIngredient(entry)) {
                fluidIndices.add(Integer.valueOf(index));
                continue;
            }
            Optional<Ingredient> ingredient = decodeIngredient(entry);
            if (!ingredient.isPresent()) {
                return Optional.empty();
            }
            itemIndices.add(Integer.valueOf(index));
            itemIngredients.add(ingredient.get());
        }
        return Optional.of(new Split(itemIndices, fluidIndices, itemIngredients));
    }

    /** The index lists one recipe's {@code ingredients} array splits into. */
    static final class Split {
        final List<Integer> itemIndices;
        final List<Integer> fluidIndices;
        /** The item entries decoded, in the same order as {@link #itemIndices}. */
        final List<Ingredient> itemIngredients;

        private Split(List<Integer> itemIndices, List<Integer> fluidIndices,
                      List<Ingredient> itemIngredients) {
            this.itemIndices = itemIndices;
            this.fluidIndices = fluidIndices;
            this.itemIngredients = itemIngredients;
        }
    }

    /**
     * {@code ItemStack.matches} element-wise over two item arrays of equal length,
     * which is the comparison {@code ItemHelper.condenseIngredients} groups by.
     */
    static boolean sameItems(ItemStack[] first, ItemStack[] second) {
        if (first == null || second == null || first.length != second.length) {
            return false;
        }
        for (int index = 0; index < first.length; index++) {
            if (!ItemStack.matches(first[index], second[index])) {
                return false;
            }
        }
        return true;
    }
}
