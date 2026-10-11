package cc.sighs.jeieditortests;

import cc.sighs.JEIEditor.client.JeiRecipeIntrospection;
import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.IngredientKind;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.platform.recipe.CraftingRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.DerivedSlotSequence;
import cc.sighs.JEIEditor.platform.recipe.FuelRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.ModdedRecipeAdapter;
import cc.sighs.JEIEditor.platform.recipe.ModdedRecipeAdapters;
import cc.sighs.JEIEditor.platform.recipe.ModdedRecipeModelSupport;
import cc.sighs.JEIEditor.platform.recipe.RecipeAdapterSupport;
import cc.sighs.JEIEditor.platform.recipe.RecipeEditorAdapters;
import cc.sighs.JEIEditor.recipe.RecipeFieldMapping;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Walks every recipe page JEI has registered and checks that the editor can
 * model and patch what JEI shows, using the editor's own code paths:
 *
 * <ul>
 *   <li>the slots view comes from
 *       {@link IRecipeManager#createRecipeLayoutDrawable}, exactly like the
 *       layout of a visible page;</li>
 *   <li>the model is built in the same order the client editor resolves a
 *       displayed recipe: fuel, JEI-generated vanilla page, declared mod
 *       recipe, then a plain {@link RecipeHolder};</li>
 *   <li>the patch round trip goes through
 *       {@link RecipeEditorAdapters#replaceSlot}.</li>
 * </ul>
 *
 * <p>Assertions (all of them only apply to the wired page list):
 * <ul>
 *   <li><b>A</b> every wired page must produce at least one editor model;</li>
 *   <li><b>B</b> a declared model must expose exactly the slots its declaration
 *       names for this page - {@code input.0 .. input.N-1} plus {@code output},
 *       or {@code output.0 .. output.M-1} when the page draws several outputs -
 *       and the declaration must resolve every one of those keys to a recipe
 *       JSON field. An ordered segment list ({@code concatenatedOutputs}) is the
 *       exception: which field lands at an output ordinal only the recipe JSON
 *       decides, so this only checks the declaration's slot-count bound and the
 *       server resolves the field while writing. Each slot must also carry a kind
 *       its declared field holds - for an ordered segment list, one of the kinds
 *       its segments hold, since the segment is resolved server side - and a slot
 *       the declaration names as a fluid/chemical must not be left empty: that is
 *       what a fluid page has to expose for the drag to work at all. The field of
 *       every slot is resolved twice - once by this test's own expansion, once by
 *       the server's gate ({@link ModdedRecipeAdapters#declaredKinds}) - and the
 *       two resolutions must agree, so the client cannot offer an edit the server
 *       would refuse;</li>
 *   <li><b>C</b> the write path, for every input slot and every output slot: a
 *       slot carrying an ingredient must return a patch carrying that slot's own
 *       key ({@code output.n.item} / {@code input.0.item}), and an empty one must
 *       be handled by its own contract - a declared page refuses it with an
 *       {@code IllegalArgumentException} (the editor's own contract for
 *       JEI-generated pages, armor trims and unresolved declared slots), while the
 *       vanilla and grid paths fill it. A furnace fuel entry is the input-side
 *       counterpart: its item is its identity, so replacing {@code input.0} must be
 *       refused the same way (see {@code FuelRecipeEditorAdapter}). A slot whose
 *       kind is not an item is written in that kind's own fields: the item drag
 *       must be refused, the drag of the slot's own kind - resolved through
 *       {@code RecipeAdapterSupport.editorIngredient} from JEI's registered
 *       ingredient, exactly like the ghost handler does - must produce
 *       {@code <slot>.fluid}/{@code .fluid_amount} or
 *       {@code <slot>.chemical}/{@code .chemical_amount} with the dragged id and
 *       amount, and an item slot must refuse a fluid drag the same way.</li>
 * </ul>
 *
 * <p>A page that produces a model without being wired is reported, not failed:
 * that means the editor matches a page we did not intend.
 */
public final class RecipePageClientTest {
    /** Recipes sampled per page; enough to see every page shape without running for hours. */
    private static final int SAMPLE_LIMIT = 40;
    /** Models recorded per page for triage: "recipe id [serializer via route]". */
    private static final int MODELLED_SAMPLE_LIMIT = 3;
    private static final String STONE_ITEM = "minecraft:stone";
    private static final String DIRT_ITEM = "minecraft:dirt";
    /**
     * The kinds of a slot a declaration names as an item field: the only declared
     * shape that may stay empty, because an item slot's emptiness is a legitimate
     * "nothing here" the UI already handles.
     */
    private static final Set<IngredientKind> ITEM_ONLY =
            java.util.Collections.singleton(IngredientKind.ITEM);
    /** Mods the fixed test pack must provide; only used to diagnose a broken run. */
    private static final String[] EXPECTED_TEST_MODS = {
            "jei", "jeieditor", "create", "immersiveengineering", "mekanism",
            "horsepowered", "rackitup", "nnp_easy_farming"
    };

    private RecipePageClientTest() {
    }

    /** Runs the whole walk on the client thread and returns the report. */
    public static JsonObject run(IJeiRuntime runtime, Set<String> wiredPages, String wiredSource) {
        long startedAt = System.nanoTime();
        Minecraft minecraft = Minecraft.getInstance();
        HolderLookup.Provider registries = minecraft.level == null ? null : minecraft.level.registryAccess();
        IRecipeManager manager = runtime.getRecipeManager();
        IFocusGroup focus = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();

        List<IRecipeCategory<?>> categories = manager.createRecipeCategoryLookup().includeHidden().get()
                .collect(Collectors.toList());
        categories.sort(Comparator.comparing(category -> category.getRecipeType().getUid().toString()));

        List<PageResult> pages = new ArrayList<PageResult>(categories.size());
        for (IRecipeCategory<?> category : categories) {
            String uid = category.getRecipeType().getUid().toString();
            PageResult page;
            try {
                page = walkCategory(manager, category, focus, registries, wiredPages.contains(uid));
            } catch (Throwable throwable) {
                page = new PageResult(uid, wiredPages.contains(uid));
                page.errors.add("walking the page failed: " + describe(throwable));
            }
            pages.add(page);
        }
        long walkMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        return buildReport(pages, wiredPages, wiredSource, walkMillis);
    }

    /** Report used when the walk itself could not run at all. */
    public static JsonObject errorReport(Throwable throwable) {
        JsonObject report = baseReport(new JsonArray(), "unknown", 0L);
        JsonArray failures = new JsonArray();
        failures.add(failure("W", "", "the walk did not run: " + describe(throwable)));
        report.add("failures", failures);
        report.addProperty("passed", false);
        return report;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PageResult walkCategory(IRecipeManager manager, IRecipeCategory<?> category, IFocusGroup focus,
                                           HolderLookup.Provider registries, boolean wired) {
        return walk(manager, (IRecipeCategory) category, focus, registries, wired);
    }

    private static <T> PageResult walk(IRecipeManager manager, IRecipeCategory<T> category, IFocusGroup focus,
                                       HolderLookup.Provider registries, boolean wired) {
        PageResult page = new PageResult(category.getRecipeType().getUid().toString(), wired);
        List<T> recipes;
        try {
            recipes = manager.createRecipeLookup(category.getRecipeType()).includeHidden().get()
                    .limit(SAMPLE_LIMIT).collect(Collectors.toList());
        } catch (Throwable throwable) {
            page.errors.add("the recipe lookup failed: " + describe(throwable));
            return page;
        }
        for (T recipe : recipes) {
            page.sampled++;
            Optional<IRecipeLayoutDrawable<T>> layout;
            try {
                layout = manager.createRecipeLayoutDrawable(category, recipe, focus);
            } catch (Throwable throwable) {
                page.layoutFailures++;
                page.notes.add("JEI could not build the layout of a " + className(recipe) + ": " + describe(throwable));
                continue;
            }
            if (!layout.isPresent()) {
                page.layoutFailures++;
                page.notes.add("JEI returned no layout for a " + className(recipe));
                continue;
            }

            IRecipeSlotsView slots = layout.get().getRecipeSlotsView();
            String serializerId = serializerId(recipe);
            if (serializerId != null) {
                page.serializers.add(serializerId);
            }
            Optional<ResourceLocation> recipeId = recipeId(category, recipe);
            Optional<ModelResult> resolved = resolveModel(recipe, category, slots, recipeId, serializerId,
                    registries, page);
            if (!resolved.isPresent()) {
                page.unmodelable++;
                continue;
            }
            page.recordModel(resolved.get(), slots, loadedRecipe(recipe));
        }
        return page;
    }

    /**
     * The recipe object the mod loaded for a displayed page entry, or null when the
     * displayed object is not a recipe at all (JEI's own fuel entries). The declared
     * assertions read it to re-derive, independently of the editor, what a page with
     * a recipe-derived slot sequence has to show: the {@code Recipe} the mod itself
     * registered is the same object its JEI category drew the slots from.
     */
    static Recipe<?> loadedRecipe(Object displayed) {
        if (displayed instanceof RecipeHolder) {
            return ((RecipeHolder<?>) displayed).value();
        }
        return displayed instanceof Recipe ? (Recipe<?>) displayed : null;
    }

    /**
     * The editor's own model resolution order ({@code JeiRecipeEditorPlugin.resolveModel}):
     * furnace fuel, JEI-generated vanilla pages, declared mod pages, and finally
     * the plain recipe-holder adapters.
     */
    private static Optional<ModelResult> resolveModel(Object displayedRecipe, IRecipeCategory<?> category,
                                                      IRecipeSlotsView slots, Optional<ResourceLocation> recipeId,
                                                      String serializerId, HolderLookup.Provider registries,
                                                      PageResult page) {
        // Fuel entries are JEI's own IJeiFuelingRecipe objects, not recipes, so
        // this is tried first and without any type check - exactly like the editor.
        try {
            Optional<EditorModel> fuel = RecipeEditorAdapters.createFuelModel(displayedRecipe);
            if (fuel.isPresent()) {
                return Optional.of(new ModelResult(fuel.get(), "fuel"));
            }
        } catch (Throwable throwable) {
            page.errors.add("the fuel model threw: " + describe(throwable));
        }
        try {
            Optional<EditorModel> jeiModel = RecipeEditorAdapters.createJeiModel(displayedRecipe,
                    recipeId.orElse(null), registries);
            if (jeiModel.isPresent()) {
                return Optional.of(new ModelResult(jeiModel.get(), "jei-generated"));
            }
        } catch (Throwable throwable) {
            page.errors.add("the JEI-generated model threw: " + describe(throwable));
        }
        try {
            Optional<EditorModel> declared = ModdedRecipeModelSupport.createModel(slots,
                    recipeId.isPresent() ? recipeId.get().toString() : null, serializerId,
                    category == null ? null : category.getRecipeType().getUid(),
                    loadedRecipe(displayedRecipe));
            if (declared.isPresent()) {
                return Optional.of(new ModelResult(declared.get(), "declared"));
            }
        } catch (Throwable throwable) {
            page.errors.add("the declared model threw for serializer " + serializerId + ": " + describe(throwable));
        }
        if (!isHandled(category, displayedRecipe)) {
            return Optional.empty();
        }
        Optional<RecipeHolder<?>> holder = resolveRecipeHolder(displayedRecipe, category, recipeId);
        if (!holder.isPresent()) {
            return Optional.empty();
        }
        try {
            Optional<EditorModel> model = RecipeEditorAdapters.createModel(holder.get(), registries);
            if (model.isPresent()) {
                return Optional.of(new ModelResult(model.get(), "recipe-holder"));
            }
        } catch (Throwable throwable) {
            page.errors.add("the recipe-holder model threw: " + describe(throwable));
        }
        return Optional.empty();
    }

    /** Serializer of the held recipe, or null when the page is not a recipe record. */
    private static String serializerId(Object displayedRecipe) {
        Recipe<?> recipe = null;
        if (displayedRecipe instanceof RecipeHolder<?>) {
            recipe = ((RecipeHolder<?>) displayedRecipe).value();
        } else if (displayedRecipe instanceof Recipe) {
            // Some mods push the bare recipe object into JEI, not a holder.
            recipe = (Recipe<?>) displayedRecipe;
        }
        if (recipe == null) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        return id == null ? null : id.toString();
    }

    /**
     * The recipe id the editor uses: the holder id, otherwise the id of the
     * client recipe manager holder wrapping the identical recipe instance.
     */
    private static Optional<ResourceLocation> recipeId(IRecipeCategory<?> category, Object displayedRecipe) {
        Optional<ResourceLocation> id = JeiRecipeIntrospection.recipeId(category, displayedRecipe);
        if (id.isPresent()) {
            return id;
        }
        return holderByInstanceIdentity(displayedRecipe).map(RecipeHolder::id);
    }

    private static Optional<RecipeHolder<?>> holderByInstanceIdentity(Object displayedRecipe) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return Optional.empty();
        }
        for (RecipeHolder<?> candidate : minecraft.level.getRecipeManager().getRecipes()) {
            if (candidate.value() == displayedRecipe) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Mirrors {@code JeiRecipeEditorPlugin.resolveRecipeHolder}.
     *
     * <p>Like the editor, the registry lookup is only trusted for a
     * {@code RecipeHolder}: its id is the recipe's own id. For anything else the
     * id came from the category's registry name, which can be a tag id (JEI's
     * tag pages) or a category-generated uid, so those pages are resolved by
     * identity instead of by an id that may belong to another recipe. A page
     * that displays another recipe type's recipes under a rewritten id resolves
     * through the same viewer alias the editor uses.
     */
    private static Optional<RecipeHolder<?>> resolveRecipeHolder(Object displayedRecipe,
                                                                 IRecipeCategory<?> category,
                                                                 Optional<ResourceLocation> recipeId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return Optional.empty();
        }
        if (displayedRecipe instanceof RecipeHolder<?> && recipeId.isPresent()) {
            Optional<RecipeHolder<?>> holder = minecraft.level.getRecipeManager().byKey(recipeId.get());
            if (holder.isPresent()) {
                return holder;
            }
            Optional<RecipeHolder<?>> aliased =
                    JeiRecipeIntrospection.aliasedRecipeHolder(category, recipeId.get());
            if (aliased.isPresent()) {
                return aliased;
            }
        }
        return minecraft.level.getRecipeManager().getRecipes().stream()
                .filter(candidate -> candidate.value() == displayedRecipe
                        || candidate.value().equals(displayedRecipe))
                .findFirst();
    }

    @SuppressWarnings({"rawtypes", "unchecked", "removal"})
    private static boolean isHandled(IRecipeCategory<?> category, Object displayedRecipe) {
        if (category == null || displayedRecipe == null) {
            return false;
        }
        try {
            return ((IRecipeCategory) category).isHandled(displayedRecipe);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static JsonObject buildReport(List<PageResult> pages, Set<String> wiredPages, String wiredSource,
                                          long walkMillis) {
        JsonArray failures = new JsonArray();
        JsonArray rows = new JsonArray();
        JsonArray unexpected = new JsonArray();
        JsonArray patchRoundTripIssues = new JsonArray();
        Set<String> seenUids = new TreeSet<String>();

        for (PageResult page : pages) {
            rows.add(page.toJson());
            seenUids.add(page.uid);
            if (page.wired) {
                if (page.modelCount == 0) {
                    failures.add(failure("A", page.uid, page.sampled == 0
                            ? "the page registered no recipes at all"
                            : "no editor model for any of the " + page.sampled + " sampled recipes"
                            + " (layout failures: " + page.layoutFailures + ", serializers: "
                            + page.serializers + ")"));
                }
                for (String issue : page.slotKeyIssues) {
                    failures.add(failure("B", page.uid, issue));
                }
                for (String issue : page.patchIssues) {
                    failures.add(failure("C", page.uid, issue));
                }
                for (String issue : page.errors) {
                    failures.add(failure("X", page.uid, issue));
                }
            } else {
                if (page.modelCount > 0) {
                    unexpected.add(page.uid + " (" + page.modelCount + "/" + page.sampled + " modelled, routes "
                            + page.routes + ")");
                }
                for (String issue : page.patchIssues) {
                    patchRoundTripIssues.add(failure("C", page.uid, issue));
                }
                for (String issue : page.errors) {
                    patchRoundTripIssues.add(failure("X", page.uid, issue));
                }
            }
        }

        JsonObject report = baseReport(rows, wiredSource, walkMillis);
        for (String wired : wiredPages) {
            if (!seenUids.contains(wired)) {
                failures.add(failure("A", wired, "the page is not registered in JEI at all"));
            }
        }
        if (wiredPages.isEmpty()) {
            failures.add(failure("W", "", "the wired page list is empty; the harness could not read it"));
        }
        report.add("failures", failures);
        report.add("unexpected_pages", unexpected);
        report.add("patch_roundtrip_issues", patchRoundTripIssues);
        report.addProperty("pass_failures", failures.size());
        report.addProperty("passed", failures.size() == 0);
        return report;
    }

    private static JsonObject baseReport(JsonArray rows, String wiredSource, long walkMillis) {
        JsonObject report = new JsonObject();
        report.addProperty("report_version", 1);
        report.addProperty("generated_at", Instant.now().toString());
        report.addProperty("wired_pages_source", wiredSource);
        report.addProperty("page_count", rows.size());
        report.addProperty("sample_limit", SAMPLE_LIMIT);
        report.addProperty("walk_millis", walkMillis);
        report.add("pages", rows);
        report.addProperty("mods_loaded", modListSize());
        JsonArray missingMods = new JsonArray();
        for (String modId : EXPECTED_TEST_MODS) {
            if (!isModLoaded(modId)) {
                missingMods.add(modId);
            }
        }
        report.add("missing_test_mods", missingMods);
        return report;
    }

    private static int modListSize() {
        ModList modList = ModList.get();
        return modList == null ? -1 : modList.getMods().size();
    }

    private static boolean isModLoaded(String modId) {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(modId);
    }

    private static JsonObject failure(String assertion, String uid, String detail) {
        JsonObject entry = new JsonObject();
        entry.addProperty("assertion", assertion);
        entry.addProperty("uid", uid);
        entry.addProperty("detail", detail);
        return entry;
    }

    private static String className(Object recipe) {
        return recipe == null ? "null" : recipe.getClass().getSimpleName() + " recipe";
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private static final class ModelResult {
        private final EditorModel model;
        private final String route;

        private ModelResult(EditorModel model, String route) {
            this.model = model;
            this.route = route;
        }
    }

    /** One page's findings, with the assertions evaluated while walking. */
    private static final class PageResult {
        private final String uid;
        private final boolean wired;
        private int sampled;
        private int modelCount;
        private int unmodelable;
        private int layoutFailures;
        private int outputChecked;
        private int outputOk;
        private int outputSkipped;
        private int outputRefused;
        private int inputChecked;
        private int inputOk;
        private int inputSkipped;
        /**
         * Input slots the editor refuses: fuel entries whose item is their identity,
         * and empty slots of a page whose write path the editor does not implement.
         */
        private int inputRefused;
        /** Amount-bearing slots (fluid/chemical) whose kind-correct drag round tripped. */
        private int kindSlotsChecked;
        private int kindSlotsOk;
        /** Drags of the wrong kind that the editor refused, as the contract requires. */
        private int foreignKindRefused;
        private final Map<String, Integer> routes = new TreeMap<String, Integer>();
        private final Set<String> serializers = new TreeSet<String>();
        private final Set<String> slotKeyShapes = new TreeSet<String>();
        private final Set<Integer> declaredInputCounts = new TreeSet<Integer>();
        /** Up to {@link #MODELLED_SAMPLE_LIMIT} "id [serializer via route]" samples, for triage. */
        private final List<String> modelledSamples = new ArrayList<String>();
        private final Set<String> slotKeyIssues = new TreeSet<String>();
        private final Set<String> patchIssues = new TreeSet<String>();
        private final Set<String> notes = new TreeSet<String>();
        private final Set<String> errors = new TreeSet<String>();

        private PageResult(String uid, boolean wired) {
            this.uid = uid;
            this.wired = wired;
        }

        private void recordModel(ModelResult result, IRecipeSlotsView slots, Recipe<?> loadedRecipe) {
            modelCount++;
            Integer previous = routes.get(result.route);
            routes.put(result.route, previous == null ? 1 : previous + 1);
            if (modelledSamples.size() < MODELLED_SAMPLE_LIMIT) {
                // The slot contents are part of the sample because they are what
                // RecipeModelSupport.fingerprint hashes: recording them lets a
                // server-side fixture reproduce this exact model's fingerprint
                // without running the client again. The shape is
                // "key=role[:kind:id:amount]".
                StringBuilder detail = new StringBuilder();
                detail.append(result.model.recipeId()).append(" [").append(result.model.serializerId())
                        .append(" via ").append(result.route).append("] {");
                for (EditorSlot slot : result.model.slots()) {
                    detail.append(slot.key()).append('=').append(slot.role());
                    if (slot.ingredient() != null) {
                        detail.append(':').append(slot.ingredient().kind().id())
                                .append(':').append(slot.ingredient().id())
                                .append(':').append(slot.ingredient().amount());
                    }
                    detail.append(';');
                }
                detail.append('}');
                modelledSamples.add(detail.toString());
            }

            List<String> keys = new ArrayList<String>(result.model.slots().size());
            int inputs = 0;
            int outputs = 0;
            for (EditorSlot slot : result.model.slots()) {
                keys.add(slot.key());
                if ("input".equals(slot.role())) {
                    inputs++;
                } else if ("output".equals(slot.role())) {
                    outputs++;
                }
            }
            slotKeyShapes.add(String.join(",", keys));

            if ("declared".equals(result.route)) {
                Optional<ModdedRecipeAdapter> declaration = ModdedRecipeAdapters.forSerializer(
                        result.model.serializerId());
                if (!declaration.isPresent()) {
                    slotKeyIssues.add("a declared model uses serializer " + result.model.serializerId()
                            + ", which no declaration provides");
                } else {
                    checkDeclaredShape(result.model, declaration.get(), inputs, outputs, keys, slots,
                            loadedRecipe);
                }
            }
            checkPatchRoundTrip(result.model, slots);
        }

        /**
         * Assertion B: a declared model must expose exactly the slots its
         * declaration names for this page - {@code input.0 .. input.N-1} plus
         * {@code output} (one output) or {@code output.0 .. output.M-1} (several)
         * - and the declaration must be able to name every one of them.
         *
         * <p>The expected slot keys are re-derived here from the declaration
         * pattern list, independently of {@code RecipeFieldMapping}, so a change
         * to the expansion rule shows up as a failure instead of agreeing with
         * itself. The kinds are checked twice: against the model, and against the
         * ingredient each JEI slot really shows, so a declaration that names the
         * wrong kind for a slot the page fills cannot pass by leaving that slot
         * empty.
         *
         * <p>A declaration that models its whole slot sequence with a
         * recipe-derived sequence has no pattern list to expand, so it is checked
         * by {@link #checkDerivedShape} instead.
         */
        private void checkDeclaredShape(EditorModel model, ModdedRecipeAdapter declaration,
                                        int inputs, int outputs, List<String> keys,
                                        IRecipeSlotsView slots, Recipe<?> loadedRecipe) {
            declaredInputCounts.add(declaration.inputFields().size());
            // A page-scoped declaration is selected by its page uid, and the model has to
            // carry that uid as its serializer: the serializer alone names no direction,
            // so a patch built from this model has to be addressed to the page. A model
            // that carried the bare serializer would reach the server as an ambiguous
            // patch and be refused there, after the UI had already offered the edit.
            if (declaration.pageUid() != null && !declaration.pageUid().equals(model.serializerId())) {
                slotKeyIssues.add("the page-scoped declaration of " + declaration.pageUid()
                        + " produced a model of " + model.recipeId() + " carrying "
                        + model.serializerId() + " as its serializer");
            }
            if (declaration.derivedSlots() != null) {
                checkDerivedShape(model, declaration, inputs, outputs, keys, slots, loadedRecipe);
                return;
            }
            List<String> expectedInputs = expandPatterns(declaration.inputFields(), inputs, false);
            if (expectedInputs == null) {
                slotKeyIssues.add("the declared model of " + model.recipeId() + " shows " + inputs
                        + " input slots, which the declaration " + declaration.inputFields() + " cannot name");
                return;
            }
            boolean concatenated = declaration.concatenatedOutputs();
            List<String> expectedOutputs = null;
            if (concatenated) {
                if (!segmentOutputsFit(declaration.outputFields(), outputs)) {
                    slotKeyIssues.add("the declared model of " + model.recipeId() + " shows " + outputs
                            + " output slots, which the ordered segment list " + declaration.outputFields()
                            + " cannot name");
                    return;
                }
            } else {
                expectedOutputs = expandPatterns(declaration.outputFields(), outputs, true);
                if (expectedOutputs == null) {
                    slotKeyIssues.add("the declared model of " + model.recipeId() + " shows " + outputs
                            + " output slots, which the declaration " + declaration.outputFields() + " cannot name");
                    return;
                }
            }
            List<String> expected = new ArrayList<String>(inputs + outputs);
            for (int index = 0; index < expectedInputs.size(); index++) {
                expected.add("input." + index);
            }
            if (outputs == 1) {
                expected.add("output");
            } else {
                for (int index = 0; index < outputs; index++) {
                    expected.add("output." + index);
                }
            }
            if (!expected.equals(keys)) {
                slotKeyIssues.add("the declared model of " + model.recipeId() + " has slots " + keys
                        + " but the declaration " + declaration.inputFields() + " / "
                        + declaration.outputFields() + " names " + expected);
            }
            for (String key : keys) {
                // An ordered segment list names an output ordinal only with the recipe
                // JSON in hand, which the client does not have; its fit is the count
                // bound checked above, and the server resolves the field while writing.
                if (concatenated && RecipeFieldMapping.outputIndex(key) >= 0) {
                    continue;
                }
                if (RecipeFieldMapping.field(declaration.inputFields(), declaration.outputFields(), key) == null) {
                    slotKeyIssues.add("the declared model of " + model.recipeId() + " exposes " + key
                            + ", which the declaration does not resolve to a recipe JSON field");
                }
            }
            // The declaration's accepted kinds for a slot are resolved twice: the server
            // gate resolves them through RecipeFieldMapping (ModdedRecipeAdapters.declaredKinds)
            // and this test recomputes the field of every slot from its own expansion above.
            // A divergence is a slot the client maps to one field and the server to another,
            // so the client offers an edit the server refuses - the shape an incomplete
            // fluid-field declaration had. Holding the two resolutions together keeps the
            // client's slot model and the server's per-slot gate on the same field.
            for (int index = 0; index < keys.size(); index++) {
                String key = keys.get(index);
                if (concatenated && RecipeFieldMapping.outputIndex(key) >= 0) {
                    continue;
                }
                int outputOrdinal = RecipeFieldMapping.outputIndex(key);
                String independentField;
                if (outputOrdinal >= 0) {
                    if (expectedOutputs == null || outputOrdinal >= expectedOutputs.size()) {
                        continue;
                    }
                    independentField = expectedOutputs.get(outputOrdinal);
                } else {
                    int inputOrdinal = RecipeFieldMapping.inputOrdinal(key);
                    if (inputOrdinal < 0 || inputOrdinal >= expectedInputs.size()) {
                        continue;
                    }
                    independentField = expectedInputs.get(inputOrdinal);
                }
                Set<IngredientKind> independent = new TreeSet<IngredientKind>();
                independent.add(declaration.kindOfField(independentField));
                Set<IngredientKind> server = ModdedRecipeAdapters.declaredKinds(declaration, key);
                if (!server.equals(independent)) {
                    slotKeyIssues.add("the declared model of " + model.recipeId() + " resolves " + key
                            + " to " + independentField + " (" + kindsText(independent) + ") while the server"
                            + " gate accepts " + kindsText(server) + " for it, so any edit offered there"
                            + " would be refused");
                }
            }
            // The model must expose each slot in the kind the declaration names for it:
            // a fluid field that the model read as an item (or that it left empty) is
            // exactly the failure the ingredient-kind work exists to prevent, and it
            // would otherwise only show up as a missing drag target at runtime.
            // A slot of an ordered segment list may hold any of the list's kinds - which
            // segment lands there is the recipe's business - and the page's own ingredient
            // decides which one it is.
            for (EditorSlot slot : model.slots()) {
                Set<IngredientKind> declared = declaredKinds(declaration, slot.key(), "output".equals(slot.role()));
                if (slot.ingredient() == null) {
                    if (!declared.equals(ITEM_ONLY)) {
                        slotKeyIssues.add("the declared model of " + model.recipeId() + " leaves the "
                                + kindsText(declared) + " slot " + slot.key() + " empty, so a drag of that kind"
                                + " has nothing to resolve");
                    }
                    continue;
                }
                if (!declared.contains(slot.ingredient().kind())) {
                    slotKeyIssues.add("the declared model of " + model.recipeId() + " exposes " + slot.key()
                            + " as a " + slot.ingredient().kind().id() + " while the declaration of "
                            + declaration.inputFields() + " / " + declaration.outputFields()
                            + " says " + kindsText(declared));
                }
            }
            // The lists above are checked against the model, and a slot whose declared kind
            // the page does not show is simply modelled empty. That is legitimate for an
            // item slot (an empty item slot is a shape the UI already handles), so it would
            // not show up there - but a slot the page *does* fill with an addressable
            // ingredient whose kind the declaration does not name for it is a declaration
            // describing another page: the slot is drawn, yet every drag at it is refused.
            // Only slots carrying at least one addressable candidate are compared, because a
            // slot with nothing in it has no kind to disagree with.
            int inputOrdinal = 0;
            int outputOrdinal = 0;
            for (IRecipeSlotView slotView : slots.getSlotViews()) {
                boolean input = slotView.getRole() == RecipeIngredientRole.INPUT;
                boolean output = slotView.getRole() == RecipeIngredientRole.OUTPUT;
                if (!input && !output) {
                    continue;
                }
                String key;
                if (input) {
                    key = "input." + inputOrdinal++;
                } else {
                    key = outputs == 1 ? "output" : "output." + outputOrdinal++;
                }
                Set<IngredientKind> shown = new TreeSet<IngredientKind>();
                for (ITypedIngredient<?> candidate : slotView.getAllIngredientsList()) {
                    Optional<EditorIngredient> ingredient = RecipeAdapterSupport.editorIngredient(candidate);
                    if (ingredient.isPresent()) {
                        shown.add(ingredient.get().kind());
                    }
                }
                if (shown.isEmpty()) {
                    continue;
                }
                Set<IngredientKind> declared = declaredKinds(declaration, key, output);
                if (!declared.containsAll(shown)) {
                    Set<IngredientKind> missing = new TreeSet<IngredientKind>(shown);
                    missing.removeAll(declared);
                    slotKeyIssues.add("the declared model of " + model.recipeId() + " shows " + key
                            + " as " + kindsText(missing) + " while the declaration of "
                            + declaration.inputFields() + " / " + declaration.outputFields()
                            + " names " + kindsText(declared) + " for it, so that slot can never be edited");
                }
            }
            checkPreservedBaseField(model);
        }

        /**
         * Assertion B for a nested ingredient field: {@code mekanism:painting} writes its
         * {@code item_input} as a {@code neoforge:difference} in 160 of its 176 shipped
         * files, and the page offers exactly base-minus-subtracted. The write for such a
         * field (declared with {@code withPreservedIngredientBase}) replaces only the
         * node's {@code base} and keeps {@code type} and {@code subtracted}, so the slot
         * the editor offers has to be a member of that difference: that id is what lands
         * in the recipe's {@code base}, and the page then rebuilds base-minus-subtracted
         * from it - an id outside the base, or the subtracted one, would leave an input
         * the page cannot show again.
         *
         * <p>The node is not visible on the page (JEI shows the difference's members), so
         * the recipe JSON is read here and the difference is recomputed from it,
         * independently of the writer. The JSON the client can reach is the same data pack
         * resource the server patches, so a divergence between the two is the failure
         * this check exists for. A recipe whose file cannot be read is noted rather than
         * failed: the shipped shape is pinned by {@code scripts/jei-declared-smoke.ps1},
         * which asserts the written node itself.
         */
        private void checkPreservedBaseField(EditorModel model) {
            if (!"mekanism:painting".equals(model.serializerId())) {
                return;
            }
            EditorSlot input = null;
            for (EditorSlot slot : model.slots()) {
                if ("input".equals(slot.role())) {
                    input = slot;
                    break;
                }
            }
            if (input == null || input.ingredient() == null || !input.ingredient().isItem()) {
                slotKeyIssues.add("the painting model of " + model.recipeId()
                        + " has no modelable item input, so the recipe's difference offers no editable base");
                return;
            }
            JsonObject json;
            try {
                json = recipeJson(model.recipeId());
            } catch (RuntimeException exception) {
                json = null;
            }
            if (json == null) {
                notes.add("could not read the recipe JSON of " + model.recipeId()
                        + ", so its nested item_input could not be checked");
                return;
            }
            JsonElement node = json.get("item_input");
            if (node == null || !node.isJsonObject()) {
                return;
            }
            JsonObject itemInput = node.getAsJsonObject();
            JsonElement base = itemInput.get("base");
            if (base == null || !base.isJsonObject()) {
                return;
            }
            Optional<ModdedRecipeAdapter> declaration = ModdedRecipeAdapters.forSerializer(model.serializerId());
            if (!declaration.isPresent() || !declaration.get().preservesIngredientBase("item_input")) {
                slotKeyIssues.add("the painting recipe " + model.recipeId() + " writes item_input as the"
                        + " nested node " + itemInput + " but its declaration does not preserve that base,"
                        + " so editing the slot would replace the node and drop "
                        + itemInput.get("subtracted"));
            }
            Set<String> available = ingredientIds(base);
            available.removeAll(ingredientIds(itemInput.get("subtracted")));
            if (!available.contains(input.ingredient().id())) {
                slotKeyIssues.add("the painting model of " + model.recipeId() + " models "
                        + input.ingredient().id() + " as its item input, but the recipe's difference offers "
                        + available + "; the base the write stores would not be an item the page can show");
            }
        }

        /** The item ids of one ingredient JSON, empty when it does not parse as one. */
        private static Set<String> ingredientIds(JsonElement element) {
            Set<String> ids = new TreeSet<String>();
            if (element == null) {
                return ids;
            }
            Optional<net.minecraft.world.item.crafting.Ingredient> ingredient;
            try {
                ingredient = net.minecraft.world.item.crafting.Ingredient.CODEC
                        .parse(com.mojang.serialization.JsonOps.INSTANCE, element).result();
            } catch (RuntimeException exception) {
                return ids;
            }
            if (!ingredient.isPresent()) {
                return ids;
            }
            for (ItemStack stack : ingredient.get().getItems()) {
                ids.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
            return ids;
        }

        /**
         * One recipe JSON out of the game's own data packs, or null when it is not there.
         * The integrated server is preferred because the editor patches its resources;
         * the client's own manager is the fallback.
         */
        private static JsonObject recipeJson(String recipeId) {
            ResourceLocation id = ResourceLocation.tryParse(recipeId);
            if (id == null) {
                return null;
            }
            Minecraft minecraft = Minecraft.getInstance();
            net.minecraft.server.MinecraftServer server = minecraft.getSingleplayerServer();
            net.minecraft.server.packs.resources.ResourceManager resources =
                    server == null ? minecraft.getResourceManager() : server.getResourceManager();
            ResourceLocation file = ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                    "recipe/" + id.getPath() + ".json");
            Optional<net.minecraft.server.packs.resources.Resource> resource = resources.getResource(file);
            if (!resource.isPresent()) {
                return null;
            }
            try (java.io.Reader reader = resource.get().openAsReader()) {
                JsonElement element = com.google.gson.JsonParser.parseReader(reader);
                return element.isJsonObject() ? element.getAsJsonObject() : null;
            } catch (java.io.IOException exception) {
                return null;
            }
        }

        /**
         * Assertion B for a declaration whose whole slot sequence is recipe-derived
         * ({@link DerivedSlotSequence}): Create's basin pages, whose slot order comes
         * from {@code BasinCategory.setRecipe} merging item ingredients with equal
         * {@code Ingredient.getItems()} and putting every item slot before every fluid
         * one.
         *
         * <p>The sequence itself is resolved server side, because only there is the
         * recipe JSON at hand, so this checks the two halves the client can decide:
         *
         * <ul>
         *   <li>the model exposes exactly {@code input.0 .. input.N-1} plus the output
         *       keys, and the declaration's own count bounds allow that shape;</li>
         *   <li>the page's slot sequence really is the derived one, re-derived here from
         *       the recipe the mod loaded - not from the production resolver: the inputs
         *       have to be one slot per condensed item ingredient followed by one slot
         *       per fluid ingredient the page shows. A page that drew an unmerged entry,
         *       drew the fluids first, or left a slot the derivation cannot name fails
         *       here, which is the client-side evidence that the server's derivation and
         *       the drawn layout agree;</li>
         *   <li>every slot is modelled in a kind the sequence declares for its role, and
         *       every kind the page really shows is declared - a slot the page fills with
         *       a fluid the declaration does not name could never be edited.</li>
         * </ul>
         */
        private void checkDerivedShape(EditorModel model, ModdedRecipeAdapter declaration,
                                       int inputs, int outputs, List<String> keys,
                                       IRecipeSlotsView slots, Recipe<?> loadedRecipe) {
            DerivedSlotSequence sequence = declaration.derivedSlots();
            List<String> expected = new ArrayList<String>();
            for (int index = 0; index < inputs; index++) {
                expected.add("input." + index);
            }
            if (outputs == 1) {
                expected.add("output");
            } else {
                for (int index = 0; index < outputs; index++) {
                    expected.add("output." + index);
                }
            }
            if (!expected.equals(keys)) {
                slotKeyIssues.add("the derived model of " + model.recipeId() + " has slots " + keys
                        + " but a page of " + inputs + " inputs and " + outputs + " outputs names " + expected);
            }
            if (!sequence.inputCountFits(inputs) || !sequence.outputCountFits(outputs)) {
                slotKeyIssues.add("the derived declaration of " + declaration.serializerId()
                        + " does not allow a page of " + inputs + " inputs and " + outputs + " outputs,"
                        + " which is what " + model.recipeId() + " drew");
            }
            for (EditorSlot slot : model.slots()) {
                boolean output = "output".equals(slot.role());
                Set<IngredientKind> declared = output ? sequence.outputKinds() : sequence.inputKinds();
                if (slot.ingredient() == null) {
                    if (!declared.equals(ITEM_ONLY)) {
                        slotKeyIssues.add("the derived model of " + model.recipeId() + " leaves the "
                                + kindsText(declared) + " slot " + slot.key() + " empty, so a drag of that"
                                + " kind has nothing to resolve");
                    }
                    continue;
                }
                if (!declared.contains(slot.ingredient().kind())) {
                    slotKeyIssues.add("the derived model of " + model.recipeId() + " exposes " + slot.key()
                            + " as a " + slot.ingredient().kind().id() + " while the sequence declares "
                            + kindsText(declared) + " for that role");
                }
            }
            for (IRecipeSlotView slotView : slots.getSlotViews()) {
                if (slotView.getRole() != RecipeIngredientRole.OUTPUT) {
                    continue;
                }
                Set<IngredientKind> shown = shownKinds(slotView);
                if (!shown.isEmpty() && !sequence.outputKinds().containsAll(shown)) {
                    slotKeyIssues.add("the derived model of " + model.recipeId() + " shows an output slot as "
                            + kindsText(shown) + " while the sequence declares "
                            + kindsText(sequence.outputKinds()) + " for its outputs, so that slot can never"
                            + " be edited");
                }
            }
            if (loadedRecipe == null) {
                return;
            }
            int itemSlots = 0;
            int fluidSlots = 0;
            int fieldlessSlots = 0;
            int inputOrdinal = 0;
            Set<Integer> fieldBackedOrdinals = sequence.fieldBackedInputOrdinals(loadedRecipe, inputs);
            boolean fluidBandStarted = false;
            for (IRecipeSlotView slotView : slots.getSlotViews()) {
                if (slotView.getRole() != RecipeIngredientRole.INPUT) {
                    continue;
                }
                int ordinal = inputOrdinal++;
                Set<IngredientKind> shown = shownKinds(slotView);
                if (shown.equals(ITEM_ONLY)) {
                    if (fluidBandStarted && sequence.drawsBandsInDisplayOrder()) {
                        slotKeyIssues.add("the derived model of " + model.recipeId() + " draws the item input."
                                + itemSlots + " after a fluid input, while the derivation declares that"
                                + " every item input comes before every fluid input");
                    }
                    itemSlots++;
                } else if (!shown.isEmpty() && !shown.contains(IngredientKind.ITEM)) {
                    fluidBandStarted = true;
                    fluidSlots++;
                } else if (shown.isEmpty()) {
                    // A slot the page draws with nothing addressable is the derivation's own
                    // "no field here" shape - Create's mechanical crafting draws one slot per
                    // pattern cell and a blank cell holds nothing, the campfire pot draws a
                    // seasoning preview - so such an ordinal must NOT be in the set of
                    // field-backed ordinals. An ordinal in that set but empty on the page is
                    // one the derivation claims to name and does not.
                    fieldlessSlots++;
                    if (fieldBackedOrdinals.contains(Integer.valueOf(ordinal))) {
                        slotKeyIssues.add("the derived model of " + model.recipeId() + " shows input."
                                + ordinal + " with nothing addressable while the derivation declares it as"
                                + " a slot with a field");
                    }
                } else {
                    slotKeyIssues.add("the derived model of " + model.recipeId() + " shows an input slot as "
                            + kindsText(shown)
                            + ", which is neither the item band nor the fluid band of the derivation");
                }
            }
            int condensed = condensedIngredientCount(loadedRecipe.getIngredients());
            if (sequence.condensesItemInputs() && itemSlots != condensed) {
                slotKeyIssues.add("the derived model of " + model.recipeId() + " draws " + itemSlots
                        + " item input slots, but the recipe's own " + loadedRecipe.getIngredients().size()
                        + " item ingredients condense to " + condensed + " (ItemHelper.condenseIngredients"
                        + " merges entries with equal Ingredient.getItems()), so the page and the derivation"
                        + " disagree");
            }
            if (itemSlots + fluidSlots + fieldlessSlots != inputs) {
                slotKeyIssues.add("the derived model of " + model.recipeId() + " draws " + inputs
                        + " input slots but its item band (" + itemSlots + "), fluid band ("
                        + fluidSlots + ") and field-less slots (" + fieldlessSlots + ") hold "
                        + (itemSlots + fluidSlots + fieldlessSlots));
            }
        }

        /** The addressable kinds one JEI slot really shows, empty when it shows none. */
        private static Set<IngredientKind> shownKinds(IRecipeSlotView slotView) {
            Set<IngredientKind> shown = new TreeSet<IngredientKind>();
            for (ITypedIngredient<?> candidate : slotView.getAllIngredientsList()) {
                Optional<EditorIngredient> ingredient = RecipeAdapterSupport.editorIngredient(candidate);
                if (ingredient.isPresent()) {
                    shown.add(ingredient.get().kind());
                }
            }
            return shown;
        }

        /**
         * {@code ItemHelper.condenseIngredients} re-derived here, over the
         * {@code Ingredient} objects the mod itself loaded: an entry joins the first
         * group whose item array is length-equal and element-wise
         * {@code ItemStack.matches}, and otherwise starts one, so the count is the
         * number of distinct item arrays in order. Re-derived rather than called, like
         * every other expectation in this test.
         */
        private static int condensedIngredientCount(
                net.minecraft.core.NonNullList<net.minecraft.world.item.crafting.Ingredient> ingredients) {
            List<ItemStack[]> groups = new ArrayList<ItemStack[]>();
            for (net.minecraft.world.item.crafting.Ingredient ingredient : ingredients) {
                ItemStack[] items = ingredient.getItems();
                boolean merged = false;
                for (ItemStack[] group : groups) {
                    if (sameItemArrays(group, items)) {
                        merged = true;
                        break;
                    }
                }
                if (!merged) {
                    groups.add(items);
                }
            }
            return groups.size();
        }

        /** {@code ItemStack.matches} element-wise over two arrays of equal length. */
        private static boolean sameItemArrays(ItemStack[] first, ItemStack[] second) {
            if (first == null || second == null || first.length != second.length) {
                return false;
            }
            for (int index = 0; index < first.length; index++) {
                if (!ItemStack.matches(first[index], second[index])) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The kinds the declaration accepts for one slot, resolved here exactly as the
         * model builder and the server do. A positional list names one; an ordered
         * segment list names every kind its segments hold, because which segment lands
         * at an output ordinal is only decidable from the recipe JSON.
         */
        private static Set<IngredientKind> declaredKinds(ModdedRecipeAdapter declaration, String slotKey,
                                                         boolean output) {
            if (output && declaration.concatenatedOutputs()) {
                Set<IngredientKind> kinds = new TreeSet<IngredientKind>();
                for (String field : declaration.outputFields()) {
                    kinds.add(declaration.kindOfField(field));
                }
                return kinds;
            }
            return java.util.Collections.singleton(declaration.kindOfField(RecipeFieldMapping.field(
                    declaration.inputFields(), declaration.outputFields(), slotKey)));
        }

        private static String kindsText(Set<IngredientKind> kinds) {
            return kinds.stream().map(IngredientKind::id).collect(Collectors.joining("/"));
        }

        /**
         * The ingredient id the declaration fixes this slot's field to, or null, resolved
         * here as the model builder does. An output ordinal of an ordered segment list has
         * no single field, so it has no fixed id either.
         */
        private static String fixedIdFor(EditorModel model, String slotKey) {
            Optional<ModdedRecipeAdapter> declaration =
                    ModdedRecipeAdapters.forSerializer(model.serializerId());
            if (!declaration.isPresent()) {
                return null;
            }
            ModdedRecipeAdapter adapter = declaration.get();
            if (adapter.concatenatedOutputs() && RecipeFieldMapping.outputIndex(slotKey) >= 0) {
                return null;
            }
            return adapter.fixedIdOfField(RecipeFieldMapping.field(
                    adapter.inputFields(), adapter.outputFields(), slotKey));
        }

        /**
         * Whether an ordered segment list can name a page with {@code count} output
         * slots, re-derived here independently of {@code RecipeFieldMapping}: a
         * repeating segment makes the count unbounded, a list of single paths bounds it
         * at their number, and a page always draws at least one output slot.
         */
        private static boolean segmentOutputsFit(List<String> segments, int count) {
            if (segments == null || segments.isEmpty() || count < 1) {
                return false;
            }
            boolean repeat = false;
            for (String segment : segments) {
                if (segment == null || segment.isEmpty()) {
                    return false;
                }
                if (segment.contains("%d")) {
                    repeat = true;
                }
            }
            return repeat || count <= segments.size();
        }

        /**
         * The declaration's field per slot, for a page with {@code count} slots
         * of that role: the patterns before the first {@code %d} one name one
         * slot each, the {@code %d} one names every remaining slot, and no
         * pattern may follow it. {@code allowUnusedTrailing} additionally allows
         * a page with fewer slots than the list (an optional trailing output);
         * null means the declaration cannot describe this page.
         */
        private static List<String> expandPatterns(List<String> patterns, int count,
                                                   boolean allowUnusedTrailing) {
            if (patterns == null || count < 0) {
                return null;
            }
            int repeatAt = -1;
            for (int index = 0; index < patterns.size(); index++) {
                String pattern = patterns.get(index);
                if (pattern == null || pattern.isEmpty()) {
                    return null;
                }
                if (repeatAt < 0 && pattern.contains("%d")) {
                    repeatAt = index;
                }
            }
            if (repeatAt >= 0 && repeatAt != patterns.size() - 1) {
                return null;
            }
            if (repeatAt < 0) {
                if (count == patterns.size()) {
                    return patterns;
                }
                return allowUnusedTrailing && count >= 1 && count < patterns.size()
                        ? patterns.subList(0, count)
                        : null;
            }
            if (count < (allowUnusedTrailing ? Math.max(1, repeatAt) : repeatAt)) {
                return null;
            }
            List<String> expanded = new ArrayList<String>(count);
            for (int index = 0; index < repeatAt; index++) {
                expanded.add(patterns.get(index));
            }
            for (int index = repeatAt; index < count; index++) {
                expanded.add(patterns.get(repeatAt).replace("%d", Integer.toString(index - repeatAt)));
            }
            return expanded;
        }

        /**
         * The client half of the write path, per slot.
         *
         * <p>Every input slot and every output slot the editor offers must be
         * writable and the patch must carry the new ingredient under that slot's own
         * key - {@code output.item} on an item slot, {@code output.fluid} or
         * {@code output.chemical} on a fluid/chemical slot, and the numbered
         * {@code output.n.…} form on a page with several outputs. A slot that holds
         * no ingredient is not silently skipped: a declared page must refuse it (its
         * declared kind is what makes a slot writable), while the vanilla and grid
         * paths must still fill it, which is what the crafting grid's blank cells
         * are for. A model with no {@code input.0} at all - a fuel entry - keeps its
         * own contract below.
         */
        private void checkPatchRoundTrip(EditorModel model, IRecipeSlotsView slots) {
            boolean fuel = FuelRecipeEditorAdapter.SERIALIZER.equals(model.serializerId());
            boolean hasFirstInput = false;
            boolean hasOutput = false;
            Map<String, IRecipeSlotView> views = slotViews(slots);
            for (EditorSlot slot : model.slots()) {
                if ("output".equals(slot.role())) {
                    hasOutput = true;
                    checkOutputSlot(model, slot, views.get(slot.key()));
                } else if ("input".equals(slot.role())) {
                    hasFirstInput |= "input.0".equals(slot.key());
                    // A fuel entry is checked by its own branch below: its item is its
                    // identity, so an item swap is not an operation the editor has.
                    if (!fuel) {
                        checkInputSlot(model, slot, views.get(slot.key()));
                    }
                }
            }
            if (!hasOutput) {
                outputSkipped++;
            }
            if (!hasFirstInput) {
                inputSkipped++;
            }
            if (!fuel) {
                return;
            }
            // A fuel entry is identified by its item (its generated recipe id encodes
            // it), so swapping the item is not an operation this editor implements:
            // the UI does not offer the drag and the server refuses such a patch. The
            // contract is therefore a refusal, like an empty output slot.
            inputRefused++;
            try {
                RecipeEditorAdapters.replaceSlot(model, "input.0", new ItemStack(Items.DIRT));
                patchIssues.add("the fuel input of " + model.recipeId()
                        + " accepted an item replacement instead of refusing it");
            } catch (IllegalArgumentException expected) {
                // Refused, as intended.
            } catch (Throwable throwable) {
                patchIssues.add("replacing the fuel input of " + model.recipeId() + " threw "
                        + describe(throwable) + " instead of an IllegalArgumentException");
            }
            // The operation a fuel entry does support must keep working, so the refusal
            // above cannot be satisfied by breaking the adapter.
            try {
                RecipePatch patch = RecipeEditorAdapters.setFuelBurnTime(model, 200);
                if (!"200".equals(patch.fields().get("fuel.burn_time"))) {
                    patchIssues.add("setting the burn time of " + model.recipeId()
                            + " returned fuel.burn_time=" + patch.fields().get("fuel.burn_time"));
                }
            } catch (Throwable throwable) {
                patchIssues.add("setting the burn time of " + model.recipeId() + " threw "
                        + describe(throwable));
            }
        }

        /**
         * One input slot. A slot with an ingredient is exercised in its own kind. A
         * slot without one is not skipped: a declared page only writes the slots
         * whose declared kind the model resolved, so it must refuse; the crafting
         * grid's blank cells must still be fillable, which is what they are for; and
         * every other page must at least answer cleanly - either with a patch that
         * carries that slot's own key, or with an {@code IllegalArgumentException}
         * the UI can show.
         */
        private void checkInputSlot(EditorModel model, EditorSlot slot, IRecipeSlotView view) {
            inputChecked++;
            if (slot.ingredient() == null) {
                if (ModdedRecipeAdapters.supports(model.serializerId())) {
                    expectRefusal(model, slot.key(), new ItemStack(Items.STONE),
                            "the unresolved declared input " + slot.key() + " of " + model.recipeId());
                } else if (CraftingRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
                    fillEmptySlot(model, slot.key());
                } else {
                    emptySlotOutcome(model, slot.key());
                }
                return;
            }
            if (checkIngredientDrag(model, slot, slot.key(), view)) {
                inputOk++;
            }
        }

        /** An empty crafting-grid cell must accept the item drag and keep its own key. */
        private void fillEmptySlot(EditorModel model, String key) {
            try {
                RecipePatch patch = RecipeEditorAdapters.replaceSlot(model, key, new ItemStack(Items.STONE));
                if (!STONE_ITEM.equals(patch.fields().get(key + ".item"))) {
                    patchIssues.add("filling the empty grid cell " + key + " of " + model.recipeId()
                            + " returned " + key + ".item=" + patch.fields().get(key + ".item"));
                    return;
                }
            } catch (Throwable throwable) {
                patchIssues.add("filling the empty grid cell " + key + " of " + model.recipeId()
                        + " threw " + describe(throwable));
                return;
            }
            inputOk++;
            expectRefusal(model, key, new EditorIngredient(IngredientKind.FLUID, "minecraft:water", 1000),
                    "the empty grid cell " + key + " of " + model.recipeId() + " accepted a fluid");
        }

        /**
         * An empty slot of a page whose editor does not implement that serializer:
         * a patch carrying the slot's own key and a clean refusal are both outcomes
         * the UI can live with, so both are accepted; anything else is reported.
         */
        private void emptySlotOutcome(EditorModel model, String key) {
            try {
                RecipePatch patch = RecipeEditorAdapters.replaceSlot(model, key, new ItemStack(Items.STONE));
                if (!STONE_ITEM.equals(patch.fields().get(key + ".item"))) {
                    patchIssues.add("filling the empty input " + key + " of " + model.recipeId()
                            + " returned " + key + ".item=" + patch.fields().get(key + ".item")
                            + " (the editor offers that slot to an item drag, so it must either fill it or"
                            + " refuse it)");
                    return;
                }
                inputOk++;
            } catch (IllegalArgumentException expected) {
                // A clean refusal: the editor offers the slot but the server-side shape
                // of this page is not implemented, and the drop reports why.
                inputRefused++;
            } catch (Throwable throwable) {
                patchIssues.add("filling the empty input " + key + " of " + model.recipeId() + " threw "
                        + describe(throwable) + " instead of an IllegalArgumentException");
            }
        }

        private void checkOutputSlot(EditorModel model, EditorSlot outputSlot, IRecipeSlotView view) {
            String key = outputSlot.key();
            if (outputSlot.ingredient() == null) {
                outputRefused++;
                expectRefusal(model, key, new ItemStack(Items.STONE),
                        "the empty output slot " + key + " of " + model.recipeId());
                return;
            }
            outputChecked++;
            if (checkIngredientDrag(model, outputSlot, key, view)) {
                outputOk++;
            }
        }

        /**
         * The JEI slot view of every INPUT and OUTPUT slot of the page, keyed exactly
         * like the model ({@code input.N}, {@code output} or {@code output.N}), so a
         * slot check can look at the ingredients the page itself offers there.
         */
        private static Map<String, IRecipeSlotView> slotViews(IRecipeSlotsView slots) {
            List<IRecipeSlotView> inputs = new ArrayList<IRecipeSlotView>();
            List<IRecipeSlotView> outputs = new ArrayList<IRecipeSlotView>();
            for (IRecipeSlotView view : slots.getSlotViews()) {
                if (view.getRole() == RecipeIngredientRole.INPUT) {
                    inputs.add(view);
                } else if (view.getRole() == RecipeIngredientRole.OUTPUT) {
                    outputs.add(view);
                }
            }
            Map<String, IRecipeSlotView> byKey = new TreeMap<String, IRecipeSlotView>();
            for (int index = 0; index < inputs.size(); index++) {
                byKey.put("input." + index, inputs.get(index));
            }
            if (outputs.size() == 1) {
                byKey.put("output", outputs.get(0));
            } else {
                for (int index = 0; index < outputs.size(); index++) {
                    byKey.put("output." + index, outputs.get(index));
                }
            }
            return byKey;
        }

        /**
         * The page's own JEI ingredient for what the model holds, or empty when the
         * slot does not offer it. This is the drag source the editor really receives:
         * the objects the page's slots carry, which the drop path
         * ({@code RecipeAdapterSupport.editorIngredient}) resolves like any other JEI
         * ingredient.
         *
         * <p>It has to be consulted before the id lookup, because a page can offer an
         * ingredient that is not in JEI's own ingredient panel: JEI registers 64 fluid
         * ingredients in this pack, while a NeoForge fluid tag can hold any registered
         * fluid - Create's {@code compacting/honey} is the {@code c:honey} tag, whose
         * first member is {@code create:flowing_honey}, and the editor renders a fluid
         * by its registry entry ({@code ClientEditorState}) rather than by JEI's list.
         * The slot is still fully editable: the page offers {@code create:honey} too,
         * and a drag of it is what gets written.
         */
        private static Optional<ITypedIngredient<?>> offeredIngredient(IRecipeSlotView view,
                                                                       EditorIngredient ingredient) {
            if (view == null) {
                return Optional.empty();
            }
            for (ITypedIngredient<?> candidate : view.getAllIngredientsList()) {
                Optional<EditorIngredient> resolved = RecipeAdapterSupport.editorIngredient(candidate);
                if (resolved.isPresent() && resolved.get().kind() == ingredient.kind()
                        && resolved.get().id().equals(ingredient.id())) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }

        /**
         * One slot's write path, in the slot's own kind.
         *
         * <p>An item slot must accept an item and must refuse a fluid. A fluid or
         * chemical slot is the mirror image: it must refuse an item, and the drag the
         * page does support - the slot's own ingredient when the page offers it, or the
         * JEI ingredient of that id otherwise - must resolve through the same code the
         * ghost handler uses ({@code RecipeAdapterSupport.editorIngredient}) and produce
         * a patch carrying that slot's own {@code .fluid}/{@code .chemical} id and
         * amount field.
         */
        private boolean checkIngredientDrag(EditorModel model, EditorSlot slot, String key,
                                            IRecipeSlotView view) {
            EditorIngredient original = slot.ingredient();
            if (original.isItem()) {
                try {
                    RecipePatch patch = RecipeEditorAdapters.replaceSlot(model, key, new ItemStack(Items.STONE));
                    if (!STONE_ITEM.equals(patch.fields().get(key + ".item"))) {
                        patchIssues.add("replacing the " + key + " of " + model.recipeId() + " returned "
                                + key + ".item=" + patch.fields().get(key + ".item"));
                        return false;
                    }
                } catch (Throwable throwable) {
                    patchIssues.add("replacing the " + key + " of " + model.recipeId() + " threw "
                            + describe(throwable));
                    return false;
                }
                return expectRefusal(model, key,
                        new EditorIngredient(IngredientKind.FLUID, "minecraft:water", 1000),
                        "the item slot " + key + " of " + model.recipeId() + " accepted a fluid");
            }
            if (!expectRefusal(model, key, new EditorIngredient(IngredientKind.ITEM, DIRT_ITEM, 1),
                    "the " + original.kind().id() + " slot " + key + " of " + model.recipeId()
                            + " accepted an item")) {
                return false;
            }
            Optional<ITypedIngredient<?>> typed = offeredIngredient(view, original);
            if (!typed.isPresent()) {
                typed = typedIngredient(original);
            }
            if (!typed.isPresent()) {
                patchIssues.add("the page offers no " + original.kind().id() + " ingredient with id "
                        + original.id() + " and JEI registers none either, while the model's " + key
                        + " of " + model.recipeId() + " holds it; a drag of it could not be resolved");
                return false;
            }
            Optional<EditorIngredient> dragged = RecipeAdapterSupport.editorIngredient(typed.get());
            if (!dragged.isPresent()) {
                patchIssues.add("the registered " + original.kind().id() + " ingredient "
                        + original.id() + " is not addressable by the editor (the drag of "
                        + key + " of " + model.recipeId() + " would be refused)");
                return false;
            }
            if (dragged.get().kind() != original.kind() || !dragged.get().id().equals(original.id())) {
                patchIssues.add("the registered ingredient of " + original.id() + " resolved to "
                        + dragged.get().kind().id() + " " + dragged.get().id()
                        + " instead of " + original.kind().id() + " " + original.id());
                return false;
            }
            kindSlotsChecked++;
            EditorIngredient ingredient = dragged.get();
            try {
                RecipePatch patch = RecipeEditorAdapters.replaceSlot(model, key, ingredient);
                String idField = key + "." + ingredient.kind().idField();
                String amountField = key + "." + ingredient.kind().amountField();
                String id = patch.fields().get(idField);
                String amount = patch.fields().get(amountField);
                if (!ingredient.id().equals(id) || !Integer.toString(ingredient.amount()).equals(amount)) {
                    patchIssues.add("replacing the " + ingredient.kind().id() + " slot " + key + " of "
                            + model.recipeId() + " returned " + idField + "=" + id + ", "
                            + amountField + "=" + amount + " instead of " + ingredient.id() + " / "
                            + ingredient.amount());
                    return false;
                }
            } catch (Throwable throwable) {
                patchIssues.add("replacing the " + ingredient.kind().id() + " slot " + key + " of "
                        + model.recipeId() + " threw " + describe(throwable));
                return false;
            }
            kindSlotsOk++;
            // A field whose JSON value cannot carry an ingredient id (IE's coke oven writes
            // its by-product as the bare amount "creosote": 250) is fixed to one ingredient
            // by the declaration, because the recipe reads the amount back as whatever the
            // field name implies. A drag of another ingredient of the same kind must
            // therefore be refused - accepting it would write an amount under a different
            // fluid's name - and that refusal is what this asserts.
            String fixedId = fixedIdFor(model, key);
            if (fixedId != null) {
                String otherId = ingredient.kind() == IngredientKind.ITEM ? STONE_ITEM : "minecraft:water";
                if (!fixedId.equals(otherId)
                        && !expectRefusal(model, key,
                                new EditorIngredient(ingredient.kind(), otherId, ingredient.amount()),
                                "the id-fixed " + ingredient.kind().id() + " slot " + key + " of "
                                        + model.recipeId() + " accepted " + otherId)) {
                    return false;
                }
            }
            // An amount-only edit keeps the id and writes the kind's amount field; that
            // is the operation the output scroll performs, so it is only checked on a
            // slot the scroll can address.
            if (RecipeFieldMapping.outputIndex(key) < 0) {
                return true;
            }
            try {
                RecipePatch patch = RecipeEditorAdapters.setOutputCount(model, key, ingredient.amount());
                String amountField = key + "." + ingredient.kind().amountField();
                if (!Integer.toString(ingredient.amount()).equals(patch.fields().get(amountField))) {
                    patchIssues.add("resizing the " + ingredient.kind().id() + " slot " + key + " of "
                            + model.recipeId() + " returned " + amountField + "="
                            + patch.fields().get(amountField));
                    return false;
                }
            } catch (Throwable throwable) {
                patchIssues.add("resizing the " + ingredient.kind().id() + " slot " + key + " of "
                        + model.recipeId() + " threw " + describe(throwable));
                return false;
            }
            return true;
        }

        /** The registered JEI ingredient the model's ingredient stands for. */
        private static Optional<ITypedIngredient<?>> typedIngredient(EditorIngredient ingredient) {
            String typeUid = ingredient.kind() == IngredientKind.FLUID
                    ? RecipeAdapterSupport.FLUID_TYPE_UID
                    : ingredient.kind() == IngredientKind.CHEMICAL
                            ? RecipeAdapterSupport.CHEMICAL_TYPE_UID
                            : null;
            return typeUid == null
                    ? Optional.<ITypedIngredient<?>>empty()
                    : RecipeAdapterSupport.typedIngredient(typeUid, ingredient.id());
        }

        /**
         * The editor must refuse that drag with an IllegalArgumentException rather
         * than building a patch the server would reject. A successful patch, or any
         * other throwable, is reported.
         */
        private boolean expectRefusal(EditorModel model, String key, ItemStack stack, String what) {
            try {
                RecipePatch patch = RecipeEditorAdapters.replaceSlot(model, key, stack);
                patchIssues.add(what + " instead of refusing it (fields " + patch.fields() + ")");
                return false;
            } catch (IllegalArgumentException expected) {
                foreignKindRefused++;
                return true;
            } catch (Throwable throwable) {
                patchIssues.add(what + " threw " + describe(throwable)
                        + " instead of an IllegalArgumentException");
                return false;
            }
        }

        private boolean expectRefusal(EditorModel model, String key, EditorIngredient ingredient, String what) {
            try {
                RecipePatch patch = RecipeEditorAdapters.replaceSlot(model, key, ingredient);
                patchIssues.add(what + " instead of refusing it (fields " + patch.fields() + ")");
                return false;
            } catch (IllegalArgumentException expected) {
                foreignKindRefused++;
                return true;
            } catch (Throwable throwable) {
                patchIssues.add(what + " threw " + describe(throwable)
                        + " instead of an IllegalArgumentException");
                return false;
            }
        }

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("uid", uid);
            json.addProperty("wired", wired);
            json.addProperty("recipes_sampled", sampled);
            json.addProperty("models", modelCount);
            json.addProperty("unmodelable", unmodelable);
            json.addProperty("layout_failures", layoutFailures);
            JsonObject routeJson = new JsonObject();
            for (Map.Entry<String, Integer> entry : routes.entrySet()) {
                routeJson.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("routes", routeJson);
            json.add("serializers", toJsonArray(serializers));
            json.add("slot_key_shapes", toJsonArray(slotKeyShapes));
            JsonArray counts = new JsonArray();
            for (Integer count : declaredInputCounts) {
                counts.add(count);
            }
            json.add("declared_input_counts", counts);
            JsonObject roundTrip = new JsonObject();
            roundTrip.addProperty("output_checked", outputChecked);
            roundTrip.addProperty("output_ok", outputOk);
            roundTrip.addProperty("output_skipped_no_slot", outputSkipped);
            // Models whose output slot exists but holds no ingredient: the
            // editor is expected to refuse those, so they are not part of the
            // "must round trip" tally but have a column of their own.
            roundTrip.addProperty("output_refused_empty_slot", outputRefused);
            roundTrip.addProperty("input_checked", inputChecked);
            roundTrip.addProperty("input_ok", inputOk);
            roundTrip.addProperty("input_skipped_no_slot", inputSkipped);
            // Fuel entries hold exactly one input slot (the item), so "no input slot"
            // never applies to them; their refusal is counted on its own.
            roundTrip.addProperty("input_refused_fuel", inputRefused);
            // Fluid/chemical slots exercised through the editor's own drag path: the
            // ingredient came from JEI's registry and the patch carried that slot's
            // .fluid/.chemical id field and fluid_amount/chemical_amount field.
            roundTrip.addProperty("kind_slots_checked", kindSlotsChecked);
            roundTrip.addProperty("kind_slots_ok", kindSlotsOk);
            roundTrip.addProperty("foreign_kind_refused", foreignKindRefused);
            json.add("patch_roundtrip", roundTrip);
            json.add("modelled_samples", toJsonArray(modelledSamples));
            json.add("slot_key_issues", toJsonArray(slotKeyIssues));
            json.add("patch_issues", toJsonArray(patchIssues));
            json.add("notes", toJsonArray(notes));
            json.add("errors", toJsonArray(errors));
            return json;
        }

        private static JsonArray toJsonArray(Iterable<String> values) {
            JsonArray array = new JsonArray();
            for (String value : values) {
                array.add(value);
            }
            return array;
        }
    }
}
