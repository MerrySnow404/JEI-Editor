package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One declared mod recipe type. Instances are built by the per-mod declaration
 * holders ({@code CreateRecipeDeclarations}, {@code MekanismRecipeDeclarations},
 * {@code ImmersiveEngineeringRecipeDeclarations}, {@code MiscRecipeDeclarations},
 * {@code SilentGearRecipeDeclarations})
 * and aggregated by {@link ModdedRecipeAdapters}.
 *
 * <p>Fields are recipe JSON paths in JEI display order. A plain name addresses an
 * object key; numeric segments index an array, so {@code "ingredients.0"} targets
 * the first entry of the {@code ingredients} array and {@code "results.0"} the
 * first result. A field containing {@code %d} is repeatable - it names every
 * remaining slot with {@code %d} replaced by the ordinal relative to it - so a
 * page whose slot count varies per recipe can be declared too.
 *
 * <p>Fields hold items unless {@link #withFieldKind} says otherwise. The kind is
 * declared per field pattern, so a page that mixes an item input with a fluid
 * input is expressible: the pattern, not the ordinal, carries the kind, and a
 * pattern containing {@code %d} carries it for every ordinal it names.
 *
 * <p>{@link #withConcatenatedOutputs()} reads the output list as an ordered
 * segment list instead: the segments are concatenated against the recipe JSON to
 * form the effective output sequence (see
 * {@link cc.sighs.JEIEditor.recipe.RecipeFieldMapping#expandSegments}). It is
 * the form for a page whose ordinal-to-field mapping depends on the recipe,
 * which a positional list cannot express. The client cannot resolve that
 * concatenation either, so it accepts any kind the list's segments declare for an
 * output slot - the ingredient the page really shows decides which one it is -
 * and the server, which does have the JSON, checks the patch's kind against the
 * kind of the field the concatenation resolved to. A list whose segments hold
 * different kinds is therefore the form for a page with an optional item output
 * followed by an optional chemical one.
 *
 * <p>A declaration is keyed by its serializer unless {@link #withPageUid} scopes
 * it to one JEI page, and a field is written in the declaration's own style unless
 * {@link #withFixedFieldIds} pins its ingredient id because the value cannot carry
 * one.
 */
final class ModdedRecipeDeclaration implements ModdedRecipeAdapter {
    private final String serializerId;
    private final String pageUid;
    private final List<String> inputFields;
    private final List<String> outputFields;
    private final Set<String> readOnlyPageUids;
    private final Map<String, IngredientKind> fieldKinds;
    private final Map<String, String> fixedFieldIds;
    private final ItemJsonStyle inputStyle;
    private final ItemJsonStyle outputStyle;
    private final ModdedJsonStyle inputFluidStyle;
    private final ModdedJsonStyle outputFluidStyle;
    private final ModdedJsonStyle inputChemicalStyle;
    private final ModdedJsonStyle outputChemicalStyle;
    private final boolean concatenatedOutputs;
    private final DerivedSlotSequence derivedSlots;
    private final Set<String> preservedBaseFields;

    /** One guaranteed output field, the shape every single-output page used before. */
    ModdedRecipeDeclaration(String serializerId, List<String> inputFields, String outputField,
                            ItemJsonStyle inputStyle, ItemJsonStyle outputStyle) {
        this(serializerId, null, inputFields, Collections.singletonList(outputField),
                Collections.<String>emptySet(), Collections.<String, IngredientKind>emptyMap(),
                Collections.<String, String>emptyMap(), inputStyle, outputStyle,
                ModdedJsonStyle.FLUID, ModdedJsonStyle.FLUID_ID,
                ModdedJsonStyle.CHEMICAL, ModdedJsonStyle.CHEMICAL_ID, false, null,
                Collections.<String>emptySet());
    }

    ModdedRecipeDeclaration(String serializerId, List<String> inputFields, List<String> outputFields,
                            ItemJsonStyle inputStyle, ItemJsonStyle outputStyle) {
        this(serializerId, null, inputFields, outputFields, Collections.<String>emptySet(),
                Collections.<String, IngredientKind>emptyMap(), Collections.<String, String>emptyMap(),
                inputStyle, outputStyle, ModdedJsonStyle.FLUID, ModdedJsonStyle.FLUID_ID,
                ModdedJsonStyle.CHEMICAL, ModdedJsonStyle.CHEMICAL_ID, false, null,
                Collections.<String>emptySet());
    }

    ModdedRecipeDeclaration(String serializerId, List<String> inputFields, List<String> outputFields,
                            Set<String> readOnlyPageUids, ItemJsonStyle inputStyle, ItemJsonStyle outputStyle) {
        this(serializerId, null, inputFields, outputFields, readOnlyPageUids,
                Collections.<String, IngredientKind>emptyMap(), Collections.<String, String>emptyMap(),
                inputStyle, outputStyle, ModdedJsonStyle.FLUID, ModdedJsonStyle.FLUID_ID,
                ModdedJsonStyle.CHEMICAL, ModdedJsonStyle.CHEMICAL_ID, false, null,
                Collections.<String>emptySet());
    }

    /**
     * A declaration whose whole slot sequence the mod derives from the recipe, so
     * that neither field list names a slot and only the sequence's own count bound
     * and kind bands describe the page (see {@link DerivedSlotSequence}).
     *
     * @param fluidInputStyle the shape of one fluid ingredient; Create's
     *                        {@code SizedFluidIngredient} codec requires the explicit
     *                        {@code type} that {@link ModdedJsonStyle#TYPED_FLUID} writes
     */
    ModdedRecipeDeclaration(String serializerId, DerivedSlotSequence sequence,
                            ItemJsonStyle inputStyle, ItemJsonStyle outputStyle,
                            ModdedJsonStyle fluidInputStyle, ModdedJsonStyle fluidOutputStyle) {
        this(serializerId, null, Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptySet(),
                Collections.<String, IngredientKind>emptyMap(), Collections.<String, String>emptyMap(),
                inputStyle, outputStyle, fluidInputStyle, fluidOutputStyle,
                ModdedJsonStyle.CHEMICAL, ModdedJsonStyle.CHEMICAL_ID, false, requireSequence(sequence),
                Collections.<String>emptySet());
    }

    private static DerivedSlotSequence requireSequence(DerivedSlotSequence sequence) {
        if (sequence == null) {
            throw new IllegalArgumentException("a derived declaration needs a slot sequence");
        }
        return sequence;
    }

    private ModdedRecipeDeclaration(String serializerId, String pageUid, List<String> inputFields,
                                    List<String> outputFields, Set<String> readOnlyPageUids,
                                    Map<String, IngredientKind> fieldKinds, Map<String, String> fixedFieldIds,
                                    ItemJsonStyle inputStyle, ItemJsonStyle outputStyle,
                                    ModdedJsonStyle inputFluidStyle, ModdedJsonStyle outputFluidStyle,
                                    ModdedJsonStyle inputChemicalStyle, ModdedJsonStyle outputChemicalStyle,
                                    boolean concatenatedOutputs, DerivedSlotSequence derivedSlots,
                                    Set<String> preservedBaseFields) {
        this.serializerId = serializerId;
        this.pageUid = pageUid;
        this.inputFields = Collections.unmodifiableList(new ArrayList<String>(inputFields));
        this.outputFields = Collections.unmodifiableList(new ArrayList<String>(outputFields));
        this.readOnlyPageUids = Collections.unmodifiableSet(new LinkedHashSet<String>(readOnlyPageUids));
        this.fieldKinds = Collections.unmodifiableMap(
                new LinkedHashMap<String, IngredientKind>(fieldKinds));
        this.fixedFieldIds = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(fixedFieldIds));
        this.inputStyle = inputStyle;
        this.outputStyle = outputStyle;
        this.inputFluidStyle = inputFluidStyle;
        this.outputFluidStyle = outputFluidStyle;
        this.inputChemicalStyle = inputChemicalStyle;
        this.outputChemicalStyle = outputChemicalStyle;
        this.concatenatedOutputs = concatenatedOutputs;
        this.derivedSlots = derivedSlots;
        this.preservedBaseFields = Collections.unmodifiableSet(
                new LinkedHashSet<String>(preservedBaseFields));
    }

    /** The same declaration, plus the pages of the serializer that stay read-only. */
    ModdedRecipeDeclaration withReadOnlyPages(String... pageUids) {
        return new ModdedRecipeDeclaration(serializerId, pageUid, inputFields, outputFields,
                new LinkedHashSet<String>(Arrays.asList(pageUids)), fieldKinds, fixedFieldIds,
                inputStyle, outputStyle,
                inputFluidStyle, outputFluidStyle, inputChemicalStyle, outputChemicalStyle,
                concatenatedOutputs, derivedSlots, preservedBaseFields);
    }

    /**
     * The same declaration, scoped to one JEI page instead of its whole serializer.
     *
     * <p>Only a mod that puts one serializer's recipes on two pages with different
     * slot roles needs this: Mekanism's {@code mekansm:rotary} is drawn by
     * {@code mekanism:condensentrating} as a chemical input plus a fluid output and
     * by {@code mekanism:decondensentrating} as the reverse, and both pages read the
     * same recipe JSON. A page-scoped declaration replaces the serializer-keyed one
     * completely for that uid - the page's slots, their kinds and the fields they
     * are written to are this declaration's - and the serializer it names is then no
     * longer declared on its own, so a patch that names only the serializer is
     * refused rather than being written in one arbitrary direction.
     */
    ModdedRecipeDeclaration withPageUid(String uid) {
        if (uid == null || uid.isEmpty()) {
            throw new IllegalArgumentException("a page-scoped declaration needs a page uid");
        }
        return new ModdedRecipeDeclaration(serializerId, uid, inputFields, outputFields, readOnlyPageUids,
                fieldKinds, fixedFieldIds, inputStyle, outputStyle, inputFluidStyle, outputFluidStyle,
                inputChemicalStyle, outputChemicalStyle, concatenatedOutputs, derivedSlots,
                preservedBaseFields);
    }

    /**
     * The same declaration, with its output list read as an ordered segment list
     * concatenated against the recipe JSON instead of a positional list.
     *
     * <p>The segments may hold different ingredient kinds - that is the form for a
     * page with an optional item output and an optional chemical one, each drawn only
     * when its field is present. The client cannot resolve the concatenation (it has
     * no JSON), so it names each output slot after the ingredient the page shows
     * there, and the server checks the patch kind against the kind of the field the
     * concatenation really resolves to.
     */
    ModdedRecipeDeclaration withConcatenatedOutputs() {
        if (outputFields.isEmpty()) {
            throw new IllegalArgumentException("a concatenated output list needs at least one segment: "
                    + serializerId);
        }
        return new ModdedRecipeDeclaration(serializerId, pageUid, inputFields, outputFields, readOnlyPageUids,
                fieldKinds, fixedFieldIds, inputStyle, outputStyle, inputFluidStyle, outputFluidStyle,
                inputChemicalStyle, outputChemicalStyle, true, derivedSlots, preservedBaseFields);
    }

    /** The same declaration, with {@code pattern} holding {@code kind}. */
    ModdedRecipeDeclaration withFieldKind(String pattern, IngredientKind kind) {
        if (pattern == null || pattern.isEmpty() || kind == null) {
            throw new IllegalArgumentException("a declared field kind needs a pattern and a kind");
        }
        if (!inputFields.contains(pattern) && !outputFields.contains(pattern)) {
            throw new IllegalArgumentException(
                    "the declaration of " + serializerId + " names no field " + pattern);
        }
        LinkedHashMap<String, IngredientKind> kinds = new LinkedHashMap<String, IngredientKind>(fieldKinds);
        kinds.put(pattern, kind);
        return new ModdedRecipeDeclaration(serializerId, pageUid, inputFields, outputFields, readOnlyPageUids,
                kinds, fixedFieldIds, inputStyle, outputStyle, inputFluidStyle, outputFluidStyle,
                inputChemicalStyle, outputChemicalStyle, concatenatedOutputs, derivedSlots,
                preservedBaseFields);
    }

    /**
     * The same declaration, with the named item fields written as nested ingredient
     * nodes whose editable base is rewritten in place
     * ({@link #preservesIngredientBase(String)}).
     */
    ModdedRecipeDeclaration withPreservedIngredientBase(String... patterns) {
        LinkedHashSet<String> fields = new LinkedHashSet<String>(preservedBaseFields);
        for (String pattern : patterns) {
            if (pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("a preserved ingredient base needs a field pattern");
            }
            if (!inputFields.contains(pattern) && !outputFields.contains(pattern)) {
                throw new IllegalArgumentException(
                        "the declaration of " + serializerId + " names no field " + pattern);
            }
            fields.add(pattern);
        }
        return new ModdedRecipeDeclaration(serializerId, pageUid, inputFields, outputFields, readOnlyPageUids,
                fieldKinds, fixedFieldIds, inputStyle, outputStyle, inputFluidStyle, outputFluidStyle,
                inputChemicalStyle, outputChemicalStyle, concatenatedOutputs, derivedSlots, fields);
    }

    /**
     * The same declaration, with {@code pattern} fixed to the ingredient {@code id}.
     *
     * <p>For a field whose JSON value cannot carry an id - Immersive Engineering's
     * {@code "creosote": 250}, a bare amount - the field name itself names the fluid,
     * so the id has to be declared and both sides refuse any other. Also the module
     * that reads a field, so a declaration can state what a page actually shows.
     */
    ModdedRecipeDeclaration withFixedFieldIds(String pattern, String id) {
        if (pattern == null || pattern.isEmpty() || id == null || id.isEmpty()) {
            throw new IllegalArgumentException("a fixed field id needs a pattern and an id");
        }
        if (!inputFields.contains(pattern) && !outputFields.contains(pattern)) {
            throw new IllegalArgumentException(
                    "the declaration of " + serializerId + " names no field " + pattern);
        }
        LinkedHashMap<String, String> ids = new LinkedHashMap<String, String>(fixedFieldIds);
        ids.put(pattern, id);
        return new ModdedRecipeDeclaration(serializerId, pageUid, inputFields, outputFields, readOnlyPageUids,
                fieldKinds, ids, inputStyle, outputStyle, inputFluidStyle, outputFluidStyle,
                inputChemicalStyle, outputChemicalStyle, concatenatedOutputs, derivedSlots,
                preservedBaseFields);
    }

    /** The same declaration, with {@code patterns} holding a fluid. */
    ModdedRecipeDeclaration withFluidFields(String... patterns) {
        return withKinds(IngredientKind.FLUID, patterns);
    }

    /** The same declaration, with {@code patterns} holding a chemical. */
    ModdedRecipeDeclaration withChemicalFields(String... patterns) {
        return withKinds(IngredientKind.CHEMICAL, patterns);
    }

    private ModdedRecipeDeclaration withKinds(IngredientKind kind, String... patterns) {
        ModdedRecipeDeclaration declaration = this;
        for (String pattern : patterns) {
            declaration = declaration.withFieldKind(pattern, kind);
        }
        return declaration;
    }

    /** The same declaration, with its own fluid value shapes. */
    ModdedRecipeDeclaration withFluidStyles(ModdedJsonStyle input, ModdedJsonStyle output) {
        return new ModdedRecipeDeclaration(serializerId, pageUid, inputFields, outputFields, readOnlyPageUids,
                fieldKinds, fixedFieldIds, inputStyle, outputStyle, input, output, inputChemicalStyle,
                outputChemicalStyle, concatenatedOutputs, derivedSlots, preservedBaseFields);
    }

    /** The same declaration, with its own chemical value shapes. */
    ModdedRecipeDeclaration withChemicalStyles(ModdedJsonStyle input, ModdedJsonStyle output) {
        return new ModdedRecipeDeclaration(serializerId, pageUid, inputFields, outputFields, readOnlyPageUids,
                fieldKinds, fixedFieldIds, inputStyle, outputStyle, inputFluidStyle, outputFluidStyle,
                input, output, concatenatedOutputs, derivedSlots, preservedBaseFields);
    }

    @Override
    public boolean supports(String declarationKey) {
        return scopeKey().equals(declarationKey);
    }

    @Override
    public String serializerId() {
        return serializerId;
    }

    @Override
    public String pageUid() {
        return pageUid;
    }

    @Override
    public String fixedIdOfField(String path) {
        if (path == null) {
            return null;
        }
        String exact = fixedFieldIds.get(path);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : fixedFieldIds.entrySet()) {
            if (matches(entry.getKey(), path)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public List<String> inputFields() {
        return inputFields;
    }

    @Override
    public List<String> outputFields() {
        return outputFields;
    }

    @Override
    public boolean concatenatedOutputs() {
        return concatenatedOutputs;
    }

    @Override
    public DerivedSlotSequence derivedSlots() {
        return derivedSlots;
    }

    @Override
    public boolean isRepeatField(String path) {
        if (path == null) {
            return false;
        }
        for (String pattern : inputFields) {
            if (pattern.contains(cc.sighs.JEIEditor.recipe.RecipeFieldMapping.REPEAT)
                    && matches(pattern, path)) {
                return true;
            }
        }
        for (String pattern : outputFields) {
            if (pattern.contains(cc.sighs.JEIEditor.recipe.RecipeFieldMapping.REPEAT)
                    && matches(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean preservesIngredientBase(String path) {
        if (path == null) {
            return false;
        }
        for (String pattern : preservedBaseFields) {
            if (pattern.equals(path) || matches(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<String> readOnlyPageUids() {
        return readOnlyPageUids;
    }

    @Override
    public ItemJsonStyle inputStyle() {
        return inputStyle;
    }

    @Override
    public ItemJsonStyle outputStyle() {
        return outputStyle;
    }

    @Override
    public IngredientKind kindOfField(String path) {
        if (path == null) {
            return IngredientKind.ITEM;
        }
        IngredientKind exact = fieldKinds.get(path);
        if (exact != null) {
            return exact;
        }
        // A caller passes the resolved path, so a repeatable pattern has to be
        // matched component-wise: "inputs.%d" answers for "inputs.3".
        for (Map.Entry<String, IngredientKind> entry : fieldKinds.entrySet()) {
            if (matches(entry.getKey(), path)) {
                return entry.getValue();
            }
        }
        return IngredientKind.ITEM;
    }

    @Override
    public ModdedJsonStyle fluidStyle(boolean output) {
        return output ? outputFluidStyle : inputFluidStyle;
    }

    @Override
    public ModdedJsonStyle chemicalStyle(boolean output) {
        return output ? outputChemicalStyle : inputChemicalStyle;
    }

    /** Whether a declared pattern names {@code path}; {@code %d} matches any component. */
    private static boolean matches(String pattern, String path) {
        String[] patternParts = pattern.split("\\.", -1);
        String[] pathParts = path.split("\\.", -1);
        if (patternParts.length != pathParts.length) {
            return false;
        }
        for (int index = 0; index < patternParts.length; index++) {
            if (!cc.sighs.JEIEditor.recipe.RecipeFieldMapping.REPEAT.equals(patternParts[index])
                    && !patternParts[index].equals(pathParts[index])) {
                return false;
            }
        }
        return true;
    }
}
