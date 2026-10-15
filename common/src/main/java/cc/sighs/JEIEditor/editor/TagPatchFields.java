package cc.sighs.JEIEditor.editor;

import java.util.Optional;

/**
 * The field vocabulary of a tag patch: the patch kind that adds or removes one
 * member of an item, block or fluid tag, or creates or deletes the whole tag.
 *
 * <p>A tag is not a recipe. JEI shows the loader's tags as pages
 * ({@code minecraft:tag_recipes/item|block|fluid}) and a tag is edited by
 * rewriting the tag itself, not by rebuilding a recipe record, yet the patch
 * still travels the same route as every other edit: the same bundle, the same
 * world {@code RecipeEditsSavedData} and the same server gate. There is no
 * patch "kind" field in that route, so the kind is named by the serializer id
 * alone, exactly as {@code FuelRecipeEditorAdapter#isFuelPatch} does it.
 *
 * <p>{@code recipeId} carries the registry and the tag id because both the
 * SavedData and the import bundle key a patch by {@code recipeId}, and a tag id
 * is only unique within one registry: {@code minecraft:planks} can name an item
 * tag and a block tag at the same time. The encoding is
 * {@code jeieditor:tag/<registry>/<tagNamespace>/<tagPath>}; a tag path may
 * itself contain {@code /} (every {@code minecraft:mineable/*} tag does), which
 * is why the registry is the fixed second segment and everything after it is
 * the tag id.
 */
public final class TagPatchFields {
    /** The serializer id every tag patch names. */
    public static final String SERIALIZER = "jeieditor:tag";
    /** The namespace of every encoded tag recipe id. */
    public static final String RECIPE_NAMESPACE = "jeieditor";
    /** The path prefix of every encoded tag recipe id. */
    public static final String RECIPE_PREFIX = "tag/";

    /** The registries a tag page exists for, as the client spells them. */
    public static final String REGISTRY_ITEM = "item";
    public static final String REGISTRY_BLOCK = "block";
    public static final String REGISTRY_FLUID = "fluid";

    public static final String OP_FIELD = "tag.op";
    public static final String REGISTRY_FIELD = "tag.registry";
    public static final String ELEMENT_FIELD = "tag.element";

    public static final String OP_MEMBER_ADD = "member.add";
    public static final String OP_MEMBER_REMOVE = "member.remove";
    public static final String OP_CREATE = "tag.create";
    public static final String OP_DELETE = "tag.delete";

    private TagPatchFields() {
    }

    /**
     * The encoded recipe id of one tag.
     *
     * <p>The tag id's own colon is written as a slash: {@link RecipePatch} takes
     * a namespaced resource id and refuses a second colon, so
     * {@code minecraft:mineable/pickaxe} is stored as
     * {@code jeieditor:tag/block/minecraft/mineable/pickaxe}. The tag namespace
     * is therefore the first segment after the registry and the tag path is
     * everything after it.
     */
    public static String recipeId(String registry, String tagId) {
        int separator = tagId == null ? -1 : tagId.indexOf(':');
        if (!isRegistry(registry) || separator <= 0 || separator == tagId.length() - 1) {
            throw new IllegalArgumentException("registry must be item, block or fluid and tag id a "
                    + "namespaced resource id, but got " + registry + " / " + tagId);
        }
        return RECIPE_NAMESPACE + ":" + RECIPE_PREFIX + registry + "/"
                + tagId.substring(0, separator) + "/" + tagId.substring(separator + 1);
    }

    /** Whether the patch is a tag patch. The serializer id is the whole kind test. */
    public static boolean isTagPatch(RecipePatch patch) {
        return patch != null && SERIALIZER.equals(patch.serializerId());
    }

    /**
     * The registry and tag id a tag patch addresses, or empty when the patch is
     * not a tag patch, its id cannot be decoded, or its {@code tag.registry}
     * disagrees with the registry the id carries.
     *
     * <p>The two places that carry the registry have to agree: the id decides
     * the file the applier writes, the field decides the live registry it reads
     * members from, and writing one while reading the other would produce a
     * member list from the wrong registry.
     */
    public static Optional<Target> target(RecipePatch patch) {
        if (!isTagPatch(patch)) {
            return Optional.empty();
        }
        Optional<Target> decoded = target(patch.recipeId());
        if (!decoded.isPresent() || !decoded.get().registry().equals(patch.fields().get(REGISTRY_FIELD))) {
            return Optional.empty();
        }
        return decoded;
    }

    /**
     * The registry and tag id an encoded tag recipe id names, or empty when it is
     * not one.
     *
     * <p>The client needs this without a patch: a tag page is modelled from the
     * displayed tag, and which registry that model belongs to - which decides what
     * a dropped ingredient has to become - is only carried by the encoded recipe id
     * ({@link #recipeId(String, String)}). Decoding it through the same rule the
     * applier uses keeps the two from disagreeing.
     */
    public static Optional<Target> target(String recipeId) {
        Target decoded = decode(recipeId);
        return decoded == null ? Optional.<Target>empty() : Optional.of(decoded);
    }

    /** The op the patch names, or null when it names none or is not a tag patch. */
    public static String op(RecipePatch patch) {
        return isTagPatch(patch) ? patch.fields().get(OP_FIELD) : null;
    }

    public static boolean isMemberOp(RecipePatch patch) {
        String op = op(patch);
        return OP_MEMBER_ADD.equals(op) || OP_MEMBER_REMOVE.equals(op);
    }

    public static boolean isCreate(RecipePatch patch) {
        return OP_CREATE.equals(op(patch));
    }

    public static boolean isDelete(RecipePatch patch) {
        return OP_DELETE.equals(op(patch));
    }

    /**
     * The member the patch names, or null when it names none or is not a tag
     * patch. A delete carries no element by contract, so an element left on one is
     * ignored rather than read as a member the operation does not have.
     */
    public static String element(RecipePatch patch) {
        if (!isTagPatch(patch) || isDelete(patch)) {
            return null;
        }
        String value = patch.fields().get(ELEMENT_FIELD);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /**
     * The id the namespace policy is asked about, which is the tag's own id
     * rather than the encoded recipe id: the policy is configured over resource
     * ids, and a tag page is edited by changing a tag other mods also read. This
     * mirrors {@code FuelRecipeEditorAdapter.itemId(patch)}, which hands the item
     * id of a fuel patch to the same gate. Falls back to the recipe id when the
     * patch cannot be decoded, which is what the fuel call site does with its
     * empty result, so a malformed patch is still offered to the policy and
     * denied or refused there instead of here.
     */
    public static String policyId(RecipePatch patch) {
        Optional<Target> target = target(patch);
        return target.isPresent() ? target.get().tagId() : (patch == null ? null : patch.recipeId());
    }

    /** Whether {@code registry} is one of the three registries a tag page exists for. */
    public static boolean isRegistry(String registry) {
        return REGISTRY_ITEM.equals(registry) || REGISTRY_BLOCK.equals(registry)
                || REGISTRY_FLUID.equals(registry);
    }

    /**
     * The registry and tag id an encoded tag recipe id names, or null.
     *
     * <p>The registry is the segment before the first slash, the tag namespace
     * the segment after it and the tag path everything after that, which is
     * where the tag id's colon is put back. A tag namespace or path that is
     * empty is refused rather than read as a tag with no name.
     */
    private static Target decode(String recipeId) {
        if (recipeId == null) {
            return null;
        }
        String prefix = RECIPE_NAMESPACE + ":" + RECIPE_PREFIX;
        if (!recipeId.startsWith(prefix)) {
            return null;
        }
        String encoded = recipeId.substring(prefix.length());
        int registryEnd = encoded.indexOf('/');
        if (registryEnd <= 0 || registryEnd == encoded.length() - 1) {
            return null;
        }
        String registry = encoded.substring(0, registryEnd);
        String tag = encoded.substring(registryEnd + 1);
        int namespaceEnd = tag.indexOf('/');
        if (namespaceEnd <= 0 || namespaceEnd == tag.length() - 1) {
            return null;
        }
        String tagId = tag.substring(0, namespaceEnd) + ":" + tag.substring(namespaceEnd + 1);
        if (!isRegistry(registry) || !isNamespacedId(tagId)) {
            return null;
        }
        return new Target(registry, tagId);
    }

    /** Whether the text is a namespaced resource id: one colon, both halves non-empty. */
    private static boolean isNamespacedId(String value) {
        if (value == null || value.isEmpty() || value.length() > 256) {
            return false;
        }
        int separator = value.indexOf(':');
        return separator > 0 && separator < value.length() - 1
                && value.indexOf(':', separator + 1) < 0
                && value.indexOf(' ') < 0;
    }

    /** One decoded tag patch target. */
    public static final class Target {
        private final String registry;
        private final String tagId;

        private Target(String registry, String tagId) {
            this.registry = registry;
            this.tagId = tagId;
        }

        /** The registry name, one of {@code item}, {@code block}, {@code fluid}. */
        public String registry() {
            return registry;
        }

        /** The tag id, always {@code namespace:path}. */
        public String tagId() {
            return tagId;
        }
    }
}
