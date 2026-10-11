package cc.sighs.JEIEditor.server;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.IngredientKind;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipePatchSemantics;
import cc.sighs.JEIEditor.editor.SlotPatchFields;
import cc.sighs.JEIEditor.platform.fuel.FuelOverrideState;
import cc.sighs.JEIEditor.platform.recipe.CookingRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.CraftingSlotMapper;
import cc.sighs.JEIEditor.platform.recipe.DerivedSlot;
import cc.sighs.JEIEditor.platform.recipe.DerivedSlotSequence;
import cc.sighs.JEIEditor.platform.recipe.FuelRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeAdapterSupport;
import cc.sighs.JEIEditor.platform.recipe.RecipeCreationAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeDeletionAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeEditorAdapters;
import cc.sighs.JEIEditor.platform.recipe.VanillaSpecialRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.JeiVanillaRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.ModdedJsonStyle;
import cc.sighs.JEIEditor.platform.recipe.ModdedRecipeAdapter;
import cc.sighs.JEIEditor.platform.recipe.ModdedRecipeAdapters;
import cc.sighs.JEIEditor.recipe.RecipeFieldMapping;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.datamaps.builtin.Compostable;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;
import mezz.jei.library.gui.helpers.CraftingGridHelper;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import com.google.gson.JsonParser;

/** Persists accepted recipe patches and performs targeted recipe synchronization. */
public final class RecipeEditsApplier {
    private static final String PACK_DESCRIPTION = "JEI Editor recipe overrides";

    private RecipeEditsApplier() {
    }

    public static boolean canApply(MinecraftServer server, RecipePatch patch) {
        if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            return canApplyFuel(server, patch);
        }
        if (JeiVanillaRecipeEditorAdapter.isSyntheticPatch(patch)) {
            if ("jei:anvil".equals(patch.serializerId())) {
                return canApplyAnvil(server, patch);
            }
            return JeiVanillaRecipeEditorAdapter.validatePatch(patch);
        }
        ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
        if (recipeId == null) {
            return false;
        }
        if (RecipePatchSemantics.isCreation(patch)) {
            // createNewRecipeJson already requires a creatable vanilla serializer.
            return "jeieditor".equals(recipeId.getNamespace())
                    && server.getRecipeManager().byKey(recipeId).isEmpty()
                    && createNewRecipeJson(patch).isPresent();
        }
        RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null) {
            return false;
        }
        if (RecipePatchSemantics.isDeletion(patch)) {
            // Deleting a recipe leaves an empty override and never rebuilds it, so it stays
            // valid for any serializer, modded ones included.
            return RecipeDeletionAdapter.matches(holder, patch.serializerId(), patch.baseFingerprint());
        }
        // An edit is only applicable when this editor implements the serializer: a declared
        // mod recipe type, which is patched in place, or one of the vanilla serializers that
        // has a dedicated adapter. A modded serializer whose recipe class merely extends a
        // vanilla one is neither, and rebuilding such a recipe in the vanilla JSON shape
        // would drop the mod's own fields.
        if (!ModdedRecipeAdapters.supports(patch.serializerId())
                && !RecipeEditorAdapters.isImplementedVanillaSerializer(patch.serializerId())) {
            return false;
        }
        return patchModel(server, holder, patch)
                .flatMap(model -> createRecipeJson(server, holder, patch, server.registryAccess(), model))
                .isPresent();
    }

    public static CompletableFuture<Void> apply(MinecraftServer server, RecipePatch patch) {
        if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            return applyFuel(server, patch);
        }
        if (JeiVanillaRecipeEditorAdapter.isSyntheticPatch(patch)) {
            if (JeiVanillaRecipeEditorAdapter.isCompostingPatch(patch)) {
                return saveComposting(server, patch)
                        .thenCompose(ignored -> reloadComposting(server, patch));
            }
            if ("jei:anvil".equals(patch.serializerId())) {
                return saveAnvil(server, patch)
                        .thenCompose(ignored -> reloadSaved(server, patch));
            }
            return JeiVanillaRecipeEditorAdapter.validatePatch(patch)
                    ? CompletableFuture.completedFuture(null)
                    : failedFuture(new IOException("synthetic JEI patch is invalid: " + patch.recipeId()));
        }
        try {
            ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
            if (recipeId == null) {
                throw new IOException("invalid recipe id: " + patch.recipeId());
            }
            RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId).orElse(null);
            if (holder == null && !RecipePatchSemantics.isCreation(patch)) {
                throw new IOException("recipe does not exist: " + patch.recipeId());
            }
            Optional<JsonObject> json = RecipePatchSemantics.isCreation(patch)
                    ? createNewRecipeJson(patch)
                    : createRecipeJson(server, holder, patch, server.registryAccess());
            if (!json.isPresent()) {
                throw new IOException("recipe patch is stale or unsupported: " + patch.recipeId());
            }
            Path recipePath = recipePath(server, recipeId);
            Files.createDirectories(recipePath.getParent());
            ensurePackMetadata(server);
            ensureGeneratedPackSelected(server);
            boolean existed = Files.exists(recipePath);
            byte[] previous = existed ? Files.readAllBytes(recipePath) : null;
            writeAtomically(recipePath, json.get().toString().getBytes(StandardCharsets.UTF_8));
            if (RecipePatchSemantics.isDeletion(patch)) {
                removeRecipe(server, recipeId);
                return CompletableFuture.completedFuture(null);
            }
            return reloadWithRollback(server, recipeId, recipePath, existed, previous,
                    RecipePatchSemantics.isCreation(patch));
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    /** Saves a patch to the generated datapack without changing live recipes. */
    public static CompletableFuture<Void> save(MinecraftServer server, RecipePatch patch) {
        if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            return saveFuel(server, patch);
        }
        if (JeiVanillaRecipeEditorAdapter.isSyntheticPatch(patch)) {
            if (JeiVanillaRecipeEditorAdapter.isCompostingPatch(patch)) {
                return saveComposting(server, patch);
            }
            if ("jei:anvil".equals(patch.serializerId())) {
                return saveAnvil(server, patch);
            }
            return canApply(server, patch)
                    ? CompletableFuture.completedFuture(null)
                    : failedFuture(new IOException("synthetic JEI patch is invalid: " + patch.recipeId()));
        }
        try {
            ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
            if (recipeId == null) {
                throw new IOException("invalid recipe id: " + patch.recipeId());
            }
            RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId).orElse(null);
            if (holder == null && !RecipePatchSemantics.isCreation(patch)) {
                throw new IOException("recipe does not exist: " + patch.recipeId());
            }
            Optional<JsonObject> json = RecipePatchSemantics.isCreation(patch)
                    ? createNewRecipeJson(patch)
                    : createRecipeJson(server, holder, patch, server.registryAccess());
            if (!json.isPresent()) {
                throw new IOException("recipe patch is stale or unsupported: " + patch.recipeId());
            }
            Path recipePath = recipePath(server, recipeId);
            Files.createDirectories(recipePath.getParent());
            ensurePackMetadata(server);
            ensureGeneratedPackSelected(server);
            writeAtomically(recipePath, json.get().toString().getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    /** Applies one already-saved patch to the live recipe manager only. */
    public static CompletableFuture<Void> reloadSaved(MinecraftServer server, RecipePatch patch) {
        if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            Optional<ResourceLocation> itemId = FuelRecipeEditorAdapter.itemId(patch);
            int burnTime = FuelRecipeEditorAdapter.burnTime(patch);
            if (!itemId.isPresent() || burnTime < 1) {
                return failedFuture(new IOException("saved fuel patch is invalid: " + patch.recipeId()));
            }
            FuelOverrideState.set(itemId.get(), burnTime);
            return CompletableFuture.completedFuture(null);
        }
        if (JeiVanillaRecipeEditorAdapter.isSyntheticPatch(patch)) {
            if (JeiVanillaRecipeEditorAdapter.isCompostingPatch(patch)) {
                return reloadComposting(server, patch);
            }
            // Older versions allowed editing generated JEI anvil pages and
            // may have left such patches in SavedData. They are no longer
            // valid under the anvil read-only policy. Drop them during the
            // next reload so one stale entry cannot abort the whole batch and
            // prevent normal crafting output edits from being synchronized.
            if ("jei:anvil".equals(patch.serializerId())
                    && !RecipePatchSemantics.isCreation(patch)) {
                RecipeEditsSavedData.get(server).remove(patch.recipeId());
                return CompletableFuture.completedFuture(null);
            }
            return canApply(server, patch)
                    ? CompletableFuture.completedFuture(null)
                    : failedFuture(new IOException("saved synthetic JEI patch is invalid: " + patch.recipeId()));
        }
        try {
            ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
            if (recipeId == null) {
                throw new IOException("invalid recipe id: " + patch.recipeId());
            }
            Path path = recipePath(server, recipeId);
            if (Files.notExists(path)) {
                throw new IOException("saved recipe file does not exist: " + patch.recipeId());
            }
            if (RecipePatchSemantics.isDeletion(patch)) {
                if (readRecipeJson(server, recipeId, path).size() != 0) {
                    throw new IOException("saved deletion override is not empty: " + patch.recipeId());
                }
                removeRecipe(server, recipeId);
                return CompletableFuture.completedFuture(null);
            }
            reloadRecipe(server, recipeId, path, RecipePatchSemantics.isCreation(patch));
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    public static CompletableFuture<Void> reset(MinecraftServer server, String recipeIdText) {
        return reset(server, recipeIdText, null);
    }

    public static CompletableFuture<Void> reset(MinecraftServer server, String recipeIdText, EditorModel baseModel) {
        if (FuelRecipeEditorAdapter.itemIdFromRecipeId(recipeIdText).isPresent()) {
            return resetFuel(server, recipeIdText, baseModel);
        }
        RecipePatch savedSynthetic = RecipeEditsSavedData.get(server).patches().get(recipeIdText);
        if (JeiVanillaRecipeEditorAdapter.isSyntheticPatch(savedSynthetic)) {
            return resetSynthetic(server, savedSynthetic);
        }
        try {
            ResourceLocation recipeId = ResourceLocation.tryParse(recipeIdText);
            if (recipeId == null) {
                throw new IOException("invalid recipe id: " + recipeIdText);
            }
            Path recipePath = recipePath(server, recipeId);
            boolean existed = Files.exists(recipePath);
            byte[] previous = existed ? Files.readAllBytes(recipePath) : null;
            if (existed) {
                Files.delete(recipePath);
                pruneEmptyParents(recipePath.getParent());
            }
            if (RecipeCreationAdapter.isCreatedModel(baseModel)) {
                removeRecipe(server, recipeId);
                return CompletableFuture.completedFuture(null);
            }
            return reloadWithRollback(server, recipeId, recipePath, existed, previous);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    /** Resolves the current model for either a normal recipe or a synthetic fuel entry. */
    public static EditorModel currentModel(MinecraftServer server, RecipePatch patch) {
        if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            Optional<ResourceLocation> itemId = FuelRecipeEditorAdapter.itemId(patch);
            if (!itemId.isPresent()) {
                return null;
            }
            Item item = BuiltInRegistries.ITEM.get(itemId.get());
            RecipePatch savedPatch = RecipeEditsSavedData.get(server).patches().get(patch.recipeId());
            int burnTime = savedPatch != null ? FuelRecipeEditorAdapter.burnTime(savedPatch)
                    : new ItemStack(item).getBurnTime(null);
            return FuelRecipeEditorAdapter.createModel(itemId.get(), burnTime);
        }
        ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
        if (recipeId == null) {
            return null;
        }
        if (RecipePatchSemantics.isCreation(patch)) {
            return RecipeCreationAdapter.modelFromPatch(patch);
        }
        RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null) {
            return null;
        }
        return RecipePatchSemantics.isDeletion(patch)
                ? RecipeDeletionAdapter.createModel(holder).orElse(null)
                : RecipeEditorAdapters.createModel(holder, server.registryAccess()).orElse(null);
    }

    /**
     * Removes persisted synthetic entries whose generated datapack artifact no
     * longer exists. SavedData is a world cache, not an independent source of
     * truth for created anvil recipes; this also handles a user deleting the
     * generated datapack outside the editor.
     */
    public static int pruneMissingSavedArtifacts(MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        RecipeEditsSavedData data = RecipeEditsSavedData.get(server);
        int removed = 0;
        for (RecipePatch patch : new ArrayList<RecipePatch>(data.patches().values())) {
            if (!JeiVanillaRecipeEditorAdapter.isCreatedAnvilPatch(patch)) {
                continue;
            }
            Path path = anvilDataPath(server, ResourceLocation.tryParse(patch.recipeId()));
            if (path == null || Files.notExists(path)) {
                data.remove(patch.recipeId());
                removed++;
            }
        }
        return removed;
    }

    private static boolean canApplyFuel(MinecraftServer server, RecipePatch patch) {
        if (!FuelRecipeEditorAdapter.itemIdFromRecipeId(patch.recipeId()).isPresent()) {
            return false;
        }
        Optional<ResourceLocation> itemId = FuelRecipeEditorAdapter.itemId(patch);
        if (!itemId.isPresent()) {
            return false;
        }
        ResourceLocation encoded = FuelRecipeEditorAdapter.itemIdFromRecipeId(patch.recipeId()).get();
        if (!encoded.equals(itemId.get())) {
            return false;
        }
        int burnTime = FuelRecipeEditorAdapter.burnTime(patch);
        if (burnTime < 1 || burnTime > 2_000_000_000) {
            return false;
        }
        if (!BuiltInRegistries.ITEM.containsKey(itemId.get())) {
            return false;
        }
        EditorModel model = currentModel(server, patch);
        return model != null && model.baseFingerprint().equals(patch.baseFingerprint());
    }

    /** Existing JEI anvil pages are generated views, not editable recipe
     * records. Only drafts created by this editor may be saved or deleted. */
    private static boolean canApplyAnvil(MinecraftServer server, RecipePatch patch) {
        if (server == null || patch == null || !"jei:anvil".equals(patch.serializerId())) {
            return false;
        }
        if (RecipePatchSemantics.isCreation(patch)) {
            ResourceLocation id = ResourceLocation.tryParse(patch.recipeId());
            return id != null && "jeieditor".equals(id.getNamespace())
                    && server.getRecipeManager().byKey(id).isEmpty()
                    && JeiVanillaRecipeEditorAdapter.validatePatch(patch);
        }
        if (!RecipePatchSemantics.isDeletion(patch)) {
            return false;
        }
        return isCreatedAnvilIdentity(server, patch);
    }

    private static boolean isCreatedAnvilIdentity(MinecraftServer server, RecipePatch patch) {
        ResourceLocation id = ResourceLocation.tryParse(patch.recipeId());
        if (id == null || !"jeieditor".equals(id.getNamespace())
                || server.getRecipeManager().byKey(id).isPresent()) {
            return false;
        }
        EditorModel baseModel = RecipeEditsSavedData.get(server).baseModel(patch.recipeId());
        if (baseModel != null && RecipeCreationAdapter.isCreatedModel(baseModel)
                && "jei:anvil".equals(baseModel.serializerId())) {
            return true;
        }
        RecipePatch saved = RecipeEditsSavedData.get(server).patches().get(patch.recipeId());
        if (JeiVanillaRecipeEditorAdapter.isCreatedAnvilPatch(saved)) {
            return true;
        }
        // A draft can be deleted before its first save. Its generated
        // fingerprint and namespace still prove it came from the creation UI.
        return patch.baseFingerprint().startsWith("new:");
    }

    private static CompletableFuture<Void> applyFuel(MinecraftServer server, RecipePatch patch) {
        return saveFuel(server, patch).thenCompose(ignored -> reloadSaved(server, patch));
    }

    private static CompletableFuture<Void> saveFuel(MinecraftServer server, RecipePatch patch) {
        try {
            if (!canApplyFuel(server, patch)) {
                throw new IOException("recipe patch is stale or unsupported: " + patch.recipeId());
            }
            ResourceLocation itemId = FuelRecipeEditorAdapter.itemId(patch).get();
            int burnTime = FuelRecipeEditorAdapter.burnTime(patch);
            Path path = fuelDataMapPath(server);
            ensurePackMetadata(server);
            ensureGeneratedPackSelected(server);
            writeFuelValue(path, itemId, burnTime);
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static CompletableFuture<Void> resetFuel(MinecraftServer server, String recipeIdText,
                                                     EditorModel baseModel) {
        try {
            ResourceLocation itemId = FuelRecipeEditorAdapter.itemIdFromRecipeId(recipeIdText).get();
            removeFuelValue(fuelDataMapPath(server), itemId);
            FuelOverrideState.remove(itemId);
            if (baseModel != null && FuelRecipeEditorAdapter.SERIALIZER.equals(baseModel.serializerId())) {
                int originalBurnTime;
                try {
                    originalBurnTime = Integer.parseInt(baseModel.properties().get("burn_time"));
                } catch (RuntimeException exception) {
                    originalBurnTime = 0;
                }
                // Keep the live server correct even when the generated pack
                // was loaded before the reset and its data map is still cached.
                FuelOverrideState.set(itemId, Math.max(0, originalBurnTime));
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static Path fuelDataMapPath(MinecraftServer server) {
        return packRoot(server).resolve("data").resolve("neoforge")
                .resolve("data_maps").resolve("item").resolve("furnace_fuels.json");
    }

    private static CompletableFuture<Void> saveComposting(MinecraftServer server, RecipePatch patch) {
        try {
            if (!JeiVanillaRecipeEditorAdapter.validatePatch(patch)) {
                throw new IOException("composting patch is invalid: " + patch.recipeId());
            }
            ResourceLocation original = patchItem(patch, "match.input.0");
            ResourceLocation replacement = patchItem(patch, "input.0");
            if (replacement == null) {
                replacement = original;
            }
            float chance = compostChance(patch);
            if (original == null || replacement == null || chance <= 0.0F) {
                throw new IOException("composting patch has no valid input or chance: " + patch.recipeId());
            }
            Path path = compostDataMapPath(server);
            JsonObject root = readJsonObject(path);
            RecipePatch previous = RecipeEditsSavedData.get(server).patches().get(patch.recipeId());
            if (JeiVanillaRecipeEditorAdapter.isCompostingPatch(previous)) {
                removeCompostPatch(root, previous);
            }
            applyCompostPatch(root, original, replacement, chance);
            Files.createDirectories(path.getParent());
            ensurePackMetadata(server);
            ensureGeneratedPackSelected(server);
            writeAtomically(path, root.toString().getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static CompletableFuture<Void> reloadComposting(MinecraftServer server, RecipePatch patch) {
        try {
            if (!JeiVanillaRecipeEditorAdapter.validatePatch(patch)) {
                throw new IOException("saved composting patch is invalid: " + patch.recipeId());
            }
            ResourceLocation original = patchItem(patch, "match.input.0");
            ResourceLocation replacement = patchItem(patch, "input.0");
            if (replacement == null) {
                replacement = original;
            }
            float chance = compostChance(patch);
            if (original == null || replacement == null || chance <= 0.0F) {
                throw new IOException("saved composting patch has no valid input or chance: " + patch.recipeId());
            }
            Map<ResourceKey<Item>, Compostable> values =
                    BuiltInRegistries.ITEM.getDataMap(NeoForgeDataMaps.COMPOSTABLES);
            ResourceKey<Item> originalKey = ResourceKey.create(Registries.ITEM, original);
            ResourceKey<Item> replacementKey = ResourceKey.create(Registries.ITEM, replacement);
            Compostable originalValue = values.get(originalKey);
            if (!original.equals(replacement)) {
                values.remove(originalKey);
            }
            values.put(replacementKey, new Compostable(chance,
                    originalValue != null && originalValue.canVillagerCompost()));
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static CompletableFuture<Void> resetSynthetic(MinecraftServer server, RecipePatch patch) {
        if (JeiVanillaRecipeEditorAdapter.isCreatedAnvilPatch(patch)) {
            try {
                Path path = anvilDataPath(server, ResourceLocation.tryParse(patch.recipeId()));
                if (path != null) {
                    Files.deleteIfExists(path);
                    pruneEmptyParents(path.getParent());
                }
                return CompletableFuture.completedFuture(null);
            } catch (IOException | RuntimeException exception) {
                return failedFuture(exception);
            }
        }
        if (!JeiVanillaRecipeEditorAdapter.isCompostingPatch(patch)) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            Path path = compostDataMapPath(server);
            JsonObject root = readJsonObject(path);
            removeCompostPatch(root, patch);
            if (root.getAsJsonObject("values").size() == 0
                    && (!root.has("remove") || root.getAsJsonArray("remove").size() == 0)) {
                Files.deleteIfExists(path);
                pruneEmptyParents(path.getParent());
            } else {
                writeAtomically(path, root.toString().getBytes(StandardCharsets.UTF_8));
            }

            ResourceLocation original = patchItem(patch, "match.input.0");
            ResourceLocation replacement = patchItem(patch, "input.0");
            if (replacement == null) {
                replacement = original;
            }
            Map<ResourceKey<Item>, Compostable> values =
                    BuiltInRegistries.ITEM.getDataMap(NeoForgeDataMaps.COMPOSTABLES);
            if (replacement != null && !replacement.equals(original)) {
                values.remove(ResourceKey.create(Registries.ITEM, replacement));
            }
            float chance = compostChance(patch);
            if (original != null && chance > 0.0F) {
                values.put(ResourceKey.create(Registries.ITEM, original), new Compostable(chance));
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static Path compostDataMapPath(MinecraftServer server) {
        return packRoot(server).resolve("data").resolve("neoforge")
                .resolve("data_maps").resolve("item").resolve("compostables.json");
    }

    /**
     * Anvil recipes are not vanilla RecipeManager entries, so their datapack
     * representation lives under the mod's data namespace instead of the
     * vanilla recipe directory. SavedData remains the runtime source used by
     * SyntheticRecipeOverrides; this file makes the accepted edit durable and
     * inspectable in the generated datapack as well.
     */
    private static CompletableFuture<Void> saveAnvil(MinecraftServer server, RecipePatch patch) {
        try {
            if (!canApplyAnvil(server, patch)) {
                throw new IOException("anvil patch is stale or unsupported: " + patch.recipeId());
            }
            ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
            Path path = anvilDataPath(server, recipeId);
            if (path == null) {
                throw new IOException("invalid anvil recipe id: " + patch.recipeId());
            }
            ensurePackMetadata(server);
            ensureGeneratedPackSelected(server);
            if (RecipePatchSemantics.isDeletion(patch)) {
                Files.deleteIfExists(path);
                pruneEmptyParents(path.getParent());
            } else {
                Files.createDirectories(path.getParent());
                writeAtomically(path, anvilDatapackJson(patch).toString()
                        .getBytes(StandardCharsets.UTF_8));
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static Path anvilDataPath(MinecraftServer server, ResourceLocation recipeId) {
        if (server == null || recipeId == null || !"jeieditor".equals(recipeId.getNamespace())) {
            return null;
        }
        return packRoot(server).resolve("data").resolve("jeieditor")
                .resolve("jei_editor").resolve("anvil")
                .resolve(recipeId.getPath() + ".json");
    }

    private static JsonObject anvilDatapackJson(RecipePatch patch) {
        JsonObject root = new JsonObject();
        root.addProperty("type", patch.serializerId());
        root.addProperty("id", patch.recipeId());
        JsonObject fields = new JsonObject();
        for (Map.Entry<String, String> entry : patch.fields().entrySet()) {
            fields.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("fields", fields);
        addAnvilStack(root, "left", patch, "input.0");
        addAnvilStack(root, "right", patch, "input.1");
        addAnvilStack(root, "result", patch, "output");
        return root;
    }

    private static void addAnvilStack(JsonObject root, String name, RecipePatch patch, String prefix) {
        JsonObject stack = new JsonObject();
        String item = patch.fields().get(prefix + ".item");
        String count = patch.fields().get(prefix + ".count");
        stack.addProperty("item", item == null ? "minecraft:air" : item);
        stack.addProperty("count", count == null ? "0" : count);
        String encoded = patch.fields().get(prefix + ".stack");
        if (encoded != null) {
            stack.addProperty("stack", encoded);
        }
        root.add(name, stack);
    }

    private static void applyCompostPatch(JsonObject root, ResourceLocation original,
                                          ResourceLocation replacement, float chance) {
        JsonObject values = root.has("values") && root.get("values").isJsonObject()
                ? root.getAsJsonObject("values") : new JsonObject();
        JsonObject value = new JsonObject();
        value.addProperty("chance", chance);
        values.add(replacement.toString(), value);
        root.add("values", values);
        if (!original.equals(replacement)) {
            JsonArray removals = root.has("remove") && root.get("remove").isJsonArray()
                    ? root.getAsJsonArray("remove") : new JsonArray();
            if (!jsonArrayContains(removals, original.toString())) {
                removals.add(original.toString());
            }
            root.add("remove", removals);
        }
    }

    private static void removeCompostPatch(JsonObject root, RecipePatch patch) {
        JsonObject values = root.has("values") && root.get("values").isJsonObject()
                ? root.getAsJsonObject("values") : new JsonObject();
        ResourceLocation original = patchItem(patch, "match.input.0");
        ResourceLocation replacement = patchItem(patch, "input.0");
        if (replacement == null) {
            replacement = original;
        }
        if (replacement != null) {
            values.remove(replacement.toString());
        }
        root.add("values", values);
        if (original != null && root.has("remove") && root.get("remove").isJsonArray()) {
            JsonArray removals = root.getAsJsonArray("remove");
            for (int index = removals.size() - 1; index >= 0; index--) {
                if (removals.get(index).isJsonPrimitive()
                        && original.toString().equals(removals.get(index).getAsString())) {
                    removals.remove(index);
                }
            }
        }
    }

    private static boolean jsonArrayContains(JsonArray values, String expected) {
        for (JsonElement value : values) {
            if (value.isJsonPrimitive() && expected.equals(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static ResourceLocation patchItem(RecipePatch patch, String prefix) {
        return patch == null ? null : ResourceLocation.tryParse(
                patch.fields().get(prefix + ".item"));
    }

    private static float compostChance(RecipePatch patch) {
        try {
            return Float.parseFloat(patch.fields().get(
                    JeiVanillaRecipeEditorAdapter.COMPOST_CHANCE_PROPERTY));
        } catch (RuntimeException exception) {
            return -1.0F;
        }
    }

    private static void writeFuelValue(Path path, ResourceLocation itemId, int burnTime) throws IOException {
        JsonObject root = readJsonObject(path);
        JsonObject values = root.has("values") && root.get("values").isJsonObject()
                ? root.getAsJsonObject("values") : new JsonObject();
        JsonObject value = new JsonObject();
        value.addProperty("burn_time", burnTime);
        values.add(itemId.toString(), value);
        root.add("values", values);
        Files.createDirectories(path.getParent());
        writeAtomically(path, root.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void removeFuelValue(Path path, ResourceLocation itemId) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        JsonObject root = readJsonObject(path);
        if (!root.has("values") || !root.get("values").isJsonObject()) {
            return;
        }
        JsonObject values = root.getAsJsonObject("values");
        values.remove(itemId.toString());
        if (values.size() == 0 && !root.has("remove")) {
            Files.deleteIfExists(path);
            pruneEmptyParents(path.getParent());
        } else {
            root.add("values", values);
            writeAtomically(path, root.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static JsonObject readJsonObject(Path path) throws IOException {
        if (Files.notExists(path)) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw new IOException("fuel data map is not a JSON object");
            }
            return element.getAsJsonObject();
        }
    }

    private static Path packRoot(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR)
                .resolve("jeieditor-generated");
    }

    private static Path recipePath(MinecraftServer server, ResourceLocation recipeId) {
        return packRoot(server).resolve("data").resolve(recipeId.getNamespace())
                .resolve("recipe").resolve(recipeId.getPath() + ".json");
    }

    private static void ensurePackMetadata(MinecraftServer server) throws IOException {
        Path root = packRoot(server);
        Files.createDirectories(root.resolve("data"));
        Path metadata = root.resolve("pack.mcmeta");
        if (!Files.exists(metadata)) {
            String value = "{\"pack\":{\"pack_format\":48,\"description\":\"" + PACK_DESCRIPTION + "\"}}";
            writeAtomically(metadata, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Refreshes only pack discovery/selection; it does not reload resources. */
    private static void ensureGeneratedPackSelected(MinecraftServer server) throws IOException {
        PackRepository repository = server.getPackRepository();
        repository.reload();
        Optional<String> packId = repository.getAvailableIds().stream()
                .filter(id -> id.endsWith("/jeieditor-generated"))
                .findFirst();
        if (!packId.isPresent()) {
            throw new IOException("generated datapack was not discovered by the server");
        }
        List<String> selected = new ArrayList<String>(repository.getSelectedIds());
        if (!selected.contains(packId.get())) {
            selected.add(packId.get());
            repository.setSelected(selected);
        }
    }

    private static CompletableFuture<Void> reloadWithRollback(MinecraftServer server, ResourceLocation recipeId,
                                                               Path recipePath, boolean existed, byte[] previous) {
        return reloadWithRollback(server, recipeId, recipePath, existed, previous, false);
    }

    private static CompletableFuture<Void> reloadWithRollback(MinecraftServer server, ResourceLocation recipeId,
                                                               Path recipePath, boolean existed, byte[] previous,
                                                               boolean allowAdd) {
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        try {
            reloadRecipe(server, recipeId, recipePath, allowAdd);
            result.complete(null);
        } catch (IOException | RuntimeException error) {
            try {
                restoreRecipeFile(recipePath, existed, previous);
                reloadRecipe(server, recipeId, recipePath, allowAdd);
            } catch (IOException | RuntimeException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            result.completeExceptionally(error);
        }
        return result;
    }

    /** Replaces one recipe in the live manager and syncs the recipe collection,
     * without rebuilding unrelated server resources. */
    private static void reloadRecipe(MinecraftServer server, ResourceLocation recipeId,
                                      Path recipePath) throws IOException {
        reloadRecipe(server, recipeId, recipePath, false);
    }

    private static void reloadRecipe(MinecraftServer server, ResourceLocation recipeId,
                                      Path recipePath, boolean allowAdd) throws IOException {
        JsonObject json = readRecipeJson(server, recipeId, recipePath);
        RecipeHolder<?> replacement = RecipeManagerParser.parse(recipeId, json, server.registryAccess());
        List<RecipeHolder<?>> recipes = new ArrayList<RecipeHolder<?>>(server.getRecipeManager().getOrderedRecipes());
        boolean found = false;
        for (int index = 0; index < recipes.size(); index++) {
            if (recipes.get(index).id().equals(recipeId)) {
                recipes.set(index, replacement);
                found = true;
                break;
            }
        }
        if (!found) {
            if (!allowAdd) {
                throw new IOException("recipe does not exist: " + recipeId);
            }
            recipes.add(replacement);
        }
        server.getRecipeManager().replaceRecipes(recipes);
        server.getPlayerList().broadcastAll(new ClientboundUpdateRecipesPacket(
                server.getRecipeManager().getOrderedRecipes()));
    }

    /** Removes one live recipe and synchronizes the resulting collection. */
    private static void removeRecipe(MinecraftServer server, ResourceLocation recipeId) {
        List<RecipeHolder<?>> recipes = new ArrayList<RecipeHolder<?>>(
                server.getRecipeManager().getOrderedRecipes());
        recipes.removeIf(holder -> holder.id().equals(recipeId));
        server.getRecipeManager().replaceRecipes(recipes);
        server.getPlayerList().broadcastAll(new ClientboundUpdateRecipesPacket(
                server.getRecipeManager().getOrderedRecipes()));
    }

    private static JsonObject readRecipeJson(MinecraftServer server, ResourceLocation recipeId,
                                             Path recipePath) throws IOException {
        if (Files.exists(recipePath)) {
            try (Reader reader = Files.newBufferedReader(recipePath, StandardCharsets.UTF_8)) {
                return parseObject(reader, recipeId.toString());
            }
        }
        ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(recipeId.getNamespace(),
                "recipe/" + recipeId.getPath() + ".json");
        Optional<Resource> resource = server.getResourceManager().getResource(resourceId);
        if (!resource.isPresent()) {
            throw new IOException("recipe resource does not exist: " + recipeId);
        }
        try (Reader reader = resource.get().openAsReader()) {
            return parseObject(reader, resourceId.toString());
        }
    }

    private static JsonObject parseObject(Reader reader, String resourceId) throws IOException {
        com.google.gson.JsonElement element = JsonParser.parseReader(reader);
        if (!element.isJsonObject()) {
            throw new IOException("recipe resource is not an object: " + resourceId);
        }
        return element.getAsJsonObject();
    }

    private static void restoreRecipeFile(Path recipePath, boolean existed, byte[] previous) throws IOException {
        if (existed) {
            Files.createDirectories(recipePath.getParent());
            writeAtomically(recipePath, previous);
        } else {
            Files.deleteIfExists(recipePath);
            pruneEmptyParents(recipePath.getParent());
        }
    }

    private static void writeAtomically(Path path, byte[] bytes) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName().toString() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void pruneEmptyParents(Path directory) throws IOException {
        Path root = directory;
        while (root != null && Files.isDirectory(root) && !root.getFileName().toString().equals("jeieditor-generated")) {
            try (Stream<Path> entries = Files.list(root)) {
                if (entries.findAny().isPresent()) {
                    break;
                }
            }
            Files.delete(root);
            root = root.getParent();
        }
    }

    private static Optional<JsonObject> createRecipeJson(MinecraftServer server, RecipeHolder<?> holder,
                                                          RecipePatch patch,
                                                          HolderLookup.Provider registries) {
        if (RecipePatchSemantics.isCreation(patch)) {
            return createNewRecipeJson(patch);
        }
        if (RecipePatchSemantics.isDeletion(patch)) {
            return RecipeDeletionAdapter.matches(holder, patch.serializerId(), patch.baseFingerprint())
                    ? Optional.of(new JsonObject()) : Optional.empty();
        }
        return patchModel(server, holder, patch)
                .flatMap(model -> createRecipeJson(server, holder, patch, registries, model));
    }

    /**
     * The model a patch is validated and rebuilt against. Declared mod types
     * only expose the slot layout their declaration promises: JEI's displayed
     * representatives cannot be replayed on the server, so their slot contents
     * stay unknown here and the editor patches the original recipe JSON.
     */
    private static Optional<EditorModel> patchModel(MinecraftServer server, RecipeHolder<?> holder,
                                                     RecipePatch patch) {
        Optional<ModdedRecipeAdapter> declared = ModdedRecipeAdapters.forSerializer(patch.serializerId());
        if (declared.isPresent()) {
            return declaredModel(patch, declared.get());
        }
        return RecipeEditorAdapters.createModel(holder, server.registryAccess());
    }

    /**
     * The declared model of the patch being applied. A declaration names the
     * recipe JSON field of every slot, so the model is derived from the patch's
     * own slots: each one must be addressable, and a variable-arity declaration
     * ({@code %d}) addresses any ordinal the original JSON happens to carry - as
     * does a concatenated output list, whose ordinal-to-path mapping only the
     * recipe JSON decides. Whether the addressed path really exists is decided
     * while the JSON is rewritten, which refuses the whole patch instead of
     * writing part of it.
     */
    private static Optional<EditorModel> declaredModel(RecipePatch patch, ModdedRecipeAdapter adapter) {
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        for (String key : patch.fields().keySet()) {
            int separator = key.lastIndexOf('.');
            if (separator <= 0) {
                return Optional.empty();
            }
            String slotKey = key.substring(0, separator);
            if (!declarationNames(adapter, slotKey)) {
                return Optional.empty();
            }
            if (findSlot(slots, slotKey) != null) {
                continue;
            }
            slots.add(new EditorSlot(slotKey,
                    RecipeFieldMapping.outputIndex(slotKey) >= 0 ? "output" : "input", null));
        }
        if (slots.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EditorModel(patch.recipeId(), patch.serializerId(),
                patch.baseFingerprint(), slots));
    }

    /**
     * Whether the declaration names a slot key. A concatenated output list is an
     * ordered segment list resolved against the recipe JSON, which this model
     * does not carry, so any output ordinal is accepted here;
     * {@link #createModdedRecipeJson} resolves the ordinal against the JSON and
     * refuses the whole patch when the concatenation does not reach it. A
     * recipe-derived slot sequence is resolved there too, for both roles.
     */
    private static boolean declarationNames(ModdedRecipeAdapter adapter, String slotKey) {
        if (adapter.derivedSlots() != null) {
            return RecipeFieldMapping.inputOrdinal(slotKey) >= 0
                    || RecipeFieldMapping.outputIndex(slotKey) >= 0;
        }
        if (adapter.concatenatedOutputs() && RecipeFieldMapping.outputIndex(slotKey) >= 0) {
            return true;
        }
        return RecipeFieldMapping.field(adapter.inputFields(), adapter.outputFields(), slotKey) != null;
    }

    private static EditorSlot findSlot(List<EditorSlot> slots, String slotKey) {
        for (EditorSlot slot : slots) {
            if (slotKey.equals(slot.key())) {
                return slot;
            }
        }
        return null;
    }

    private static Optional<JsonObject> createNewRecipeJson(RecipePatch patch) {
        if (!RecipePatchSemantics.isCreation(patch)
                || !RecipeCreationAdapter.isCreatableSerializer(patch.serializerId())) {
            return Optional.empty();
        }
        ResourceLocation id = ResourceLocation.tryParse(patch.recipeId());
        ResourceLocation output = ResourceLocation.tryParse(patch.fields().get("output.item"));
        boolean smithingTrim = "minecraft:smithing_trim".equals(patch.serializerId());
        if (id == null || !"jeieditor".equals(id.getNamespace())
                || (!smithingTrim && (output == null || !BuiltInRegistries.ITEM.containsKey(output)))) {
            return Optional.empty();
        }
        int count;
        try {
            count = Integer.parseInt(patch.fields().get("output.count"));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (smithingTrim ? count != 0 : (count < 1 || count > 64)) {
            return Optional.empty();
        }
        if (!RecipeCreationAdapter.isCreatableSerializer(patch.serializerId())) {
            return Optional.empty();
        }
        boolean crafting = "minecraft:crafting_shaped".equals(patch.serializerId())
                || "minecraft:crafting_shapeless".equals(patch.serializerId());
        boolean special = VanillaSpecialRecipeEditorAdapter.supportsSerializer(patch.serializerId());
        if (!crafting && !special) {
            try {
                float experience = Float.parseFloat(patch.fields().getOrDefault("recipe.experience", "0.0"));
                int cookingTime = Integer.parseInt(patch.fields().getOrDefault("recipe.cooking_time", "200"));
                if (!Float.isFinite(experience) || experience < 0.0F || experience > 1000.0F
                        || cookingTime < 1 || cookingTime > 1000000) {
                    return Optional.empty();
                }
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", patch.serializerId());
        List<EditorIngredient> creationGrid = creationInputGrid(patch);
        if ("minecraft:crafting_shaped".equals(patch.serializerId())
                || "minecraft:crafting_shapeless".equals(patch.serializerId())) {
            if (!hasIngredient(creationGrid)) {
                return Optional.empty();
            }
        }
        if ("minecraft:crafting_shaped".equals(patch.serializerId())) {
            recipe.addProperty("category", "misc");
            recipe.add("pattern", shapedPattern(creationGrid));
            recipe.add("key", shapedKey(creationGrid));
        } else if ("minecraft:crafting_shapeless".equals(patch.serializerId())) {
            recipe.addProperty("category", "misc");
            JsonArray ingredients = new JsonArray();
            for (EditorIngredient ingredient : creationGrid) {
                if (ingredient != null) {
                    ingredients.add(ingredientJson(ingredient));
                }
            }
            recipe.add("ingredients", ingredients);
        } else if ("minecraft:stonecutting".equals(patch.serializerId())) {
            if (creationGrid.isEmpty() || creationGrid.get(0) == null) {
                return Optional.empty();
            }
            recipe.add("ingredient", ingredientJson(creationGrid.get(0)));
        } else if ("minecraft:smithing_transform".equals(patch.serializerId())
                || "minecraft:smithing_trim".equals(patch.serializerId())) {
            if (creationGrid.size() < 3 || creationGrid.get(0) == null
                    || creationGrid.get(1) == null || creationGrid.get(2) == null) {
                return Optional.empty();
            }
            recipe.add("template", ingredientJson(creationGrid.get(0)));
            recipe.add("base", ingredientJson(creationGrid.get(1)));
            recipe.add("addition", ingredientJson(creationGrid.get(2)));
        } else {
            if (creationGrid.isEmpty() || creationGrid.get(0) == null) {
                return Optional.empty();
            }
            recipe.add("ingredient", ingredientJson(creationGrid.get(0)));
            recipe.addProperty("experience", patch.fields().getOrDefault("recipe.experience", "0.0"));
            recipe.addProperty("cookingtime", patch.fields().getOrDefault("recipe.cooking_time", "200"));
        }
        if (!smithingTrim) {
            JsonObject result = new JsonObject();
            result.addProperty("id", output.toString());
            result.addProperty("count", count);
            recipe.add("result", result);
        }
        return Optional.of(recipe);
    }

    private static List<EditorIngredient> creationInputGrid(RecipePatch patch) {
        int size;
        if (CookingRecipeEditorAdapter.supportsSerializer(patch.serializerId())
                || "minecraft:stonecutting".equals(patch.serializerId())) {
            size = 1;
        } else if ("minecraft:smithing_transform".equals(patch.serializerId())
                || "minecraft:smithing_trim".equals(patch.serializerId())) {
            size = 3;
        } else {
            size = 9;
        }
        List<EditorIngredient> grid = new ArrayList<EditorIngredient>(size);
        for (int index = 0; index < size; index++) {
            String prefix = "input." + index;
            String item = patch.fields().get(prefix + ".item");
            String countText = patch.fields().get(prefix + ".count");
            if (item == null && countText == null) {
                grid.add(null);
                continue;
            }
            if (item == null || "minecraft:air".equals(item) || "0".equals(countText)) {
                grid.add(null);
                continue;
            }
            ResourceLocation itemId = ResourceLocation.tryParse(item);
            if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                return new ArrayList<EditorIngredient>();
            }
            int count;
            try {
                count = countText == null ? 1 : Integer.parseInt(countText);
            } catch (NumberFormatException exception) {
                return new ArrayList<EditorIngredient>();
            }
            if (count < 1 || count > 64) {
                return new ArrayList<EditorIngredient>();
            }
            grid.add(new EditorIngredient(itemId.toString(), count));
        }
        return grid;
    }

    private static Optional<JsonObject> createRecipeJson(MinecraftServer server, RecipeHolder<?> holder,
                                                          RecipePatch patch,
                                                          HolderLookup.Provider registries, EditorModel model) {
        // A declared model cannot reproduce the client's fingerprint: its slot
        // contents come from JEI's ingredient supplier, which the server cannot
        // replay. Declared patches are gated by their slot layout and by the
        // original recipe JSON instead.
        Optional<ModdedRecipeAdapter> declared = ModdedRecipeAdapters.forSerializer(patch.serializerId());
        if (model == null || !model.serializerId().equals(patch.serializerId())
                || !validatePatch(model, patch)
                || (!declared.isPresent() && !model.baseFingerprint().equals(patch.baseFingerprint()))) {
            return Optional.empty();
        }

        return createRecipeJsonContent(server, holder, patch, model);
    }

    private static Optional<JsonObject> createRecipeJsonContent(MinecraftServer server, RecipeHolder<?> holder,
                                                                 RecipePatch patch, EditorModel model) {
        Optional<ModdedRecipeAdapter> declared = ModdedRecipeAdapters.forSerializer(patch.serializerId());
        if (declared.isPresent()) {
            return createModdedRecipeJson(server, holder, patch, model, declared.get());
        }

        // Everything below rebuilds the recipe in the vanilla serializer's own JSON shape,
        // which is only correct for a serializer this editor implements. Gate here as well
        // as in the adapters and in canApply: the write path must never emit a vanilla
        // object for a modded serializer that merely extends a vanilla recipe class.
        if (!RecipeEditorAdapters.isImplementedVanillaSerializer(patch.serializerId())) {
            return Optional.empty();
        }

        List<EditorIngredient> inputs = new ArrayList<EditorIngredient>();
        for (EditorSlot slot : model.slots()) {
            if (!"input".equals(slot.role())) {
                continue;
            }
            EditorIngredient ingredient = patchedIngredient(slot, patch.fields());
            inputs.add(ingredient);
        }
        EditorSlot outputSlot = model.slots().stream()
                .filter(slot -> "output".equals(slot.role()))
                .findFirst()
                .orElse(null);
        if (outputSlot == null) {
            return Optional.empty();
        }
        boolean smithingTrim = "minecraft:smithing_trim".equals(patch.serializerId());
        EditorIngredient output = smithingTrim ? null : patchedIngredient(outputSlot, patch.fields());
        if (!smithingTrim && output == null) {
            return Optional.empty();
        }

        JsonObject recipe = new JsonObject();
        ResourceLocation serializerId = ResourceLocation.tryParse(patch.serializerId());
        if (serializerId == null) {
            return Optional.empty();
        }
        recipe.addProperty("type", serializerId.toString());
        if (holder.value() instanceof ShapedRecipe) {
            ShapedRecipe shaped = (ShapedRecipe) holder.value();
            if (!shaped.getGroup().isEmpty()) {
                recipe.addProperty("group", shaped.getGroup());
            }
            recipe.addProperty("category", shaped.category().getSerializedName());
            if (!shaped.showNotification()) {
                recipe.addProperty("show_notification", false);
            }
            List<EditorIngredient> grid = shapedInputGrid(shaped, model, patch);
            if (!hasIngredient(grid)) {
                return Optional.empty();
            }
            recipe.add("pattern", shapedPattern(grid));
            recipe.add("key", shapedKey(shaped, model, patch, grid));
        } else if (holder.value() instanceof ShapelessRecipe) {
            ShapelessRecipe shapeless = (ShapelessRecipe) holder.value();
            if (!shapeless.getGroup().isEmpty()) {
                recipe.addProperty("group", shapeless.getGroup());
            }
            recipe.addProperty("category", shapeless.category().getSerializedName());
            JsonArray ingredients = shapelessIngredients(holder, model, patch);
            if (ingredients.size() == 0) {
                return Optional.empty();
            }
            recipe.add("ingredients", ingredients);
        } else if (holder.value() instanceof AbstractCookingRecipe) {
            if (inputs.size() != 1 || inputs.get(0) == null) return Optional.empty();
            // The group is part of the recipe record, so it is written back like the
            // crafting branch above does: dropping it would silently change the
            // recipe book grouping of a recipe this editor only meant to edit.
            String cookingGroup = ((AbstractCookingRecipe) holder.value()).getGroup();
            if (!cookingGroup.isEmpty()) {
                recipe.addProperty("group", cookingGroup);
            }
            recipe.add("ingredient", cookingIngredientJson(
                    (AbstractCookingRecipe) holder.value(), patch, inputs.get(0)));
            recipe.addProperty("experience", cookingExperience(model, patch));
            recipe.addProperty("cookingtime", cookingTime(model, patch));
        } else if (holder.value() instanceof StonecutterRecipe) {
            if (inputs.size() != 1 || inputs.get(0) == null) {
                return Optional.empty();
            }
            recipe.add("ingredient", vanillaIngredientJson(
                    ((StonecutterRecipe) holder.value()).getIngredients().get(0),
                    patch, "input.0", inputs.get(0)));
        } else if (holder.value() instanceof SmithingRecipe) {
            if (inputs.size() != 3 || inputs.get(0) == null || inputs.get(1) == null
                    || inputs.get(2) == null) {
                return Optional.empty();
            }
            List<Ingredient> originals = VanillaSpecialRecipeEditorAdapter
                    .ingredientDefinitions(holder.value());
            if (originals.size() != 3) {
                return Optional.empty();
            }
            recipe.add("template", vanillaIngredientJson(originals.get(0), patch, "input.0", inputs.get(0)));
            recipe.add("base", vanillaIngredientJson(originals.get(1), patch, "input.1", inputs.get(1)));
            recipe.add("addition", vanillaIngredientJson(originals.get(2), patch, "input.2", inputs.get(2)));
        } else {
            return Optional.empty();
        }
        if (output != null) {
            recipe.add("result", resultJson(output));
        }
        return Optional.of(recipe);
    }

    /**
     * Declared mod recipe types keep their original recipe JSON and only have
     * the edited fields replaced, so unrelated keys (conditions, chances, molds
     * or any other mod data) survive verbatim.
     */
    private static Optional<JsonObject> createModdedRecipeJson(MinecraftServer server, RecipeHolder<?> holder,
                                                                RecipePatch patch,
                                                                EditorModel model, ModdedRecipeAdapter adapter) {
        ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
        if (recipeId == null) {
            return Optional.empty();
        }
        JsonObject recipe;
        try {
            recipe = readRecipeJson(server, recipeId, recipePath(server, recipeId));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
        DerivedSlotSequence derived = adapter.derivedSlots();
        if (derived != null) {
            return createDerivedRecipeJson(recipe, holder, patch, model, adapter, derived);
        }
        List<String> inputFields = adapter.inputFields();
        List<String> outputFields = adapter.outputFields();
        // A concatenated output list is resolved against this exact recipe JSON,
        // because which segment lands at which ordinal depends on it.
        RecipeFieldMapping.SegmentSource segments =
                adapter.concatenatedOutputs() ? new JsonSegmentSource(recipe) : null;
        List<int[]> removals = new ArrayList<int[]>();
        List<String> removalParents = new ArrayList<String>();
        for (EditorSlot slot : model.slots()) {
            if (!hasSlotPatch(patch, slot.key())) {
                continue;
            }
            String declaredField;
            if (segments != null && "output".equals(slot.role())) {
                declaredField = RecipeFieldMapping.segmentPath(outputFields,
                        RecipeFieldMapping.outputIndex(slot.key()), segments);
            } else {
                declaredField = RecipeFieldMapping.field(inputFields, outputFields, slot.key());
            }
            if (declaredField == null) {
                return Optional.empty();
            }
            if (clearsItemSlot(patch, slot.key())) {
                // A cleared element of a variable-arity list is removed rather than emptied:
                // neither `minecraft:air` ("Item must not be minecraft:air") nor a zero count
                // parses in the mod codecs, so the only representable "this input is gone" is
                // absence. Applied after the writes, from the back, because removing shifts
                // the indices of the elements still to remove.
                String[] element = arrayElementOf(declaredField);
                if (element != null) {
                    removals.add(new int[]{Integer.parseInt(element[1])});
                    removalParents.add(element[0]);
                    continue;
                }
            }
            JsonElement original = readPath(recipe, declaredField);
            if (adapter.preservesIngredientBase(declaredField)) {
                Optional<Boolean> preserved = writePreservedIngredientBase(
                        original, patch, slot.key(), adapter);
                if (!preserved.isPresent()) {
                    return Optional.empty();
                }
                if (preserved.get().booleanValue()) {
                    continue;
                }
            }
            Optional<JsonElement> value = declaredValueJson(original, patch, slot.key(),
                    declaredField, adapter, "output".equals(slot.role()));
            if (!value.isPresent() || !writePath(recipe, declaredField, value.get())) {
                return Optional.empty();
            }
        }
        for (int at = removals.size() - 1; at >= 0; at--) {
            if (!removeArrayElement(recipe, removalParents.get(at), removals.get(at)[0])) {
                return Optional.empty();
            }
        }
        return Optional.of(recipe);
    }

    /**
     * The array path and element index a declared field addresses, or null when the field
     * is a plain object path (a single-value input/output rather than one element of a list).
     */
    private static String[] arrayElementOf(String field) {
        if (field == null) {
            return null;
        }
        int dot = field.lastIndexOf('.');
        if (dot <= 0 || dot == field.length() - 1) {
            return null;
        }
        int index = parseIndex(field.substring(dot + 1));
        return index < 0 ? null : new String[]{field.substring(0, dot), Integer.toString(index)};
    }

    /** Drops one element of the array at {@code arrayPath}. */
    private static boolean removeArrayElement(JsonObject root, String arrayPath, int index) {
        JsonElement node = readPath(root, arrayPath);
        if (node == null || !node.isJsonArray()) {
            return false;
        }
        JsonArray array = node.getAsJsonArray();
        if (index < 0 || index >= array.size()) {
            return false;
        }
        array.remove(index);
        return true;
    }

    /** Whether the patch empties this slot ({@link SlotPatchFields#clearItem}'s shape). */
    private static boolean clearsItemSlot(RecipePatch patch, String slotKey) {
        return slotKey != null
                && SlotPatchFields.CLEARED_ITEM.equals(patch.fields().get(slotKey + ".item"))
                && SlotPatchFields.CLEARED_AMOUNT.equals(patch.fields().get(slotKey + ".count"));
    }

    /**
     * Writes the slots of a recipe-derived sequence ({@link DerivedSlotSequence}) -
     * Create's basin pages - by resolving the whole sequence from the recipe the
     * patch targets and rewriting the paths the ordinal of each patched slot
     * resolved to.
     *
     * <p>The sequence is the only thing that knows where a slot lives, so it is
     * resolved here, once, against the original JSON and the recipe the mod loaded;
     * a recipe the sequence cannot reproduce answers empty and the patch is refused
     * with nothing written. A slot resolved to several paths (the mod merged those
     * recipe entries into one drawn slot) writes the same value to all of them,
     * which is what keeps the recipe's own multiplicity - and therefore the merged
     * layout the page shows after the reload - unchanged.
     */
    private static Optional<JsonObject> createDerivedRecipeJson(JsonObject recipe, RecipeHolder<?> holder,
                                                                RecipePatch patch, EditorModel model,
                                                                ModdedRecipeAdapter adapter,
                                                                DerivedSlotSequence sequence) {
        Recipe<?> loaded = holder == null ? null : holder.value();
        Optional<List<DerivedSlot>> inputs = sequence.inputSlots(recipe, loaded);
        Optional<List<DerivedSlot>> outputs = sequence.outputSlots(recipe, loaded);
        if (!inputs.isPresent() || !outputs.isPresent()) {
            return Optional.empty();
        }
        for (EditorSlot slot : model.slots()) {
            if (!hasSlotPatch(patch, slot.key())) {
                continue;
            }
            boolean output = "output".equals(slot.role());
            int ordinal = output ? RecipeFieldMapping.outputIndex(slot.key())
                    : RecipeFieldMapping.inputOrdinal(slot.key());
            List<DerivedSlot> slots = output ? outputs.get() : inputs.get();
            if (ordinal < 0 || ordinal >= slots.size()) {
                return Optional.empty();
            }
            DerivedSlot target = slots.get(ordinal);
            IngredientKind kind = SlotPatchFields.kindOf(patch.fields(), slot.key());
            if (kind == null || kind != target.kind()) {
                // The kind of the field the sequence resolved to, not of a declared
                // field pattern: which band this ordinal is in is what the sequence
                // just decided.
                return Optional.empty();
            }
            Optional<JsonElement> value = declaredValueJson(readPath(recipe, target.paths().get(0)),
                    patch, slot.key(), kind, adapter.styleFor(kind, output),
                    adapter.fixedIdOfField(target.paths().get(0)));
            if (!value.isPresent()) {
                return Optional.empty();
            }
            for (String path : target.paths()) {
                if (!writePath(recipe, path, value.get())) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(recipe);
    }

    /**
     * Rewrites only the base of a nested ingredient node of a field the declaration
     * names as base-preserving ({@link ModdedRecipeAdapter#preservesIngredientBase}),
     * keeping every other key of the node - {@code type}, {@code count} and the
     * sibling operands such as {@code neoforge:difference}'s {@code subtracted} -
     * exactly as the file had them.
     *
     * <p>Answers {@code false} for a node that is not nested, so its field is written
     * the ordinary way, and empty to refuse the patch. The edited item goes into
     * {@code base} as the declaration's own item id key and nothing else: the outer
     * {@code count} of a {@code SizedIngredient} is that ingredient's amount, and all
     * 176 shipped {@code mekanism:painting} recipes write 1 there while the page draws
     * one item, so the conservative reading - the editor edits which item the
     * difference starts from, not how many the recipe consumes - leaves it untouched
     * instead of writing a count the slot never showed.
     */
    private static Optional<Boolean> writePreservedIngredientBase(JsonElement node, RecipePatch patch,
                                                                  String slotKey, ModdedRecipeAdapter adapter) {
        if (node == null || !node.isJsonObject()) {
            return Optional.of(Boolean.FALSE);
        }
        JsonObject object = node.getAsJsonObject();
        JsonElement base = object.get("base");
        if (base == null || !base.isJsonObject()) {
            return Optional.of(Boolean.FALSE);
        }
        IngredientKind kind = SlotPatchFields.kindOf(patch.fields(), slotKey);
        String id = kind == null ? null : SlotPatchFields.id(patch.fields(), slotKey);
        if (kind != IngredientKind.ITEM || id == null || !validIngredientId(kind, id)) {
            return Optional.empty();
        }
        JsonObject rewritten = new JsonObject();
        String chosenId = id;
        if (subtractedCovers(object.get("subtracted"), chosenId)) {
            // The preserved node is `difference(base, subtracted)`. Writing a base that
            // the subtracted side removes leaves the empty ingredient: the recipe then
            // carries an Ingredient with no values at all, and Mekanism's
            // incomplete-recipe scanner calls Ingredient.getValues() on it and crashes
            // the server on the next world start. Refuse the patch instead of writing
            // a datapack file that cannot be loaded.
            throw new IllegalArgumentException("this input is the recipe's own set minus "
                    + chosenId + "; picking " + chosenId + " would leave it empty");
        }
        rewritten.addProperty(ModdedJsonStyle.of(adapter.inputStyle()).idKey(), id);
        object.add("base", rewritten);
        return Optional.of(Boolean.TRUE);
    }

    /** Whether the preserved {@code subtracted} node removes the chosen item, i.e.
     * {@code difference(base, subtracted)} would be the empty set for it. */
    private static boolean subtractedCovers(JsonElement subtracted, String itemId) {
        if (subtracted == null || !subtracted.isJsonObject()) {
            return false;
        }
        JsonObject object = subtracted.getAsJsonObject();
        JsonElement item = object.get("item");
        if (item != null && item.isJsonPrimitive()) {
            return itemId.equals(item.getAsString());
        }
        JsonElement tag = object.get("tag");
        if (tag != null && tag.isJsonPrimitive()) {
            ResourceLocation tagId = ResourceLocation.tryParse(tag.getAsString());
            ResourceLocation chosen = ResourceLocation.tryParse(itemId);
            if (tagId == null || chosen == null) {
                return false;
            }
            var holder = BuiltInRegistries.ITEM.getHolder(ResourceKey.create(Registries.ITEM, chosen));
            return holder.isPresent() && holder.get().is(TagKey.create(Registries.ITEM, tagId));
        }
        return false;
    }

    /**
     * Reads a declared recipe JSON path. A plain segment is an object key; a
     * numeric segment indexes an array, so {@code ingredients.0} addresses the
     * first element of {@code ingredients} (the shape Create uses).
     */
    private static JsonElement readPath(JsonObject root, String path) {
        JsonElement current = root;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            if (current.isJsonArray()) {
                int index = parseIndex(segment);
                JsonArray array = current.getAsJsonArray();
                current = index < 0 || index >= array.size() ? null : array.get(index);
            } else if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(segment);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * The read-only view of one recipe JSON that a concatenated output list is
     * resolved against. A plain path counts when the recipe carries it and the
     * value is not empty; a missing array counts as empty - no element, exactly
     * like an array that is present but empty - while a present value that is
     * not an array is an error the expansion refuses. This mirrors what the JEI
     * category draws: it adds a slot only for a non-empty {@code stripped} /
     * {@code slag}, and one per element of an array that may be missing.
     */
    private static final class JsonSegmentSource implements RecipeFieldMapping.SegmentSource {
        private final JsonObject recipe;

        private JsonSegmentSource(JsonObject recipe) {
            this.recipe = recipe;
        }

        @Override
        public boolean hasPath(String path) {
            JsonElement value = readPath(recipe, path);
            if (value == null || value.isJsonNull()) {
                return false;
            }
            if (value.isJsonObject()) {
                return value.getAsJsonObject().size() > 0;
            }
            if (value.isJsonArray()) {
                return value.getAsJsonArray().size() > 0;
            }
            return true;
        }

        @Override
        public int arrayLength(String path) {
            JsonElement value = readPath(recipe, path);
            if (value == null || value.isJsonNull()) {
                return 0;
            }
            return value.isJsonArray() ? value.getAsJsonArray().size() : -1;
        }
    }

    /** Writes a declared recipe JSON path, creating a missing array when needed. */
    private static boolean writePath(JsonObject root, String path, JsonElement value) {
        String[] segments = path.split("\\.");
        JsonElement current = root;
        for (int index = 0; index < segments.length - 1; index++) {
            String segment = segments[index];
            JsonElement next;
            if (current.isJsonArray()) {
                int arrayIndex = parseIndex(segment);
                JsonArray array = current.getAsJsonArray();
                if (arrayIndex < 0 || arrayIndex >= array.size()) {
                    return false;
                }
                next = array.get(arrayIndex);
            } else if (current.isJsonObject()) {
                next = current.getAsJsonObject().get(segment);
            } else {
                return false;
            }
            if (next == null) {
                return false;
            }
            current = next;
        }
        String last = segments[segments.length - 1];
        if (current.isJsonArray()) {
            int arrayIndex = parseIndex(last);
            JsonArray array = current.getAsJsonArray();
            if (arrayIndex < 0 || arrayIndex > array.size()) {
                return false;
            }
            if (arrayIndex == array.size()) {
                // One past the end is the page's spare slot: dropping there appends, which is
                // how a variable-arity input list grows.
                array.add(value);
                return true;
            }
            array.set(arrayIndex, value);
            return true;
        }
        if (current.isJsonObject()) {
            current.getAsJsonObject().add(last, value);
            return true;
        }
        return false;
    }

    private static int parseIndex(String segment) {
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /**
     * Encodes one edited slot in the JSON shape its kind and the declaration's own
     * style require. A value the patch does not carry - the amount of a count-only
     * edit, the id of an amount-only edit - is read from the original node with
     * that style's keys, so a partial edit keeps what it did not name.
     *
     * <p>{@code fieldPath} is the recipe JSON field this slot resolved to, which for
     * an output of an ordered segment list is known only here. The kind check is made
     * against that field rather than against the slot key, so a mixed segment list -
     * an optional item output followed by an optional chemical one - accepts the patch
     * only for the field the concatenation really reached.
     *
     * <p>A field whose style writes the bare amount carries no id of its own, so the
     * declaration has to fix one and any other id is refused: writing the amount alone
     * of a dragged fluid would be read back as the fluid the field's name implies.
     */
    private static Optional<JsonElement> declaredValueJson(JsonElement original, RecipePatch patch,
                                                           String slotKey, String fieldPath,
                                                           ModdedRecipeAdapter adapter, boolean output) {
        IngredientKind kind = SlotPatchFields.kindOf(patch.fields(), slotKey);
        if (kind == null || adapter.kindOfField(fieldPath) != kind) {
            return Optional.empty();
        }
        return declaredValueJson(original, patch, slotKey, kind, adapter.styleFor(kind, output),
                adapter.fixedIdOfField(fieldPath));
    }

    /**
     * The same value, with the kind, the value shape and the fixed id resolved by
     * the caller.
     *
     * <p>A recipe-derived slot sequence has no declared field to ask: the kind of the
     * slot is the kind of the band the sequence resolved it to and its style comes
     * from that kind alone. Splitting the two keeps one encoder for both, so a
     * derived slot is written in exactly the shape a declared field of the same kind
     * is.
     */
    private static Optional<JsonElement> declaredValueJson(JsonElement original, RecipePatch patch,
                                                           String slotKey, IngredientKind kind,
                                                           ModdedJsonStyle style, String fixedId) {
        String id = SlotPatchFields.id(patch.fields(), slotKey);
        String amountText = SlotPatchFields.amount(patch.fields(), slotKey);
        if (style.amountOnly()) {
            if (fixedId == null) {
                return Optional.empty();
            }
            if (id != null && !fixedId.equals(id)) {
                return Optional.empty();
            }
            id = fixedId;
            if (amountText == null) {
                amountText = numberText(original);
            }
        } else {
            if (fixedId != null && id != null && !fixedId.equals(id)) {
                return Optional.empty();
            }
            JsonObject node = original != null && original.isJsonObject() ? original.getAsJsonObject() : null;
            if (id == null) {
                id = primitive(node, style.idKey());
            }
            if (amountText == null) {
                amountText = primitive(node, style.amountKey());
            }
            if (fixedId != null && id == null) {
                id = fixedId;
            }
        }
        int amount;
        try {
            amount = amountText == null ? -1 : Integer.parseInt(amountText);
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
        // A cleared declared item slot is NOT written as an empty node: `minecraft:air` with
        // `count 0` writes a file the recipe loader refuses ("Parsing error loading recipe"),
        // which silently drops the recipe on the next load -- worse than refusing the patch.
        // See docs/JEI_REAL_UI_TEST_FINDINGS.md, E9/E10.
        if (id == null || id.isEmpty() || !kind.acceptsAmount(amount) || !validIngredientId(kind, id)) {
            return Optional.empty();
        }
        return Optional.of(ModdedRecipeAdapters.valueElement(style, id, amount));
    }

    private static String primitive(JsonObject json, String key) {
        if (json == null || key == null) {
            return null;
        }
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** A bare JSON number, as the amount-only style writes it. */
    private static String numberText(JsonElement original) {
        return original != null && original.isJsonPrimitive() && original.getAsJsonPrimitive().isNumber()
                ? original.getAsString() : null;
    }

    private static JsonElement vanillaIngredientJson(Ingredient original, RecipePatch patch,
                                                      String slotKey, EditorIngredient current) {
        if (patch.fields().containsKey(slotKey + ".item")
                || patch.fields().containsKey(slotKey + ".count")) {
            return ingredientJson(current);
        }
        try {
            Optional<JsonElement> encoded = Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, original).result();
            if (encoded.isPresent()) {
                return encoded.get();
            }
        } catch (RuntimeException ignored) {
            // Fall back to the concrete representative when a codec is unavailable.
        }
        return ingredientJson(current);
    }

    /**
     * Keep a tag/multi-item cooking ingredient when the input was untouched.
     * Once the user edits that slot, the patch intentionally narrows it to the
     * selected concrete item.
     */
    private static JsonElement cookingIngredientJson(AbstractCookingRecipe recipe,
                                                      RecipePatch patch,
                                                      EditorIngredient editedInput) {
        if (patch.fields().containsKey("input.0.item")
                || patch.fields().containsKey("input.0.count")) {
            return ingredientJson(editedInput);
        }
        try {
            Optional<JsonElement> encoded = Ingredient.CODEC
                    .encodeStart(JsonOps.INSTANCE, recipe.getIngredients().get(0)).result();
            if (encoded.isPresent()) {
                return encoded.get();
            }
        } catch (RuntimeException ignored) {
            // Fall back to the representative item if a custom codec cannot
            // be encoded in this context.
        }
        return ingredientJson(editedInput);
    }

    private static boolean validatePatch(EditorModel model, RecipePatch patch) {
        if (RecipePatchSemantics.isDeletion(patch)) {
            return true;
        }
        if (patch.fields().isEmpty()) {
            return false;
        }
        Map<String, EditorSlot> slots = new HashMap<String, EditorSlot>();
        for (EditorSlot slot : model.slots()) {
            slots.put(slot.key(), slot);
        }
        Optional<ModdedRecipeAdapter> declared = ModdedRecipeAdapters.forSerializer(model.serializerId());
        for (Map.Entry<String, String> field : patch.fields().entrySet()) {
            String key = field.getKey();
            int separator = key.lastIndexOf('.');
            if (separator <= 0 || separator == key.length() - 1) {
                return false;
            }
            String property = key.substring(separator + 1);
            if (key.startsWith("recipe.") && CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
                if ("experience".equals(property)) {
                    try { float value = Float.parseFloat(field.getValue()); if (!Float.isFinite(value) || value < 0.0F || value > 1000.0F) return false; }
                    catch (NumberFormatException exception) { return false; }
                } else if ("cooking_time".equals(property)) {
                    try { int value = Integer.parseInt(field.getValue()); if (value < 1 || value > 1000000) return false; }
                    catch (NumberFormatException exception) { return false; }
                } else return false;
                continue;
            }
            // Declared mod types expose exactly the slots their declaration
            // promises (input.N plus output), so the slot lookup below already
            // rejects any slot the declaration does not cover.
            EditorSlot slot = slots.get(key.substring(0, separator));
            if (slot == null && isCraftingGridSerializer(model.serializerId())
                    && isGridInputKey(key.substring(0, separator))) {
                slot = new EditorSlot(key.substring(0, separator), "input", null);
            }
            if (slot == null) {
                return false;
            }
            // Only a declared page has non-item fields, and there the kind has to
            // be one the declaration names for the slot: a fluid written into
            // an item field (or the reverse) is refused before anything is read.
            // An ordered segment list allows every kind its segments hold here and
            // the write path narrows that to the field the concatenation resolves to.
            // This kind half of the gate is the same predicate the client's emitted
            // field shapes are held to (SlotPatchFields.acceptsProperty), so the
            // shapes the client can produce and the ones the server takes are one rule.
            Set<IngredientKind> accepted = declared.isPresent()
                    ? ModdedRecipeAdapters.declaredKinds(declared.get(), slot.key())
                    : Collections.<IngredientKind>singleton(IngredientKind.ITEM);
            if (!SlotPatchFields.acceptsProperty(patch.fields(), slot.key(), property, accepted)) {
                return false;
            }
            IngredientKind kind = IngredientKind.forProperty(property);
            if (property.equals(kind.idField())) {
                if (!validIngredientId(kind, field.getValue())) {
                    return false;
                }
            } else if (!validAmount(kind, field.getValue(), slot, patch)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an id field names an ingredient the game can resolve.
     *
     * <p>An item and a fluid are checked against their game registries. A chemical
     * lives in Mekanism's own registry, which this target does not compile against,
     * so its id is only checked to be a resource id; an unknown one is caught when
     * the written recipe is re-parsed and the write is rolled back.
     */
    private static boolean validIngredientId(IngredientKind kind, String value) {
        if (value != null && value.startsWith("#")) {
            // A tag ingredient, addressed as `#ns:path`. Accepted only when the tag exists in the
            // kind's own registry, so no patch can write a tag the game cannot resolve.
            ResourceLocation tag = ResourceLocation.tryParse(value.substring(1));
            if (tag == null) {
                return false;
            }
            if (kind == IngredientKind.FLUID) {
                return BuiltInRegistries.FLUID.getTag(net.minecraft.tags.TagKey.create(
                        net.minecraft.core.registries.Registries.FLUID, tag)).isPresent();
            }
            if (kind == IngredientKind.CHEMICAL) {
                // A Mekanism chemical has no tag form this editor writes.
                return false;
            }
            return BuiltInRegistries.ITEM.getTag(net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.ITEM, tag)).isPresent();
        }
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            return false;
        }
        if (kind == IngredientKind.FLUID) {
            return BuiltInRegistries.FLUID.containsKey(id);
        }
        if (kind == IngredientKind.CHEMICAL) {
            return true;
        }
        return BuiltInRegistries.ITEM.containsKey(id);
    }

    /** The amount is inside its kind's range, or the historical item clear form. */
    private static boolean validAmount(IngredientKind kind, String value, EditorSlot slot,
                                       RecipePatch patch) {
        int amount;
        try {
            amount = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return false;
        }
        if (kind.acceptsAmount(amount)) {
            return true;
        }
        return kind == IngredientKind.ITEM && amount == 0 && "input".equals(slot.role())
                && SlotPatchFields.CLEARED_ITEM.equals(patch.fields().get(slot.key() + ".item"));
    }

    private static float cookingExperience(EditorModel model, RecipePatch patch) {
        String value = patch.fields().get("recipe.experience");
        if (value == null) value = model.properties().get("experience");
        return Float.parseFloat(value);
    }

    private static int cookingTime(EditorModel model, RecipePatch patch) {
        String value = patch.fields().get("recipe.cooking_time");
        if (value == null) value = model.properties().get("cooking_time");
        return Integer.parseInt(value);
    }

    /** The ingredient a patch yields for one slot, or null when it clears it. */
    private static EditorIngredient patchedIngredient(EditorSlot slot, Map<String, String> fields) {
        return SlotPatchFields.patched(fields, slot.key(), slot.ingredient());
    }

    private static List<EditorIngredient> shapedInputGrid(ShapedRecipe shaped, EditorModel model,
                                                          RecipePatch patch) {
        List<EditorIngredient> grid = new ArrayList<EditorIngredient>(9);
        for (int i = 0; i < 9; i++) {
            grid.add(null);
        }
        boolean fixedGrid = hasInputSlot(model, "input.8");
        int[] dimensions = new int[] {shaped.getWidth(), shaped.getHeight()};
        for (EditorSlot slot : model.slots()) {
            if (!"input".equals(slot.role())) {
                continue;
            }
            int slotIndex = inputIndex(slot.key());
            if (slotIndex < 0) {
                continue;
            }
            int gridIndex = fixedGrid
                    ? slotIndex
                    : CraftingSlotMapper.craftingGridIndex(slotIndex, dimensions[0], dimensions[1]);
            if (gridIndex < 0 || gridIndex >= grid.size()) {
                continue;
            }
            grid.set(gridIndex, patchedIngredient(slot, patch.fields()));
        }
        // A patch can contain fixed-grid fields that did not exist in an old
        // compact model (for example input.5 on a 2x3 recipe). Apply these
        // semantic coordinates after compact-slot translation so new edits can
        // add cells in the third column or row without ambiguity.
        for (int gridIndex = 0; gridIndex < grid.size(); gridIndex++) {
            String prefix = "input." + gridIndex;
            String item = patch.fields().get(prefix + ".item");
            String count = patch.fields().get(prefix + ".count");
            if (item != null || count != null) {
                grid.set(gridIndex, patchedIngredient(
                        new EditorSlot(prefix, "input", null), patch.fields()));
            }
        }
        return grid;
    }

    private static JsonArray shapedPattern(List<EditorIngredient> grid) {
        int minX = 3;
        int minY = 3;
        int maxX = -1;
        int maxY = -1;
        for (int index = 0; index < grid.size(); index++) {
            if (grid.get(index) == null) {
                continue;
            }
            int x = index % 3;
            int y = index / 3;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        JsonArray pattern = new JsonArray();
        for (int y = minY; y <= maxY; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = minX; x <= maxX; x++) {
                int index = y * 3 + x;
                row.append(grid.get(index) != null ? (char) ('a' + index) : ' ');
            }
            pattern.add(row.toString());
        }
        return pattern;
    }

    private static JsonObject shapedKey(ShapedRecipe shaped, EditorModel model,
                                        RecipePatch patch, List<EditorIngredient> grid) {
        JsonObject key = new JsonObject();
        for (int i = 0; i < grid.size(); i++) {
            EditorIngredient current = grid.get(i);
            if (current != null) {
                Ingredient original = shapedIngredientAt(shaped, i);
                String patchKey = inputPatchKeyForGrid(shaped, model, patch, i);
                key.add(String.valueOf((char) ('a' + i)),
                        ingredientJson(original, current, patchKey != null));
            }
        }
        return key;
    }

    private static JsonObject shapedKey(List<EditorIngredient> grid) {
        JsonObject key = new JsonObject();
        for (int i = 0; i < grid.size(); i++) {
            EditorIngredient ingredient = grid.get(i);
            if (ingredient != null) {
                key.add(String.valueOf((char) ('a' + i)), ingredientJson(ingredient));
            }
        }
        return key;
    }

    private static JsonArray shapelessIngredients(RecipeHolder<?> holder, EditorModel model,
                                                   RecipePatch patch) {
        JsonArray result = new JsonArray();
        @SuppressWarnings("unchecked")
        RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> craftingHolder =
                (RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)
                        (RecipeHolder<?>) holder;
        Map<Integer, Ingredient> originals = CraftingGridHelper
                .getGuiSlotToIngredientMap(craftingHolder, 0, 0);
        for (EditorSlot slot : model.slots()) {
            if (!"input".equals(slot.role())) {
                continue;
            }
            int gridIndex = inputIndex(slot.key());
            if (gridIndex < 0) {
                continue;
            }
            EditorIngredient current = patchedIngredient(slot, patch.fields());
            if (current == null) {
                continue;
            }
            Ingredient original = originals.get(Integer.valueOf(gridIndex));
            result.add(ingredientJson(original, current, hasSlotPatch(patch, slot.key())));
        }
        return result;
    }

    private static Ingredient shapedIngredientAt(ShapedRecipe shaped, int gridIndex) {
        List<Ingredient> ingredients = shaped.getIngredients();
        int width = shaped.getWidth();
        int height = shaped.getHeight();
        for (int compactIndex = 0; compactIndex < ingredients.size(); compactIndex++) {
            if (CraftingSlotMapper.craftingGridIndex(compactIndex, width, height) == gridIndex) {
                return ingredients.get(compactIndex);
            }
        }
        return null;
    }

    private static String inputPatchKeyForGrid(ShapedRecipe shaped, EditorModel model,
                                                RecipePatch patch, int gridIndex) {
        String fixedKey = "input." + gridIndex;
        if (hasSlotPatch(patch, fixedKey)) {
            return fixedKey;
        }
        boolean fixedGrid = hasInputSlot(model, "input.8");
        int width = shaped.getWidth();
        int height = shaped.getHeight();
        for (EditorSlot slot : model.slots()) {
            if (!"input".equals(slot.role())) {
                continue;
            }
            int compactIndex = inputIndex(slot.key());
            if (compactIndex < 0) {
                continue;
            }
            int mapped = fixedGrid ? compactIndex
                    : CraftingSlotMapper.craftingGridIndex(compactIndex,
                    width, height);
            if (mapped == gridIndex && hasSlotPatch(patch, slot.key())) {
                return slot.key();
            }
        }
        return null;
    }

    private static boolean hasSlotPatch(RecipePatch patch, String slotKey) {
        return SlotPatchFields.touches(patch.fields(), slotKey);
    }

    private static boolean hasIngredient(List<EditorIngredient> grid) {
        for (EditorIngredient ingredient : grid) {
            if (ingredient != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInputSlot(EditorModel model, String key) {
        for (EditorSlot slot : model.slots()) {
            if (key.equals(slot.key()) && "input".equals(slot.role())) {
                return true;
            }
        }
        return false;
    }

    private static int inputIndex(String key) {
        if (!key.startsWith("input.")) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring("input.".length()));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static boolean isGridInputKey(String key) {
        int index = inputIndex(key);
        return index >= 0 && index < 9;
    }

    private static boolean isCraftingGridSerializer(String serializerId) {
        return "minecraft:crafting_shaped".equals(serializerId)
                || "minecraft:crafting_shapeless".equals(serializerId);
    }

    private static JsonObject ingredientJson(EditorIngredient ingredient) {
        JsonObject json = new JsonObject();
        String id = ingredient.itemId();
        if (id != null && id.startsWith("#")) {
            // A tag ingredient: the vanilla serializers take `{"tag":"ns:path"}` in exactly the
            // place an item takes `{"item":"ns:path"}`.
            json.addProperty("tag", id.substring(1));
            return json;
        }
        json.addProperty("item", id);
        return json;
    }

    /** Keep tag/custom ingredient JSON for untouched slots. Once a slot is
     * edited, the concrete item selected by the user intentionally replaces
     * the original ingredient. */
    private static JsonElement ingredientJson(Ingredient original, EditorIngredient current,
                                              boolean edited) {
        if (!edited && original != null && !original.isEmpty()) {
            try {
                Optional<JsonElement> encoded = Ingredient.CODEC
                        .encodeStart(JsonOps.INSTANCE, original).result();
                if (encoded.isPresent()) {
                    return encoded.get();
                }
            } catch (RuntimeException ignored) {
                // Fall through to the representative item if this ingredient
                // uses a codec that cannot be encoded in the current context.
            }
        }
        return ingredientJson(current);
    }

    private static JsonObject resultJson(EditorIngredient result) {
        JsonObject json = new JsonObject();
        json.addProperty("id", result.itemId());
        json.addProperty("count", result.count());
        return json;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(throwable);
        return future;
    }
}
