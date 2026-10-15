package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.IngredientKind;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.TagPatchFields;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * The client half of an editable JEI tag page: the four operations such a page
 * offers - add one member, remove one member, create the tag from one element,
 * delete the tag - as {@link TagPatchFields} patches.
 *
 * <p>A tag page is not a recipe record: JEI pushes a bare
 * {@code mezz.jei.library.plugins.jei.tags.TagInfoRecipe} into the category, and
 * the editor's model for it is one slot holding the tag's first member (see
 * {@code JeiRecipeEditorPlugin.createTagModel}). Nothing here rebuilds recipe
 * JSON therefore: each operation names one member - or none, for a delete - and
 * the server's {@code TagEditApplier} rewrites the tag as a whole from it.
 *
 * <p>The one thing that needs the tag's registry is the element id, and it is
 * resolved in exactly one place ({@link #elementId(String, IngredientKind, String)})
 * so an add, a remove and a create cannot disagree about what a dropped
 * ingredient becomes.
 */
public final class TagEditorAdapter {
    private TagEditorAdapter() {
    }

    /** Whether this is the tag patch kind. The serializer id is the whole kind test. */
    public static boolean supportsSerializer(String serializerId) {
        return TagPatchFields.SERIALIZER.equals(serializerId);
    }

    /** Whether the patch is a tag patch, read through the same test the server's gate uses. */
    public static boolean isTagPatch(RecipePatch patch) {
        return TagPatchFields.isTagPatch(patch);
    }

    /**
     * The registry a tag model addresses ({@code item}, {@code block} or
     * {@code fluid}), or null when the model is not a resolvable tag model.
     *
     * <p>The registry is not a field of the model: it lives in the encoded recipe
     * id, which is what the world's SavedData, the import bundle and the server's
     * policy gate key a tag patch by.
     */
    public static String registryOf(EditorModel model) {
        if (model == null || !supportsSerializer(model.serializerId())) {
            return null;
        }
        Optional<TagPatchFields.Target> target = TagPatchFields.target(model.recipeId());
        return target.isPresent() ? target.get().registry() : null;
    }

    /**
     * A member-add patch for {@code elementId}, or empty when the tag's registry
     * cannot hold it. The server appends the member, and refuses the patch when the
     * tag already holds it or the registry does not know the element.
     */
    public static Optional<RecipePatch> addMember(EditorModel model, IngredientKind kind, String elementId) {
        return memberPatch(model, TagPatchFields.OP_MEMBER_ADD, kind, elementId);
    }

    /**
     * A member-remove patch for {@code elementId}, or empty when the tag's registry
     * cannot hold it. The server drops the member and refuses the patch when the tag
     * does not hold it, which is what a stale page sends after somebody else changed
     * the tag.
     */
    public static Optional<RecipePatch> removeMember(EditorModel model, String elementId) {
        String registry = registryOf(model);
        return memberPatch(model, TagPatchFields.OP_MEMBER_REMOVE, kindOfRegistry(registry), elementId);
    }

    /**
     * A tag-create patch whose single member is {@code elementId}, or empty when the
     * tag's registry cannot hold it.
     *
     * <p>The new tag is named by the model's recipe id, so a client that wants a
     * different tag name has to rewrite the patch's recipe id first - which is what
     * {@code TagPatchFields.recipeId(registry, typedTagId)} is for.
     */
    public static Optional<RecipePatch> createTag(EditorModel model, String elementId) {
        String registry = registryOf(model);
        return memberPatch(model, TagPatchFields.OP_CREATE, kindOfRegistry(registry), elementId);
    }

    /**
     * A tag-delete patch for the model's tag.
     *
     * <p>A delete carries no element by contract: the operation empties the tag, and
     * which member the user happened to click says nothing about it.
     */
    public static Optional<RecipePatch> deleteTag(EditorModel model) {
        String registry = registryOf(model);
        return registry == null
                ? Optional.<RecipePatch>empty()
                : Optional.of(patch(model, registry, TagPatchFields.OP_DELETE, null));
    }

    private static Optional<RecipePatch> memberPatch(EditorModel model, String op, IngredientKind kind,
                                                      String ingredientId) {
        String registry = registryOf(model);
        if (registry == null) {
            return Optional.empty();
        }
        Optional<String> element = elementId(registry, kind, ingredientId);
        return element.isPresent()
                ? Optional.of(patch(model, registry, op, element.get()))
                : Optional.<RecipePatch>empty();
    }

    private static RecipePatch patch(EditorModel model, String registry, String op, String element) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(TagPatchFields.OP_FIELD, op);
        fields.put(TagPatchFields.REGISTRY_FIELD, registry);
        if (element != null) {
            fields.put(TagPatchFields.ELEMENT_FIELD, element);
        }
        return new RecipePatch(model.recipeId(), TagPatchFields.SERIALIZER, model.baseFingerprint(), fields);
    }

    /**
     * The element id the tag file gets, or empty when the ingredient cannot be a
     * member of that registry.
     *
     * <p>A block tag is the case that needs a conversion: its members are blocks,
     * while the only ingredient the page can show and a drag can carry is the
     * block's item, so a dropped ingredient is turned back into the block it places.
     * An item that places no block (a stick, a bucket) is refused rather than
     * written into a block tag, where the loader would either drop the whole file or
     * resolve the id to something the page never showed.
     *
     * <p>A fluid tag holds fluid ids: the dropped fluid's own registry name, with no
     * amount, because a tag member is an id and not a stack. The explicit registry
     * test is a guard against an id that happens to name entries in two registries
     * ({@code minecraft:water} is both a fluid and a block).
     */
    private static Optional<String> elementId(String registry, IngredientKind kind, String ingredientId) {
        if (registry == null || ingredientId == null) {
            return Optional.empty();
        }
        ResourceLocation id = ResourceLocation.tryParse(ingredientId.trim());
        if (id == null) {
            return Optional.empty();
        }
        if (TagPatchFields.REGISTRY_FLUID.equals(registry)) {
            return kind == IngredientKind.FLUID && BuiltInRegistries.FLUID.containsKey(id)
                    ? Optional.of(id.toString()) : Optional.<String>empty();
        }
        if (kind != IngredientKind.ITEM || !BuiltInRegistries.ITEM.containsKey(id)) {
            return Optional.empty();
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return Optional.empty();
        }
        if (!TagPatchFields.REGISTRY_BLOCK.equals(registry)) {
            return Optional.of(id.toString());
        }
        if (!(item instanceof BlockItem)) {
            return Optional.empty();
        }
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(((BlockItem) item).getBlock());
        return blockId == null ? Optional.<String>empty() : Optional.of(blockId.toString());
    }

    /**
     * The kind of ingredient a member of that registry is dropped as: the item and
     * block pages show items (JEI's block tag page draws the block items), the fluid
     * page shows fluids.
     */
    private static IngredientKind kindOfRegistry(String registry) {
        return TagPatchFields.REGISTRY_FLUID.equals(registry) ? IngredientKind.FLUID : IngredientKind.ITEM;
    }
}
