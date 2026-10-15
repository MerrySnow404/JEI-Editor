package cc.sighs.JEIEditor.client;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.TagPatchFields;
import cc.sighs.JEIEditor.JEIEditorNeoForge121;
import cc.sighs.JEIEditor.platform.network.NeoForge121Network;
import cc.sighs.JEIEditor.platform.recipe.TagEditorAdapter;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = JEIEditorNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class ClientEditorEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("JEI Editor Client");
    private static final int RECIPE_ID_INPUT_WIDTH = 110;
    private static final int RECIPE_ID_INPUT_HEIGHT = 14;
    private static final int MENU_WIDTH = 148;
    private static final int MENU_ROW_HEIGHT = 17;
    private static final int MENU_BORDER = 1;
    private static final int MENU_TEXT_PADDING = 7;
    private static final float MENU_TEXT_SCALE = 0.8F;
    private static final Map<net.minecraft.client.gui.screens.Screen, MenuState> menus =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, MenuState>();
    private static final Map<net.minecraft.client.gui.screens.Screen, EditBox> recipeIdInputs =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, EditBox>();
    private static final Map<net.minecraft.client.gui.screens.Screen, Boolean> initializedRecipeScreens =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Boolean>();

    private ClientEditorEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        // Controls are created lazily by the context menu.
        if (ClientEditorState.isRecipeScreen(event.getScreen())
                && initializedRecipeScreens.put(event.getScreen(), Boolean.TRUE) == null) {
            // A new JEI page starts with the editor status, not a stale message
            // left over from the previous page instance.
            ClientEditorState.setLastDrop("");
        }
    }

    private static void pressEdit(net.minecraft.client.gui.screens.Screen screen) {
        if (ClientEditorState.toggleFromButton()) {
            if (!ClientEditorState.isEditing()) {
                ClientEditorState.clearPendingPatch();
                closeRecipeIdInput(screen);
            } else {
                ClientEditorState.setLastDrop("");
            }
        }
    }

    /** Re-arm the edit switch after the physical left mouse button is released. */
    @SubscribeEvent
    public static void onMouseButtonReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (event.getButton() == 0 && event.getScreen() instanceof RecipesGui gui
                && JeiIngredientDragController.complete(gui, event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
            ClientEditorState.armToggle();
            ClientEditorState.armActionClick();
            ClientEditorState.clearGhostHighlightAreas();
            return;
        }
        if (event.getButton() == 0) {
            EditBox input = recipeIdInputs.get(event.getScreen());
            if (input != null && input.isMouseOver(event.getMouseX(), event.getMouseY())) {
                event.setCanceled(true);
            }
        }
        if (event.getButton() == 0 && event.getScreen() instanceof RecipesGui
                && FuelCountInputController.isMouseOver((RecipesGui) event.getScreen(),
                event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
        if (event.getButton() == 0 && event.getScreen() instanceof RecipesGui
                && AnvilExperienceInputController.isMouseOver((RecipesGui) event.getScreen(),
                event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            if (event.getButton() == 0
                    && menuItemUnderMouse(event.getScreen(), event.getMouseX(), event.getMouseY()) != null) {
                // The editor invokes its button from MouseButtonPressed.Pre.
                // Do not let JEI execute the same physical click again on
                // release, which can also trigger its page navigation/close.
                event.setCanceled(true);
            }
        }
        if (event.getButton() == 0) {
            ClientEditorState.armToggle();
            ClientEditorState.armActionClick();
        }
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            ClientEditorState.clearGhostHighlightAreas();
        }
    }

    /** Handle editor buttons before JEI's input router, then keep other edits mouse-only. */
    @SubscribeEvent
    public static void onMouseButton(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() == 0 && event.getScreen() instanceof RecipesGui
                && ClientEditorState.isEditing()
                && AnvilExperienceInputController.mouseClicked((RecipesGui) event.getScreen(),
                event.getMouseX(), event.getMouseY(), event.getButton())) {
            closeMenu(event.getScreen());
            event.setCanceled(true);
            return;
        }
        if (event.getButton() == 0 && event.getScreen() instanceof RecipesGui
                && ClientEditorState.isEditing()
                && FuelCountInputController.mouseClicked((RecipesGui) event.getScreen(),
                event.getMouseX(), event.getMouseY(), event.getButton())) {
            closeMenu(event.getScreen());
            event.setCanceled(true);
            return;
        }
        if (event.getButton() == 0 && event.getScreen() instanceof RecipesGui) {
            EditBox input = recipeIdInputs.get(event.getScreen());
            if (input != null && input.isMouseOver(event.getMouseX(), event.getMouseY())) {
                input.setFocused(true);
                input.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton());
                event.setCanceled(true);
                return;
            }
            if (input != null) {
                input.setFocused(false);
            }
        }
        if (event.getButton() == 0 && ClientEditorState.isRecipeScreen(event.getScreen())) {
            MenuItem item = menuItemUnderMouse(event.getScreen(), event.getMouseX(), event.getMouseY());
            if (item != null) {
                if (ClientEditorState.consumeActionClick()) {
                    playMenuClick();
                    item.activate();
                    closeMenu(event.getScreen());
                }
                event.setCanceled(true);
                return;
            }
            if (JeiIngredientDragController.start((RecipesGui) event.getScreen(),
                    event.getMouseX(), event.getMouseY())) {
                closeMenu(event.getScreen());
                event.setCanceled(true);
                return;
            }
            closeMenu(event.getScreen());
        }
        if (!(event.getScreen() instanceof RecipesGui gui) || event.getButton() != 1) {
            return;
        }
        EditBox recipeIdInput = recipeIdInputs.get(gui);
        if (recipeIdInput != null && recipeIdInput.isMouseOver(event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
            return;
        }
        // JEI owns its sidebars, including empty grid cells and the search
        // field. Short-circuit before recipe target lookup so an overlay can
        // never be mistaken for editor-menu space.
        if (JeiRecipeIntrospection.isOverlayAt(gui, event.getMouseX(), event.getMouseY())) {
            closeMenu(gui);
            return;
        }
        if (isMenuOpen(gui)) {
            closeMenu(gui);
            event.setCanceled(true);
            return;
        }
        Optional<EditorModel> deletionTarget = JeiRecipeEditorPlugin.recipeDeletionTargetAtMouse(
                gui, event.getMouseX(), event.getMouseY());
        boolean overRecipeLayout = gui.getRecipeLayoutUnderMouse(event.getMouseX(), event.getMouseY()).isPresent();
        if (deletionTarget.isPresent() || overRecipeLayout) {
            Optional<EditorModel> creationTarget = JeiRecipeEditorPlugin.recipeCreationTargetAtMouse(
                    gui, event.getMouseX(), event.getMouseY());
            Optional<JeiRecipeEditorPlugin.TagPageTarget> tagTarget =
                    JeiRecipeEditorPlugin.tagPageTargetAtMouse(gui, event.getMouseX(), event.getMouseY());
            boolean clearableInput = deletionTarget.isPresent()
                    && !ClientEditorState.isRecipeDeleted(deletionTarget.get().recipeId())
                    && JeiRecipeEditorPlugin.hasEditableInputAtMouse(
                    gui, event.getMouseX(), event.getMouseY());
            openMenu(gui, event.getMouseX(), event.getMouseY(), deletionTarget, clearableInput,
                    creationTarget, tagTarget);
            event.setCanceled(true);
            return;
        }
        if (isBlankJeiArea(gui, event.getMouseX(), event.getMouseY())) {
            Optional<EditorModel> creationTarget = JeiRecipeEditorPlugin.recipeCreationTargetOnPage(gui);
            openMenu(gui, event.getMouseX(), event.getMouseY(), Optional.empty(), false,
                    creationTarget, Optional.empty());
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (event.getMouseButton() == 0 && event.getScreen() instanceof RecipesGui gui) {
            JeiIngredientDragController.update(gui, event.getMouseX(), event.getMouseY());
            if (JeiIngredientDragController.drag(gui)) {
                event.setCanceled(true);
            }
        }
    }

    /**
     * The recipe-layout lookup only covers the central recipe panels. JEI's
     * bookmark and ingredient sidebars are outside those layouts, but they
     * still own the mouse when an ingredient is under the pointer. Only a
     * genuinely empty JEI area should open the editor context menu.
     */
    private static boolean isBlankJeiArea(RecipesGui gui, double mouseX, double mouseY) {
        // JEI's left/right ingredient overlays are outside RecipesGui's own
        // area. Do not treat those overlay regions as editor-menu space.
        if (!gui.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (JeiRecipeIntrospection.isOverlayAt(gui, mouseX, mouseY)) {
            return false;
        }
        if (gui.getRecipeLayoutUnderMouse(mouseX, mouseY).isPresent()) {
            return false;
        }
        if (gui.getIngredientUnderMouse(mouseX, mouseY).findAny().isPresent()) {
            return false;
        }
        return !gui.getDraggableIngredientUnderMouse(mouseX, mouseY).findAny().isPresent();
    }

    /** The output count is adjusted with the wheel while the pointer is over the output slot. */
    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!ClientEditorState.isEditing() || !(event.getScreen() instanceof RecipesGui gui)) {
            return;
        }
        if (JeiRecipeEditorPlugin.adjustOutputAtMouse(
                gui, event.getMouseX(), event.getMouseY(), event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        EditBox recipeIdInput = recipeIdInputs.get(event.getScreen());
        if (recipeIdInput != null && recipeIdInput.isFocused()) {
            // Character keys (for example E) are handled later by
            // CharacterTyped.Pre and return false from EditBox.keyPressed().
            // Consume the key event regardless, otherwise JEI sees its own
            // close-screen binding before the character event is delivered.
            recipeIdInput.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
            event.setCanceled(true);
            return;
        }
        if (event.getScreen() instanceof RecipesGui
                && FuelCountInputController.keyPressed((RecipesGui) event.getScreen(),
                event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
            event.setCanceled(true);
            return;
        }
        if (event.getScreen() instanceof RecipesGui
                && AnvilExperienceInputController.keyPressed((RecipesGui) event.getScreen(),
                event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
        EditBox recipeIdInput = recipeIdInputs.get(event.getScreen());
        if (recipeIdInput != null && recipeIdInput.isFocused()
                && recipeIdInput.charTyped(event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
            return;
        }
        if (event.getScreen() instanceof RecipesGui
                && FuelCountInputController.charTyped((RecipesGui) event.getScreen(),
                event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
            return;
        }
        if (event.getScreen() instanceof RecipesGui
                && AnvilExperienceInputController.charTyped((RecipesGui) event.getScreen(),
                event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    private static Component label() {
        return Component.literal(ClientEditorState.isEditing() ? "Editing: ON" : "Editing: OFF");
    }

    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        ClientEditorState.clearPreview();
        // JEI handles this event too and rebuilds its lookup maps after the
        // callback. Restore synthetic pages on the next client task so JEI
        // cannot overwrite them again.
        JeiRecipeEditorPlugin.scheduleCreatedAnvilRestore();
        // The rebuild also drops ingredients added at runtime, so the tag entries have to be
        // applied again by the next sync.
        TagIngredientRegistry.invalidate();
        if (ClientEditorState.isEditing()) {
            ClientEditorState.setLastDrop("Recipes synchronized from server");
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // Runtime recipe registration must happen after JEI has consumed the
        // RecipesUpdatedEvent; doing it from the event callback is overwritten
        // by JEI's own lookup rebuild.
        JeiRecipeEditorPlugin.tickCreatedAnvilRestore();
        // Same timing rule for the tag entries: they are added while editing is on and
        // removed while it is off, checked once a tick so the switch cannot be missed.
        TagIngredientRegistry.sync();
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            JeiIngredientDragController.cancel((RecipesGui) event.getScreen());
            FuelCountInputController.close((RecipesGui) event.getScreen());
            AnvilExperienceInputController.close((RecipesGui) event.getScreen());
            initializedRecipeScreens.remove(event.getScreen());
            closeMenu(event.getScreen());
            closeRecipeIdInput(event.getScreen());
            ClientEditorState.clearGhostHighlightAreas();
            ClientEditorState.closeRecipeScreen();
        }
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof RecipesGui) {
            FuelCountInputController.prepare((RecipesGui) event.getScreen());
            AnvilExperienceInputController.prepare((RecipesGui) event.getScreen());
            JeiRecipeEditorPlugin.refreshPendingPreviews((RecipesGui) event.getScreen());
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            JeiRecipeEditorPlugin.drawPendingSlotHighlights((RecipesGui) event.getScreen(), event.getGuiGraphics());
            ClientEditorState.drawGhostHighlights(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
            event.getGuiGraphics().pose().pushPose();
            event.getGuiGraphics().pose().translate(0.0D, 0.0D, 1000.0D);
            JeiIngredientDragController.render(event.getGuiGraphics(), (RecipesGui) event.getScreen(),
                    event.getMouseX(), event.getMouseY());
            event.getGuiGraphics().pose().popPose();
            FuelCountInputController.render((RecipesGui) event.getScreen(), event.getGuiGraphics(),
                    event.getMouseX(), event.getMouseY());
            AnvilExperienceInputController.render((RecipesGui) event.getScreen(), event.getGuiGraphics(),
                    event.getMouseX(), event.getMouseY());
            renderRecipeIdInput(event);
            renderMenuAboveJei(event);
        }
        if (ClientEditorState.isRecipeScreen(event.getScreen()) && ClientEditorState.isEditing()) {
            net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
            String status = ClientEditorState.getLastDrop().isEmpty()
                    ? "Editing Mode: ON"
                    : ClientEditorState.getLastDrop();
            status = fitStatus(font, status, Math.max(80, event.getScreen().width - 24));
            Component text = Component.literal(status);
            event.getGuiGraphics().drawString(font, text,
                    (event.getScreen().width - font.width(text)) / 2, 8, 0xFFFFFFFF);
        }
    }

    private static void renderRecipeIdInput(ScreenEvent.Render.Post event) {
        net.minecraft.client.gui.screens.Screen screen = event.getScreen();
        EditBox input = recipeIdInputs.get(screen);
        if (input == null || !(screen instanceof RecipesGui)) {
            return;
        }
        RecipesGui gui = (RecipesGui) screen;
        int width = RECIPE_ID_INPUT_WIDTH;
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        String namespace = "jeieditor:";
        int gap = 4;
        int groupWidth = font.width(namespace) + gap + width;
        int groupLeft = (screen.width - groupWidth) / 2;
        java.util.Optional<net.minecraft.client.renderer.Rect2i> pageArea =
                JeiRecipeIntrospection.recipePageNavigationArea(gui);
        if (pageArea.isPresent()) {
            net.minecraft.client.renderer.Rect2i area = pageArea.get();
            groupLeft = area.getX() + (area.getWidth() - groupWidth) / 2;
        }
        int x = groupLeft + font.width(namespace) + gap;
        int y = JeiRecipeEditorPlugin.recipeIdInputY(gui, RECIPE_ID_INPUT_HEIGHT);
        input.setX(x);
        input.setY(y);
        event.getGuiGraphics().pose().pushPose();
        event.getGuiGraphics().pose().translate(0.0D, 0.0D, 1000.0D);
        int labelY = y + Math.max(0, (RECIPE_ID_INPUT_HEIGHT - font.lineHeight) / 2);
        event.getGuiGraphics().drawString(font, Component.literal(namespace),
                x - gap - font.width(namespace), labelY, 0xFFFFFFFF);
        input.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), 0.0F);
        event.getGuiGraphics().pose().popPose();
    }

    private static void openRecipeIdInput(RecipesGui screen) {
        EditBox input = recipeIdInputs.get(screen);
        if (input == null) {
            input = new EditBox(Minecraft.getInstance().font, 0, 0,
                    RECIPE_ID_INPUT_WIDTH, RECIPE_ID_INPUT_HEIGHT,
                    Component.literal("Recipe ID"));
            input.setMaxLength(128);
            input.setHint(Component.literal("recipe path"));
            input.setValue("");
            recipeIdInputs.put(screen, input);
        }
        input.setFocused(true);
        input.moveCursorToEnd(false);
    }

    private static void closeRecipeIdInput(net.minecraft.client.gui.screens.Screen screen) {
        EditBox input = recipeIdInputs.remove(screen);
        if (input != null) {
            input.setFocused(false);
        }
    }

    /** Keep status feedback readable on narrow screens and for generated IDs. */
    private static String fitStatus(net.minecraft.client.gui.Font font, String status, int maxWidth) {
        if (status == null || font.width(status) <= maxWidth) {
            return status == null ? "" : status;
        }
        String suffix = "...";
        int end = status.length();
        while (end > 0 && font.width(status.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return status.substring(0, end) + suffix;
    }

    /** JEI draws its recipe page after Screen.render(), so draw the context menu
     * again in the post pass at a high GUI Z level instead of letting the page
     * cover it. */
    private static void renderMenuAboveJei(ScreenEvent.Render.Post event) {
        net.minecraft.client.gui.screens.Screen screen = event.getScreen();
        MenuState menu = menus.get(screen);
        if (menu == null) {
            return;
        }
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        int bottom = menu.y + menu.height;
        event.getGuiGraphics().pose().pushPose();
        event.getGuiGraphics().pose().translate(0.0D, 0.0D, 1000.0D);
        // A small shadow and a restrained dark panel keep the menu distinct
        // from both JEI and the vanilla button style.
        event.getGuiGraphics().fill(menu.x + 2, menu.y + 2, menu.x + menu.width + 2,
                bottom + 2, 0x55000000);
        event.getGuiGraphics().fill(menu.x, menu.y, menu.x + menu.width, bottom, 0xEE11171C);
        event.getGuiGraphics().fill(menu.x, menu.y, menu.x + menu.width, menu.y + MENU_BORDER,
                0xFF60717D);
        event.getGuiGraphics().fill(menu.x, bottom - MENU_BORDER, menu.x + menu.width, bottom,
                0xFF34434D);
        event.getGuiGraphics().fill(menu.x, menu.y, menu.x + MENU_BORDER, bottom, 0xFF53636E);
        event.getGuiGraphics().fill(menu.x + menu.width - MENU_BORDER, menu.y,
                menu.x + menu.width, bottom, 0xFF34434D);
        for (int index = 0; index < menu.items.size(); index++) {
            MenuItem item = menu.items.get(index);
            boolean hovered = item.contains(mouseX, mouseY);
            if (hovered) {
                event.getGuiGraphics().fill(item.x, item.y, item.x + item.width,
                        item.y + item.height, 0xFF2C4658);
            } else if ((index & 1) == 1) {
                event.getGuiGraphics().fill(item.x, item.y, item.x + item.width,
                        item.y + item.height, 0xFF151E24);
            }
            if (index > 0) {
                event.getGuiGraphics().fill(menu.x + MENU_BORDER, item.y,
                        menu.x + menu.width - MENU_BORDER, item.y + 1, 0xFF293740);
            }
            event.getGuiGraphics().pose().pushPose();
            event.getGuiGraphics().pose().translate(item.x + MENU_TEXT_PADDING, item.y + 4, 0.0D);
            event.getGuiGraphics().pose().scale(MENU_TEXT_SCALE, MENU_TEXT_SCALE, 1.0F);
            event.getGuiGraphics().drawString(font, Component.literal(item.label), 0, 0,
                    hovered ? 0xFFFFFFFF : 0xFFE1E7EA, false);
            event.getGuiGraphics().pose().popPose();
        }
        event.getGuiGraphics().pose().popPose();
    }

    private static void openMenu(RecipesGui screen, double mouseX, double mouseY,
                                 Optional<EditorModel> deletionTarget, boolean clearableInput,
                                 Optional<EditorModel> creationTarget,
                                 Optional<JeiRecipeEditorPlugin.TagPageTarget> tagTarget) {
        // A context-menu click does not travel through JEI's left-click field
        // handler. Commit a focused anvil cost before the menu can submit it.
        AnvilExperienceInputController.commitAll(screen);
        closeMenu(screen);
        List<MenuItem> items = new ArrayList<MenuItem>();
        items.add(new MenuItem(label().getString(), () -> pressEdit(screen)));

        // Reload is intentionally available in view mode. All other editing
        // actions are hidden until the client-side switch is enabled.
        if (!ClientEditorState.isEditing()) {
            items.add(new MenuItem("Reload", () -> reload(screen)));
        } else {
            items.add(new MenuItem("Save", () -> {
                AnvilExperienceInputController.commitAll(screen);
                if (ClientEditorState.getPendingPatches().isEmpty()) {
                    LOGGER.info("Recipe save skipped: no pending recipe edit");
                    ClientEditorState.setLastDrop("No pending recipe edit");
                    return;
                }
                if (ClientEditorState.hasCreationDraft(screen)) {
                    EditBox input = recipeIdInputs.get(screen);
                    String error = ClientEditorState.renameCreationDraft(screen,
                            input == null ? "" : input.getValue());
                    if (!error.isEmpty()) {
                        LOGGER.info("Recipe save skipped: {}", error);
                        ClientEditorState.setLastDrop(error);
                        return;
                    }
                }
                if (ClientEditorState.hasTagCreateDraft(screen)) {
                    // A staged new tag is named the same way a created recipe is: the
                    // page bar types the path and the namespace is the editor's own, so
                    // the patch is addressed to the tag the user just named.
                    EditBox input = recipeIdInputs.get(screen);
                    String error = ClientEditorState.renameTagCreateDraft(screen,
                            input == null ? "" : input.getValue());
                    if (!error.isEmpty()) {
                        LOGGER.info("Recipe save skipped: {}", error);
                        ClientEditorState.setLastDrop(error);
                        return;
                    }
                }
                java.util.List<cc.sighs.JEIEditor.editor.RecipePatch> patches =
                        ClientEditorState.getPendingPatches();
                LOGGER.info("Submitting {} pending recipe edit(s): {}", patches.size(),
                        patches.stream().map(cc.sighs.JEIEditor.editor.RecipePatch::recipeId).toList());
                ClientEditorState.markSaveSubmittedPatches(patches);
                NeoForge121Network.send(patches);
                ClientEditorState.setLastDrop("Recipe edits saved; reload separately to apply");
            }));
            items.add(new MenuItem("Reload", () -> reload(screen)));
            items.add(new MenuItem("Reset Page Changes", () -> {
                ClientEditorState.clearPendingPatches(screen,
                        JeiRecipeEditorPlugin.currentPageRecipeIds(screen));
                ClientEditorState.setLastDrop("Current page changes reset");
                closeRecipeIdInput(screen);
            }));
            if (clearableInput) {
                items.add(new MenuItem("Clear Input Slot", () ->
                        JeiRecipeEditorPlugin.clearInputAtMouse(screen, mouseX, mouseY)));
            }
            if (deletionTarget.isPresent()) {
                EditorModel target = deletionTarget.get();
                boolean deleted = ClientEditorState.isRecipeDeleted(target.recipeId());
                items.add(new MenuItem(deleted ? "Cancel Recipe Delete" : "Delete Recipe", () -> {
                    if (ClientEditorState.isRecipeDeleted(target.recipeId())) {
                        ClientEditorState.cancelRecipeDeletion(target.recipeId());
                        ClientEditorState.setLastDrop("Recipe deletion canceled: " + target.recipeId());
                    } else {
                        ClientEditorState.deleteRecipe(target);
                        ClientEditorState.setLastDrop("Recipe marked for deletion: " + target.recipeId());
                    }
                }));
            }
            if (creationTarget.isPresent()) {
                EditorModel target = creationTarget.get();
                boolean creationStaged = ClientEditorState.isCreationStaged(target.recipeId())
                        || ClientEditorState.isRecipeDeleted(target.recipeId());
                items.add(new MenuItem(creationStaged ? "Cancel New Recipe" : "New Recipe", () -> {
                    try {
                        if (creationStaged) {
                            if (ClientEditorState.cancelCreationDraft(screen, target.recipeId())) {
                                closeRecipeIdInput(screen);
                                ClientEditorState.setLastDrop("New recipe canceled");
                            }
                        } else {
                            ClientEditorState.createRecipe(screen, target);
                            openRecipeIdInput(screen);
                            ClientEditorState.setLastDrop("New recipe ready. Fill inputs, then Save.");
                        }
                    } catch (IllegalArgumentException exception) {
                        ClientEditorState.setLastDrop(exception.getMessage());
                    }
                }));
            }
            if (tagTarget.isPresent()) {
                // A tag page offers neither of the two items above: it has no recipe to
                // delete and no page of its own to create one on, and its one editable
                // operation per member is a tag rewrite. Both member items are offered
                // only when the pointer is really on a member the editor can name.
                JeiRecipeEditorPlugin.TagPageTarget tag = tagTarget.get();
                if (tag.onTagSlot()) {
                    items.add(new MenuItem("Delete Tag", () -> stageTagDelete(tag.model())));
                } else if (tag.memberId() != null) {
                    String member = tag.memberId();
                    items.add(new MenuItem("Remove " + member + " From Tag",
                            () -> stageTagMemberRemoval(tag.model(), member)));
                    items.add(new MenuItem("New Tag From This Element...",
                            () -> stageNewTag(screen, tag.model(), member)));
                }
            }
        }
        int height = MENU_BORDER * 2 + items.size() * MENU_ROW_HEIGHT;
        int x = Math.max(2, Math.min(screen.width - MENU_WIDTH - 2, (int) mouseX));
        int y = Math.max(2, Math.min(screen.height - height - 2, (int) mouseY));
        for (int index = 0; index < items.size(); index++) {
            MenuItem item = items.get(index);
            item.x = x + MENU_BORDER;
            item.y = y + MENU_BORDER + index * MENU_ROW_HEIGHT;
            item.width = MENU_WIDTH - MENU_BORDER * 2;
            item.height = MENU_ROW_HEIGHT;
        }
        menus.put(screen, new MenuState(x, y, MENU_WIDTH, height, items));
    }

    /**
     * Stages the tag-delete of the tag page the pointer is on. Deleting a tag means
     * our pack overrides it with no members (see the server's {@code TagEditApplier}),
     * so nothing the user has to confirm is dropped from the world; the server refuses
     * the patch if the tag already has no members.
     */
    private static void stageTagDelete(EditorModel model) {
        try {
            ClientEditorState.setTagPatch(TagEditorAdapter.deleteTag(model).orElseThrow(() ->
                    new IllegalArgumentException("this tag page cannot be deleted")));
            ClientEditorState.setLastDrop("Tag marked for deletion: " + tagIdOf(model));
        } catch (IllegalArgumentException exception) {
            ClientEditorState.setLastDrop(exception.getMessage());
        }
    }

    /** Stages the removal of one member the pointer is on. */
    private static void stageTagMemberRemoval(EditorModel model, String memberId) {
        try {
            ClientEditorState.setTagPatch(TagEditorAdapter.removeMember(model, memberId).orElseThrow(() ->
                    new IllegalArgumentException("this tag cannot hold " + memberId)));
            ClientEditorState.setLastDrop("Removing " + memberId + " from " + tagIdOf(model));
        } catch (IllegalArgumentException exception) {
            ClientEditorState.setLastDrop(exception.getMessage());
        }
    }

    /**
     * Stages a new tag whose single element is the member the pointer is on, and asks
     * for its id in the page bar. The new tag only exists once the patch is saved and
     * the server reloads, so nothing is created here.
     */
    private static void stageNewTag(RecipesGui screen, EditorModel model, String memberId) {
        try {
            ClientEditorState.createTag(screen, model, memberId);
            openRecipeIdInput(screen);
            ClientEditorState.setLastDrop("New tag from " + memberId
                    + ". Type its ID, then Save.");
        } catch (IllegalArgumentException exception) {
            ClientEditorState.setLastDrop(exception.getMessage());
        }
    }

    /** The tag id a tag model addresses, for status messages; falls back to its recipe id. */
    private static String tagIdOf(EditorModel model) {
        Optional<TagPatchFields.Target> target = TagPatchFields.target(model.recipeId());
        return target.isPresent() ? target.get().tagId() : model.recipeId();
    }

    private static void reload(net.minecraft.client.gui.screens.Screen screen) {
        if (screen instanceof RecipesGui) {
            AnvilExperienceInputController.commitAll((RecipesGui) screen);
        }
        NeoForge121Network.sendReload();
        ClientEditorState.setLastDrop("Saved recipe edits reload submitted");
        screen.onClose();
    }

    private static void playMenuClick() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private static boolean isMenuOpen(net.minecraft.client.gui.screens.Screen screen) {
        return menus.containsKey(screen);
    }

    private static void closeMenu(net.minecraft.client.gui.screens.Screen screen) {
        menus.remove(screen);
    }

    private static MenuItem menuItemUnderMouse(net.minecraft.client.gui.screens.Screen screen,
                                               double mouseX, double mouseY) {
        MenuState menu = menus.get(screen);
        if (menu == null) {
            return null;
        }
        for (MenuItem item : menu.items) {
            if (item.contains(mouseX, mouseY)) {
                return item;
            }
        }
        return null;
    }

    private interface MenuAction {
        void run();
    }

    private static final class MenuItem {
        private final String label;
        private final MenuAction action;
        private int x;
        private int y;
        private int width;
        private int height;

        private MenuItem(String label, MenuAction action) {
            this.label = label;
            this.action = action;
        }

        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }

        private void activate() {
            action.run();
        }
    }

    private static final class MenuState {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final List<MenuItem> items;

        private MenuState(int x, int y, int width, int height, List<MenuItem> items) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.items = items;
        }
    }
}
