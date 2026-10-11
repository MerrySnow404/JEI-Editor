package cc.sighs.JEIEditor.client;

import mezz.jei.api.ingredients.IIngredientRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws one tag entry and explains it.
 *
 * <p>Two things separate a tag from the item it displays. A border in the configured preset
 * colour ({@code config/jei-editor.json}), drawn after the member so the two cannot blend
 * into one, and a tooltip that names the tag, states how many members it has and lists the
 * first few of them - without it the entry is indistinguishable from its own first member,
 * which is exactly the confusion the index entry is there to remove.
 */
final class TagIngredientRenderer implements IIngredientRenderer<TagIngredient> {
    /** Members named in the tooltip; a tag can hold hundreds and the list is not the point. */
    private static final int TOOLTIP_MEMBERS = 8;

    @Override
    public void render(GuiGraphics graphics, TagIngredient ingredient) {
        ItemStack display = ingredient.display();
        if (!display.isEmpty()) {
            graphics.renderItem(display, 0, 0);
        }
        drawBorder(graphics, TagIngredientRegistry.borderColor());
    }

    @Override
    public List<Component> getTooltip(TagIngredient ingredient, TooltipFlag tooltipFlag) {
        List<Component> lines = new ArrayList<Component>();
        lines.add(Component.literal(ingredient.tagText()).withStyle(
                ingredient.fluid() ? ChatFormatting.AQUA : ChatFormatting.GOLD));
        List<String> members = TagIngredientRegistry.memberNames(ingredient);
        lines.add(Component.literal((ingredient.fluid() ? "Fluid tag" : "Item tag")
                + " · " + members.size() + " member(s)").withStyle(ChatFormatting.GRAY));
        for (int index = 0; index < Math.min(TOOLTIP_MEMBERS, members.size()); index++) {
            lines.add(Component.literal("  " + members.get(index)).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (members.size() > TOOLTIP_MEMBERS) {
            lines.add(Component.literal("  ... and " + (members.size() - TOOLTIP_MEMBERS) + " more")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    /** A one pixel frame around the entry. */
    private static void drawBorder(GuiGraphics graphics, int argb) {
        graphics.fill(0, 0, 16, 1, argb);
        graphics.fill(0, 15, 16, 16, argb);
        graphics.fill(0, 0, 1, 16, argb);
        graphics.fill(15, 0, 16, 16, argb);
    }
}
