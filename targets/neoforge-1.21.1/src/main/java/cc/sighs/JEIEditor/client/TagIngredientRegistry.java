package cc.sighs.JEIEditor.client;

import cc.sighs.JEIEditor.editor.IngredientKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeps the tag entries in JEI's ingredient index in step with the editing switch.
 *
 * <p>JEI can only grow an index at runtime ({@link IIngredientManager#addIngredientsAtRuntime}),
 * so the ingredient type is registered empty at startup and the tags are added when editing
 * turns on and removed when it turns off. That is what the requirement "tags exist in the
 * index only while editing" reduces to, and it also keeps the index unchanged for a player
 * who never turns the editor on.
 *
 * <p>The border colour comes from {@code config/jei-editor.json}, written with the default
 * on first run. Only the presets in {@link Preset} are accepted: an unknown name is reported
 * and falls back to the default rather than being taken as an arbitrary colour, so a typo
 * cannot produce an invisible or clashing border.
 */
final class TagIngredientRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("JEI Editor Tags");
    private static final String CONFIG_FILE = "jei-editor.json";
    private static final String CONFIG_KEY = "tagBorderColor";
    private static final Preset DEFAULT_PRESET = Preset.CYAN;

    /** The colours a config may name. */
    private enum Preset {
        CYAN(0xFF35E0D0),
        GOLD(0xFFFFC24A),
        PURPLE(0xFFB47CFF),
        GREEN(0xFF5CD65C),
        RED(0xFFFF6B6B);

        private final int argb;

        Preset(int argb) {
            this.argb = argb;
        }
    }

    private static List<TagIngredient> tags;
    private static Boolean appliedInIndex;
    private static Integer borderColor;

    private TagIngredientRegistry() {
    }

    /** Adds the tags to the index while editing is on, and removes them while it is off. */
    static void sync() {
        Optional<IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            appliedInIndex = null;
            return;
        }
        boolean editing = ClientEditorState.isEditing();
        if (editing) {
            if (Boolean.TRUE.equals(appliedInIndex)) {
                return;
            }
        } else if (!Boolean.TRUE.equals(appliedInIndex)) {
            // Nothing was added (a fresh session, or the switch is still off): remember the
            // state instead of asking JEI to remove ingredients it was never given.
            appliedInIndex = Boolean.FALSE;
            return;
        }
        List<TagIngredient> all = tags();
        if (all.isEmpty()) {
            return;
        }
        if (editing) {
            // Write the config (default preset) as soon as the index is about to gain tags, so it
            // exists for the operator to edit instead of appearing only once a tag is drawn.
            borderColor();
        }
        IIngredientManager manager = runtime.get().getIngredientManager();
        try {
            if (editing) {
                manager.addIngredientsAtRuntime(TagIngredient.TYPE, all);
            } else {
                manager.removeIngredientsAtRuntime(TagIngredient.TYPE, all);
            }
            appliedInIndex = Boolean.valueOf(editing);
        } catch (RuntimeException exception) {
            // JEI rebuilds its lookups on a recipe reload; the next tick tries again.
            LOGGER.warn("Could not {} tag ingredients in the JEI index",
                    editing ? "add" : "remove", exception);
            appliedInIndex = null;
        }
    }

    /** Forgets what the index holds, so the next {@link #sync()} re-applies it. */
    static void invalidate() {
        appliedInIndex = null;
    }

    /** The border colour from the config, read once per session. */
    static int borderColor() {
        if (borderColor == null) {
            borderColor = Integer.valueOf(readPreset().argb);
        }
        return borderColor.intValue();
    }

    /** The registry ids of a tag's members, in the tag's own order. */
    static List<String> memberNames(TagIngredient ingredient) {
        List<String> names = new ArrayList<String>();
        if (ingredient.fluid()) {
            BuiltInRegistries.FLUID.getTag(TagKey.create(Registries.FLUID, ingredient.tagId()))
                    .ifPresent(set -> set.stream().forEach(holder ->
                            names.add(BuiltInRegistries.FLUID.getKey(holder.value()).toString())));
        } else {
            BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, ingredient.tagId()))
                    .ifPresent(set -> set.stream().forEach(holder ->
                            names.add(BuiltInRegistries.ITEM.getKey(holder.value()).toString())));
        }
        return names;
    }

    /** Every non-empty item and fluid tag, built once: a registry never gains tags at runtime. */
    private static List<TagIngredient> tags() {
        if (tags != null) {
            return tags;
        }
        List<TagIngredient> built = new ArrayList<TagIngredient>();
        for (TagKey<Item> key : BuiltInRegistries.ITEM.getTagNames().toList()) {
            ItemStack display = BuiltInRegistries.ITEM.getTag(key)
                    .flatMap(set -> set.stream().findFirst())
                    .map(holder -> new ItemStack(holder.value()))
                    .orElse(ItemStack.EMPTY);
            if (!display.isEmpty()) {
                built.add(new TagIngredient(key.location(), false, display));
            }
        }
        for (TagKey<Fluid> key : BuiltInRegistries.FLUID.getTagNames().toList()) {
            ItemStack display = BuiltInRegistries.FLUID.getTag(key)
                    .flatMap(set -> set.stream().findFirst())
                    .map(holder -> new ItemStack(holder.value().getBucket()))
                    .orElse(ItemStack.EMPTY);
            if (!display.isEmpty()) {
                built.add(new TagIngredient(key.location(), true, display));
            }
        }
        tags = built;
        return tags;
    }

    /** Whether an editor ingredient id names a tag ({@code #ns:path}) rather than a registry entry. */
    static boolean isTagId(String id) {
        return id != null && id.startsWith("#");
    }

    /**
     * The stack that stands for a tag where a stack is all the caller can draw: a slot preview,
     * a patch that has to show something. The tag's own first member (or, for a fluid tag, that
     * fluid's bucket) is the closest an unmodified slot can get to "any of these".
     */
    static Optional<ItemStack> representative(IngredientKind kind, String id) {
        if (!isTagId(id)) {
            return Optional.empty();
        }
        ResourceLocation tag = ResourceLocation.tryParse(id.substring(1));
        if (tag == null) {
            return Optional.empty();
        }
        if (kind == IngredientKind.FLUID) {
            return BuiltInRegistries.FLUID.getTag(TagKey.create(Registries.FLUID, tag))
                    .flatMap(set -> set.stream().findFirst())
                    .map(holder -> new ItemStack(holder.value().getBucket()))
                    .filter(stack -> !stack.isEmpty());
        }
        return BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tag))
                .flatMap(set -> set.stream().findFirst())
                .map(holder -> new ItemStack(holder.value()));
    }

    private static Preset readPreset() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
        try {
            if (Files.notExists(path)) {
                writeDefaultConfig(path);
                return DEFAULT_PRESET;
            }
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String name = root.has(CONFIG_KEY) ? root.get(CONFIG_KEY).getAsString() : "";
            try {
                return Preset.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                LOGGER.warn("{} '{}' in {} is not one of {}; using {}",
                        CONFIG_KEY, name, CONFIG_FILE, presetNames(), DEFAULT_PRESET);
                return DEFAULT_PRESET;
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not read {}; using {}", CONFIG_FILE, DEFAULT_PRESET, exception);
            return DEFAULT_PRESET;
        }
    }

    private static void writeDefaultConfig(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path,
                "{\n"
                        + "  \"tagBorderColor\": \"" + DEFAULT_PRESET.name() + "\",\n"
                        + "  \"_allowed\": \"" + presetNames() + "\"\n"
                        + "}\n",
                StandardCharsets.UTF_8);
        LOGGER.info("Wrote {} with the default tag border colour {}", CONFIG_FILE, DEFAULT_PRESET);
    }

    private static String presetNames() {
        StringBuilder builder = new StringBuilder();
        for (Preset preset : Preset.values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(preset.name());
        }
        return builder.toString();
    }
}
