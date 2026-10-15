package cc.sighs.JEIEditor.client;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.JEIEditorNeoForge121;
import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.IngredientKind;
import cc.sighs.JEIEditor.editor.RecipePatchSemantics;
import cc.sighs.JEIEditor.editor.SlotPatchFields;
import cc.sighs.JEIEditor.editor.TagPatchFields;
import cc.sighs.JEIEditor.platform.fuel.FuelOverrideState;
import cc.sighs.JEIEditor.platform.recipe.CraftingSlotMapper;
import cc.sighs.JEIEditor.platform.recipe.FuelRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.JeiVanillaRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.ModdedRecipeModelSupport;
import cc.sighs.JEIEditor.platform.recipe.RecipeAdapterSupport;
import cc.sighs.JEIEditor.platform.recipe.RecipeCreationAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeDeletionAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeEditorAdapters;
import cc.sighs.JEIEditor.platform.recipe.TagEditorAdapter;
import cc.sighs.JEIEditor.recipe.RecipeFieldMapping;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.vanilla.IJeiFuelingRecipe;
import mezz.jei.gui.recipes.RecipesGui;
import mezz.jei.common.Internal;
import mezz.jei.library.plugins.jei.tags.ITagInfoRecipe;
import mezz.jei.library.plugins.vanilla.cooking.fuel.FuelingRecipe;
import mezz.jei.library.plugins.vanilla.anvil.AnvilRecipe;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

@JeiPlugin
public final class JeiRecipeEditorPlugin implements IModPlugin {
    private static final int CREATED_ANVIL_VISIBILITY_CHECK_TICKS = 3;
    private static final Map<String, Integer> fuelPreviewTimes = new HashMap<String, Integer>();
    private static final Map<String, RecipePatch> createdAnvilPatches =
            new HashMap<String, RecipePatch>();
    private static boolean createdAnvilRestorePending;
    private static int createdAnvilVisibilityChecksRemaining;
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(JEIEditorNeoForge121.MOD_ID, "jei_plugin");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(RecipesGui.class, new RecipeGhostHandler());
    }

    @Override
    @SuppressWarnings("removal")
    public void registerIngredients(mezz.jei.api.registration.IModIngredientRegistration registration) {
        // Registered with no entries on purpose: JEI can only grow an index at runtime, and the
        // tags are added when editing turns on and removed when it turns off, so they appear in
        // the ingredient index only in editing mode. The 4-argument overload is the one JEI 19.44
        // still supports without a codec (the codec overload is for ingredients that persist).
        registration.register(TagIngredient.TYPE, java.util.Collections.<TagIngredient>emptyList(),
                new TagIngredientHelper(), new TagIngredientRenderer());
    }

    static boolean clearInputAtMouse(RecipesGui gui, double mouseX, double mouseY) {
        Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
        if (!target.isPresent()) {
            return false;
        }
        if (!target.get().slotKey.startsWith("input.")) {
            return false;
        }
        try {
            ClientEditorState.rememberTarget(target.get().model, target.get().slotKey, target.get().slots,
                    target.get().recipe);
            ClientEditorState.setPendingPatch(RecipeEditorAdapters.clearSlot(
                    target.get().model, target.get().slotKey));
            ClientEditorState.setLastDrop("Cleared " + target.get().slotKey);
            return true;
        } catch (IllegalArgumentException exception) {
            ClientEditorState.setLastDrop(exception.getMessage());
            return false;
        }
    }

    static boolean hasEditableInputAtMouse(RecipesGui gui, double mouseX, double mouseY) {
        Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
        return target.isPresent() && target.get().slotKey.startsWith("input.");
    }

    static boolean adjustOutputAtMouse(RecipesGui gui, double mouseX, double mouseY, double scrollDelta) {
        if (scrollDelta == 0.0D) {
            return false;
        }
        Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
        if (!target.isPresent() || FuelRecipeEditorAdapter.SERIALIZER.equals(target.get().model.serializerId())
                || ClientEditorState.isRecipeDeleted(target.get().model.recipeId())
                || RecipeFieldMapping.outputIndex(target.get().slotKey) < 0) {
            return false;
        }
        ClientEditorState.rememberTarget(target.get().model, target.get().slotKey, target.get().slots,
                target.get().recipe);
        RecipePatch patch = ClientEditorState.adjustOutputCount(target.get().slotKey,
                scrollDelta > 0.0D ? 1 : -1);
        if (patch == null) {
            IngredientKind kind = ClientEditorState.lastOutputKind();
            ClientEditorState.setLastDrop(ClientEditorState.canResizeOutput(target.get().slotKey) && kind != null
                    ? "Output amount must be between " + kind.minAmount() + " and " + kind.maxAmount()
                    : "This output slot holds no item the editor can rewrite");
        } else {
            ClientEditorState.setPendingPatch(patch);
            ClientEditorState.setLastDrop("Output count adjusted");
        }
        return true;
    }

    /** Draws a translucent blue background over every visible slot changed by
     * the pending patch. JEI does not expose a slot background override, so the
     * actual JEI slot rectangles are used and the item remains visible above it.
     */
    @SuppressWarnings("removal")
    static void drawPendingSlotHighlights(RecipesGui gui, GuiGraphics graphics) {
        List<RecipePatch> patches = ClientEditorState.getPendingPatches();
        if (patches.isEmpty()) {
            return;
        }
        Set<String> seenAreas = new HashSet<String>();
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            IRecipeCategory<?> category = layout.getRecipeLayout().getRecipeCategory();
            Optional<EditorModel> sourceModel = resolveModel(displayedRecipe, category,
                    layout.getRecipeLayout().getRecipeSlotsView());
            EditorModel model = sourceModel.orElse(null);
            RecipePatch patch = findPendingPatch(displayedRecipe, category, patches);
            if (sourceModel.isPresent()) {
                Optional<EditorModel> creationModel = ClientEditorState.creationModelFor(
                        gui, sourceModel.get().recipeId());
                if (creationModel.isPresent()) {
                    model = creationModel.get();
                    patch = ClientEditorState.getPendingPatch(model.recipeId());
                }
            }
            if (patch == null || patch.fields().isEmpty()) {
                continue;
            }
            if (RecipePatchSemantics.isDeletion(patch)) {
                Rect2i screenArea = layout.getRecipeLayout().getRectWithBorder();
                String areaKey = "deleted:" + screenArea.getX() + ":" + screenArea.getY() + ":"
                        + screenArea.getWidth() + ":" + screenArea.getHeight();
                if (seenAreas.add(areaKey)) {
                    graphics.fill(screenArea.getX(), screenArea.getY(),
                            screenArea.getX() + screenArea.getWidth(),
                            screenArea.getY() + screenArea.getHeight(), 0x40f05a5a);
                }
                continue;
            }
            if (model == null) {
                continue;
            }
            IRecipeSlotsView slots = layout.getRecipeLayout().getRecipeSlotsView();
            for (mezz.jei.api.gui.ingredient.IRecipeSlotView view : slots.getSlotViews()) {
                if (!(view instanceof mezz.jei.api.gui.ingredient.IRecipeSlotDrawable)
                        || (view.getRole() != RecipeIngredientRole.INPUT
                        && view.getRole() != RecipeIngredientRole.OUTPUT)) {
                    continue;
                }
                String slotKey = RecipeGhostHandler.slotKey(slots.getSlotViews(),
                        (mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view,
                        displayedRecipe, model);
                if (!isPatchedSlot(patch, model, slotKey)) {
                    continue;
                }
                Rect2i screenArea = JeiRecipeIntrospection.screenTargetArea(
                        layout.getRecipeLayout(),
                        (mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view);
                String areaKey = screenArea.getX() + ":" + screenArea.getY() + ":"
                        + screenArea.getWidth() + ":" + screenArea.getHeight();
                if (seenAreas.add(areaKey)) {
                    graphics.fill(screenArea.getX(), screenArea.getY(),
                            screenArea.getX() + screenArea.getWidth(),
                            screenArea.getY() + screenArea.getHeight(), 0x662b6cff);
                }
                drawPatchedAmount(graphics, screenArea, patch, slotKey);
            }
        }
    }

    /**
     * States the amount the patch will save, on the slot itself.
     *
     * <p>Needed for every kind whose displayed amount JEI cannot be told about: a chemical
     * has no registered candidate carrying the edited amount, so JEI renders the registry
     * default instead -- a number that is not the one being written. Item counts render
     * correctly already, so this only covers the other kinds.
     */
    private static void drawPatchedAmount(GuiGraphics graphics, Rect2i area, RecipePatch patch,
                                          String slotKey) {
        String amount = SlotPatchFields.amount(patch.fields(), slotKey);
        IngredientKind kind = SlotPatchFields.kindOf(patch.fields(), slotKey);
        if (amount == null || amount.isEmpty() || kind == null || kind == IngredientKind.ITEM) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int width = font.width(amount);
        int x = area.getX() + area.getWidth() - width;
        int y = area.getY() + area.getHeight() - 8;
        // Same z lift the editor's other own overlays use (the context menu and the drag
        // icon): without it the fill and the text are not visible over JEI's recipe area.
        com.mojang.blaze3d.vertex.PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0D, 0.0D, 1000.0D);
        graphics.fill(x - 1, y - 1, x + width + 1, y + 9, 0xE0202020);
        graphics.drawString(font, amount, x, y, 0xFFFFD75E, false);
        pose.popPose();
    }

    /** Rebuilds local JEI display overrides for every visible recipe with a
     * pending patch, not just the last slot the user touched. */
    @SuppressWarnings("removal")
    static void refreshPendingPreviews(RecipesGui gui) {
        ClientEditorState.beginPreviewRefresh();
        List<RecipePatch> patches = ClientEditorState.getPendingPatches();
        if (patches.isEmpty()) {
            fuelPreviewTimes.clear();
            return;
        }
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            IRecipeCategory<?> category = layout.getRecipeLayout().getRecipeCategory();
            Optional<EditorModel> sourceModel = resolveModel(displayedRecipe, category,
                    layout.getRecipeLayout().getRecipeSlotsView());
            EditorModel model = sourceModel.orElse(null);
            RecipePatch patch = findPendingPatch(displayedRecipe, category, patches);
            if (sourceModel.isPresent()) {
                Optional<EditorModel> creationModel = ClientEditorState.creationModelFor(
                        gui, sourceModel.get().recipeId());
                if (creationModel.isPresent()) {
                    model = creationModel.get();
                    patch = ClientEditorState.getPendingPatch(model.recipeId());
                }
            }
            if (patch == null) {
                continue;
            }
            if (RecipePatchSemantics.isDeletion(patch)) {
                continue;
            }
            ensureFuelPreview(displayedRecipe, patch);
            if (model != null) {
                ClientEditorState.applyPreview(
                        layout.getRecipeLayout().getRecipeSlotsView(), model, patch,
                        displayedRecipe);
            }
        }
    }

    private static boolean isPatchedSlot(RecipePatch patch, EditorModel model, String slotKey) {
        if (patch == null || model == null || slotKey == null) {
            return false;
        }
        if (FuelRecipeEditorAdapter.isFuelPatch(patch) && "input.0".equals(slotKey)) {
            String burnTime = patch.fields().get("fuel.burn_time");
            return burnTime != null && !burnTime.equals(model.properties().get("burn_time"));
        }
        // A tag patch carries no slot fields: the operation it names is the edit, and
        // the slot it is shown on is the page's single tag slot.
        if (TagPatchFields.isTagPatch(patch)) {
            return "input.0".equals(slotKey);
        }
        cc.sighs.JEIEditor.editor.EditorSlot original = model.slots().stream()
                .filter(slot -> slotKey.equals(slot.key()))
                .findFirst()
                .orElse(null);
        if (original == null) {
            return false;
        }
        if (SlotPatchFields.kindOf(patch.fields(), slotKey) == null) {
            return false;
        }
        EditorIngredient ingredient = original.ingredient();
        String id = SlotPatchFields.id(patch.fields(), slotKey);
        String amount = SlotPatchFields.amount(patch.fields(), slotKey);
        boolean idChanged = id != null && (ingredient == null
                ? !SlotPatchFields.CLEARED_ITEM.equals(id)
                : !id.equals(ingredient.id()));
        boolean amountChanged = amount != null && (ingredient == null
                ? !SlotPatchFields.CLEARED_AMOUNT.equals(amount)
                : !amount.equals(Integer.toString(ingredient.amount())));
        String stack = patch.fields().get(slotKey + ".stack");
        boolean stackChanged = stack != null && !stack.equals(model.properties().get(
                JeiVanillaRecipeEditorAdapter.STACK_PROPERTY_PREFIX + slotKey));
        return idChanged || amountChanged || stackChanged;
    }

    private static boolean matchesRecipe(Object displayedRecipe, IRecipeCategory<?> category, String recipeId) {
        if (FuelRecipeEditorAdapter.isFuelRecipe(displayedRecipe)) {
            Optional<EditorModel> model = RecipeEditorAdapters.createFuelModel(displayedRecipe);
            return model.isPresent() && model.get().recipeId().equals(recipeId);
        }
        // A tag page's patch is addressed to the encoded tag id, which is what its
        // model carries, and no recipe holder ever has that id: resolve the page's
        // own identity first so a pending tag edit attaches to the page it was made on.
        String tagId = tagRecipeId(category, displayedRecipe);
        if (tagId != null) {
            return tagId.equals(recipeId);
        }
        Optional<ResourceLocation> categoryId = JeiRecipeIntrospection.recipeId(category, displayedRecipe);
        // Some JEI generated pages (notably anvil enchantment pages) have no
        // registry UID. Use the same adapter-derived synthetic identity that
        // resolveModel() uses so pending previews still attach to the page.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            Optional<EditorModel> jeiModel = RecipeEditorAdapters.createJeiModel(
                    displayedRecipe, categoryId.orElse(null), minecraft.level.registryAccess());
            if (jeiModel.isPresent()) {
                return jeiModel.get().recipeId().equals(recipeId);
            }
        }
        if (categoryId.isPresent()) {
            if (categoryId.get().toString().equals(recipeId)) {
                return true;
            }
            // A page that displays another recipe type's recipes under a rewritten
            // id attaches its pending preview to the real recipe's id, which is the
            // id its patches carry.
            Optional<RecipeHolder<?>> aliased =
                    JeiRecipeIntrospection.aliasedRecipeHolder(category, categoryId.get());
            return aliased.isPresent() && aliased.get().id().toString().equals(recipeId);
        }
        if (displayedRecipe instanceof RecipeHolder<?>) {
            return ((RecipeHolder<?>) displayedRecipe).id().toString().equals(recipeId);
        }
        return minecraft.level != null && minecraft.level.getRecipeManager().getRecipes().stream()
                .anyMatch(holder -> holder.id().toString().equals(recipeId)
                        && (holder.value() == displayedRecipe || holder.value().equals(displayedRecipe)));
    }

    private static RecipePatch findPendingPatch(Object displayedRecipe, IRecipeCategory<?> category,
                                                List<RecipePatch> patches) {
        for (RecipePatch patch : patches) {
            if (matchesRecipe(displayedRecipe, category, patch.recipeId())) {
                return patch;
            }
        }
        return null;
    }

    private static Optional<RecipeTarget> resolveTarget(RecipesGui gui, double mouseX, double mouseY) {
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            if (!layout.getRecipeLayout().isMouseOver(mouseX, mouseY)) {
                continue;
            }
            Optional<mezz.jei.api.gui.inputs.RecipeSlotUnderMouse> slot =
                    layout.getRecipeLayout().getSlotUnderMouse(mouseX, mouseY);
            if (!slot.isPresent()) {
                continue;
            }
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            Optional<EditorModel> model = resolveModel(displayedRecipe,
                    layout.getRecipeLayout().getRecipeCategory(),
                    layout.getRecipeLayout().getRecipeSlotsView());
            if (!model.isPresent()) {
                continue;
            }
            EditorModel editableModel = ClientEditorState.creationModelFor(gui, model.get().recipeId())
                    .orElse(model.get());
            if (isExistingAnvil(editableModel)) {
                continue;
            }
            IRecipeSlotsView slots = layout.getRecipeLayout().getRecipeSlotsView();
            String slotKey = RecipeGhostHandler.slotKey(slots.getSlotViews(), slot.get().slot(), displayedRecipe,
                    editableModel);
            if (slotKey != null) {
                return Optional.of(new RecipeTarget(editableModel, slotKey, slots, null, displayedRecipe));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the currently displayed item in the recipe slot under the
     * pointer. Display overrides are already reflected by JEI's slot view, so
     * dragging a locally edited item uses exactly what the player sees.
     */
    static Optional<ItemStack> editorItemAtMouse(RecipesGui gui, double mouseX, double mouseY) {
        Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
        if (!target.isPresent() || ClientEditorState.isRecipeDeleted(target.get().model.recipeId())) {
            return Optional.empty();
        }
        for (mezz.jei.api.gui.ingredient.IRecipeSlotView view : target.get().slots.getSlotViews()) {
            if (view.getRole() != RecipeIngredientRole.INPUT
                    && view.getRole() != RecipeIngredientRole.OUTPUT) {
                continue;
            }
            if (!(view instanceof mezz.jei.api.gui.ingredient.IRecipeSlotDrawable)) {
                continue;
            }
            String slotKey = RecipeGhostHandler.slotKey(target.get().slots.getSlotViews(),
                    (mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view,
                    target.get().recipe, target.get().model);
            if (target.get().slotKey.equals(slotKey)) {
                return view.getDisplayedItemStack().map(ItemStack::copy);
            }
        }
        return Optional.empty();
    }

    /** Applies a dragged editor item to the editable slot under the pointer. */
    static boolean replaceItemAtMouse(RecipesGui gui, double mouseX, double mouseY, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
        if (!target.isPresent() || ClientEditorState.isRecipeDeleted(target.get().model.recipeId())) {
            return false;
        }
        try {
            ClientEditorState.rememberTarget(target.get().model, target.get().slotKey,
                    target.get().slots, target.get().recipe);
            ClientEditorState.setPendingPatch(RecipeEditorAdapters.replaceSlot(
                    target.get().model, target.get().slotKey, stack,
                    Minecraft.getInstance().level == null ? null
                            : Minecraft.getInstance().level.registryAccess()));
            ClientEditorState.setLastDrop("Moved " + stack.getHoverName().getString()
                    + " to " + target.get().slotKey);
            return true;
        } catch (IllegalArgumentException exception) {
            ClientEditorState.setLastDrop(exception.getMessage());
            return false;
        }
    }

    static Optional<EditorModel> recipeDeletionTargetAtMouse(
            RecipesGui gui, double mouseX, double mouseY) {
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            if (!layout.getRecipeLayout().isMouseOver(mouseX, mouseY)) {
                continue;
            }
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            Optional<EditorModel> source = resolveModel(displayedRecipe,
                    layout.getRecipeLayout().getRecipeCategory(),
                    layout.getRecipeLayout().getRecipeSlotsView());
            if (source.isPresent() && "jei:anvil".equals(source.get().serializerId())) {
                Optional<EditorModel> creation = ClientEditorState.creationModelFor(
                        gui, source.get().recipeId());
                if (creation.isPresent()) {
                    return creation;
                }
                // Existing JEI anvil pages are intentionally read-only. They
                // may still expose the page context menu for New Recipe.
                continue;
            }
            Optional<RecipeHolder<?>> holder = resolveRecipeHolder(displayedRecipe,
                    layout.getRecipeLayout().getRecipeCategory());
            Optional<EditorModel> model = holder.flatMap(RecipeDeletionAdapter::createModel);
            if (model.isPresent()) {
                return model;
            }
        }
        return Optional.empty();
    }

    /**
     * The tag page under the pointer and what the pointer is on inside it, or empty
     * when the pointer is not on a slot of a tag page.
     *
     * <p>JEI draws the page, not the editor: one INPUT slot holds every member and
     * each member gets an OUTPUT slot of its own, so the slot under the pointer is
     * found through JEI's own input router (the same call the editor's other slot
     * lookups use) rather than by a second hit test. The result carries the member
     * the hovered member slot shows, because both operations a member slot offers -
     * removing it and creating a tag from it - need that id; a slot whose member
     * cannot be resolved offers nothing rather than a wrong id.
     */
    static Optional<TagPageTarget> tagPageTargetAtMouse(RecipesGui gui, double mouseX, double mouseY) {
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            if (!layout.getRecipeLayout().isMouseOver(mouseX, mouseY)) {
                continue;
            }
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            if (!(displayedRecipe instanceof ITagInfoRecipe)) {
                continue;
            }
            IRecipeCategory<?> category = layout.getRecipeLayout().getRecipeCategory();
            Optional<EditorModel> model = resolveModel(displayedRecipe, category,
                    layout.getRecipeLayout().getRecipeSlotsView());
            if (!model.isPresent() || !TagEditorAdapter.supportsSerializer(model.get().serializerId())
                    || ClientEditorState.isRecipeDeleted(model.get().recipeId())) {
                continue;
            }
            Optional<mezz.jei.api.gui.inputs.RecipeSlotUnderMouse> slot =
                    layout.getRecipeLayout().getSlotUnderMouse(mouseX, mouseY);
            if (!slot.isPresent()) {
                continue;
            }
            IRecipeSlotsView slots = layout.getRecipeLayout().getRecipeSlotsView();
            String slotKey = RecipeGhostHandler.slotKey(slots.getSlotViews(), slot.get().slot(),
                    displayedRecipe, model.get());
            if ("input.0".equals(slotKey)) {
                return Optional.of(new TagPageTarget(model.get(), "input.0", null));
            }
            String memberId = memberId(slot.get().slot());
            if (memberId != null) {
                return Optional.of(new TagPageTarget(model.get(), null, memberId));
            }
        }
        return Optional.empty();
    }

    /**
     * The member id one tag member slot shows, or null when the pointer is not on a
     * member slot or what it shows cannot be named as one.
     *
     * <p>Only INPUT and OUTPUT slots exist on a JEI page, and the model names the
     * input one, so any other slot the pointer can be on is a member slot.
     */
    private static String memberId(mezz.jei.api.gui.ingredient.IRecipeSlotDrawable slot) {
        if (slot == null || slot.getRole() != RecipeIngredientRole.OUTPUT) {
            return null;
        }
        for (ITypedIngredient<?> candidate : slot.getAllIngredientsList()) {
            Optional<EditorIngredient> ingredient = RecipeAdapterSupport.editorIngredient(candidate);
            if (ingredient.isPresent()) {
                return ingredient.get().id();
            }
        }
        return null;
    }

    static Optional<EditorModel> recipeCreationTargetAtMouse(
            RecipesGui gui, double mouseX, double mouseY) {
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            if (!layout.getRecipeLayout().isMouseOver(mouseX, mouseY)) {
                continue;
            }
            Optional<EditorModel> source = resolveModel(layout.getRecipeLayout().getRecipe(),
                    layout.getRecipeLayout().getRecipeCategory(),
                    layout.getRecipeLayout().getRecipeSlotsView());
            Optional<EditorModel> creation = source.flatMap(value ->
                    creationModelForSource(gui, value));
            if (creation.isPresent()) {
                return creation;
            }
        }
        // All layouts visible in one JEI tab belong to the same recipe
        // category. If the hovered layout cannot provide a source model,
        // use another supported layout from that same page.
        return recipeCreationTargetOnPage(gui);
    }

    static Optional<EditorModel> recipeCreationTargetOnPage(RecipesGui gui) {
        if (Minecraft.getInstance().level == null) {
            return Optional.empty();
        }
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            Optional<EditorModel> source = resolveModel(layout.getRecipeLayout().getRecipe(),
                    layout.getRecipeLayout().getRecipeCategory(),
                    layout.getRecipeLayout().getRecipeSlotsView());
            Optional<EditorModel> creation = source.flatMap(value ->
                    creationModelForSource(gui, value));
            if (creation.isPresent()) {
                // A page can show several recipes from the same JEI category;
                // use the first supported layout as the creation template.
                return creation;
            }
        }
        return Optional.empty();
    }

    private static Optional<EditorModel> creationModelForSource(RecipesGui gui, EditorModel source) {
        Optional<EditorModel> existing = ClientEditorState.creationModelFor(gui, source.recipeId());
        return existing.isPresent() ? existing : RecipeCreationAdapter.createModel(source);
    }

    /** Builds the editor model of one displayed page. {@code slots} must be the
     * slot views of the layout currently showing {@code displayedRecipe}; the
     * declared mod pages are modelled from them directly. */
    private static Optional<EditorModel> resolveModel(Object displayedRecipe, IRecipeCategory<?> category,
                                                      IRecipeSlotsView slots) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            ClientEditorState.setLastDrop("No client level available");
            return Optional.empty();
        }
        Optional<EditorModel> fuelModel = RecipeEditorAdapters.createFuelModel(displayedRecipe);
        if (fuelModel.isPresent()) {
            return fuelModel;
        }
        Optional<EditorModel> jeiModel = RecipeEditorAdapters.createJeiModel(displayedRecipe,
                JeiRecipeIntrospection.recipeId(category, displayedRecipe).orElse(null),
                minecraft.level.registryAccess());
        if (jeiModel.isPresent()) {
            return jeiModel;
        }
        // Declared mod recipe pages are modelled from the slot views of the
        // displayed layout, so a declaration needs no per-mod reading code.
        // The id comes from the category, or - for mods that push bare Recipe
        // objects into JEI instead of RecipeHolder - from the recipe manager
        // identity lookup.
        Optional<ResourceLocation> moddedRecipeId = JeiRecipeIntrospection.recipeId(category, displayedRecipe);
        if (!moddedRecipeId.isPresent()) {
            moddedRecipeId = resolveRecipeHolder(displayedRecipe, category).map(RecipeHolder::id);
        }
        Optional<EditorModel> moddedModel = ModdedRecipeModelSupport.createModel(slots,
                moddedRecipeId.isPresent() ? moddedRecipeId.get().toString() : null,
                recipeSerializerId(displayedRecipe),
                category == null ? null : category.getRecipeType().getUid(),
                loadedRecipe(displayedRecipe));
        if (moddedModel.isPresent()) {
            return moddedModel;
        }
        Optional<EditorModel> tagModel = createTagModel(category, displayedRecipe);
        if (tagModel.isPresent()) {
            return tagModel;
        }
        if (category != null && !JeiRecipeIntrospection.isHandled(category, displayedRecipe)) {
            return Optional.empty();
        }
        Optional<RecipeHolder<?>> holder = resolveRecipeHolder(displayedRecipe, category);
        if (!holder.isPresent()) {
            ClientEditorState.setLastDrop("Recipe id could not be resolved");
            return Optional.empty();
        }
        Optional<EditorModel> model = RecipeEditorAdapters.createModel(
                holder.get(), minecraft.level.registryAccess());
        if (!model.isPresent()) {
            ClientEditorState.setLastDrop("This recipe format is not supported by the editor adapter");
        }
        return model;
    }

    /**
     * The editor model of a JEI tag page, or empty when the page is not one.
     *
     * <p>The model has exactly one slot, holding the tag's first member, and not one
     * slot per member. A tag can hold 1052 members ({@code minecraft:item} in this
     * pack) while EditorModel accepts at most 128 slots, and the patch is addressed
     * to the tag as a whole - {@code TagPatchFields} carries one element per
     * operation - so a per-member model would be both unbuildable and unusable. The
     * slot exists so the page has a drag target and a model identity at all: dropping
     * an ingredient on it adds that ingredient to the tag.
     *
     * <p>The registry lives in the recipe id
     * ({@link cc.sighs.JEIEditor.editor.TagPatchFields#recipeId(String, String)})
     * because that is what the world's SavedData, the import bundle and the server's
     * policy gate key a tag patch by, and a tag id is not unique across registries -
     * {@code minecraft:planks} names an item tag and a block tag at once.
     */
    private static Optional<EditorModel> createTagModel(IRecipeCategory<?> category, Object displayedRecipe) {
        String registry = tagRegistry(category);
        if (registry == null || !(displayedRecipe instanceof ITagInfoRecipe)) {
            return Optional.empty();
        }
        ITagInfoRecipe recipe = (ITagInfoRecipe) displayedRecipe;
        Optional<EditorIngredient> member = firstMember(recipe, registry);
        TagKey<?> tag = recipe.getTag();
        if (!member.isPresent() || tag == null) {
            // A tag with no member the editor can address has nothing to model: the
            // page shows its members, and a model with no ingredient would offer a
            // drag whose patch names whatever the user then dropped - not an edit of
            // what the page displayed.
            return Optional.empty();
        }
        String recipeId = TagPatchFields.recipeId(registry, tag.location().toString());
        List<EditorSlot> slots = Collections.<EditorSlot>singletonList(
                new EditorSlot("input.0", "input", member.get()));
        LinkedHashMap<String, String> properties = new LinkedHashMap<String, String>();
        // The same visual mapping key ModdedRecipeModelSupport writes, so the slot
        // mapper resolves JEI's one input slot to this key deterministically; JEI's
        // member slots (one OUTPUT slot each) map to no key and stay read-only.
        properties.put(JeiVanillaRecipeEditorAdapter.VISUAL_INPUT_PROPERTY_PREFIX + "0", "input.0");
        return Optional.of(new EditorModel(recipeId, TagPatchFields.SERIALIZER,
                RecipeAdapterSupport.fingerprint(recipeId, TagPatchFields.SERIALIZER, slots, properties),
                slots, properties));
    }

    /**
     * The registry a tag page belongs to, or null when the page is not one of the
     * three a tag patch can write.
     *
     * <p>The recipe type uid is where the registry comes from: JEI registers one tag
     * category per registry that has an ingredient type and builds the uid as
     * {@code <registry namespace>:tag_recipes/<registry path>}. The registry is
     * therefore read from the id rather than from what the page displays, because a
     * dropped ingredient has to be converted into the registry the tag file is
     * written in ({@code TagEditorAdapter}). Only the item, block and fluid
     * registries have an applier; a page of any other registry (a modded ingredient
     * registry beyond those three) keeps no model, so no drag is offered there.
     */
    private static String tagRegistry(IRecipeCategory<?> category) {
        if (category == null) {
            return null;
        }
        ResourceLocation uid = category.getRecipeType().getUid();
        if (uid == null || !"minecraft".equals(uid.getNamespace())) {
            return null;
        }
        String prefix = "tag_recipes/";
        if (!uid.getPath().startsWith(prefix)) {
            return null;
        }
        String registry = uid.getPath().substring(prefix.length());
        return TagPatchFields.isRegistry(registry) ? registry : null;
    }

    /**
     * The encoded recipe id of a tag page's tag, or null when the page is not a tag
     * page the tag patch kind can address.
     *
     * <p>This is the identity a tag model carries, so the pending-patch lookup reads
     * it through here rather than re-deriving the encoding: {@code matchesRecipe}
     * compares a pending tag patch against the page it belongs to by exactly this id.
     */
    private static String tagRecipeId(IRecipeCategory<?> category, Object displayedRecipe) {
        String registry = tagRegistry(category);
        if (registry == null || !(displayedRecipe instanceof ITagInfoRecipe)) {
            return null;
        }
        TagKey<?> tag = ((ITagInfoRecipe) displayedRecipe).getTag();
        return tag == null ? null : TagPatchFields.recipeId(registry, tag.location().toString());
    }

    /**
     * The tag's first member as an editor ingredient of the kind the page shows, or
     * empty when the member cannot be named by a registry id.
     *
     * <p>The kind follows the page: JEI's item and block tag pages draw item stacks
     * (the block page draws the block's item, which is also what a drag carries and
     * what {@code TagEditorAdapter} converts back into a block id), the fluid page
     * draws fluid stacks.
     */
    private static Optional<EditorIngredient> firstMember(ITagInfoRecipe recipe, String registry) {
        List<ITypedIngredient<?>> members = recipe.getTypedIngredients();
        if (members == null || members.isEmpty() || members.get(0) == null) {
            return Optional.empty();
        }
        ITypedIngredient<?> first = members.get(0);
        if (TagPatchFields.REGISTRY_FLUID.equals(registry)) {
            Optional<FluidStack> stack = first.getIngredient(NeoForgeTypes.FLUID_STACK);
            if (!stack.isPresent()) {
                return Optional.empty();
            }
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(stack.get().getFluid());
            // A tag member is an id and carries no amount, so the stack's own amount is
            // only the display representative; JEI builds it from the type's default
            // ingredient, which is clamped here to the kind's minimum rather than
            // refusing a tag whose members have no volume.
            return id == null
                    ? Optional.<EditorIngredient>empty()
                    : Optional.of(new EditorIngredient(IngredientKind.FLUID, id.toString(),
                            Math.max(IngredientKind.FLUID.minAmount(), stack.get().getAmount())));
        }
        Optional<ItemStack> stack = first.getIngredient(VanillaTypes.ITEM_STACK);
        if (!stack.isPresent() || stack.get().isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.get().getItem());
        return id == null
                ? Optional.<EditorIngredient>empty()
                : Optional.of(new EditorIngredient(id.toString(), Math.max(1, stack.get().getCount())));
    }

    /** Serializer of the held recipe, or null when the page is not a recipe record. */
    private static String recipeSerializerId(Object displayedRecipe) {
        Recipe recipe = null;
        if (displayedRecipe instanceof RecipeHolder<?>) {
            recipe = ((RecipeHolder<?>) displayedRecipe).value();
        } else if (displayedRecipe instanceof Recipe) {
            // Some mods push the bare recipe object into JEI, not a holder.
            recipe = (Recipe) displayedRecipe;
        }
        if (recipe == null) {
            return null;
        }
        ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        return serializerId == null ? null : serializerId.toString();
    }

    /**
     * The recipe object the mod loaded for a displayed entry, or null when the page shows
     * something that is not a recipe record. A recipe-derived sequence reads it to tell which
     * of the page's input slots its fields actually cover
     * ({@code DerivedSlotSequence#fieldBackedInputOrdinals}).
     */
    private static Recipe<?> loadedRecipe(Object displayedRecipe) {
        if (displayedRecipe instanceof RecipeHolder<?>) {
            return ((RecipeHolder<?>) displayedRecipe).value();
        }
        return displayedRecipe instanceof Recipe<?> ? (Recipe<?>) displayedRecipe : null;
    }

    private static Optional<RecipeHolder<?>> resolveRecipeHolder(
            Object displayedRecipe, IRecipeCategory<?> category) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return Optional.empty();
        }
        // The registry lookup is only trustworthy for a RecipeHolder, whose id is
        // the recipe's own id. For every other displayed object the id comes from
        // the category's registry name, which can be a tag id (JEI's tag pages) or
        // a category-generated uid; looking that up would hand back a completely
        // unrelated recipe, so those pages are resolved by identity below.
        if (displayedRecipe instanceof RecipeHolder<?>) {
            Optional<ResourceLocation> recipeId = JeiRecipeIntrospection.recipeId(category, displayedRecipe);
            if (recipeId.isPresent()) {
                Optional<RecipeHolder<?>> holder = minecraft.level.getRecipeManager().byKey(recipeId.get());
                if (holder.isPresent()) {
                    return holder;
                }
                // A page can display another recipe type's recipes under a rewritten
                // id (Mekanism's smelting page). The manager holds the recipe under
                // the original id, and that recipe is the one an edit writes.
                Optional<RecipeHolder<?>> aliased =
                        JeiRecipeIntrospection.aliasedRecipeHolder(category, recipeId.get());
                if (aliased.isPresent()) {
                    return aliased;
                }
            }
        }
        return minecraft.level.getRecipeManager().getRecipes().stream()
                .filter(candidate -> candidate.value() == displayedRecipe
                        || candidate.value().equals(displayedRecipe))
                .findFirst();
    }

    @SuppressWarnings("removal")
    private static List<RecipeTarget> visibleEditableTargets(RecipesGui gui, IngredientKind draggedKind) {
        List<RecipeTarget> targets = new ArrayList<RecipeTarget>();
        Set<String> seenAreas = new HashSet<String>();
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            IRecipeCategory<?> category = layout.getRecipeLayout().getRecipeCategory();
            Optional<EditorModel> model = resolveModel(displayedRecipe, category,
                    layout.getRecipeLayout().getRecipeSlotsView());
            if (!model.isPresent()) {
                continue;
            }
            EditorModel source = model.get();
            if (ClientEditorState.isRecipeDeleted(source.recipeId())) {
                continue;
            }
            EditorModel editableModel = ClientEditorState.creationModelFor(gui, source.recipeId())
                    .orElse(source);
            if (isExistingAnvil(editableModel)) {
                continue;
            }
            // Fuel entries are generated from the item registry rather than
            // recipe JSON. Their editable operation is burn-time scrolling;
            // dragging a different item would require changing the synthetic
            // identity and is intentionally not offered as a ghost target.
            if (FuelRecipeEditorAdapter.SERIALIZER.equals(editableModel.serializerId())) {
                continue;
            }
            IRecipeSlotsView slots = layout.getRecipeLayout().getRecipeSlotsView();
            for (mezz.jei.api.gui.ingredient.IRecipeSlotView view : slots.getSlotViews()) {
                if ((view.getRole() != RecipeIngredientRole.INPUT
                        && view.getRole() != RecipeIngredientRole.OUTPUT)
                        || !(view instanceof mezz.jei.api.gui.ingredient.IRecipeSlotDrawable)) {
                    continue;
                }
                mezz.jei.api.gui.ingredient.IRecipeSlotDrawable drawable =
                        (mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view;
                String slotKey = RecipeGhostHandler.slotKey(slots.getSlotViews(), drawable, displayedRecipe,
                        editableModel);
                if (isEditableSlotKey(slotKey) && acceptsKind(editableModel, slotKey, draggedKind)) {
                    Rect2i screenArea = JeiRecipeIntrospection.screenTargetArea(layout.getRecipeLayout(), drawable);
                    String areaKey = screenArea.getX() + ":" + screenArea.getY() + ":"
                            + screenArea.getWidth() + ":" + screenArea.getHeight();
                    if (seenAreas.add(areaKey)) {
                        targets.add(new RecipeTarget(editableModel, slotKey, slots, screenArea, displayedRecipe));
                    }
                }
            }
        }
        return targets;
    }

    /**
     * Whether the drag of {@code kind} may be dropped on one slot.
     *
     * <p>A slot whose model ingredient says what kind it holds accepts exactly
     * that kind, so a fluid is never offered an item field and vice versa. A slot
     * the model could not read (an empty optional output, a result carrying a
     * component patch) keeps the historical item behaviour: it is offered and the
     * drop is refused with the reason, rather than silently not highlighting. A
     * non-item drag is never offered such a slot, because nothing there is known
     * to hold a fluid or a chemical.
     */
    private static boolean acceptsKind(EditorModel model, String slotKey, IngredientKind draggedKind) {
        for (cc.sighs.JEIEditor.editor.EditorSlot slot : model.slots()) {
            if (slotKey.equals(slot.key())) {
                return slot.ingredient() == null
                        ? draggedKind == IngredientKind.ITEM
                        : slot.ingredient().kind() == draggedKind;
            }
        }
        return false;
    }

    /**
     * Slot keys the ghost drag can place into: every input, and any output -
     * {@code output} on a single-output page, {@code output.0 .. output.M-1} on
     * a page that draws several.
     */
    private static boolean isEditableSlotKey(String slotKey) {
        return slotKey != null
                && (slotKey.startsWith("input.") || RecipeFieldMapping.outputIndex(slotKey) >= 0);
    }

    private static boolean isExistingAnvil(EditorModel model) {
        return model != null && "jei:anvil".equals(model.serializerId())
                && !RecipeCreationAdapter.isCreatedModel(model);
    }

    /** Returns every layout currently shown by JEI through the compatibility boundary. */
    private static List<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>> visibleRecipeLayouts(RecipesGui gui) {
        return JeiRecipeIntrospection.visibleLayouts(gui);
    }

    /** Places the creation ID field in JEI's recipe pagination strip. */
    static int recipeIdInputY(RecipesGui gui, int inputHeight) {
        Optional<Rect2i> pageArea = JeiRecipeIntrospection.recipePageNavigationArea(gui);
        if (pageArea.isPresent()) {
            Rect2i area = pageArea.get();
            return area.getY() + Math.max(0, (area.getHeight() - inputHeight) / 2);
        }
        int firstSlotY = Integer.MAX_VALUE;
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            IRecipeSlotsView slots = layout.getRecipeLayout().getRecipeSlotsView();
            for (mezz.jei.api.gui.ingredient.IRecipeSlotView view : slots.getSlotViews()) {
                if (!(view instanceof mezz.jei.api.gui.ingredient.IRecipeSlotDrawable)) {
                    continue;
                }
                Rect2i area = JeiRecipeIntrospection.screenTargetArea(
                        layout.getRecipeLayout(),
                        (mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view);
                firstSlotY = Math.min(firstSlotY, area.getY());
            }
        }
        if (firstSlotY != Integer.MAX_VALUE) {
            // The first slot starts below JEI's category header. The page
            // counter strip is roughly one compact widget row above it.
            return Math.max(22, firstSlotY - 20 + (20 - inputHeight) / 2);
        }
        return Math.max(22, gui.height / 2 - 230);
    }

    static Set<String> currentPageRecipeIds(RecipesGui gui) {
        Set<String> result = new HashSet<String>();
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            Object recipe = layout.getRecipeLayout().getRecipe();
            IRecipeCategory<?> category = layout.getRecipeLayout().getRecipeCategory();
            Optional<ResourceLocation> id = JeiRecipeIntrospection.recipeId(category, recipe);
            if (id.isPresent()) {
                result.add(id.get().toString());
            } else {
                Optional<RecipeHolder<?>> holder = resolveRecipeHolder(recipe, category);
                holder.ifPresent(value -> result.add(value.id().toString()));
            }
        }
        return result;
    }

    /** Replace a generated JEI fuel entry with the locally edited burn time. */
    private static void ensureFuelPreview(Object displayedRecipe, RecipePatch patch) {
        if (!(displayedRecipe instanceof IJeiFuelingRecipe) || !FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            return;
        }
        int burnTime = FuelRecipeEditorAdapter.burnTime(patch);
        if (burnTime < 1) {
            return;
        }
        IJeiFuelingRecipe current = (IJeiFuelingRecipe) displayedRecipe;
        if (current.getBurnTime() == burnTime) {
            fuelPreviewTimes.put(patch.recipeId(), Integer.valueOf(burnTime));
            return;
        }
        Integer applied = fuelPreviewTimes.get(patch.recipeId());
        if (applied != null && applied.intValue() == burnTime) {
            return;
        }
        Optional<mezz.jei.api.runtime.IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return;
        }
        ResourceLocation itemId = FuelRecipeEditorAdapter.itemId(patch).orElse(null);
        if (itemId == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(itemId)) {
            return;
        }
        ItemStack item = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId));
        runtime.get().getRecipeManager().hideRecipes(RecipeTypes.FUELING,
                java.util.Collections.singletonList(current));
        runtime.get().getRecipeManager().addRecipes(RecipeTypes.FUELING,
                java.util.Collections.<IJeiFuelingRecipe>singletonList(
                        new FuelingRecipe(java.util.Collections.singletonList(item), burnTime)));
        fuelPreviewTimes.put(patch.recipeId(), Integer.valueOf(burnTime));
    }

    /** Applies a saved/reset fuel value to JEI's generated fuel list. */
    static void applyFuelResult(List<RecipePatch> submitted) {
        if (submitted == null) {
            return;
        }
        Optional<mezz.jei.api.runtime.IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return;
        }
        for (RecipePatch patch : submitted) {
            if (!FuelRecipeEditorAdapter.isFuelPatch(patch)) {
                continue;
            }
            FuelOverrideState.get(FuelRecipeEditorAdapter.itemId(patch).orElse(null)).ifPresent(value ->
                    replaceFuelRecipes(runtime.get(), FuelRecipeEditorAdapter.itemId(patch).get(), value.intValue()));
        }
    }

    static void applyFuelReset(ResourceLocation itemId) {
        Optional<mezz.jei.api.runtime.IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent() || itemId == null
                || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(itemId)) {
            return;
        }
        ItemStack stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId));
        int burnTime = stack.getBurnTime(null);
        if (burnTime > 0) {
            replaceFuelRecipes(runtime.get(), itemId, burnTime);
        } else {
            hideFuelRecipes(runtime.get(), itemId);
        }
    }

    /** Re-registers persisted synthetic anvil pages after a server reload. */
    static void applyCreatedAnvilPatches(List<RecipePatch> patches) {
        if (patches == null) {
            return;
        }
        Set<ResourceLocation> removed = new HashSet<ResourceLocation>();
        for (RecipePatch patch : patches) {
            if (patch == null || !"jei:anvil".equals(patch.serializerId())) {
                continue;
            }
            if (RecipePatchSemantics.isDeletion(patch)) {
                createdAnvilPatches.remove(patch.recipeId());
                ResourceLocation uid = ResourceLocation.tryParse(patch.recipeId());
                if (uid != null) {
                    removed.add(uid);
                }
            } else if (RecipePatchSemantics.isCreation(patch)) {
                createdAnvilPatches.put(patch.recipeId(), patch);
            }
        }
        hideCreatedAnvilRecipes(removed);
        scheduleCreatedAnvilRestore();
    }

    /** Replaces the client snapshot received from the server after login or reload. */
    static void replaceCreatedAnvilPatches(List<RecipePatch> patches) {
        Set<ResourceLocation> removed = new HashSet<ResourceLocation>();
        for (String recipeId : new HashSet<String>(createdAnvilPatches.keySet())) {
            ResourceLocation uid = ResourceLocation.tryParse(recipeId);
            if (uid != null) {
                removed.add(uid);
            }
        }
        createdAnvilPatches.clear();
        if (patches != null) {
            for (RecipePatch patch : patches) {
                if (patch != null && JeiVanillaRecipeEditorAdapter.isCreatedAnvilPatch(patch)) {
                    createdAnvilPatches.put(patch.recipeId(), patch);
                    ResourceLocation uid = ResourceLocation.tryParse(patch.recipeId());
                    if (uid != null) {
                        removed.remove(uid);
                    }
                }
            }
        }
        hideCreatedAnvilRecipes(removed);
        scheduleCreatedAnvilRestore();
    }

    static RecipePatch createdAnvilPatch(String recipeId) {
        return recipeId == null ? null : createdAnvilPatches.get(recipeId);
    }

    /**
     * JEI rebuilds its recipe maps from a RecipesUpdatedEvent after the
     * vanilla client recipe manager changes. Queue synthetic entries until
     * that rebuild has completed; registering them from inside the event can
     * otherwise be overwritten by JEI a moment later.
     */
    static void scheduleCreatedAnvilRestore() {
        createdAnvilRestorePending = true;
    }

    /** Runs after JEI has finished handling the recipe-update event. */
    static void tickCreatedAnvilRestore() {
        if (Minecraft.getInstance().level == null
                || !Internal.getOptionalJeiRuntime().isPresent()) {
            return;
        }
        if (createdAnvilRestorePending) {
            createdAnvilRestorePending = false;
            restoreCreatedAnvilRecipes();
            createdAnvilVisibilityChecksRemaining = CREATED_ANVIL_VISIBILITY_CHECK_TICKS;
            return;
        }
        if (createdAnvilVisibilityChecksRemaining <= 0) {
            return;
        }
        createdAnvilVisibilityChecksRemaining--;
        mezz.jei.api.runtime.IJeiRuntime runtime = Internal.getOptionalJeiRuntime().get();
        if (!createdAnvilRecipesVisible(runtime)) {
            restoreCreatedAnvilRecipes();
        }
    }

    /** Reapplies the client-side JEI entries after JEI rebuilds its recipe list. */
    static void restoreCreatedAnvilRecipes() {
        if (createdAnvilPatches.isEmpty() || Minecraft.getInstance().level == null) {
            return;
        }
        Optional<mezz.jei.api.runtime.IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return;
        }
        List<IJeiAnvilRecipe> recipes = new ArrayList<IJeiAnvilRecipe>();
        for (RecipePatch patch : createdAnvilPatches.values()) {
            Optional<IJeiAnvilRecipe> recipe = createdAnvilRecipe(patch);
            recipe.ifPresent(recipes::add);
        }
        if (recipes.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (IJeiAnvilRecipe desired : recipes) {
            List<IJeiAnvilRecipe> existing = runtime.get().getRecipeManager()
                    .createRecipeLookup(RecipeTypes.ANVIL).includeHidden().get()
                    .filter(recipe -> desired.getUid().equals(recipe.getUid()))
                    .collect(java.util.stream.Collectors.toList());
            IJeiAnvilRecipe matching = existing.stream()
                    .filter(recipe -> sameAnvilRecipe(desired, recipe)).findFirst().orElse(null);
            if (matching != null) {
                // A previous restore may have hidden this exact object. Make
                // it visible again instead of appending duplicate recipes.
                runtime.get().getRecipeManager().unhideRecipes(RecipeTypes.ANVIL,
                        java.util.Collections.singletonList(matching));
                List<IJeiAnvilRecipe> stale = existing.stream()
                        .filter(value -> value != matching).collect(java.util.stream.Collectors.toList());
                if (!stale.isEmpty()) {
                    runtime.get().getRecipeManager().hideRecipes(RecipeTypes.ANVIL, stale);
                }
            } else {
                if (!existing.isEmpty()) {
                    runtime.get().getRecipeManager().hideRecipes(RecipeTypes.ANVIL, existing);
                }
                runtime.get().getRecipeManager().addRecipes(RecipeTypes.ANVIL,
                        java.util.Collections.singletonList(desired));
            }
            changed = true;
        }
        // RecipesGui is a long-lived JEI singleton. Reload intentionally
        // closes it, but its focused recipe lookup remains cached while the
        // screen is closed. Invalidate JEI's runtime-owned instance so the
        // next open sees newly registered synthetic recipes immediately.
        if (changed && runtime.get().getRecipesGui() instanceof RecipesGui) {
            JeiRecipeIntrospection.refreshRecipeLookup(
                    (RecipesGui) runtime.get().getRecipesGui());
        }
    }

    private static boolean createdAnvilRecipesVisible(mezz.jei.api.runtime.IJeiRuntime runtime) {
        if (createdAnvilPatches.isEmpty()) {
            return true;
        }
        List<IJeiAnvilRecipe> visible = runtime.getRecipeManager()
                .createRecipeLookup(RecipeTypes.ANVIL).get()
                .collect(java.util.stream.Collectors.toList());
        for (RecipePatch patch : createdAnvilPatches.values()) {
            Optional<IJeiAnvilRecipe> desired = createdAnvilRecipe(patch);
            if (desired.isPresent() && visible.stream()
                    .noneMatch(recipe -> sameAnvilRecipe(desired.get(), recipe))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameAnvilRecipe(IJeiAnvilRecipe left, IJeiAnvilRecipe right) {
        return left.getUid().equals(right.getUid())
                && sameStacks(left.getLeftInputs(), right.getLeftInputs())
                && sameStacks(left.getRightInputs(), right.getRightInputs())
                && sameStacks(left.getOutputs(), right.getOutputs());
    }

    private static boolean sameStacks(List<ItemStack> left, List<ItemStack> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            ItemStack leftStack = left.get(index);
            ItemStack rightStack = right.get(index);
            if (leftStack.getCount() != rightStack.getCount()
                    || !ItemStack.isSameItemSameComponents(leftStack, rightStack)) {
                return false;
            }
        }
        return true;
    }

    private static void hideCreatedAnvilRecipes(Set<ResourceLocation> uids) {
        if (uids == null || uids.isEmpty()) {
            return;
        }
        Optional<mezz.jei.api.runtime.IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return;
        }
        List<IJeiAnvilRecipe> existing = runtime.get().getRecipeManager()
                .createRecipeLookup(RecipeTypes.ANVIL).includeHidden().get()
                .filter(recipe -> uids.contains(recipe.getUid()))
                .collect(java.util.stream.Collectors.toList());
        if (!existing.isEmpty()) {
            runtime.get().getRecipeManager().hideRecipes(RecipeTypes.ANVIL, existing);
        }
    }

    private static Optional<IJeiAnvilRecipe> createdAnvilRecipe(RecipePatch patch) {
        if (patch == null || Minecraft.getInstance().level == null) {
            return Optional.empty();
        }
        ResourceLocation uid = ResourceLocation.tryParse(patch.recipeId());
        if (uid == null) {
            return Optional.empty();
        }
        Optional<ItemStack> left = cc.sighs.JEIEditor.platform.recipe.JeiVanillaRecipeEditorAdapter
                .stackFromFields(patch, "input.0", Minecraft.getInstance().level.registryAccess());
        Optional<ItemStack> right = cc.sighs.JEIEditor.platform.recipe.JeiVanillaRecipeEditorAdapter
                .stackFromFields(patch, "input.1", Minecraft.getInstance().level.registryAccess());
        Optional<ItemStack> output = cc.sighs.JEIEditor.platform.recipe.JeiVanillaRecipeEditorAdapter
                .stackFromFields(patch, "output", Minecraft.getInstance().level.registryAccess());
        if (!left.isPresent() || !right.isPresent() || !output.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new AnvilRecipe(
                Collections.singletonList(left.get().copy()),
                Collections.singletonList(right.get().copy()),
                Collections.singletonList(output.get().copy()), uid));
    }

    private static void hideFuelRecipes(mezz.jei.api.runtime.IJeiRuntime runtime, ResourceLocation itemId) {
        List<IJeiFuelingRecipe> matches = runtime.getRecipeManager()
                .createRecipeLookup(RecipeTypes.FUELING).includeHidden().get()
                .filter(recipe -> recipe.getInputs().size() == 1
                        && itemId.equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                        recipe.getInputs().get(0).getItem())))
                .collect(java.util.stream.Collectors.toList());
        if (!matches.isEmpty()) {
            runtime.getRecipeManager().hideRecipes(RecipeTypes.FUELING, matches);
        }
    }

    private static void replaceFuelRecipes(mezz.jei.api.runtime.IJeiRuntime runtime,
                                           ResourceLocation itemId, int burnTime) {
        List<IJeiFuelingRecipe> matches = runtime.getRecipeManager()
                .createRecipeLookup(RecipeTypes.FUELING).includeHidden().get()
                .filter(recipe -> recipe.getInputs().size() == 1
                        && itemId.equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                        recipe.getInputs().get(0).getItem())))
                .collect(java.util.stream.Collectors.toList());
        if (!matches.isEmpty()) {
            runtime.getRecipeManager().hideRecipes(RecipeTypes.FUELING, matches);
            runtime.getRecipeManager().addRecipes(RecipeTypes.FUELING,
                    java.util.Collections.<IJeiFuelingRecipe>singletonList(new FuelingRecipe(
                            java.util.Collections.singletonList(new ItemStack(
                                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId))), burnTime)));
        }
    }

    private static final class RecipeTarget {
        private final cc.sighs.JEIEditor.editor.EditorModel model;
        private final String slotKey;
        private final IRecipeSlotsView slots;
        private final Rect2i area;
        private final Object recipe;

        private RecipeTarget(cc.sighs.JEIEditor.editor.EditorModel model, String slotKey, IRecipeSlotsView slots,
                             Rect2i area, Object recipe) {
            this.model = model;
            this.slotKey = slotKey;
            this.slots = slots;
            this.area = area;
            this.recipe = recipe;
        }
    }

    /**
     * One tag page under the pointer: its model and what the pointer is on inside it.
     *
     * <p>Exactly one of the two is set. {@code tagSlotKey} is the model's own slot
     * key, which is the tag's slot - the one holding every member - and
     * {@code memberId} is the member a member slot shows. A tag page has no third
     * kind of slot, so an empty {@code memberId} means the pointer is not on any
     * slot this descriptor knows.
     */
    static final class TagPageTarget {
        private final EditorModel model;
        private final String tagSlotKey;
        private final String memberId;

        private TagPageTarget(EditorModel model, String tagSlotKey, String memberId) {
            this.model = model;
            this.tagSlotKey = tagSlotKey;
            this.memberId = memberId;
        }

        /** The tag page's model, whose recipe id is the tag patch's identity. */
        EditorModel model() {
            return model;
        }

        /** True when the pointer is on the tag's own slot rather than on a member. */
        boolean onTagSlot() {
            return tagSlotKey != null;
        }

        /** The member the pointer is on, or null when it is on the tag's own slot. */
        String memberId() {
            return memberId;
        }
    }

    private static final class RecipeGhostHandler implements IGhostIngredientHandler<RecipesGui> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(RecipesGui gui, ITypedIngredient<I> ingredient, boolean doStart) {
            // The dragged ingredient is addressed by its kind, id and amount, so a
            // fluid or a chemical becomes a target exactly like an item. A kind the
            // editor cannot address at all (an information or energy ingredient)
            // yields no targets instead of throwing.
            cc.sighs.JEIEditor.editor.EditorIngredient dragged = draggedIngredient(ingredient);
            if (!doStart || !ClientEditorState.isEditing() || dragged == null) {
                return Collections.emptyList();
            }

            List<RecipeTarget> editableTargets = visibleEditableTargets(gui, dragged.kind());
            if (!editableTargets.isEmpty()) {
                List<Rect2i> highlightAreas = new ArrayList<Rect2i>();
                for (RecipeTarget target : editableTargets) {
                    if (target.area != null) {
                        highlightAreas.add(target.area);
                    }
                }
                ClientEditorState.setGhostHighlightAreas(highlightAreas);
                List<Target<I>> targets = new ArrayList<Target<I>>(editableTargets.size());
                for (RecipeTarget recipeTarget : editableTargets) {
                    targets.add(new Target<I>() {
                        @Override
                        public Rect2i getArea() {
                            return recipeTarget.area;
                        }

                        @Override
                        public void accept(I ignored) {
                            applyAtTarget(recipeTarget, dragged);
                        }
                    });
                }
                return targets;
            }
            ClientEditorState.clearGhostHighlightAreas();
            return Collections.emptyList();
        }

        /**
         * The editor ingredient a dragged JEI ingredient stands for.
         *
         * <p>A tag needs its own branch: the tag type also offers its first member as a cheat
         * stack, so the generic conversion would quietly turn a dragged tag into that member -
         * the one thing the tag entry exists to avoid.
         */
        private static <I> cc.sighs.JEIEditor.editor.EditorIngredient draggedIngredient(
                ITypedIngredient<I> ingredient) {
            if (ingredient != null && ingredient.getIngredient() instanceof TagIngredient) {
                TagIngredient tag = (TagIngredient) ingredient.getIngredient();
                IngredientKind kind = tag.fluid() ? IngredientKind.FLUID : IngredientKind.ITEM;
                // A fluid tag needs a volume; the smallest one is a placeholder that
                // sizedForTarget replaces with the target slot's own amount.
                return new cc.sighs.JEIEditor.editor.EditorIngredient(kind, tag.tagText(),
                        kind == IngredientKind.ITEM ? 1 : kind.minAmount());
            }
            return RecipeAdapterSupport.editorIngredient(ingredient).orElse(null);
        }

        /**
         * A fluid or chemical tag carries a placeholder volume, so the target slot's own amount is
         * borrowed instead of inventing one - the recipe expected that amount before the edit.
         */
        private static cc.sighs.JEIEditor.editor.EditorIngredient sizedForTarget(
                RecipeTarget target, cc.sighs.JEIEditor.editor.EditorIngredient ingredient) {
            if (ingredient.kind() == IngredientKind.ITEM) {
                return ingredient;
            }
            for (cc.sighs.JEIEditor.editor.EditorSlot slot : target.model.slots()) {
                if (slot.key().equals(target.slotKey) && slot.ingredient() != null
                        && slot.ingredient().kind() == ingredient.kind()) {
                    return new cc.sighs.JEIEditor.editor.EditorIngredient(ingredient.kind(),
                            ingredient.id(), slot.ingredient().amount());
                }
            }
            return ingredient;
        }

        private static void applyAtTarget(RecipeTarget target, cc.sighs.JEIEditor.editor.EditorIngredient ingredient) {
            try {
                ClientEditorState.rememberTarget(target.model, target.slotKey, target.slots, target.recipe);
                ClientEditorState.setPendingPatch(RecipeEditorAdapters.replaceSlot(
                        target.model, target.slotKey, sizedForTarget(target, ingredient)));
                ClientEditorState.setLastDrop("Placed " + ingredient.kind().id() + " " + ingredient.id()
                        + " in " + target.slotKey);
            } catch (IllegalArgumentException exception) {
                ClientEditorState.setLastDrop(exception.getMessage());
            }
        }

        private static String slotKey(List<mezz.jei.api.gui.ingredient.IRecipeSlotView> slots,
                                      mezz.jei.api.gui.ingredient.IRecipeSlotDrawable target,
                                      Object displayedRecipe, EditorModel model) {
            return CraftingSlotMapper.slotKey(model, displayedRecipe, slots, target);
        }

        @Override
        public void onComplete() {
            ClientEditorState.clearGhostHighlightAreas();
        }

        @Override
        public boolean shouldHighlightTargets() {
            // JEI's built-in overlay is opaque green and has no color setting.
            // ClientEditorState draws a softer overlay for these targets.
            return false;
        }
    }
}
