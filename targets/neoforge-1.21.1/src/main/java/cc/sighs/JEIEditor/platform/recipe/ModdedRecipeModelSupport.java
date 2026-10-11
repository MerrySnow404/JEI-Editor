package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.IngredientKind;
import cc.sighs.JEIEditor.recipe.RecipeFieldMapping;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the editor model of a declared mod recipe page from the slot views of
 * the displayed JEI layout, so a declaration never needs per-mod reading code.
 *
 * <p>One editor slot is created per real INPUT slot, in the order the layout
 * shows them, plus one per real OUTPUT slot: {@code output} for a single-output
 * page and {@code output.0 .. output.M-1} for a page with several (IE's crusher
 * adds one per {@code secondaries} entry, Create's {@code results} array can hold
 * two, Horse Powered's {@code secondary} is optional). JEI's ingredient supplier
 * ({@link cc.sighs.JEIEditor.client.JeiRecipeIntrospection#recipeIngredients})
 * cannot be used for this: it flattens every slot of a role into one list with
 * one entry per candidate stack, so a single slot holding a 24-member tag would
 * look like 24 slots. The slot view list is the same list, in the same order,
 * that {@link CraftingSlotMapper} uses to resolve slot keys, so the model and
 * the drag targeting agree.
 *
 * <p>A slot's representative is the first candidate that matches the kind its
 * declared field holds - an item, a platform fluid stack or a Mekanism chemical
 * stack - and, when the declaration fixes the field's ingredient id, that id as
 * well. A page whose slot displays a kind the declaration does not name for that
 * field gets no representative, so the slot is shown but stays read-only rather
 * than producing a patch the server would refuse.
 *
 * <p>An ordered segment list has no single field per output ordinal - which one
 * lands there only the recipe JSON decides - so every kind its segments can hold
 * is acceptable for such a slot and the ingredient the page really shows decides
 * which one it is. That is what lets {@code mekanism:reaction} model a recipe
 * whose only output is the chemical one.
 */
public final class ModdedRecipeModelSupport {
    private ModdedRecipeModelSupport() {
    }

    public static Optional<EditorModel> createModel(IRecipeSlotsView slots, String recipeId, String serializerId,
                                                    ResourceLocation pageUid) {
        return createModel(slots, recipeId, serializerId, pageUid, null);
    }

    /**
     * The same, with the recipe the mod loaded.
     *
     * <p>{@code loaded} is what a recipe-derived sequence needs to say how much of the page
     * its fields really cover ({@link DerivedSlotSequence#fieldBackedInputOrdinals}): a page
     * that draws slots no field names - the campfire pot's seasoning previews - marks them
     * read-only instead of offering a drop the server would refuse. Callers that have no
     * recipe object pass null, which keeps every slot field-backed.
     */
    public static Optional<EditorModel> createModel(IRecipeSlotsView slots, String recipeId, String serializerId,
                                                    ResourceLocation pageUid, Recipe<?> loaded) {
        // A page-scoped declaration answers for its own uid only, so the lookup is by
        // page first and by the recipe's serializer after that.
        Optional<ModdedRecipeAdapter> declared = ModdedRecipeAdapters.forPage(serializerId,
                pageUid == null ? null : pageUid.toString());
        if (recipeId == null || slots == null || !declared.isPresent()) {
            return Optional.empty();
        }
        ModdedRecipeAdapter adapter = declared.get();
        // A page can share a serializer with the declared one and still be
        // unwritable (its recipes are generated at reload time and have no
        // recipe JSON of their own), so the declaration names those pages.
        if (pageUid != null && adapter.readOnlyPageUids().contains(pageUid.toString())) {
            return Optional.empty();
        }

        List<IRecipeSlotView> inputs = new ArrayList<IRecipeSlotView>();
        List<IRecipeSlotView> outputs = new ArrayList<IRecipeSlotView>();
        for (IRecipeSlotView slot : slots.getSlotViews()) {
            if (slot.getRole() == RecipeIngredientRole.INPUT) {
                inputs.add(slot);
            } else if (slot.getRole() == RecipeIngredientRole.OUTPUT) {
                outputs.add(slot);
            }
            // CATALYST and RENDER_ONLY slots carry no declared JSON field.
        }

        // The declaration is only trusted when it matches the page: input and
        // output counts the declaration cannot name mean the JSON fields would
        // not line up with what the user sees.
        Optional<DerivedSlotSequence> derived = Optional.ofNullable(adapter.derivedSlots());
        Optional<List<String>> keys;
        if (derived.isPresent()) {
            // A recipe-derived sequence cannot name a slot at all on the client - the
            // derivation needs the recipe JSON - so the page only has to satisfy the
            // count bound the sequence itself promises; the server resolves the paths
            // of the ordinals the patch addresses and refuses the whole patch when the
            // recipe does not reach them.
            keys = derived.get().inputCountFits(inputs.size())
                    && derived.get().outputCountFits(outputs.size())
                    ? Optional.of(RecipeFieldMapping.slotKeys(inputs.size(), outputs.size()))
                    : Optional.<List<String>>empty();
        } else {
            keys = RecipeFieldMapping.declaredSlotKeys(
                    inputs.size(), outputs.size(), adapter.inputFields(), adapter.outputFields(),
                    adapter.concatenatedOutputs());
        }
        if (!keys.isPresent()) {
            return Optional.empty();
        }

        List<EditorSlot> editorSlots = new ArrayList<EditorSlot>();
        Map<String, String> properties = new LinkedHashMap<String, String>();
        // A recipe-derived sequence may cover only some of the page's input slots: the rest
        // are slots the page draws that no field names (see fieldBackedInputOrdinals), and
        // they are modelled empty so the editor offers no drag the server would refuse.
        Set<Integer> fieldBacked = derived.isPresent()
                ? derived.get().fieldBackedInputOrdinals(loaded, inputs.size()) : null;
        for (int index = 0; index < inputs.size(); index++) {
            String key = keys.get().get(index);
            boolean editable = fieldBacked == null || fieldBacked.contains(Integer.valueOf(index));
            editorSlots.add(new EditorSlot(key, "input", editable
                    ? representative(inputs.get(index),
                            declaredKinds(adapter, key, false), requiredId(adapter, key, false)).orElse(null)
                    : null));
            // The slot mapper already reads this prefix; declaring it keeps the
            // hover/drop mapping deterministic when JEI's order is not obvious.
            properties.put(JeiVanillaRecipeEditorAdapter.VISUAL_INPUT_PROPERTY_PREFIX + index, key);
        }
        for (int index = 0; index < outputs.size(); index++) {
            String key = keys.get().get(inputs.size() + index);
            editorSlots.add(new EditorSlot(key, "output", representative(outputs.get(index),
                    declaredKinds(adapter, key, true), requiredId(adapter, key, true)).orElse(null)));
        }

        // The model carries the declaration's own key. For a page-scoped declaration
        // that is the page uid: the two pages of one serializer differ only in their
        // slot roles, so the serializer alone cannot name the direction a patch is
        // meant for, and the server selects the declaration by the key it is sent.
        String declarationKey = adapter.scopeKey();
        String fingerprint = RecipeAdapterSupport.fingerprint(recipeId, declarationKey, editorSlots, properties);
        return Optional.of(new EditorModel(recipeId, declarationKey, fingerprint, editorSlots, properties));
    }

    /**
     * The kinds one declared slot can hold.
     *
     * <p>A positional list resolves the slot key to exactly one field, so the
     * declaration names one kind. An ordered segment list cannot - which field lands
     * at an output ordinal only the recipe JSON decides, and the client has none - so
     * every kind its segments declare is acceptable there and the ingredient the page
     * shows picks between them; the server, which does have the JSON, checks the
     * patch against the kind of the field the concatenation really resolves to. A
     * recipe-derived slot sequence is the same for both roles: which band an ordinal
     * falls into is the recipe's business, so every kind the sequence names is
     * acceptable and the ingredient the page shows decides.
     */
    private static Set<IngredientKind> declaredKinds(ModdedRecipeAdapter adapter, String slotKey, boolean output) {
        if (adapter.derivedSlots() != null) {
            return output ? adapter.derivedSlots().outputKinds() : adapter.derivedSlots().inputKinds();
        }
        if (output && adapter.concatenatedOutputs() && RecipeFieldMapping.outputIndex(slotKey) >= 0) {
            Set<IngredientKind> kinds = new LinkedHashSet<IngredientKind>();
            for (String field : adapter.outputFields()) {
                kinds.add(adapter.kindOfField(field));
            }
            return kinds;
        }
        String field = RecipeFieldMapping.field(adapter.inputFields(), adapter.outputFields(), slotKey);
        return Collections.singleton(adapter.kindOfField(field));
    }

    /**
     * The ingredient id the declaration fixes the slot's field to, or null. An
     * output ordinal of an ordered segment list has no single field, so it has no
     * fixed id either, and a recipe-derived sequence never fixes one.
     */
    private static String requiredId(ModdedRecipeAdapter adapter, String slotKey, boolean output) {
        if (adapter.derivedSlots() != null) {
            return null;
        }
        if (output && adapter.concatenatedOutputs() && RecipeFieldMapping.outputIndex(slotKey) >= 0) {
            return null;
        }
        return adapter.fixedIdOfField(RecipeFieldMapping.field(
                adapter.inputFields(), adapter.outputFields(), slotKey));
    }

    /**
     * The first candidate of the slot that can be written as an ingredient of one of
     * {@code kinds} - and of {@code requiredId} when the declaration fixes the id -
     * or empty when the slot is empty or holds nothing the editor can express.
     *
     * <p>A candidate of another kind is skipped rather than written into the field:
     * the declaration says what the field holds, so a slot whose displayed
     * ingredient disagrees stays read-only instead of producing a patch the server
     * would refuse.
     *
     * <p>{@code getDisplayedItemStack()} is deliberately not used: JEI rotates
     * that stack from the wall clock ({@code CycleTimer} derives its index from
     * {@code System.currentTimeMillis() / 1000}), so a tag slot would hand the
     * model a different item every second. The fingerprint and the slot contents
     * would then change between two edits of the same recipe, and
     * {@code RecipeEditSession} merges a second patch only while the fingerprints
     * still match. The first candidate matches what every other adapter uses
     * ({@code JeiVanillaRecipeEditorAdapter.first} / {@code simpleIngredient}).
     */
    private static Optional<EditorIngredient> representative(IRecipeSlotView slot, Set<IngredientKind> kinds,
                                                             String requiredId) {
        for (ITypedIngredient<?> candidate : slot.getAllIngredientsList()) {
            Optional<EditorIngredient> ingredient = RecipeAdapterSupport.editorIngredient(candidate);
            if (!ingredient.isPresent() || !kinds.contains(ingredient.get().kind())) {
                continue;
            }
            if (requiredId != null && !requiredId.equals(ingredient.get().id())) {
                continue;
            }
            return ingredient;
        }
        return Optional.empty();
    }
}
