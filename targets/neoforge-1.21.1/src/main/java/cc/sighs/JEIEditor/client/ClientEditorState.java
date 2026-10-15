package cc.sighs.JEIEditor.client;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipeEditSession;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.IngredientKind;
import cc.sighs.JEIEditor.editor.RecipePatchSemantics;
import cc.sighs.JEIEditor.editor.SlotPatchFields;
import cc.sighs.JEIEditor.editor.TagPatchFields;
import cc.sighs.JEIEditor.platform.fuel.FuelOverrideState;
import cc.sighs.JEIEditor.platform.recipe.CookingRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.CraftingSlotMapper;
import cc.sighs.JEIEditor.platform.recipe.FuelRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.JeiVanillaRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeCreationAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeEditorAdapters;
import cc.sighs.JEIEditor.platform.recipe.TagEditorAdapter;
import cc.sighs.JEIEditor.recipe.RecipeFieldMapping;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.WeakHashMap;
import java.util.Locale;
import java.util.regex.Pattern;

/** Client-only state for the first editor interaction slice. */
public final class ClientEditorState {
    private static final Pattern RECIPE_PATH = Pattern.compile("[a-z0-9._/-]+");
    private static final String RECIPE_NAMESPACE = "jeieditor";
    /**
     * The tag path a staged new tag carries until the page bar's id input names it. A
     * valid tag path on purpose: every step in between - the page bar, the
     * pending-patch lookup, the fingerprint - then works on a well-formed patch, and
     * the save is refused while the input is still empty, so the placeholder is never
     * submitted.
     */
    private static final String NEW_TAG_PLACEHOLDER = "new_tag";
    private static String lastDrop = "";
    // Keep the UI switch independent from the pending-edit session. JEI may reset
    // or recreate recipe state while the recipe screen remains open.
    private static boolean editing;
    // A button press can be delivered more than once while JEI rebuilds its
    // widget tree. Re-arm only after the physical mouse button is released.
    private static boolean toggleArmed = true;
    // Guard all editor buttons against duplicate press routing for one click.
    private static boolean actionClickArmed = true;
    private static final RecipeEditSession session = new RecipeEditSession();
    private static final Map<String, RecipePatch> submittedSavePatches =
            new LinkedHashMap<String, RecipePatch>();
    private static final Map<Screen, Set<String>> newRecipePageIds =
            new WeakHashMap<Screen, Set<String>>();
    /** Draft models keyed by the screen that owns their source JEI layout. */
    private static final Map<Screen, Map<String, EditorModel>> creationDrafts =
            new WeakHashMap<Screen, Map<String, EditorModel>>();
    /**
     * The staged new tag of each screen: the encoded recipe id its
     * {@code tag.create} patch currently carries. A new tag has no source page and no
     * draft model - the page it was seeded from shows the tag it came from - so only
     * the patch's own id is remembered, which is what the page bar's id input renames.
     */
    private static final Map<Screen, String> tagCreateDrafts = new WeakHashMap<Screen, String>();
    private static EditorModel lastModel;
    private static String lastSlotKey;
    private static IRecipeSlotsView lastRecipeSlots;
    private static Object lastRecipe;
    private static final List<IRecipeSlotDrawable> previewSlots = new ArrayList<IRecipeSlotDrawable>();
    private static final List<Rect2i> ghostHighlightAreas = new ArrayList<Rect2i>();

    private ClientEditorState() {
    }

    static boolean isEditing() {
        return editing;
    }

    static void toggle() {
        editing = !editing;
    }

    /** Accept at most one toggle callback for each physical mouse press. */
    static boolean toggleFromButton() {
        if (!toggleArmed) {
            return false;
        }
        toggleArmed = false;
        toggle();
        return true;
    }

    static void armToggle() {
        toggleArmed = true;
    }

    static boolean consumeActionClick() {
        if (!actionClickArmed) {
            return false;
        }
        actionClickArmed = false;
        return true;
    }

    static void armActionClick() {
        actionClickArmed = true;
    }

    static void setLastDrop(String value) {
        lastDrop = value;
    }

    static String getLastDrop() {
        return lastDrop;
    }

    static RecipePatch getPendingPatch() {
        return session.pending();
    }

    static List<RecipePatch> getPendingPatches() {
        return session.pendingPatches();
    }

    static RecipePatch getPendingPatch(String recipeId) {
        for (RecipePatch patch : session.pendingPatches()) {
            if (patch.recipeId().equals(recipeId)) {
                return patch;
            }
        }
        return null;
    }

    static void setPendingPatch(RecipePatch patch) {
        session.apply(patch);
        refreshPreview();
    }

    /**
     * Stages one tag operation, replacing whatever the same tag was staged for.
     *
     * <p>A tag patch carries the whole operation ({@code tag.op} plus its element), so
     * two gestures on one tag are mutually exclusive - adding a member and then
     * deleting the tag is one edit, not two. {@link RecipeEditSession#apply} would
     * merge their fields instead, which for a delete means keeping the {@code
     * tag.element} of the operation before it; the tag contract ignores an element on a
     * delete, but the staged patch should be what the user's last gesture produced.
     */
    static void setTagPatch(RecipePatch patch) {
        session.replace(patch);
        refreshPreview();
    }

    static void deleteRecipe(EditorModel model) {
        session.replace(RecipePatchSemantics.delete(model));
        clearPreview();
    }

    static void createRecipe(Screen screen, EditorModel model) {
        session.apply(RecipeCreationAdapter.createPatch(model));
        Set<String> ids = newRecipePageIds.get(screen);
        if (ids == null) {
            ids = new HashSet<String>();
            newRecipePageIds.put(screen, ids);
        }
        ids.add(model.recipeId());
        Map<String, EditorModel> drafts = creationDrafts.get(screen);
        if (drafts == null) {
            drafts = new LinkedHashMap<String, EditorModel>();
            creationDrafts.put(screen, drafts);
        }
        drafts.put(model.recipeId(), model);
        clearPreview();
    }

    /** Returns the staged blank model whose source is the visible recipe. */
    static Optional<EditorModel> creationModelFor(Screen screen, String sourceRecipeId) {
        Map<String, EditorModel> drafts = creationDrafts.get(screen);
        if (drafts == null || sourceRecipeId == null) {
            return Optional.empty();
        }
        EditorModel match = null;
        for (EditorModel draft : drafts.values()) {
            if (sourceRecipeId.equals(draft.properties().get(RecipeCreationAdapter.SOURCE_RECIPE_FIELD))) {
                match = draft;
            }
        }
        return Optional.ofNullable(match);
    }

    static boolean isRecipeDeleted(String recipeId) {
        return RecipePatchSemantics.isDeletion(getPendingPatch(recipeId));
    }

    static boolean isCreationStaged(String recipeId) {
        return RecipePatchSemantics.isCreation(getPendingPatch(recipeId));
    }

    static boolean hasCreationDraft(Screen screen) {
        Map<String, EditorModel> drafts = creationDrafts.get(screen);
        if (drafts == null || drafts.isEmpty()) {
            return false;
        }
        for (String recipeId : drafts.keySet()) {
            if (isCreationStaged(recipeId)) {
                return true;
            }
        }
        return false;
    }

    static boolean cancelCreationDraft(Screen screen, String recipeId) {
        Map<String, EditorModel> drafts = creationDrafts.get(screen);
        if (drafts == null || recipeId == null || !drafts.containsKey(recipeId)
                || (!isCreationStaged(recipeId) && !isRecipeDeleted(recipeId))) {
            return false;
        }
        session.remove(java.util.Collections.singleton(recipeId));
        drafts.remove(recipeId);
        if (drafts.isEmpty()) {
            creationDrafts.remove(screen);
        }
        Set<String> pageIds = newRecipePageIds.get(screen);
        if (pageIds != null) {
            pageIds.remove(recipeId);
            if (pageIds.isEmpty()) {
                newRecipePageIds.remove(screen);
            }
        }
        clearPreview();
        return true;
    }

    /** Renames the staged creation patch using the path typed in the page bar. */
    static String renameCreationDraft(Screen screen, String enteredPath) {
        String path = enteredPath == null ? "" : enteredPath.trim();
        if (path.isEmpty()) {
            return "Enter a recipe ID first";
        }
        if (path.indexOf(':') >= 0 || !RECIPE_PATH.matcher(path).matches()) {
            return "Use only the recipe path; namespace is jeieditor";
        }
        path = path.toLowerCase(Locale.ROOT);
        Map<String, EditorModel> drafts = creationDrafts.get(screen);
        if (drafts == null || drafts.isEmpty()) {
            return "Create a new recipe first";
        }
        if (drafts.size() > 1) {
            return "Only one new recipe can be named at a time";
        }
        String oldId = drafts.keySet().iterator().next();
        RecipePatch current = getPendingPatch(oldId);
        EditorModel oldModel = drafts.get(oldId);
        if (current == null || oldModel == null || !RecipePatchSemantics.isCreation(current)) {
            return "Create a new recipe first";
        }
        String newId = RECIPE_NAMESPACE + ":" + path;
        if (getPendingPatch(newId) != null && !newId.equals(oldId)) {
            return "That recipe ID is already being edited";
        }
        EditorModel renamed = new EditorModel(newId, oldModel.serializerId(),
                "new:" + newId, oldModel.slots(), oldModel.properties());
        RecipePatch replacement = new RecipePatch(newId, current.serializerId(),
                "new:" + newId, current.fields());
        session.remove(java.util.Collections.singleton(oldId));
        session.apply(replacement);
        drafts.remove(oldId);
        drafts.put(newId, renamed);
        Set<String> pageIds = newRecipePageIds.get(screen);
        if (pageIds != null) {
            pageIds.remove(oldId);
            pageIds.add(newId);
        }
        return "";
    }

    static void cancelRecipeDeletion(String recipeId) {
        session.remove(java.util.Collections.singleton(recipeId));
        clearPreview();
    }

    /**
     * Stages a new tag whose only member is {@code elementId}, named by a placeholder
     * id until the page bar's id input names it.
     *
     * <p>The name cannot be asked for before the patch exists - the patch is what the
     * id input renames - and a placeholder that is a valid tag id keeps every step in
     * between (the page bar, the pending-patch lookup, the save) working on a
     * well-formed patch. Nothing is submitted until the user saves, and
     * {@link #renameTagCreateDraft} refuses that save while the placeholder is still
     * in place.
     */
    static void createTag(Screen screen, EditorModel model, String elementId) {
        String registry = TagEditorAdapter.registryOf(model);
        if (registry == null) {
            throw new IllegalArgumentException("this page is not an editable tag");
        }
        RecipePatch patch = TagEditorAdapter.createTag(model,
                elementId).orElseThrow(() -> new IllegalArgumentException(
                        "this tag cannot hold " + elementId));
        RecipePatch staged = new RecipePatch(
                TagPatchFields.recipeId(registry, RECIPE_NAMESPACE + ":" + NEW_TAG_PLACEHOLDER),
                patch.serializerId(), patch.baseFingerprint(), patch.fields());
        session.replace(staged);
        tagCreateDrafts.put(screen, staged.recipeId());
        clearPreview();
    }

    /** Whether a new tag is staged and still waiting for the id input to name it. */
    static boolean hasTagCreateDraft(Screen screen) {
        return tagCreateDrafts.containsKey(screen);
    }

    /**
     * Renames the staged new tag using the path typed in the page bar, or returns the
     * message to show instead. The namespace is forced to {@link #RECIPE_NAMESPACE}
     * exactly as a created recipe's is, because the id input draws that namespace and
     * only the path is typed.
     */
    static String renameTagCreateDraft(Screen screen, String enteredPath) {
        String oldId = tagCreateDrafts.get(screen);
        if (oldId == null) {
            return "Create a new tag first";
        }
        String path = enteredPath == null ? "" : enteredPath.trim();
        if (path.isEmpty()) {
            return "Enter a tag ID first";
        }
        if (path.indexOf(':') >= 0 || !RECIPE_PATH.matcher(path).matches()) {
            return "Use only the tag path; namespace is jeieditor";
        }
        RecipePatch current = getPendingPatch(oldId);
        Optional<TagPatchFields.Target> target = current == null
                ? Optional.<TagPatchFields.Target>empty() : TagPatchFields.target(current);
        if (!target.isPresent()) {
            tagCreateDrafts.remove(screen);
            return "Create a new tag first";
        }
        String newId = TagPatchFields.recipeId(target.get().registry(),
                RECIPE_NAMESPACE + ":" + path.toLowerCase(Locale.ROOT));
        if (getPendingPatch(newId) != null && !newId.equals(oldId)) {
            return "That tag ID is already being edited";
        }
        session.remove(java.util.Collections.singleton(oldId));
        session.replace(new RecipePatch(newId, current.serializerId(), current.baseFingerprint(),
                current.fields()));
        tagCreateDrafts.put(screen, newId);
        return "";
    }

    static void clearPendingPatch() {
        session.reset();
        creationDrafts.clear();
        tagCreateDrafts.clear();
        newRecipePageIds.clear();
        clearPreview();
        lastModel = null;
        lastSlotKey = null;
        lastRecipeSlots = null;
        lastRecipe = null;
    }

    static void clearPendingPatches(java.util.Collection<String> recipeIds) {
        session.remove(recipeIds);
        clearPreview();
        lastModel = null;
        lastSlotKey = null;
        lastRecipeSlots = null;
        lastRecipe = null;
    }

    static void clearPendingPatches(Screen screen, java.util.Collection<String> recipeIds) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<String>();
        if (recipeIds != null) {
            ids.addAll(recipeIds);
        }
        Set<String> created = newRecipePageIds.remove(screen);
        if (created != null) {
            ids.addAll(created);
        }
        // A staged new tag has no page to be found on, so its id is only reachable
        // through the draft: resetting the current page has to drop it by name.
        String tagDraft = tagCreateDrafts.remove(screen);
        if (tagDraft != null) {
            ids.add(tagDraft);
        }
        creationDrafts.remove(screen);
        clearPendingPatches(ids);
    }

    static void undo() {
        session.undo();
        refreshPreview();
    }

    static void redo() {
        session.redo();
        refreshPreview();
    }

    static void closeRecipeScreen() {
        clearPendingPatch();
    }

    static void markSaveSubmittedPatches(List<RecipePatch> patches) {
        if (patches != null) {
            for (RecipePatch patch : patches) {
                submittedSavePatches.put(patch.recipeId(), patch);
            }
        }
    }

    static void rememberTarget(EditorModel model, String slotKey, IRecipeSlotsView slots, Object recipe) {
        lastModel = model;
        lastSlotKey = slotKey;
        lastRecipeSlots = slots;
        lastRecipe = recipe;
    }

    static IRecipeSlotsView getLastRecipeSlots() {
        return lastRecipeSlots;
    }

    /** Reapply the local preview after JEI rebuilds or cycles its slot displays. */
    static void refreshPreview() {
        clearPreview();
        if (lastRecipeSlots != null && lastModel != null && session.pending() != null) {
            applyPreview(lastRecipeSlots, lastModel, session.pending(), lastRecipe);
        }
    }

    static void beginPreviewRefresh() {
        clearPreview();
    }

    static void applyPreview(IRecipeSlotsView slots, EditorModel model, RecipePatch patch,
                             Object displayedRecipe) {
        if (slots == null || model == null || patch == null) {
            return;
        }
        for (IRecipeSlotView view : slots.getSlotViews()) {
            if (!(view instanceof IRecipeSlotDrawable)) {
                continue;
            }
            IRecipeSlotDrawable drawable = (IRecipeSlotDrawable) view;
            String slotKey = CraftingSlotMapper.slotKey(model, displayedRecipe, slots.getSlotViews(), view);
            if (view.getRole() != RecipeIngredientRole.INPUT
                    && view.getRole() != RecipeIngredientRole.OUTPUT) {
                continue;
            }
            if (slotKey == null || !SlotPatchFields.touches(patch.fields(), slotKey)) {
                // Leave untouched slots under JEI's own display pipeline. An
                // empty override would hide their original ingredient.
                continue;
            }
            previewSlots.add(drawable);
            if (JeiVanillaRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
                net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
                Optional<ItemStack> stack = JeiVanillaRecipeEditorAdapter.patchedStack(
                        model, slotKey, patch,
                        minecraft.level == null ? null : minecraft.level.registryAccess());
                if (stack.isPresent()) {
                    drawable.createDisplayOverrides().addItemStack(stack.get());
                } else {
                    drawable.createDisplayOverrides();
                }
                continue;
            }
            EditorIngredient ingredient = SlotPatchFields.patched(patch.fields(), slotKey,
                    originalIngredient(model, slotKey));
            if (ingredient == null || !addPreview(drawable, ingredient)) {
                // An empty override is different from no override: without it
                // JEI falls back to the recipe's original ingredient and the
                // item remains visible until the server reloads the recipe.
                drawable.createDisplayOverrides();
            }
        }
    }

    /**
     * Shows an edited ingredient in a slot, in its own kind: an item stack, a
     * platform fluid stack, or the registered JEI ingredient whose resource id the
     * patch names (a Mekanism chemical, which the editor targets without compiling
     * against it). False when nothing can be shown, so the caller can fall back to
     * an empty override.
     */
    private static boolean addPreview(IRecipeSlotDrawable drawable, EditorIngredient ingredient) {
        if (TagIngredientRegistry.isTagId(ingredient.id())) {
            // A tag cannot be resolved from the registry, so it shows its representative member.
            // Without this the slot would render empty, which reads as "cleared" rather than
            // "any of these".
            Optional<ItemStack> representative =
                    TagIngredientRegistry.representative(ingredient.kind(), ingredient.id());
            if (!representative.isPresent()) {
                return false;
            }
            drawable.createDisplayOverrides().addItemStack(representative.get());
            return true;
        }
        if (ingredient.isItem()) {
            ResourceLocation id = ResourceLocation.tryParse(ingredient.id());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                return false;
            }
            drawable.createDisplayOverrides().addItemStack(
                    new ItemStack(BuiltInRegistries.ITEM.get(id), ingredient.amount()));
            return true;
        }
        if (ingredient.kind() == IngredientKind.FLUID) {
            ResourceLocation id = ResourceLocation.tryParse(ingredient.id());
            if (id == null || !BuiltInRegistries.FLUID.containsKey(id)) {
                return false;
            }
            drawable.createDisplayOverrides().addFluidStack(
                    BuiltInRegistries.FLUID.get(id), ingredient.amount());
            return true;
        }
        Optional<ITypedIngredient<?>> typed = cc.sighs.JEIEditor.platform.recipe.RecipeAdapterSupport
                .typedIngredient(cc.sighs.JEIEditor.platform.recipe.RecipeAdapterSupport
                        .CHEMICAL_TYPE_UID, ingredient.id());
        if (!typed.isPresent()) {
            return false;
        }
        drawable.createDisplayOverrides().addTypedIngredient(typed.get());
        return true;
    }

    static void clearPreview() {
        for (IRecipeSlotDrawable slot : previewSlots) {
            slot.clearDisplayOverrides();
        }
        previewSlots.clear();
    }

    static void setGhostHighlightAreas(List<Rect2i> areas) {
        ghostHighlightAreas.clear();
        if (areas != null) {
            ghostHighlightAreas.addAll(areas);
        }
    }

    static void clearGhostHighlightAreas() {
        ghostHighlightAreas.clear();
    }

    static void drawGhostHighlights(GuiGraphics graphics, double mouseX, double mouseY) {
        for (Rect2i area : ghostHighlightAreas) {
            boolean hovered = mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                    && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight();
            graphics.fill(area.getX(), area.getY(), area.getX() + area.getWidth(),
                    area.getY() + area.getHeight(), hovered ? 0x4849c978 : 0x3039b86b);
        }
    }

    /** The model's own ingredient of one slot, or null when it holds none. */
    private static EditorIngredient originalIngredient(EditorModel model, String slotKey) {
        EditorSlot originalSlot = model.slots().stream()
                .filter(slot -> slot.key().equals(slotKey))
                .findFirst()
                .orElse(null);
        return originalSlot == null ? null : originalSlot.ingredient();
    }

    static EditorModel getLastModel() {
        return lastModel;
    }

    static String getLastSlotKey() {
        return lastSlotKey;
    }

    /**
     * Whether the editor can resize that output slot. A slot whose item JEI
     * cannot express as a simple stack (a component-carrying result, for example)
     * is shown empty and cannot be rewritten, exactly like the empty output of a
     * JEI-generated page.
     */
    static boolean canResizeOutput(String slotKey) {
        if (lastModel == null || RecipeFieldMapping.outputIndex(slotKey) < 0) {
            return false;
        }
        for (EditorSlot slot : lastModel.slots()) {
            if (slotKey.equals(slot.key()) && "output".equals(slot.role())) {
                return slot.ingredient() != null;
            }
        }
        return false;
    }

    static RecipePatch adjustOutputCount(String slotKey, int delta) {
        if (!canResizeOutput(slotKey)) {
            return null;
        }
        EditorIngredient ingredient = null;
        for (EditorSlot slot : lastModel.slots()) {
            if (slotKey.equals(slot.key()) && slot.ingredient() != null) {
                ingredient = slot.ingredient();
                break;
            }
        }
        if (ingredient == null) {
            return null;
        }
        // The amount field and its range belong to the slot's kind: a fluid or
        // chemical output is scrolled in millibuckets, not in stack sizes.
        String amountField = ingredient.kind().amountField();
        int current = ingredient.amount();
        if (session.pending() != null && session.pending().recipeId().equals(lastModel.recipeId())) {
            String value = session.pending().fields().get(slotKey + "." + amountField);
            if (value != null) {
                try {
                    current = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        int next = current + delta;
        if (!ingredient.kind().acceptsAmount(next)) {
            return null;
        }
        return RecipeEditorAdapters.setOutputCount(lastModel, slotKey, next);
    }

    /** The amount range of an output slot's kind, for the refusal message. */
    static IngredientKind lastOutputKind() {
        if (lastModel == null) {
            return null;
        }
        for (EditorSlot slot : lastModel.slots()) {
            if ("output".equals(slot.role()) && slot.ingredient() != null) {
                return slot.ingredient().kind();
            }
        }
        return null;
    }

    static void setFuelBurnTime(EditorModel model, int burnTime) {
        if (model == null || !FuelRecipeEditorAdapter.SERIALIZER.equals(model.serializerId())) {
            return;
        }
        RecipePatch pending = getPendingPatch(model.recipeId());
        String baseFingerprint = pending == null
                ? model.baseFingerprint() : pending.baseFingerprint();
        setPendingPatch(FuelRecipeEditorAdapter.setBurnTime(model, burnTime, baseFingerprint));
    }

    static RecipePatch adjustExperience(float delta) {
        if (lastModel == null || !CookingRecipeEditorAdapter.supportsSerializer(lastModel.serializerId())) return null;
        float current = propertyFloat("experience", 0.0F);
        float next = Math.round((current + delta) * 10.0F) / 10.0F;
        return next < 0.0F || next > 1000.0F ? null : RecipeEditorAdapters.setExperience(lastModel, next);
    }
    static RecipePatch adjustCookingTime(int delta) {
        if (lastModel == null || !CookingRecipeEditorAdapter.supportsSerializer(lastModel.serializerId())) return null;
        int next = propertyInt("cooking_time", 1) + delta;
        return next < 1 || next > 1000000 ? null : RecipeEditorAdapters.setCookingTime(lastModel, next);
    }
    private static float propertyFloat(String key, float fallback) {
        String value = session.pending() == null ? null : session.pending().fields().get("recipe." + key); if (value == null) value = lastModel.properties().get(key);
        try { return value == null ? fallback : Float.parseFloat(value); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static int propertyInt(String key, int fallback) {
        String value = session.pending() == null ? null : session.pending().fields().get("recipe." + key); if (value == null) value = lastModel.properties().get(key);
        try { return value == null ? fallback : Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    public static void applyResult(boolean success, String message, String recipeId) {
        lastDrop = message + (recipeId.isEmpty() ? "" : ": " + recipeId);
        if (!success) {
            submittedSavePatches.clear();
        }
        if (success && message.startsWith("Saved ")) {
            // Save persists the patch but deliberately leaves it pending so
            // the current page still shows the blue modified state until the
            // separate Reload action is chosen.
            return;
        }
        if (success) {
            java.util.Optional<net.minecraft.resources.ResourceLocation> resetFuel =
                    FuelRecipeEditorAdapter.itemIdFromRecipeId(recipeId);
            if (resetFuel.isPresent()) {
                FuelOverrideState.remove(resetFuel.get());
                JeiRecipeEditorPlugin.applyFuelReset(resetFuel.get());
            }
            java.util.LinkedHashMap<String, RecipePatch> allKnown =
                    new java.util.LinkedHashMap<String, RecipePatch>();
            allKnown.putAll(submittedSavePatches);
            for (RecipePatch patch : session.pendingPatches()) {
                allKnown.put(patch.recipeId(), patch);
            }
            java.util.List<RecipePatch> submitted =
                    new java.util.ArrayList<RecipePatch>(allKnown.values());
            if (message.startsWith("Reloaded ")) {
                JeiRecipeEditorPlugin.applyCreatedAnvilPatches(submitted);
            }
            for (RecipePatch patch : submitted) {
                if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
                    FuelOverrideState.applyPatch(patch);
                }
            }
            JeiRecipeEditorPlugin.applyFuelResult(submitted);
            if (message.startsWith("Reloaded ")) {
                java.util.List<String> reloaded = new java.util.ArrayList<String>();
                for (Map.Entry<String, RecipePatch> entry : submittedSavePatches.entrySet()) {
                    RecipePatch current = getPendingPatch(entry.getKey());
                    if (samePatch(current, entry.getValue())) {
                        reloaded.add(entry.getKey());
                    }
                }
                session.remove(reloaded);
                submittedSavePatches.clear();
                clearPreview();
            } else {
                session.reset();
                clearPreview();
            }
            lastModel = null;
            lastSlotKey = null;
            lastRecipeSlots = null;
            lastRecipe = null;
        }
    }

    private static boolean samePatch(RecipePatch left, RecipePatch right) {
        return left != null && right != null
                && left.recipeId().equals(right.recipeId())
                && left.serializerId().equals(right.serializerId())
                && left.baseFingerprint().equals(right.baseFingerprint())
                && left.fields().equals(right.fields());
    }

    /** Applies the server's persisted synthetic entries after joining a world.
     * Save results already carry this information through the normal result
     * packet; this path covers reconnects where no edit was made this session. */
    public static void applySavedPatches(List<RecipePatch> patches) {
        // The server sends a complete snapshot. An empty snapshot must clear
        // synthetic pages that were removed from the generated datapack.
        JeiRecipeEditorPlugin.replaceCreatedAnvilPatches(patches);
        FuelOverrideState.clear();
        if (patches == null || patches.isEmpty()) {
            return;
        }
        for (RecipePatch patch : patches) {
            if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
                FuelOverrideState.applyPatch(patch);
            }
        }
        JeiRecipeEditorPlugin.applyFuelResult(patches);
    }

    static boolean isRecipeScreen(Screen screen) {
        return screen instanceof mezz.jei.gui.recipes.RecipesGui;
    }
}
