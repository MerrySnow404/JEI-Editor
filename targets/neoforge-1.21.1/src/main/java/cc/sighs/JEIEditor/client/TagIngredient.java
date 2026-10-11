package cc.sighs.JEIEditor.client;

import mezz.jei.api.ingredients.IIngredientType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * One recipe tag, offered to JEI's ingredient index as an ingredient of its own while the
 * editor is in editing mode.
 *
 * <p>A recipe slot already accepts tags - {@code {"tag":"c:ingots/brass"}} is all over the
 * shipped declarations - but JEI's index only knows <em>ingredients</em>, and a tag is not
 * one: the index lists the items a recipe may use, never the tag a recipe actually names.
 * Editing a slot into a tag was therefore impossible through the real UI: there was nothing
 * to drag. This type puts one entry per non-empty item or fluid tag into the index, drawn
 * with a distinguishing border and a tooltip that names the tag ({@link TagIngredientRenderer}),
 * so a tag can be picked up and dropped into a slot like any other ingredient.
 */
public record TagIngredient(ResourceLocation tagId, boolean fluid, ItemStack display) {

    /**
     * The JEI ingredient type. The uid is a fixed string rather than JEI's default of the
     * ingredient class name, so renaming or moving the class cannot silently orphan the
     * entries added at runtime.
     */
    public static final IIngredientType<TagIngredient> TYPE = new IIngredientType<TagIngredient>() {
        @Override
        public Class<? extends TagIngredient> getIngredientClass() {
            return TagIngredient.class;
        }

        @Override
        public String getUid() {
            return "jeieditor:tag";
        }
    };

    /** The id a patch carries for this tag, and the label the index shows: {@code #ns:path}. */
    public String tagText() {
        return "#" + tagId;
    }
}
