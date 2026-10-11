package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The slot sequence of Create's sequenced-assembly page, derived from the recipe the
 * way {@code com.simibubi.create.compat.jei.category.SequencedAssemblyCategory.setRecipe}
 * draws it.
 *
 * <p>Evidence (Create 1.21.1-6.0.10, disassembled with {@code javap -c}):
 *
 * <ol>
 *   <li>one INPUT slot for {@code recipe.getIngredient().getItems()} - the base ingredient the
 *       sequence starts from, read from the single JSON key {@code ingredient};</li>
 *   <li>one OUTPUT slot for {@code getResultItem(...)}, which is {@code resultPool.getFirst()}
 *       - the first <em>item</em> entry of the JSON {@code results} array (the whole array's
 *       chances are only summed for the slot's background and tooltip);</li>
 *   <li>then one {@code SequencedAssemblySubCategory} per entry of {@code getSequence()},
 *       laid out left to right, and the sub-category decides whether the step draws a slot:
 *       {@code create:deploying} adds one INPUT for
 *       {@code ProcessingRecipe.getIngredients().get(1)} - the step's <b>second item</b>
 *       entry, the item the deployer holds, not the incomplete item it works on -
 *       {@code create:filling} adds one INPUT for {@code getFluidIngredients().get(0)}, the
 *       step's first fluid entry, drawn through {@code CreateRecipeCategory.addFluidSlot} -
 *       and {@code create:pressing} / {@code create:cutting} add none at all (they only
 *       override {@code draw}). Those four are the only Create recipe types that implement
 *       {@code IAssemblyRecipe#getJEISubCategory}, so a step of any other type is refused
 *       rather than guessed at.</li>
 * </ol>
 *
 * <p>What follows is a page whose slots follow the <em>steps</em> rather than a field list:
 * the base ingredient, then each deploying / filling step's slot in step order. Every slot
 * does have a field of its own, but each sits at its own depth - {@code ingredient} versus
 * {@code sequence.<i>.ingredients.<j>} - so the ordinal-to-field mapping is a list of paths
 * and not a pattern. The three shipped recipes:
 *
 * <pre>
 *   precision_mechanism   1 base + 3 create:deploying    = 4 inputs / 1 output
 *   sturdy_sheet          1 base + 1 create:filling      = 2 inputs / 1 output
 *   track                 1 base + 2 create:deploying    = 3 inputs / 1 output
 * </pre>
 *
 * <p>Create's own {@code ProcessingRecipeParams} splits one {@code ingredients} array into
 * item ingredients and fluid ingredients by each entry's {@code type}
 * ({@link CreateIngredientShapes#split(JsonArray)}), and the sub-categories read the two
 * halves separately, so the derivation classifies every step's array the same way and refuses
 * a step that does not fit. It also refuses an entry that classifies as neither half.
 *
 * <p>Unlike {@link CreateBasinSlotSequence}, this derivation has no mod-side ingredient list
 * to cross-check against: {@code SequencedAssemblyRecipe} keeps every ingredient inside its
 * steps and answers {@code getIngredients()} with the interface's empty default, so the shape
 * being the codec's own is what the derivation leans on. The declared-smoke cases pin the
 * mapping for all three shipped recipes end to end.
 *
 * <p>Known limitation, shared with every other Create page that declares a {@code results}
 * entry: a written result is {@code {"id":…,"count":…}}, so editing the output drops a
 * {@code "chance"} on that entry. The slot the page draws is {@code results}'s first item
 * entry, whose chance is either absent or {@code 120.0} in the shipped files while the
 * codec's default is already a guaranteed drop, so the edit does not change what the recipe
 * produces. {@code loops}, {@code transitional_item} and every step's own {@code results} and
 * {@code type} are undeclared and survive verbatim.
 */
final class CreateSequencedAssemblySlotSequence implements DerivedSlotSequence {
    static final CreateSequencedAssemblySlotSequence INSTANCE =
            new CreateSequencedAssemblySlotSequence();

    private static final String INGREDIENT = "ingredient";
    private static final String SEQUENCE = "sequence";
    private static final String INGREDIENTS = "ingredients";
    private static final String RESULTS = "results";
    private static final String TYPE = "type";
    private static final String ID = "id";

    private static final String DEPLOYING = "create:deploying";
    private static final String FILLING = "create:filling";
    private static final String PRESSING = "create:pressing";
    private static final String CUTTING = "create:cutting";

    private CreateSequencedAssemblySlotSequence() {
    }

    /** Every input slot is one ingredient entry, but which half it is depends on the step. */
    @Override
    public Set<IngredientKind> inputKinds() {
        Set<IngredientKind> kinds = new LinkedHashSet<IngredientKind>();
        kinds.add(IngredientKind.ITEM);
        kinds.add(IngredientKind.FLUID);
        return Collections.unmodifiableSet(kinds);
    }

    @Override
    public Set<IngredientKind> outputKinds() {
        return Collections.singleton(IngredientKind.ITEM);
    }

    /** The base ingredient, plus a slot for every step that draws one. */
    @Override
    public boolean inputCountFits(int count) {
        return count >= 1;
    }

    /** {@code getResultItem} draws exactly one output slot. */
    @Override
    public boolean outputCountFits(int count) {
        return count == 1;
    }

    /**
     * The page lays its slots out along the recipe's steps, so a filling step's fluid slot
     * sits wherever that step is - between two deploying steps' item slots, if that is the
     * sequence. "Every item slot before every fluid slot" is the basin pages' property, not
     * this one's.
     */
    @Override
    public boolean drawsBandsInDisplayOrder() {
        return false;
    }

    @Override
    public Optional<List<DerivedSlot>> inputSlots(JsonObject recipe, Recipe<?> loaded) {
        JsonElement base = recipe == null ? null : recipe.get(INGREDIENT);
        if (base == null || !base.isJsonObject()
                || !CreateIngredientShapes.decodeIngredient(base).isPresent()) {
            return Optional.empty();
        }
        JsonArray steps = CreateIngredientShapes.array(recipe, SEQUENCE).orElse(null);
        if (steps == null) {
            return Optional.empty();
        }
        List<DerivedSlot> slots = new ArrayList<DerivedSlot>(steps.size() + 1);
        slots.add(DerivedSlot.of(IngredientKind.ITEM, INGREDIENT));
        for (int step = 0; step < steps.size(); step++) {
            JsonElement element = steps.get(step);
            if (element == null || !element.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject node = element.getAsJsonObject();
            String type = CreateIngredientShapes.primitive(node, TYPE);
            // Every step's array is classified even when the step draws no slot, so a step
            // whose entries are not the codec's shape refuses the whole patch.
            JsonArray entries = CreateIngredientShapes.array(node, INGREDIENTS).orElse(null);
            Optional<CreateIngredientShapes.Split> split = entries == null
                    ? Optional.<CreateIngredientShapes.Split>empty()
                    : CreateIngredientShapes.split(entries);
            if (type == null || !split.isPresent()) {
                return Optional.empty();
            }
            CreateIngredientShapes.Split classified = split.get();
            if (DEPLOYING.equals(type)) {
                // Item[1]: the held item. The incomplete item at item[0] is the step's input,
                // which the page does not draw.
                if (classified.itemIndices.size() < 2) {
                    return Optional.empty();
                }
                slots.add(DerivedSlot.of(IngredientKind.ITEM,
                        stepPath(step, classified.itemIndices.get(1).intValue())));
            } else if (FILLING.equals(type)) {
                if (classified.fluidIndices.isEmpty()) {
                    return Optional.empty();
                }
                slots.add(DerivedSlot.of(IngredientKind.FLUID,
                        stepPath(step, classified.fluidIndices.get(0).intValue())));
            } else if (!PRESSING.equals(type) && !CUTTING.equals(type)) {
                return Optional.empty();
            }
        }
        return Optional.of(slots);
    }

    @Override
    public Optional<List<DerivedSlot>> outputSlots(JsonObject recipe, Recipe<?> loaded) {
        JsonArray results = CreateIngredientShapes.array(recipe, RESULTS).orElse(null);
        if (results == null) {
            return Optional.empty();
        }
        for (int index = 0; index < results.size(); index++) {
            JsonElement entry = results.get(index);
            // The first entry the codec reads as an item output is the one getResultItem
            // hands back, and the one the page draws.
            if (entry == null || !entry.isJsonObject()
                    || CreateIngredientShapes.isFluidResult(entry)
                    || CreateIngredientShapes.primitive(entry.getAsJsonObject(), ID) == null) {
                continue;
            }
            return Optional.of(Collections.singletonList(
                    DerivedSlot.of(IngredientKind.ITEM, RESULTS + "." + index)));
        }
        return Optional.empty();
    }

    /** The JSON path of one {@code ingredients} entry of one {@code sequence} step. */
    private static String stepPath(int step, int entry) {
        return SEQUENCE + "." + step + "." + INGREDIENTS + "." + entry;
    }
}
