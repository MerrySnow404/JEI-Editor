package cc.sighs.JEIEditor.client;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.gui.widgets.ITextWidget;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import mezz.jei.api.gui.placement.HorizontalAlignment;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.common.util.ImmutableSize2i;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import mezz.jei.gui.recipes.RecipesGui;
import mezz.jei.gui.elements.IconButton;
import mezz.jei.gui.input.GuiTextFieldFilter;
import mezz.jei.gui.overlay.IngredientListOverlay;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import mezz.jei.gui.overlay.ingredients.IIngredientGridView;
import mezz.jei.library.plugins.vanilla.crafting.CraftingRecipeCategory;
import cc.sighs.JEIEditor.platform.recipe.RecipeViewerAliases;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Compatibility boundary for JEI 19.44's internal recipe GUI state.
 *
 * JEI exposes the layout and slot abstractions publicly, but it does not
 * expose the complete list of layouts currently visible on a page. All
 * private access is intentionally isolated here so the editor does not grow
 * version-specific reflection throughout its input and rendering code.
 */
public final class JeiRecipeIntrospection {
    private static Field recipesGuiLayoutsField;
    private static Field recipeLayoutsListField;
    private static Field recipesGuiLogicField;
    private static Field recipesGuiBookmarksField;
    private static Field borderPaddingField;
    private static Field recipeLayoutWidgetsField;
    private static Field recipesGuiPreviousPageField;
    private static Field recipesGuiNextPageField;
    private static Field ingredientOverlayContentsField;
    private static Field ingredientOverlaySearchField;
    private static Field ingredientOverlayConfigButtonField;
    private static Method ingredientContentsMouseOverMethod;
    private static Method iconButtonAreaMethod;
    private static Method recipeLayoutsAreaMethod;
    private static Method ensureRecipeExtrasMethod;
    private static Field textWidgetTextField;
    private static Field textWidgetHorizontalAlignmentField;
    private static final Map<ITextWidget, ScreenPosition> hiddenRecipeTextPositions =
            new WeakHashMap<ITextWidget, ScreenPosition>();
    private static final Map<IRecipeLayoutDrawable<?>, Map<IRecipeSlotDrawable, ScreenPosition>> slotOffsets =
            new WeakHashMap<IRecipeLayoutDrawable<?>, Map<IRecipeSlotDrawable, ScreenPosition>>();

    private JeiRecipeIntrospection() {
    }

    static List<IRecipeLayoutWithButtons<?>> visibleLayouts(RecipesGui gui) {
        if (gui == null) {
            return Collections.emptyList();
        }

        try {
            Object layouts = getRecipesGuiLayouts(gui);
            if (layouts != null) {
                List<IRecipeLayoutWithButtons<?>> current = copyLayoutList(getRecipeLayoutsList(layouts));
                if (current != null) {
                    // An empty list is a valid page state. Do not rediscover
                    // layouts by scanning arbitrary screen coordinates.
                    return current;
                }
            }

            List<IRecipeLayoutWithButtons<?>> generated = invokeVisibleLayoutGenerator(gui);
            return generated == null ? Collections.<IRecipeLayoutWithButtons<?>>emptyList() : generated;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("removal")
    static Rect2i screenArea(IRecipeLayoutDrawable<?> layout, IRecipeSlotDrawable slot) {
        return screenArea(layout, slot, true);
    }

    /**
     * Returns the screen-space slot rectangle. Background-inclusive bounds are
     * useful for painting a visual marker, while drag targets must use the
     * actual slot rect so a category background cannot become a huge target.
     */
    static Rect2i screenTargetArea(IRecipeLayoutDrawable<?> layout, IRecipeSlotDrawable slot) {
        return screenArea(layout, slot, false);
    }

    @SuppressWarnings("removal")
    private static Rect2i screenArea(IRecipeLayoutDrawable<?> layout, IRecipeSlotDrawable slot,
                                     boolean includeBackground) {
        Rect2i slotArea = includeBackground ? slot.getAreaIncludingBackground() : slot.getRect();
        ScreenPosition offset = findSlotOffset(layout, slot);
        return new Rect2i(offset.x() + slotArea.getX(),
                offset.y() + slotArea.getY(),
                slotArea.getWidth(), slotArea.getHeight());
    }

    /**
     * Slots in JEI recipe widgets are positioned relative to their parent.
     * RecipeSlotUnderMouse carries the parent offset all the way back to the
     * screen; use it instead of assuming every slot is a direct child of the
     * recipe layout. The direct-child path is checked first because it avoids
     * a scan for the overwhelmingly common vanilla layouts.
     */
    @SuppressWarnings("removal")
    private static ScreenPosition findSlotOffset(IRecipeLayoutDrawable<?> layout,
                                                  IRecipeSlotDrawable slot) {
        // A slot that really is a direct child of the layout is placed by the layout's own
        // origin: `IRecipeSlotDrawable.getRect()` is layout-relative, and JEI's own hit test
        // agrees with that. Trusting the offset JEI reports for a probe point instead is
        // wrong for a category whose layout carries a header: mekanism's activating page
        // reports one that lands 42 px low, which paints the pending-slot highlight (and the
        // edited amount) beside the slot rather than on it. Nested widgets fall through to
        // the passes below, which are what the offset lookup exists for.
        Rect2i origin = layout.getRect();
        ScreenPosition layoutOrigin = new ScreenPosition(origin.getX(), origin.getY());
        if (isSlotAt(layout, slot, layoutOrigin)) {
            return layoutOrigin;
        }
        Rect2i recipeArea = layout.getRect();
        Rect2i slotArea = slot.getRect();
        int centerX = recipeArea.getX() + slotArea.getX() + Math.max(0, slotArea.getWidth() / 2);
        int centerY = recipeArea.getY() + slotArea.getY() + Math.max(0, slotArea.getHeight() / 2);
        Optional<RecipeSlotUnderMouse> direct = layout.getSlotUnderMouse(centerX, centerY);
        if (direct.isPresent() && direct.get().slot() == slot) {
            return direct.get().offset();
        }

        Map<IRecipeSlotDrawable, ScreenPosition> cached = slotOffsets.get(layout);
        if (cached != null) {
            ScreenPosition offset = cached.get(slot);
            if (offset != null && isSlotAt(layout, slot, offset)) {
                return offset;
            }
        }

        // Nested widgets (for example JEI scroll grids) do not expose their
        // parent position through IRecipeSlotDrawable. Probe the layout's
        // actual input router; every normal slot is at least 16x16, so a
        // one-pixel scan is bounded by the recipe rectangle and only runs for
        // these non-direct slots.
        int left = recipeArea.getX();
        int top = recipeArea.getY();
        int right = left + recipeArea.getWidth();
        int bottom = top + recipeArea.getHeight();
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                Optional<RecipeSlotUnderMouse> hit = layout.getSlotUnderMouse(x, y);
                if (hit.isPresent() && hit.get().slot() == slot) {
                    ScreenPosition offset = hit.get().offset();
                    if (cached == null) {
                        cached = new WeakHashMap<IRecipeSlotDrawable, ScreenPosition>();
                        slotOffsets.put(layout, cached);
                    }
                    cached.put(slot, offset);
                    return offset;
                }
            }
        }
        // Preserve the old direct-child fallback if JEI changes its input
        // routing or the slot is not currently visible in a scrolled widget.
        return new ScreenPosition(recipeArea.getX(), recipeArea.getY());
    }

    @SuppressWarnings("removal")
    private static boolean isSlotAt(IRecipeLayoutDrawable<?> layout, IRecipeSlotDrawable slot,
                                    ScreenPosition offset) {
        Rect2i rect = slot.getRect();
        int x = offset.x() + rect.getX() + Math.max(0, rect.getWidth() / 2);
        int y = offset.y() + rect.getY() + Math.max(0, rect.getHeight() / 2);
        Optional<RecipeSlotUnderMouse> hit = layout.getSlotUnderMouse(x, y);
        return hit.isPresent() && hit.get().slot() == slot;
    }

    /** Temporarily moves JEI text widgets out of a recipe layout while a
     * client-side editor draws an interactive replacement in the same area. */
    static void setRecipeTextVisible(IRecipeLayoutDrawable<?> layout, boolean visible) {
        if (layout == null) {
            return;
        }
        try {
            Method ensureExtras = ensureRecipeExtrasMethod;
            if (ensureExtras == null || !ensureExtras.getDeclaringClass().isInstance(layout)) {
                ensureExtras = layout.getClass().getMethod("ensureRecipeExtrasAreCreated");
                ensureExtras.setAccessible(true);
                ensureRecipeExtrasMethod = ensureExtras;
            }
            ensureExtras.invoke(layout);

            Field widgetsField = recipeLayoutWidgetsField;
            if (widgetsField == null || !widgetsField.getDeclaringClass().isInstance(layout)) {
                widgetsField = findNamedField(layout.getClass(), "allWidgets", List.class);
                recipeLayoutWidgetsField = widgetsField;
            }
            if (widgetsField == null) {
                return;
            }
            Object widgets = widgetsField.get(layout);
            if (!(widgets instanceof List<?>)) {
                return;
            }
            for (Object widget : (List<?>) widgets) {
                if (!(widget instanceof ITextWidget) || !(widget instanceof IRecipeWidget)) {
                    continue;
                }
                ITextWidget text = (ITextWidget) widget;
                if (visible) {
                    ScreenPosition original = hiddenRecipeTextPositions.remove(text);
                    if (original != null) {
                        text.setPosition(original.x(), original.y());
                    }
                } else if (!hiddenRecipeTextPositions.containsKey(text)) {
                    hiddenRecipeTextPositions.put(text, ((IRecipeWidget) text).getPosition());
                    text.setPosition(-10000, -10000);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // JEI internals are optional here; failing closed leaves its
            // original text visible instead of breaking the recipe page.
        }
    }

    static Optional<ScreenPosition> firstRecipeTextPosition(IRecipeLayoutDrawable<?> layout) {
        if (layout == null) {
            return Optional.empty();
        }
        try {
            Method ensureExtras = ensureRecipeExtrasMethod;
            if (ensureExtras == null || !ensureExtras.getDeclaringClass().isInstance(layout)) {
                ensureExtras = layout.getClass().getMethod("ensureRecipeExtrasAreCreated");
                ensureExtras.setAccessible(true);
                ensureRecipeExtrasMethod = ensureExtras;
            }
            ensureExtras.invoke(layout);
            Field widgetsField = recipeLayoutWidgetsField;
            if (widgetsField == null || !widgetsField.getDeclaringClass().isInstance(layout)) {
                widgetsField = findNamedField(layout.getClass(), "allWidgets", List.class);
                recipeLayoutWidgetsField = widgetsField;
            }
            if (widgetsField == null) {
                return Optional.empty();
            }
            Object widgets = widgetsField.get(layout);
            if (!(widgets instanceof List<?>)) {
                return Optional.empty();
            }
            for (Object widget : (List<?>) widgets) {
                if (widget instanceof ITextWidget && widget instanceof IRecipeWidget) {
                    return Optional.of(originalRecipeWidgetPosition((ITextWidget) widget));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Keep the editor usable if JEI changes its private widget list.
        }
        return Optional.empty();
    }

    /**
     * Returns the actual JEI text widget geometry for the anvil cost label.
     * Text widgets are category-owned and their position is relative to the
     * recipe layout. This keeps callers independent from category dimensions
     * and from the screen's current page placement.
     */
    static Optional<RecipeTextGeometry> anvilCostTextGeometry(IRecipeLayoutDrawable<?> layout) {
        if (layout == null) {
            return Optional.empty();
        }
        try {
            Method ensureExtras = ensureRecipeExtrasMethod;
            if (ensureExtras == null || !ensureExtras.getDeclaringClass().isInstance(layout)) {
                ensureExtras = layout.getClass().getMethod("ensureRecipeExtrasAreCreated");
                ensureExtras.setAccessible(true);
                ensureRecipeExtrasMethod = ensureExtras;
            }
            ensureExtras.invoke(layout);

            Field widgetsField = recipeLayoutWidgetsField;
            if (widgetsField == null || !widgetsField.getDeclaringClass().isInstance(layout)) {
                widgetsField = findNamedField(layout.getClass(), "allWidgets", List.class);
                recipeLayoutWidgetsField = widgetsField;
            }
            if (widgetsField == null || !(widgetsField.get(layout) instanceof List<?>)) {
                return Optional.empty();
            }

            List<ITextWidget> textWidgets = new ArrayList<ITextWidget>();
            for (Object widget : (List<?>) widgetsField.get(layout)) {
                if (!(widget instanceof ITextWidget) || !(widget instanceof IRecipeWidget)) {
                    continue;
                }
                ITextWidget text = (ITextWidget) widget;
                textWidgets.add(text);
                if (isAnvilCostText(textWidgetString(text))) {
                    return Optional.of(recipeTextGeometry(text));
                }
            }

            // The anvil category currently contributes exactly one text
            // widget. Keep this fallback tied to JEI's widget list rather than
            // inventing a coordinate when a localization implementation hides
            // the underlying FormattedText value from reflection.
            if (textWidgets.size() == 1) {
                return Optional.of(recipeTextGeometry(textWidgets.get(0)));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Keep the editor usable if JEI changes its private widget list.
        }
        return Optional.empty();
    }

    /**
     * Rebuilds the current JEI lookup state after recipes are added through
     * the runtime API. JEI caches the focused recipe list, so adding a recipe
     * alone is not enough while a RecipesGui instance is already alive.
     */
    static void refreshRecipeLookup(RecipesGui gui) {
        if (gui == null) {
            return;
        }
        try {
            Field logicField = recipesGuiLogicField;
            if (logicField == null || !logicField.getDeclaringClass().isInstance(gui)) {
                logicField = findField(RecipesGui.class, IRecipeGuiLogic.class, false);
                recipesGuiLogicField = logicField;
            }
            if (logicField == null || !(logicField.get(gui) instanceof IRecipeGuiLogic)) {
                return;
            }
            IRecipeGuiLogic logic = (IRecipeGuiLogic) logicField.get(gui);
            Field stateField = findNamedField(logic.getClass(), "state", Object.class);
            Object state = stateField == null ? null : stateField.get(logic);
            if (state == null) {
                return;
            }
            // Keep the current category/page state. Only invalidate the two
            // JEI caches that contain the old recipe snapshot, then ask the
            // existing screen to lay itself out again.
            Method focusedMethod = state.getClass().getMethod("getFocusedRecipes");
            focusedMethod.setAccessible(true);
            Object focused = focusedMethod.invoke(state);
            if (focused != null) {
                Field recipesField = findNamedField(focused.getClass(), "recipes", List.class);
                if (recipesField != null && !Modifier.isFinal(recipesField.getModifiers())) {
                    recipesField.set(focused, null);
                } else {
                    // StaticFocusedRecipes owns an immutable snapshot. Replace
                    // that state through JEI's own lookup API so the next open
                    // is not permanently tied to the pre-reload list.
                    Method focusesMethod = state.getClass().getMethod("getFocuses");
                    focusesMethod.setAccessible(true);
                    Object focuses = focusesMethod.invoke(state);
                    IRecipeCategory<?> selected = logic.getSelectedRecipeCategory();
                    if (focuses instanceof IFocusGroup
                            && logic.showFocus((IFocusGroup) focuses)) {
                        logic.setRecipeCategory(selected);
                        return;
                    }
                }
            }
            Field layoutsField = findNamedField(logic.getClass(),
                    "cachedRecipeLayoutsWithButtons", Object.class);
            if (layoutsField != null) {
                layoutsField.set(logic, null);
            }
            Field categoryField = findNamedField(logic.getClass(), "cachedRecipeCategory", Object.class);
            if (categoryField != null) {
                categoryField.set(logic, null);
            }
            Method updateLayout = RecipesGui.class.getDeclaredMethod("updateLayout");
            updateLayout.setAccessible(true);
            updateLayout.invoke(gui);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // A stale lookup is preferable to breaking JEI if its internals
            // change; the next normal JEI open will still rebuild the state.
        }
    }

    private static boolean isAnvilCostText(String value) {
        if (value == null) {
            return false;
        }
        String marker = "JEI_EDITOR_COST_MARKER";
        String translated = net.minecraft.network.chat.Component
                .translatable("container.repair.cost", marker).getString();
        int markerIndex = translated.indexOf(marker);
        if (markerIndex < 0) {
            return false;
        }
        String prefix = translated.substring(0, markerIndex);
        String suffix = translated.substring(markerIndex + marker.length());
        return value.startsWith(prefix) && value.endsWith(suffix)
                && value.length() >= prefix.length() + suffix.length();
    }

    private static String textWidgetString(ITextWidget widget) throws ReflectiveOperationException {
        Field field = textWidgetTextField;
        if (field == null || !field.getDeclaringClass().isInstance(widget)) {
            field = findNamedField(widget.getClass(), "text", List.class);
            textWidgetTextField = field;
        }
        if (field == null || !(field.get(widget) instanceof List<?>)) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (Object value : (List<?>) field.get(widget)) {
            if (value instanceof FormattedText) {
                if (result.length() > 0) {
                    result.append('\n');
                }
                result.append(((FormattedText) value).getString());
            }
        }
        return result.toString();
    }

    private static RecipeTextGeometry recipeTextGeometry(ITextWidget widget)
            throws ReflectiveOperationException {
        HorizontalAlignment alignment = HorizontalAlignment.LEFT;
        Field field = textWidgetHorizontalAlignmentField;
        if (field == null || !field.getDeclaringClass().isInstance(widget)) {
            field = findNamedField(widget.getClass(), "horizontalAlignment", HorizontalAlignment.class);
            textWidgetHorizontalAlignmentField = field;
        }
        if (field != null && field.get(widget) instanceof HorizontalAlignment) {
            alignment = (HorizontalAlignment) field.get(widget);
        }
        return new RecipeTextGeometry(originalRecipeWidgetPosition(widget),
                widget.getWidth(), widget.getHeight(), alignment);
    }

    private static ScreenPosition originalRecipeWidgetPosition(ITextWidget widget) {
        ScreenPosition original = hiddenRecipeTextPositions.get(widget);
        return original == null ? ((IRecipeWidget) widget).getPosition() : original;
    }

    static final class RecipeTextGeometry {
        private final ScreenPosition position;
        private final int width;
        private final int height;
        private final HorizontalAlignment horizontalAlignment;

        private RecipeTextGeometry(ScreenPosition position, int width, int height,
                                   HorizontalAlignment horizontalAlignment) {
            this.position = position;
            this.width = width;
            this.height = height;
            this.horizontalAlignment = horizontalAlignment;
        }

        ScreenPosition position() {
            return position;
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }

        HorizontalAlignment horizontalAlignment() {
            return horizontalAlignment;
        }
    }

    static void restoreHiddenRecipeText() {
        for (Map.Entry<ITextWidget, ScreenPosition> entry :
                new ArrayList<Map.Entry<ITextWidget, ScreenPosition>>(hiddenRecipeTextPositions.entrySet())) {
            ITextWidget widget = entry.getKey();
            ScreenPosition position = entry.getValue();
            if (widget != null && position != null) {
                widget.setPosition(position.x(), position.y());
            }
        }
        hiddenRecipeTextPositions.clear();
    }

    /** Returns true when JEI owns this point through a sidebar or exclusion area. */
    static boolean isOverlayAt(Screen screen, double mouseX, double mouseY) {
        Optional<IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent() || screen == null) {
            return false;
        }
        try {
            // JEI's ingredient list owns its complete background rectangle,
            // including empty cells and the padding around them. Looking only
            // for an ingredient misses those pixels and lets the editor menu
            // steal a right-click from JEI.
            Object ingredientOverlay = runtime.get().getIngredientListOverlay();
            if (ingredientOverlay instanceof IngredientListOverlay) {
                IngredientListOverlay list = (IngredientListOverlay) ingredientOverlay;
                boolean listDisplayed = list.isListDisplayed();
                // IngredientGridWithNavigation owns the exact sidebar hitbox.
                // Its public API is deliberately narrower than the concrete
                // implementation, so invoke the JEI method reflectively rather
                // than reconstructing the grid geometry here.
                Field contentsField = ingredientOverlayContentsField;
                if (contentsField == null || !contentsField.getDeclaringClass().isInstance(list)) {
                    contentsField = findNamedField(list.getClass(), "contents", Object.class);
                    ingredientOverlayContentsField = contentsField;
                }
                Object contents = contentsField == null ? null : contentsField.get(list);
                if (contents != null) {
                    Method mouseOver = ingredientContentsMouseOverMethod;
                    if (mouseOver == null || !mouseOver.getDeclaringClass().isInstance(contents)) {
                        try {
                            mouseOver = contents.getClass().getMethod("isMouseOver", double.class, double.class);
                            mouseOver.setAccessible(true);
                            ingredientContentsMouseOverMethod = mouseOver;
                        } catch (NoSuchMethodException ignored) {
                            mouseOver = null;
                        }
                    }
                    if (listDisplayed && mouseOver != null
                            && Boolean.TRUE.equals(mouseOver.invoke(contents, mouseX, mouseY))) {
                        return true;
                    }
                    // Keep the grid-view fallback for JEI implementations that
                    // do not expose the concrete hit-test method.
                    if (listDisplayed && contents instanceof IIngredientGridView
                            && contains(((IIngredientGridView) contents).getBackgroundArea(), mouseX, mouseY)) {
                        return true;
                    }
                }

                if (listDisplayed) {
                    // The search field sits outside the grid background but
                    // is still part of the same JEI-owned right sidebar.
                    Field searchField = ingredientOverlaySearchField;
                    if (searchField == null || !searchField.getDeclaringClass().isInstance(list)) {
                        searchField = findNamedField(list.getClass(), "searchField", Object.class);
                        ingredientOverlaySearchField = searchField;
                    }
                    if (searchField != null && searchField.get(list) instanceof GuiTextFieldFilter
                            && ((GuiTextFieldFilter) searchField.get(list)
                            ).isMouseOver(mouseX, mouseY)) {
                        return true;
                    }
                }

                // The gear button is part of the same overlay input handler
                // even though it is drawn in the foreground rather than in
                // the grid/search background.
                Field configButtonField = ingredientOverlayConfigButtonField;
                if (configButtonField == null || !configButtonField.getDeclaringClass().isInstance(list)) {
                    configButtonField = findNamedField(list.getClass(), "configButton", Object.class);
                    ingredientOverlayConfigButtonField = configButtonField;
                }
                Object configButton = configButtonField == null ? null : configButtonField.get(list);
                if (configButton instanceof IconButton
                        && ((IconButton) configButton).isVisible()
                        && ((IconButton) configButton).isMouseOver(mouseX, mouseY)) {
                    return true;
                }
            }

            Object bookmarkOverlay = runtime.get().getBookmarkOverlay();
            if (bookmarkOverlay instanceof BookmarkOverlay) {
                BookmarkOverlay bookmarks = (BookmarkOverlay) bookmarkOverlay;
                if (bookmarks.isListDisplayed() && bookmarks.isMouseOver(mouseX, mouseY)) {
                    return true;
                }
            }

            if (runtime.get().getScreenHelper().getGuiExclusionAreas(screen)
                    .anyMatch(area -> contains(area, mouseX, mouseY))) {
                return true;
            }
            return runtime.get().getScreenHelper()
                    .getClickableIngredientUnderMouse(screen, mouseX, mouseY)
                    .findAny().isPresent();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Returns the exact strip JEI uses to draw its page counter. In JEI
     * 19.44, RecipesGui.render() unions previousPage.getArea() and
     * nextPage.getArea(), then centers pageString in that union. Keeping the
     * editor field on the same geometry avoids guesses based on recipe slots.
     */
    static Optional<Rect2i> recipePageNavigationArea(RecipesGui gui) {
        if (gui == null) {
            return Optional.empty();
        }
        try {
            Field previousField = recipesGuiPreviousPageField;
            if (previousField == null || !previousField.getDeclaringClass().isInstance(gui)) {
                previousField = findNamedField(RecipesGui.class, "previousPage", Object.class);
                recipesGuiPreviousPageField = previousField;
            }
            Field nextField = recipesGuiNextPageField;
            if (nextField == null || !nextField.getDeclaringClass().isInstance(gui)) {
                nextField = findNamedField(RecipesGui.class, "nextPage", Object.class);
                recipesGuiNextPageField = nextField;
            }
            if (previousField == null || nextField == null) {
                return Optional.empty();
            }
            Object previous = previousField.get(gui);
            Object next = nextField.get(gui);
            Rect2i previousArea = iconButtonArea(previous);
            Rect2i nextArea = iconButtonArea(next);
            if (previousArea == null || nextArea == null) {
                return Optional.empty();
            }
            int left = Math.min(previousArea.getX(), nextArea.getX());
            int top = Math.min(previousArea.getY(), nextArea.getY());
            int right = Math.max(previousArea.getX() + previousArea.getWidth(),
                    nextArea.getX() + nextArea.getWidth());
            int bottom = Math.max(previousArea.getY() + previousArea.getHeight(),
                    nextArea.getY() + nextArea.getHeight());
            return Optional.of(new Rect2i(left, top, right - left, bottom - top));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    private static Rect2i iconButtonArea(Object button) throws ReflectiveOperationException {
        if (button == null) {
            return null;
        }
        Method method = iconButtonAreaMethod;
        if (method == null || !method.getDeclaringClass().isInstance(button)) {
            method = button.getClass().getMethod("getArea");
            method.setAccessible(true);
            iconButtonAreaMethod = method;
        }
        Object area = method.invoke(button);
        if (!(area instanceof ImmutableRect2i)) {
            return null;
        }
        ImmutableRect2i rect = (ImmutableRect2i) area;
        return new Rect2i(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
    }

    private static boolean contains(Rect2i area, double mouseX, double mouseY) {
        return area != null && mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight();
    }

    private static boolean contains(ImmutableRect2i area, double mouseX, double mouseY) {
        return area != null && mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight();
    }

    /** Public so the development client tests resolve ids through the same code path. */
    public static Optional<ResourceLocation> recipeId(IRecipeCategory<?> category, Object recipe) {
        if (recipe instanceof RecipeHolder<?>) {
            return Optional.of(((RecipeHolder<?>) recipe).id());
        }
        if (category == null || recipe == null) {
            return Optional.empty();
        }
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            ResourceLocation id = ((IRecipeCategory) category).getRegistryName(recipe);
            return Optional.ofNullable(id);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /**
     * The manager's recipe for an id a page displayed, following the page's
     * viewer alias when the id itself resolves to nothing.
     *
     * <p>A page can show another recipe type's recipes under a rewritten id (see
     * {@link cc.sighs.JEIEditor.platform.recipe.RecipeViewerAliases}). The recipe
     * the manager holds - and therefore the one an edit has to write - is the one
     * behind the original id, so that is what a page displaying an alias is
     * modelled and patched as.
     */
    public static Optional<RecipeHolder<?>> aliasedRecipeHolder(IRecipeCategory<?> category,
                                                                ResourceLocation displayedId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || category == null || displayedId == null) {
            return Optional.empty();
        }
        Optional<ResourceLocation> underlying = RecipeViewerAliases
                .underlyingRecipeId(category.getRecipeType().getUid().toString(), displayedId);
        if (!underlying.isPresent()) {
            return Optional.empty();
        }
        return minecraft.level.getRecipeManager().byKey(underlying.get());
    }

    /**
     * Ask JEI's vanilla crafting category for the recipe dimensions. That
     * category delegates to its registered crafting extensions, so custom
     * crafting recipe classes use the same dimensions JEI drew instead of
     * forcing the editor to reverse-engineer them from the raw recipe class.
     */
    public static Optional<int[]> craftingGridDimensions(Object displayedRecipe) {
        if (!(displayedRecipe instanceof RecipeHolder<?>)) {
            return Optional.empty();
        }
        Optional<IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return Optional.empty();
        }
        try {
            IRecipeCategory<?> category = runtime.get().getRecipeManager()
                    .getRecipeCategory(RecipeTypes.CRAFTING);
            if (!(category instanceof CraftingRecipeCategory)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            RecipeHolder<CraftingRecipe> holder = (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) displayedRecipe;
            ImmutableSize2i size = ((CraftingRecipeCategory) category).getRecipeSize(holder);
            if (size == null || size.width() < 1 || size.height() < 1) {
                return Optional.empty();
            }
            return Optional.of(new int[] {size.width(), size.height()});
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    static boolean isHandled(IRecipeCategory<?> category, Object recipe) {
        if (category == null || recipe == null) {
            return false;
        }
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            boolean handled = ((IRecipeCategory) category).isHandled(recipe);
            return handled;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Replays a category through JEI's own ingredient supplier builder. This
     * is intentionally exposed for adapters that need all role candidates,
     * rather than only the one item currently displayed in a cycling slot.
     */
    public static Optional<mezz.jei.api.ingredients.IIngredientSupplier> recipeIngredients(
            IRecipeCategory<?> category, Object recipe) {
        if (category == null || recipe == null) {
            return Optional.empty();
        }
        Optional<IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return Optional.empty();
        }
        try {
            IRecipeManager manager = runtime.get().getRecipeManager();
            @SuppressWarnings({"rawtypes", "unchecked"})
            mezz.jei.api.ingredients.IIngredientSupplier supplier =
                    manager.getRecipeIngredients((IRecipeCategory) category, recipe);
            return Optional.ofNullable(supplier);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Object getRecipesGuiLayouts(RecipesGui gui) throws IllegalAccessException {
        Field field = recipesGuiLayoutsField;
        if (field == null) {
            field = findField(RecipesGui.class, RecipeGuiLayouts.class, false);
            recipesGuiLayoutsField = field;
        }
        return field == null ? null : field.get(gui);
    }

    private static Object getRecipeLayoutsList(Object layouts) throws IllegalAccessException {
        Field field = recipeLayoutsListField;
        if (field == null || !field.getDeclaringClass().isInstance(layouts)) {
            field = findLayoutListField(layouts.getClass());
            recipeLayoutsListField = field;
        }
        return field == null ? null : field.get(layouts);
    }

    private static Field findLayoutListField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())
                    && field.getName().equals("recipeLayoutsWithButtons")) {
                field.setAccessible(true);
                return field;
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static List<IRecipeLayoutWithButtons<?>> copyLayoutList(Object value) {
        if (!(value instanceof List<?>)) {
            return null;
        }
        List<IRecipeLayoutWithButtons<?>> result = new ArrayList<IRecipeLayoutWithButtons<?>>();
        for (Object entry : (List<?>) value) {
            if (entry instanceof IRecipeLayoutWithButtons<?>) {
                result.add((IRecipeLayoutWithButtons<?>) entry);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<IRecipeLayoutWithButtons<?>> invokeVisibleLayoutGenerator(RecipesGui gui)
            throws ReflectiveOperationException {
        Field logicField = recipesGuiLogicField;
        if (logicField == null) {
            logicField = findField(RecipesGui.class, IRecipeGuiLogic.class, false);
            recipesGuiLogicField = logicField;
        }
        Field bookmarksField = recipesGuiBookmarksField;
        if (bookmarksField == null) {
            bookmarksField = findField(RecipesGui.class, BookmarkList.class, false);
            recipesGuiBookmarksField = bookmarksField;
        }
        Method areaMethod = recipeLayoutsAreaMethod;
        if (areaMethod == null) {
            areaMethod = RecipesGui.class.getDeclaredMethod("getRecipeLayoutsArea");
            areaMethod.setAccessible(true);
            recipeLayoutsAreaMethod = areaMethod;
        }
        Field paddingField = borderPaddingField;
        if (paddingField == null) {
            paddingField = RecipesGui.class.getDeclaredField("borderPadding");
            paddingField.setAccessible(true);
            borderPaddingField = paddingField;
        }
        if (logicField == null || bookmarksField == null) {
            return Collections.emptyList();
        }
        Object logic = logicField.get(gui);
        if (!(logic instanceof IRecipeGuiLogic)) {
            return Collections.emptyList();
        }
        Object area = areaMethod.invoke(gui);
        if (!(area instanceof ImmutableRect2i)) {
            return Collections.emptyList();
        }
        int height = ((ImmutableRect2i) area).getHeight();
        int padding = paddingField.getInt(null);
        Object bookmarks = bookmarksField.get(gui);
        Object result = ((IRecipeGuiLogic) logic).getVisibleRecipeLayoutsWithButtons(
                height, padding, gui.getParentContainerMenu(), (BookmarkList) bookmarks, gui);
        List<IRecipeLayoutWithButtons<?>> copied = copyLayoutList(result);
        return copied == null ? Collections.<IRecipeLayoutWithButtons<?>>emptyList() : copied;
    }

    private static Field findField(Class<?> type, Class<?> fieldType, boolean staticOnly) {
        for (Field field : type.getDeclaredFields()) {
            if (fieldType.isAssignableFrom(field.getType())
                    && (!staticOnly || Modifier.isStatic(field.getModifiers()))) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static Field findNamedField(Class<?> type, String name, Class<?> fieldType) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                if (fieldType.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // Continue through the implementation hierarchy.
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
