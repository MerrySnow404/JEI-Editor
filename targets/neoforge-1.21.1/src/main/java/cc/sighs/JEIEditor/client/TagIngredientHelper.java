package cc.sighs.JEIEditor.client;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.subtypes.UidContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.stream.Stream;

/** JEI's identity rules for one {@link TagIngredient}. */
final class TagIngredientHelper implements IIngredientHelper<TagIngredient> {

    @Override
    public IIngredientType<TagIngredient> getIngredientType() {
        return TagIngredient.TYPE;
    }

    @Override
    public String getDisplayName(TagIngredient ingredient) {
        return ingredient.tagText();
    }

    @Override
    @SuppressWarnings("removal")
    public String getUniqueId(TagIngredient ingredient, UidContext context) {
        // Deprecated in JEI 19 in favour of getUid but still abstract, so both are implemented
        // and they must agree.
        return (String) getUid(ingredient, context);
    }

    @Override
    public Object getUid(TagIngredient ingredient, UidContext context) {
        // An item tag and a fluid tag may share an id and are still different ingredients,
        // so the side is part of the identity. JEI 19 reads this rather than getUniqueId.
        return (ingredient.fluid() ? "fluid#" : "item#") + ingredient.tagId();
    }

    @Override
    public ResourceLocation getResourceLocation(TagIngredient ingredient) {
        return ingredient.tagId();
    }

    @Override
    public TagIngredient copyIngredient(TagIngredient ingredient) {
        return new TagIngredient(ingredient.tagId(), ingredient.fluid(), ingredient.display().copy());
    }

    @Override
    public String getErrorInfo(TagIngredient ingredient) {
        return (ingredient.fluid() ? "fluid tag " : "item tag ") + ingredient.tagText();
    }

    @Override
    public ItemStack getCheatItemStack(TagIngredient ingredient) {
        // What JEI draws while the entry is being dragged out of the index, and what a
        // recipe viewer would hand out: the tag's representative member.
        return ingredient.display();
    }

    @Override
    public String getDisplayModId(TagIngredient ingredient) {
        return ingredient.tagId().getNamespace();
    }

    @Override
    public Stream<ResourceLocation> getTagStream(TagIngredient ingredient) {
        // The tag itself, so JEI's search matches the entry by its tag id and groups the
        // entry as a tag rather than by the member item's own tags.
        return Stream.of(ingredient.tagId());
    }

    @Override
    public boolean isValidIngredient(TagIngredient ingredient) {
        return !ingredient.display().isEmpty();
    }
}
