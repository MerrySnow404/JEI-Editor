package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.crafting.Recipe;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One input slot and no output slot, for a page whose whole editable surface is a single
 * plain item field.
 *
 * <p>A field list cannot describe such a page: {@link ModdedRecipeDeclaration} needs at
 * least one output field, and a page whose layout draws no OUTPUT slot at all has nothing
 * for it to name - the declared model would either invent a slot or refuse the page. The
 * three shipped pages with that shape are quoted next to their declarations, and all three
 * are pages whose "result" is not an ingredient:
 *
 * <ul>
 *   <li>Immersive Engineering's {@code fertilizer} (its result is {@code growthModifier})
 *       and {@code blast_furnace_fuel} ({@code time}), field {@code input};</li>
 *   <li>Silent Gear's {@code silentgear:salvaging/gear}, whose salvage results are
 *       computed from the gear's own material data
 *       ({@code GearSalvagingRecipe.getPossibleResultsForDisplay()} returns an empty
 *       list), field {@code ingredient};</li>
 *   <li>Mekanism's {@code mekanism:energy_conversion}, whose {@code output} is a bare
 *       energy amount in FE rather than an ingredient
 *       ({@code {"input":{…},"output":10000}}) and is shown as text beside the one slot -
 *       so it is not declared and survives verbatim, field {@code input}.</li>
 * </ul>
 *
 * <p>The field must be one item ingredient object; the shipped files write it as
 * {@code {"item":…}} or {@code {"tag":…}}. A recipe that does not carry it is refused
 * rather than guessed at, and so is a patch whose kind is not an item.
 */
final class SingleItemInputSequence implements DerivedSlotSequence {
    /** The field name Immersive Engineering's fertilizer and fuel pages use. */
    static final SingleItemInputSequence INPUT = new SingleItemInputSequence("input");
    /** The field name Silent Gear's gear salvaging writes. */
    static final SingleItemInputSequence INGREDIENT =
            new SingleItemInputSequence("ingredient");

    private static final Set<IngredientKind> ITEM_ONLY =
            Collections.singleton(IngredientKind.ITEM);

    private final String path;

    private SingleItemInputSequence(String path) {
        this.path = path;
    }

    @Override
    public Set<IngredientKind> inputKinds() {
        return ITEM_ONLY;
    }

    @Override
    public Set<IngredientKind> outputKinds() {
        return ITEM_ONLY;
    }

    @Override
    public boolean inputCountFits(int count) {
        return count == 1;
    }

    /** The whole point of this sequence: the page draws no output slot. */
    @Override
    public boolean outputCountFits(int count) {
        return count == 0;
    }

    @Override
    public Optional<List<DerivedSlot>> inputSlots(JsonObject recipe, Recipe<?> loaded) {
        if (recipe == null) {
            return Optional.empty();
        }
        JsonElement ingredient = recipe.get(path);
        if (ingredient == null || !ingredient.isJsonObject()) {
            return Optional.empty();
        }
        return Optional.of(Collections.singletonList(
                DerivedSlot.of(IngredientKind.ITEM, path)));
    }

    @Override
    public Optional<List<DerivedSlot>> outputSlots(JsonObject recipe, Recipe<?> loaded) {
        return Optional.of(Collections.<DerivedSlot>emptyList());
    }
}
