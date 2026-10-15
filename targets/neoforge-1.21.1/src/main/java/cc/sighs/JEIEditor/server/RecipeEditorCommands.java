package cc.sighs.JEIEditor.server;

import cc.sighs.JEIEditor.editor.RecipeEditBundle;
import cc.sighs.JEIEditor.editor.RecipeEditBundleCodec;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.TagPatchFields;
import cc.sighs.JEIEditor.platform.recipe.FuelRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeEditorAdapters;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Optional command helpers. Generated datapack files are the source of truth. */
public final class RecipeEditorCommands {
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private RecipeEditorCommands() { }
    public static void register() { NeoForge.EVENT_BUS.addListener(RecipeEditorCommands::registerCommands); }
    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("jeieditor")
                .then(Commands.literal("export").then(Commands.argument("name", StringArgumentType.word()).executes(context -> exportFile(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("import").then(Commands.argument("name", StringArgumentType.word()).executes(context -> importFile(context.getSource(), StringArgumentType.getString(context, "name"))))));
    }
    private static int exportFile(CommandSourceStack source, String name) {
        MinecraftServer server = source.getServer();
        if (server == null || !validName(name) || !RecipeEditorPolicy.load(server).canUseCommands(source)) { source.sendFailure(Component.literal("Invalid export name or permission denied")); return 0; }
        source.sendFailure(Component.literal("Export is unavailable: generated datapack files are the source of truth")); return 0;
    }
    private static int importFile(CommandSourceStack source, String name) {
        MinecraftServer server = source.getServer();
        if (server == null || !validName(name) || !RecipeEditorPolicy.load(server).canUseCommands(source)) { source.sendFailure(Component.literal("Invalid import name or permission denied")); return 0; }
        try {
            Path file = filePath(server, name); if (Files.notExists(file) || Files.size(file) > MAX_FILE_BYTES) throw new IOException("file is missing or too large");
            List<RecipePatch> patches = RecipeEditBundleCodec.decode(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).patches();
            for (RecipePatch patch : patches) {
                // A tag patch is policed by its tag id and has no recipe holder to
                // find, exactly as a fuel patch is policed by its item id: the
                // encoded recipe id names this mod's namespace, not the namespace of
                // the tag that is actually being changed.
                String policyId = TagPatchFields.isTagPatch(patch)
                        ? TagPatchFields.policyId(patch)
                        : FuelRecipeEditorAdapter.itemId(patch).map(ResourceLocation::toString)
                        .orElse(patch.recipeId());
                if (!RecipeEditorPolicy.load(server).canEdit(source, policyId)) throw new IOException("permission or namespace policy denied: " + patch.recipeId());
                ResourceLocation id = ResourceLocation.tryParse(patch.recipeId()); RecipeHolder<?> holder = id == null ? null : server.getRecipeManager().byKey(id).orElse(null);
                if ((!FuelRecipeEditorAdapter.isFuelPatch(patch) && !TagPatchFields.isTagPatch(patch)
                        && (holder == null && !(cc.sighs.JEIEditor.editor.RecipePatchSemantics.isDeletion(patch)
                        || cc.sighs.JEIEditor.editor.RecipePatchSemantics.isCreation(patch))
                        || (holder != null && !cc.sighs.JEIEditor.editor.RecipePatchSemantics.isDeletion(patch)
                        && !hasModel(server, holder, patch))))
                        || !RecipeEditsApplier.canApply(server, patch)) throw new IOException("stale or unsupported patch: " + patch.recipeId());
            }
            if (patches.isEmpty()) { source.sendSuccess(() -> Component.literal("Import contained no recipe edits"), false); return 1; }
            if (!RecipeEditCoordinator.tryBegin(server)) throw new IOException("another recipe reload is in progress");
            applyImported(server, source, patches, 0); return 1;
        } catch (IOException | RuntimeException exception) { source.sendFailure(Component.literal("Import failed: " + message(exception))); return 0; }
    }
    private static void applyImported(MinecraftServer server, CommandSourceStack source, List<RecipePatch> patches, int index) {
        if (index >= patches.size()) { RecipeEditCoordinator.finish(server); source.sendSuccess(() -> Component.literal("Imported " + patches.size() + " recipe edits"), true); return; }
        RecipeEditsApplier.apply(server, patches.get(index)).whenComplete((ignored, error) -> server.execute(() -> { if (error != null) { RecipeEditCoordinator.finish(server); source.sendFailure(Component.literal("Import failed: " + message(error))); } else applyImported(server, source, patches, index + 1); }));
    }
    /**
     * A patch needs a model: either a vanilla adapter understands the recipe, or
     * the serializer is a declared mod recipe type (whose model is rebuilt from
     * the declaration on the server side).
     */
    private static boolean hasModel(MinecraftServer server, RecipeHolder<?> holder, RecipePatch patch) {
        return RecipeEditorAdapters.createModel(holder, server.registryAccess()).isPresent()
                || cc.sighs.JEIEditor.platform.recipe.ModdedRecipeAdapters.supports(patch.serializerId());
    }
    private static Path filePath(MinecraftServer server, String name) { return server.getServerDirectory().resolve("config").resolve("jeieditor").resolve("exports").resolve(name + ".json").normalize(); }
    private static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9._-]{1,64}") && !".".equals(name) && !"..".equals(name); }
    private static String message(Throwable exception) { return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(); }
}
