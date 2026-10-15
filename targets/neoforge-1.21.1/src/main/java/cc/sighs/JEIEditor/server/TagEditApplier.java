package cc.sighs.JEIEditor.server;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.TagPatchFields;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The server side of an editable JEI tag page: the member list an item, block
 * or fluid tag gets after a {@link TagPatchFields} patch, the generated tag file
 * that makes it durable, and the reload that makes it take effect.
 *
 * <p>The generated file is a full snapshot with {@code "replace": true}
 * ({@code {"values":[...],"replace":true}}) in the tag's own directory of the
 * generated pack, so the editor's pack owns the tag from then on: the loader
 * merges tag files from every pack by default, and an edit that only appended a
 * member would leave whatever the other pack (or the mod jar) contributed in
 * place. The members written are the ones the operation produces over the tag as
 * it is loaded right now, read from the server's live registries.
 */
public final class TagEditApplier {
    private TagEditApplier() {
    }

    /**
     * Whether the server can carry out this tag patch, without writing anything.
     * This is {@link #resultingMembers}'s presence test, so the gate and the
     * applier cannot disagree about which patches are valid.
     */
    public static boolean canApply(MinecraftServer server, RecipePatch patch) {
        return resultingMembers(server, patch).isPresent();
    }

    /**
     * The tag's effective member list after the patch, in the tag's own order, or
     * empty when the server refuses the patch.
     *
     * <p>Refusals are the semantic preconditions, not the field syntax alone:
     * adding an element the tag already holds, removing one it does not hold or
     * creating a tag that effectively exists would all leave the file identical
     * to what it describes while the user saw an edit happen, and a remove against
     * an already emptied tag is what a stale page sends after somebody else
     * changed the tag.
     */
    public static Optional<List<String>> resultingMembers(MinecraftServer server, RecipePatch patch) {
        if (server == null) {
            return Optional.empty();
        }
        Optional<TagPatchFields.Target> target = TagPatchFields.target(patch);
        if (!target.isPresent()) {
            return Optional.empty();
        }
        String registryName = target.get().registry();
        // Members are read from the registries the running server holds, not from a
        // copy, so a tag another pack or a mod has already changed is edited from
        // what is loaded now rather than from what the page was drawn against.
        if (TagPatchFields.REGISTRY_BLOCK.equals(registryName)) {
            return membersAfterOperation(server.registryAccess().registry(Registries.BLOCK).orElse(null),
                    Registries.BLOCK, target.get(), patch);
        }
        if (TagPatchFields.REGISTRY_FLUID.equals(registryName)) {
            return membersAfterOperation(server.registryAccess().registry(Registries.FLUID).orElse(null),
                    Registries.FLUID, target.get(), patch);
        }
        return membersAfterOperation(server.registryAccess().registry(Registries.ITEM).orElse(null),
                Registries.ITEM, target.get(), patch);
    }

    /** Writes the tag file and makes it take effect on the running server. */
    public static CompletableFuture<Void> apply(MinecraftServer server, RecipePatch patch) {
        return save(server, patch).thenCompose(ignored -> reloadSaved(server, patch));
    }

    /** Writes the tag file without touching the live registries. */
    public static CompletableFuture<Void> save(MinecraftServer server, RecipePatch patch) {
        try {
            Optional<List<String>> members = resultingMembers(server, patch);
            if (!members.isPresent()) {
                throw new IOException("stale or unsupported tag patch: " + patch.recipeId());
            }
            Path path = tagPath(server, patch);
            if (path == null) {
                throw new IOException("tag patch has no tag file: " + patch.recipeId());
            }
            Files.createDirectories(path.getParent());
            RecipeEditsApplier.ensurePackMetadata(server);
            RecipeEditsApplier.ensureGeneratedPackSelected(server);
            RecipeEditsApplier.writeAtomically(path, tagJson(members.get())
                    .getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return RecipeEditsApplier.failedFuture(exception);
        }
    }

    /** Reloads the tag file an earlier save wrote, without writing it again. */
    public static CompletableFuture<Void> reloadSaved(MinecraftServer server, RecipePatch patch) {
        try {
            Path path = tagPath(server, patch);
            if (path == null) {
                throw new IOException("tag patch has no tag file: " + patch.recipeId());
            }
            if (Files.notExists(path)) {
                throw new IOException("saved tag file does not exist: " + patch.recipeId());
            }
            if (!isTagFile(path)) {
                throw new IOException("saved tag file is not a tag file: " + patch.recipeId());
            }
            return reloadGeneratedPack(server);
        } catch (IOException | RuntimeException exception) {
            return RecipeEditsApplier.failedFuture(exception);
        }
    }

    /**
     * Drops the generated tag file, so the tag reverts to whatever the other
     * packs and mod jars define for it, and reloads.
     */
    public static CompletableFuture<Void> reset(MinecraftServer server, RecipePatch patch) {
        try {
            Path path = tagPath(server, patch);
            if (path == null) {
                throw new IOException("tag patch has no tag file: " + patch.recipeId());
            }
            if (Files.exists(path)) {
                Files.delete(path);
                RecipeEditsApplier.pruneEmptyParents(path.getParent());
            }
            return reloadGeneratedPack(server);
        } catch (IOException | RuntimeException exception) {
            return RecipeEditsApplier.failedFuture(exception);
        }
    }

    /**
     * The generated tag file of one tag patch:
     * {@code <pack>/data/<tag namespace>/<tags directory>/<tag path>.json}.
     *
     * <p>The directory comes from the loader's own rule -
     * {@link Registries#tagsDirPath(ResourceKey)} - rather than from a literal,
     * because that is what {@code TagManager} scans; a hard-coded
     * {@code tags/item} would keep working only until the rule changes. Returns
     * null when the patch does not name a tag or its tag id cannot be a file
     * name, which every caller treats as a refusal.
     */
    public static Path tagPath(MinecraftServer server, RecipePatch patch) {
        if (server == null) {
            return null;
        }
        Optional<TagPatchFields.Target> target = TagPatchFields.target(patch);
        if (!target.isPresent()) {
            return null;
        }
        ResourceLocation tagId = ResourceLocation.tryParse(target.get().tagId());
        if (tagId == null) {
            return null;
        }
        return RecipeEditsApplier.packRoot(server)
                .resolve("data").resolve(tagId.getNamespace())
                .resolve(Registries.tagsDirPath(registryKey(target.get().registry())))
                .resolve(tagId.getPath() + ".json");
    }

    /**
     * The member list the operation produces over the tag as it is loaded now,
     * or empty when the operation is refused.
     */
    private static <T> Optional<List<String>> membersAfterOperation(Registry<T> registry,
                                                                    ResourceKey<? extends Registry<T>> registryKey,
                                                                    TagPatchFields.Target target,
                                                                    RecipePatch patch) {
        if (registry == null) {
            return Optional.empty();
        }
        ResourceLocation tagId = ResourceLocation.tryParse(target.tagId());
        if (tagId == null) {
            return Optional.empty();
        }
        Optional<List<String>> loaded = currentMembers(registry, registryKey, tagId);
        if (!loaded.isPresent()) {
            return Optional.empty();
        }
        List<String> members = loaded.get();
        if (TagPatchFields.isDelete(patch)) {
            // A tag that a mod jar defines cannot be removed from the running server,
            // so "delete" means our pack overrides it into an empty tag instead:
            // {"values":[],"replace":true}. That is what makes both JEI's tag page and
            // the editor's tag index drop it - JEI's TagInfoRecipeMaker emits a page
            // only when the tag's ingredient list is non-empty, and the editor's index
            // skips a tag with no drawable first member - while the mod's own file is
            // left untouched. A tag with no members is already in that state and so has
            // nothing to delete.
            return members.isEmpty() ? Optional.<List<String>>empty()
                    : Optional.of(Collections.<String>emptyList());
        }
        String op = TagPatchFields.op(patch);
        boolean adding = TagPatchFields.OP_MEMBER_ADD.equals(op);
        if (!adding && !TagPatchFields.OP_MEMBER_REMOVE.equals(op)
                && !TagPatchFields.OP_CREATE.equals(op)) {
            return Optional.empty();
        }
        String element = TagPatchFields.element(patch);
        ResourceLocation elementId = element == null ? null : ResourceLocation.tryParse(element);
        if (elementId == null || !registry.containsKey(elementId)) {
            // A member the registry does not know cannot be written into a tag file:
            // the loader would refuse the whole file on the next reload, which would
            // take every other member of the tag down with it.
            return Optional.empty();
        }
        String member = elementId.toString();
        if (TagPatchFields.OP_CREATE.equals(op)) {
            return members.isEmpty() ? Optional.of(Collections.singletonList(member))
                    : Optional.empty();
        }
        if (adding) {
            if (members.contains(member)) {
                return Optional.empty();
            }
            List<String> result = new ArrayList<String>(members);
            result.add(member);
            return Optional.of(result);
        }
        if (!members.contains(member)) {
            return Optional.empty();
        }
        List<String> result = new ArrayList<String>(members.size() - 1);
        for (String existing : members) {
            if (!existing.equals(member)) {
                result.add(existing);
            }
        }
        return Optional.of(result);
    }

    /**
     * The members the registry currently has loaded for the tag, in the tag's own
     * order. A tag nobody defines reads as an empty list rather than as a refusal,
     * because a patch may legitimately be the first thing to put members in it.
     */
    private static <T> Optional<List<String>> currentMembers(Registry<T> registry,
                                                             ResourceKey<? extends Registry<T>> registryKey,
                                                             ResourceLocation tagId) {
        Optional<HolderSet.Named<T>> tag = registry.getTag(TagKey.create(registryKey, tagId));
        if (!tag.isPresent()) {
            return Optional.of(Collections.<String>emptyList());
        }
        List<String> members = new ArrayList<String>();
        for (Holder<T> holder : tag.get()) {
            ResourceLocation elementId = registry.getKey(holder.value());
            if (elementId == null) {
                // A holder with no registry name - a tag element the registry does not
                // name - has no id to write, and dropping it would silently shrink the
                // tag, so the whole patch is refused instead.
                return Optional.empty();
            }
            members.add(elementId.toString());
        }
        return Optional.of(members);
    }

    /**
     * Triggers the reload that makes the generated tag file take effect on the
     * running server, and returns its future so callers chain on it.
     *
     * <p>A recipe edit is hot-patched into the live {@code RecipeManager} one
     * entry at a time; a tag edit has no equivalent, because the server's
     * {@code TagManager} has no public accessor and {@code Registry.getTag}
     * answers from the tag map the last reload bound. The generated pack is
     * therefore re-discovered and selected
     * ({@link RecipeEditsApplier#ensureGeneratedPackSelected} only refreshes pack
     * discovery and selection, it does not reload anything) and the selected pack
     * ids are handed to the full resource reload.
     */
    private static CompletableFuture<Void> reloadGeneratedPack(MinecraftServer server) throws IOException {
        RecipeEditsApplier.ensureGeneratedPackSelected(server);
        List<String> selected = new ArrayList<String>(server.getPackRepository().getSelectedIds());
        return server.reloadResources(selected);
    }

    /** The full tag snapshot the generated file holds. */
    private static String tagJson(List<String> members) {
        JsonArray values = new JsonArray();
        for (String member : members) {
            values.add(member);
        }
        JsonObject root = new JsonObject();
        root.add("values", values);
        root.addProperty("replace", true);
        return root.toString();
    }

    /** Whether the file is a tag file: a JSON object with a member array. */
    private static boolean isTagFile(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                return false;
            }
            JsonObject root = element.getAsJsonObject();
            return root.has("values") && root.get("values").isJsonArray();
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    /** The registry key of a tag page, which is what its tag directory is derived from. */
    private static ResourceKey<? extends Registry<?>> registryKey(String registry) {
        if (TagPatchFields.REGISTRY_BLOCK.equals(registry)) {
            return Registries.BLOCK;
        }
        if (TagPatchFields.REGISTRY_FLUID.equals(registry)) {
            return Registries.FLUID;
        }
        return Registries.ITEM;
    }
}
