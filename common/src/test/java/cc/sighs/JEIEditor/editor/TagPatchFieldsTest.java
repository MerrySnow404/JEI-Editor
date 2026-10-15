package cc.sighs.JEIEditor.editor;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tag patch id encoding and field vocabulary.
 *
 * <p>A tag patch has to name its registry and its tag twice - once in the
 * recipe id that keys the world SavedData and the import bundle, once in the
 * {@code tag.registry} field the applier reads the live registry from - and the
 * two have to agree. These tests pin the encoding down, including the tag path
 * with a slash that every {@code minecraft:mineable/*} tag has, so a client that
 * encodes and a server that decodes cannot drift apart.
 */
class TagPatchFieldsTest {
    /** An item tag whose path has no slash and a block tag whose path has one. */
    private static final String ITEM_TAG = "minecraft:planks";
    private static final String BLOCK_TAG = "minecraft:mineable/pickaxe";
    private static final String FLUID_TAG = "minecraft:water";

    private static RecipePatch patch(String recipeId, String... fields) {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        for (int index = 0; index + 1 < fields.length; index += 2) {
            map.put(fields[index], fields[index + 1]);
        }
        return new RecipePatch(recipeId, TagPatchFields.SERIALIZER, "fingerprint", map);
    }

    /** A patch of the given kind: encoded id plus the registry field it must agree with. */
    private static RecipePatch target(String registry, String tagId, String... extraFields) {
        String[] fields = new String[2 + extraFields.length];
        fields[0] = TagPatchFields.REGISTRY_FIELD;
        fields[1] = registry;
        System.arraycopy(extraFields, 0, fields, 2, extraFields.length);
        return patch(TagPatchFields.recipeId(registry, tagId), fields);
    }

    @Test
    void everyRegistryEncodesAndDecodesBackToTheSameTag() {
        String[][] samples = {
                {TagPatchFields.REGISTRY_ITEM, ITEM_TAG},
                {TagPatchFields.REGISTRY_BLOCK, BLOCK_TAG},
                {TagPatchFields.REGISTRY_FLUID, FLUID_TAG},
        };
        for (String[] sample : samples) {
            String registry = sample[0];
            String tagId = sample[1];
            String encoded = TagPatchFields.recipeId(registry, tagId);
            assertTrue(TagPatchFields.isTagPatch(target(registry, tagId)),
                    encoded + " must be recognized as a tag patch");

            Optional<TagPatchFields.Target> decoded = TagPatchFields.target(target(registry, tagId));
            assertTrue(decoded.isPresent(), encoded + " must decode");
            assertEquals(registry, decoded.get().registry());
            assertEquals(tagId, decoded.get().tagId(), encoded + " must decode back exactly");
            assertEquals(tagId, TagPatchFields.policyId(target(registry, tagId)),
                    "the policy is asked about the tag id, not the encoded recipe id");
        }

        // The encoding spells the tag id's colon as a slash, because RecipePatch
        // refuses a recipe id with a second colon.
        assertEquals("jeieditor:tag/item/minecraft/planks",
                TagPatchFields.recipeId(TagPatchFields.REGISTRY_ITEM, ITEM_TAG));
        // A tag path keeps its own slashes, so the registry is a fixed segment and
        // the tag namespace is the one after it.
        assertEquals("jeieditor:tag/block/minecraft/mineable/pickaxe",
                TagPatchFields.recipeId(TagPatchFields.REGISTRY_BLOCK, BLOCK_TAG));
    }

    @Test
    void anEncodedIdDecodesWithoutAPatch() {
        // The client reads the registry out of a tag model's recipe id, where there
        // is no patch to take the tag.registry field from.
        Optional<TagPatchFields.Target> decoded = TagPatchFields.target(
                TagPatchFields.recipeId(TagPatchFields.REGISTRY_BLOCK, BLOCK_TAG));
        assertTrue(decoded.isPresent());
        assertEquals(TagPatchFields.REGISTRY_BLOCK, decoded.get().registry());
        assertEquals(BLOCK_TAG, decoded.get().tagId());

        assertFalse(TagPatchFields.target("minecraft:planks").isPresent());
        assertFalse(TagPatchFields.target("jeieditor:tag/entity/minecraft/planks").isPresent());
        assertFalse(TagPatchFields.target((String) null).isPresent());
    }

    @Test
    void theFieldVocabularyIsTheOneTheClientEmits() {
        assertEquals("jeieditor:tag", TagPatchFields.SERIALIZER);
        assertEquals("tag.op", TagPatchFields.OP_FIELD);
        assertEquals("tag.registry", TagPatchFields.REGISTRY_FIELD);
        assertEquals("tag.element", TagPatchFields.ELEMENT_FIELD);
        assertEquals("member.add", TagPatchFields.OP_MEMBER_ADD);
        assertEquals("member.remove", TagPatchFields.OP_MEMBER_REMOVE);
        assertEquals("tag.create", TagPatchFields.OP_CREATE);
        assertEquals("tag.delete", TagPatchFields.OP_DELETE);

        assertTrue(TagPatchFields.isRegistry("item"));
        assertTrue(TagPatchFields.isRegistry("block"));
        assertTrue(TagPatchFields.isRegistry("fluid"));
        assertFalse(TagPatchFields.isRegistry("entity"));
        assertFalse(TagPatchFields.isRegistry(null));
    }

    @Test
    void anUnknownRegistryIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> TagPatchFields.recipeId("entity", ITEM_TAG));
        assertThrows(IllegalArgumentException.class,
                () -> TagPatchFields.recipeId(null, ITEM_TAG));

        // The same registry name written into an id by hand is refused when read.
        assertFalse(TagPatchFields.target(patch("jeieditor:tag/entity/minecraft/planks",
                TagPatchFields.REGISTRY_FIELD, "entity")).isPresent());
    }

    @Test
    void aRegistryFieldThatDisagreesWithTheIdIsRefused() {
        // The id says item, the field says block: the applier would write an item
        // tag file while reading block tag members.
        assertFalse(TagPatchFields.target(patch(
                TagPatchFields.recipeId(TagPatchFields.REGISTRY_ITEM, ITEM_TAG),
                TagPatchFields.REGISTRY_FIELD, TagPatchFields.REGISTRY_BLOCK)).isPresent());
        assertFalse(TagPatchFields.target(patch(
                TagPatchFields.recipeId(TagPatchFields.REGISTRY_BLOCK, BLOCK_TAG),
                TagPatchFields.REGISTRY_FIELD, TagPatchFields.REGISTRY_ITEM)).isPresent());
        // No registry field at all is just as unreadable.
        assertFalse(TagPatchFields.target(patch(
                TagPatchFields.recipeId(TagPatchFields.REGISTRY_ITEM, ITEM_TAG))).isPresent());
        // A registry field that is not one of the three fails too.
        assertFalse(TagPatchFields.target(patch(
                TagPatchFields.recipeId(TagPatchFields.REGISTRY_ITEM, ITEM_TAG),
                TagPatchFields.REGISTRY_FIELD, "entity")).isPresent());
    }

    @Test
    void aMissingorMalformedTagIdIsRefused() {
        String registryField = TagPatchFields.REGISTRY_FIELD;
        String item = TagPatchFields.REGISTRY_ITEM;
        // No namespace/path pair after the registry, and no registry segment at all.
        assertFalse(TagPatchFields.target(patch("jeieditor:tag/item", registryField, item)).isPresent());
        assertFalse(TagPatchFields.target(patch("jeieditor:tag/item/", registryField, item)).isPresent());
        // An empty tag path, an empty tag namespace.
        assertFalse(TagPatchFields.target(patch("jeieditor:tag/item/minecraft/", registryField, item)).isPresent());
        assertFalse(TagPatchFields.target(patch("jeieditor:tag/item//planks", registryField, item)).isPresent());
        // Not this kind of id at all.
        assertFalse(TagPatchFields.target(patch("jeieditor:tag", registryField, item)).isPresent());
        assertFalse(TagPatchFields.target(patch("minecraft:planks", registryField, item)).isPresent());
        assertFalse(TagPatchFields.target(patch("jeieditor:fuel/minecraft/planks", registryField, item)).isPresent());
        // A tag id whose namespace or path is empty cannot be written, so it is not accepted either.
        assertThrows(IllegalArgumentException.class,
                () -> TagPatchFields.recipeId(item, ":planks"));
        assertThrows(IllegalArgumentException.class, () -> TagPatchFields.recipeId(item, "planks"));
        assertThrows(IllegalArgumentException.class, () -> TagPatchFields.recipeId(item, "minecraft:"));
        assertThrows(IllegalArgumentException.class, () -> TagPatchFields.recipeId(item, null));
    }

    @Test
    void onlyTheTagSerializerIsATagPatch() {
        assertTrue(TagPatchFields.isTagPatch(target(TagPatchFields.REGISTRY_ITEM, ITEM_TAG)));
        assertFalse(TagPatchFields.isTagPatch(new RecipePatch("minecraft:stone",
                "minecraft:crafting_shaped", "fingerprint", new LinkedHashMap<String, String>())));
        assertFalse(TagPatchFields.isTagPatch(null));
        // A patch of the right serializer but an unreadable id has no target, yet is
        // still a tag patch: the gates refuse it on the target, not on the kind.
        RecipePatch malformed = patch("minecraft:stone", TagPatchFields.REGISTRY_FIELD, "item");
        assertTrue(TagPatchFields.isTagPatch(malformed));
        assertFalse(TagPatchFields.target(malformed).isPresent());
        assertEquals("minecraft:stone", TagPatchFields.policyId(malformed));

        // A recipe patch that happens to carry a tag field is not read as one.
        LinkedHashMap<String, String> recipeFields = new LinkedHashMap<String, String>();
        recipeFields.put(TagPatchFields.ELEMENT_FIELD, "minecraft:oak_planks");
        recipeFields.put(TagPatchFields.OP_FIELD, TagPatchFields.OP_MEMBER_ADD);
        RecipePatch recipe = new RecipePatch("minecraft:stone", "minecraft:crafting_shaped",
                "fingerprint", recipeFields);
        assertFalse(TagPatchFields.isTagPatch(recipe));
        assertNull(TagPatchFields.element(recipe));
        assertNull(TagPatchFields.op(recipe));
        assertFalse(TagPatchFields.isMemberOp(recipe));
        assertEquals("minecraft:stone", TagPatchFields.policyId(recipe));
    }

    @Test
    void theOpPredicatesAndTheElementReadBack() {
        RecipePatch add = target(TagPatchFields.REGISTRY_ITEM, ITEM_TAG,
                TagPatchFields.OP_FIELD, TagPatchFields.OP_MEMBER_ADD,
                TagPatchFields.ELEMENT_FIELD, "minecraft:oak_planks");
        assertEquals(TagPatchFields.OP_MEMBER_ADD, TagPatchFields.op(add));
        assertTrue(TagPatchFields.isMemberOp(add));
        assertFalse(TagPatchFields.isCreate(add));
        assertFalse(TagPatchFields.isDelete(add));
        assertEquals("minecraft:oak_planks", TagPatchFields.element(add));

        RecipePatch remove = target(TagPatchFields.REGISTRY_BLOCK, BLOCK_TAG,
                TagPatchFields.OP_FIELD, TagPatchFields.OP_MEMBER_REMOVE,
                TagPatchFields.ELEMENT_FIELD, "minecraft:stone");
        assertTrue(TagPatchFields.isMemberOp(remove));
        assertEquals("minecraft:stone", TagPatchFields.element(remove));

        RecipePatch create = target(TagPatchFields.REGISTRY_FLUID, "jeieditor:coolant",
                TagPatchFields.OP_FIELD, TagPatchFields.OP_CREATE,
                TagPatchFields.ELEMENT_FIELD, "minecraft:water");
        assertTrue(TagPatchFields.isCreate(create));
        assertFalse(TagPatchFields.isMemberOp(create));
        assertEquals("minecraft:water", TagPatchFields.element(create));

        RecipePatch delete = target(TagPatchFields.REGISTRY_ITEM, ITEM_TAG,
                TagPatchFields.OP_FIELD, TagPatchFields.OP_DELETE);
        assertTrue(TagPatchFields.isDelete(delete));
        assertNull(TagPatchFields.element(delete));
        // A delete carries no element, so a stray one is ignored rather than
        // read as the member the deletion does not have.
        RecipePatch deleteWithElement = target(TagPatchFields.REGISTRY_ITEM, ITEM_TAG,
                TagPatchFields.OP_FIELD, TagPatchFields.OP_DELETE,
                TagPatchFields.ELEMENT_FIELD, "minecraft:oak_planks");
        assertNull(TagPatchFields.element(deleteWithElement));

        // No op, and an op nobody knows, are neither of the four operations.
        RecipePatch noOp = target(TagPatchFields.REGISTRY_ITEM, ITEM_TAG);
        assertNull(TagPatchFields.op(noOp));
        assertFalse(TagPatchFields.isMemberOp(noOp));
        assertFalse(TagPatchFields.isCreate(noOp));
        assertFalse(TagPatchFields.isDelete(noOp));
        RecipePatch unknownOp = target(TagPatchFields.REGISTRY_ITEM, ITEM_TAG,
                TagPatchFields.OP_FIELD, "member.move");
        assertEquals("member.move", TagPatchFields.op(unknownOp));
        assertFalse(TagPatchFields.isMemberOp(unknownOp));
        assertNull(TagPatchFields.element(noOp));
    }

    @Test
    void theSameTagIdInTwoRegistriesKeepsTwoDistinctRecipeIds() {
        // Both key the SavedData and the import bundle, so they must not collide.
        RecipePatch item = target(TagPatchFields.REGISTRY_ITEM, "minecraft:planks");
        RecipePatch block = target(TagPatchFields.REGISTRY_BLOCK, "minecraft:planks");
        assertFalse(item.recipeId().equals(block.recipeId()));
        assertEquals("minecraft:planks", TagPatchFields.target(item).get().tagId());
        assertEquals("minecraft:planks", TagPatchFields.target(block).get().tagId());
        assertEquals(TagPatchFields.REGISTRY_ITEM, TagPatchFields.target(item).get().registry());
        assertEquals(TagPatchFields.REGISTRY_BLOCK, TagPatchFields.target(block).get().registry());
    }
}
