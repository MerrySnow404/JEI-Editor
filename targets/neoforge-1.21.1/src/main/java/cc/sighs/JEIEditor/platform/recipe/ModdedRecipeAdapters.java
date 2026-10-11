package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.IngredientKind;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.SlotPatchFields;
import cc.sighs.JEIEditor.recipe.RecipeFieldMapping;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The declared mod recipe types, plus the simple patch shape every declaration
 * shares. Each entry is verified against the mod's own recipe JSON (field names
 * and JEI slot order) and lives in the per-mod holder for its family; this class
 * only aggregates them, so adding a page never edits a shared list.
 */
public final class ModdedRecipeAdapters {
    public static final List<ModdedRecipeAdapter> ALL;

    static {
        List<ModdedRecipeAdapter> adapters = new ArrayList<ModdedRecipeAdapter>();
        adapters.addAll(CreateRecipeDeclarations.ALL);
        adapters.addAll(ImmersiveEngineeringRecipeDeclarations.ALL);
        adapters.addAll(MekanismRecipeDeclarations.ALL);
        adapters.addAll(MiscRecipeDeclarations.ALL);
        adapters.addAll(SilentGearRecipeDeclarations.ALL);
        adapters.addAll(CobblemonRecipeDeclarations.ALL);
        ALL = Collections.unmodifiableList(adapters);
    }

    private ModdedRecipeAdapters() {
    }

    public static Optional<ModdedRecipeAdapter> forSerializer(String serializerId) {
        if (serializerId == null) {
            return Optional.empty();
        }
        for (ModdedRecipeAdapter adapter : ALL) {
            if (adapter.supports(serializerId)) {
                return Optional.of(adapter);
            }
        }
        return Optional.empty();
    }

    public static boolean supports(String serializerId) {
        return forSerializer(serializerId).isPresent();
    }

    /**
     * The declaration that models one displayed page: a declaration scoped to that
     * exact page uid wins, then one keyed by the recipe's serializer (unless the page
     * is named read-only). A page-scoped declaration does not answer for its
     * serializer, so the two pages of one serializer cannot pick up each other's
     * declaration.
     */
    public static Optional<ModdedRecipeAdapter> forPage(String serializerId, String pageUid) {
        if (pageUid != null) {
            for (ModdedRecipeAdapter adapter : ALL) {
                if (pageUid.equals(adapter.pageUid())) {
                    return Optional.of(adapter);
                }
            }
        }
        for (ModdedRecipeAdapter adapter : ALL) {
            if (adapter.pageUid() == null && adapter.supports(serializerId)) {
                return Optional.of(adapter);
            }
        }
        return Optional.empty();
    }

    /**
     * The ingredient kinds a declaration accepts for one slot, resolved from the
     * declaration alone.
     *
     * <p>A positional list names one kind: the kind of the field the slot key
     * resolves to ({@link RecipeFieldMapping#field}). An ordered segment list names
     * every kind its segments hold, because which segment lands at an output
     * ordinal is only decidable from the recipe JSON - the exact kind is checked
     * against the resolved field while the JSON is rewritten, and a field the
     * declaration does not name resolves to the item default like any other. A
     * recipe-derived slot sequence names every kind its bands hold, with the same
     * reasoning ({@link DerivedSlotSequence}).
     *
     * <p>This is the declaration half of the server's per-slot patch gate
     * ({@code SlotPatchFields.acceptsProperty}), so the client and the server
     * resolve the accepted kinds through one method instead of two copies.
     */
    public static Set<IngredientKind> declaredKinds(ModdedRecipeAdapter adapter, String slotKey) {
        DerivedSlotSequence derived = adapter.derivedSlots();
        if (derived != null) {
            return RecipeFieldMapping.outputIndex(slotKey) >= 0
                    ? derived.outputKinds() : derived.inputKinds();
        }
        if (adapter.concatenatedOutputs() && RecipeFieldMapping.outputIndex(slotKey) >= 0) {
            Set<IngredientKind> kinds = new LinkedHashSet<IngredientKind>();
            for (String field : adapter.outputFields()) {
                kinds.add(adapter.kindOfField(field));
            }
            return kinds;
        }
        return Collections.singleton(adapter.kindOfField(
                RecipeFieldMapping.field(adapter.inputFields(), adapter.outputFields(), slotKey)));
    }

    /** Encodes one item entry in the declaration's own JSON shape. */
    public static JsonObject itemJson(ModdedRecipeAdapter.ItemJsonStyle style, String itemId, int count) {
        JsonObject json = new JsonObject();
        json.addProperty(style == ModdedRecipeAdapter.ItemJsonStyle.ID ? "id" : "item", itemId);
        json.addProperty("count", count);
        return json;
    }

    /**
     * Encodes one declared value in the id/amount keys of {@code style}.
     *
     * <p>A tag is not the same object with {@code "tag"} in place of the style's id
     * key - two things differ, and both follow the mods' own shipped files:
     *
     * <ul>
     *   <li>the <b>type</b>. Create dispatches its fluid ingredients on an explicit
     *       {@code type} name and its single-fluid name does not accept a tag, so
     *       the tag form of that style is {@code neoforge:tag} instead
     *       ({@code mixing/chocolate.json}:
     *       {@code {"type":"neoforge:tag","amount":250,"tag":"c:milk"}}).</li>
     *   <li>the <b>amount</b>. An item tag keeps the tag-alone shape every shipped
     *       file uses ({@code {"tag":"c:plates/steel"}}): the item styles' amount key
     *       is {@code count}, which belongs to a sized <em>ingredient</em> node and is
     *       simply ignored where the tag is read, so writing it would add a key no
     *       file has. A fluid tag is a sized fluid ingredient whose amount the codec
     *       reads (<code>SizedFluidIngredient.FLAT_CODEC</code> reads
     *       {@code "amount"} with the bucket volume as its default), so leaving it out
     *       does not fail - it silently changes the volume the recipe consumes to
     *       1000 mB, which is why the amount is written there.</li>
     * </ul>
     */
    public static JsonObject valueJson(ModdedJsonStyle style, IngredientKind kind, String id, int amount) {
        JsonObject json = new JsonObject();
        if (id != null && id.startsWith("#")) {
            if (style.tagTypeId() != null) {
                json.addProperty("type", style.tagTypeId());
            }
            json.addProperty("tag", id.substring(1));
            if (kind != IngredientKind.ITEM && style.amountKey() != null) {
                json.addProperty(style.amountKey(), amount);
            }
            return json;
        }
        if (style.typeId() != null) {
            json.addProperty("type", style.typeId());
        }
        json.addProperty(style.idKey(), id);
        json.addProperty(style.amountKey(), amount);
        return json;
    }

    /**
     * The JSON value of one declared field: an object with the style's id and amount
     * keys, or - for a style that writes the bare amount, like Immersive
     * Engineering's {@code "creosote": 250} - the amount alone.
     *
     * @param kind the ingredient kind the slot holds, which decides the tag form
     *             (see {@link #valueJson})
     */
    public static JsonElement valueElement(ModdedJsonStyle style, IngredientKind kind, String id, int amount) {
        if (id != null && id.startsWith("#") && style.amountOnly()) {
            // A bare-amount field can only carry a fluid the recipe already names, so there is no
            // way to write a tag into it; refusing beats writing something the mod would reject.
            throw new IllegalArgumentException(
                    "this slot is written as a bare amount, so it cannot take a tag");
        }
        return style.amountOnly()
                ? new JsonPrimitive(Integer.valueOf(amount))
                : valueJson(style, kind, id, amount);
    }

    public static RecipePatch replaceSlot(EditorModel model, String slotKey, EditorIngredient ingredient) {
        return RecipeFieldMapping.outputIndex(slotKey) >= 0
                ? replaceOutput(model, slotKey, ingredient)
                : replaceInput(model, slotKey, ingredient);
    }

    public static RecipePatch replaceInput(EditorModel model, String slotKey, EditorIngredient ingredient) {
        requireEditableSlot(model, slotKey, "input", ingredient,
                "this input slot cannot be replaced");
        return simplePatch(model, slotKey, ingredient);
    }

    /**
     * Replaces an output slot of a declared page. A slot whose representative
     * carries a component patch has none ({@code simpleStack} rejects those), so
     * the editor shows it empty; rewriting it would replace the whole result node
     * with {@code {"id":…,"count":…}} and silently drop those components
     * (Immersive Engineering's coloured bullet flares, for example), so such a
     * slot is refused exactly like the empty output of a JEI-generated page.
     */
    public static RecipePatch replaceOutput(EditorModel model, String slotKey, EditorIngredient ingredient) {
        requireEditableSlot(model, slotKey, "output", ingredient,
                "this page has no editable output");
        if (outputIngredient(model, slotKey) == null) {
            throw new IllegalArgumentException("this output slot holds no item the editor can rewrite");
        }
        return simplePatch(model, slotKey, ingredient);
    }

    /** The item flavour of {@link #replaceInput}, for the item-only callers. */
    public static RecipePatch replaceInput(EditorModel model, String slotKey, ItemStack stack) {
        return replaceInput(model, slotKey, itemIngredient(stack));
    }

    /** The item flavour of {@link #replaceOutput}, for the item-only callers. */
    public static RecipePatch replaceOutput(EditorModel model, String slotKey, ItemStack stack) {
        return replaceOutput(model, slotKey, itemIngredient(stack));
    }

    /**
     * Clears an input slot. Only an item slot has a clear form - the patch empties
     * it with {@code minecraft:air}/{@code 0}, which is the shape the write path
     * understands - so a fluid or chemical slot is refused instead of being handed
     * a patch the server would have to reject.
     */
    public static RecipePatch clearSlot(EditorModel model, String slotKey) {
        EditorSlot slot = slotWithRole(model, slotKey, "input");
        if (slot == null) {
            throw new IllegalArgumentException("this input slot cannot be cleared");
        }
        if (slot.ingredient() != null && !slot.ingredient().isItem()) {
            throw new IllegalArgumentException(
                    "a " + slot.ingredient().kind().id() + " slot cannot be cleared; replace it instead");
        }
        // Only an element a repeatable (%d) pattern named can be removed: a fixed field
        // has no empty form a mod codec accepts, and removing a fixed array element would
        // shrink a list the mod may require - Create's create:item_application page throws
        // "Item Application Recipe has no tool!" once its recipe holds fewer than two
        // ingredients, which breaks JEI's recipe area. The server refuses such a patch;
        // refusing it here keeps the editor from offering it.
        Optional<ModdedRecipeAdapter> adapter = forSerializer(model.serializerId());
        if (adapter.isPresent()) {
            String field = RecipeFieldMapping.field(adapter.get().inputFields(),
                    adapter.get().outputFields(), slotKey);
            if (field != null && !adapter.get().isRepeatField(field)) {
                throw new IllegalArgumentException(
                        "only a variable-arity input list can be cleared");
            }
        }
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(),
                SlotPatchFields.clearItem(slotKey));
    }

    /**
     * Scrolling a slot adjusts its amount: a single-output page keeps the
     * historical {@code output} key, a multi-output page numbers them, and the
     * patch always carries the amount field of the slot's own kind. The same
     * refusal as {@link #replaceOutput} applies, because an amount-only edit also
     * rewrites the whole result node.
     */
    public static RecipePatch setOutputCount(EditorModel model, String slotKey, int count) {
        if (slotWithRole(model, slotKey, "output") == null) {
            throw new IllegalArgumentException("this page has no editable output");
        }
        EditorIngredient ingredient = outputIngredient(model, slotKey);
        if (ingredient == null) {
            throw new IllegalArgumentException("this output slot holds no item the editor can rewrite");
        }
        if (!ingredient.kind().acceptsAmount(count)) {
            throw new IllegalArgumentException(ingredient.kind().id() + " amount must be between "
                    + ingredient.kind().minAmount() + " and " + ingredient.kind().maxAmount());
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(slotKey + "." + ingredient.kind().amountField(), Integer.toString(count));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    /**
     * The representative of an output slot the editor can rewrite, or null when
     * the slot is missing or holds nothing it can express as a simple ingredient.
     */
    static EditorIngredient outputIngredient(EditorModel model, String slotKey) {
        EditorSlot slot = slotWithRole(model, slotKey, "output");
        return slot == null ? null : slot.ingredient();
    }

    /**
     * The kind of ingredient {@code slotKey} holds, from the model the patch was
     * built against, or null when the slot is not part of the model.
     */
    static IngredientKind slotKind(EditorModel model, String slotKey) {
        EditorSlot slot = slotWithRoleAnyRole(model, slotKey);
        return slot == null || slot.ingredient() == null ? null : slot.ingredient().kind();
    }

    private static void requireEditableSlot(EditorModel model, String slotKey, String role,
                                            EditorIngredient ingredient, String missingSlotMessage) {
        EditorSlot slot = slotWithRole(model, slotKey, role);
        if (slot == null) {
            throw new IllegalArgumentException(missingSlotMessage);
        }
        if (ingredient == null) {
            throw new IllegalArgumentException("only simple ingredients can be placed in a declared slot");
        }
        IngredientKind kind = slotKind(model, slotKey);
        if (kind != ingredient.kind()) {
            // The declaration says what this field holds; a mismatch would be a patch
            // the server refuses (or, worse, a fluid written into an item field), so
            // the client refuses it here with the reason.
            throw new IllegalArgumentException(kind == null
                    ? "this slot holds nothing the editor can rewrite"
                    : "this slot holds a " + kind.id() + ", not a " + ingredient.kind().id());
        }
        String fixedId = fixedIdOf(model, slotKey);
        if (fixedId != null && !fixedId.equals(ingredient.id())) {
            // The field writes the amount alone, so the recipe reads whatever fluid its
            // own name implies. Writing another fluid's amount would silently keep that
            // fluid, so only its amount can be changed.
            throw new IllegalArgumentException(
                    "this slot is always " + fixedId + "; only its amount can be changed");
        }
    }

    /**
     * The ingredient id the declaration fixes the slot's field to, or null when the
     * field names its own ingredient (or the client cannot resolve the field, as for
     * an output ordinal of an ordered segment list).
     */
    private static String fixedIdOf(EditorModel model, String slotKey) {
        if (model == null || slotKey == null) {
            return null;
        }
        Optional<ModdedRecipeAdapter> declaration = forSerializer(model.serializerId());
        if (!declaration.isPresent()) {
            return null;
        }
        ModdedRecipeAdapter adapter = declaration.get();
        if (adapter.derivedSlots() != null) {
            // A recipe-derived sequence names its paths only while the JSON is rewritten
            // and never fixes an id: the client cannot resolve which band a slot is in.
            return null;
        }
        if (adapter.concatenatedOutputs() && RecipeFieldMapping.outputIndex(slotKey) >= 0) {
            return null;
        }
        return adapter.fixedIdOfField(RecipeFieldMapping.field(
                adapter.inputFields(), adapter.outputFields(), slotKey));
    }

    private static EditorIngredient itemIngredient(ItemStack stack) {
        Optional<EditorIngredient> ingredient = RecipeAdapterSupport.simpleStack(stack);
        if (!ingredient.isPresent()) {
            throw new IllegalArgumentException("only simple items can be placed in a declared slot");
        }
        return ingredient.get();
    }

    private static RecipePatch simplePatch(EditorModel model, String slotKey, EditorIngredient ingredient) {
        return RecipeAdapterSupport.slotPatch(model, slotKey, ingredient);
    }

    private static EditorSlot slotWithRole(EditorModel model, String slotKey, String role) {
        if (model == null || slotKey == null) {
            return null;
        }
        for (EditorSlot slot : model.slots()) {
            if (slotKey.equals(slot.key()) && role.equals(slot.role())) {
                return slot;
            }
        }
        return null;
    }

    /** A slot of either role, for the kind questions that do not care about the role. */
    private static EditorSlot slotWithRoleAnyRole(EditorModel model, String slotKey) {
        if (model == null || slotKey == null) {
            return null;
        }
        for (EditorSlot slot : model.slots()) {
            if (slotKey.equals(slot.key())) {
                return slot;
            }
        }
        return null;
    }
}
