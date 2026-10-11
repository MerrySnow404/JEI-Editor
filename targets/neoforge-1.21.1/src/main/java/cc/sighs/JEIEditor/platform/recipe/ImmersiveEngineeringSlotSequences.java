package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The slot sequences of the Immersive Engineering pages whose slots neither a
 * positional field list nor an ordered segment list can name (see
 * {@link DerivedSlotSequence} for what that mechanism is and how its two sides
 * split the work).
 *
 * <p>Evidence: every JSON quoted here was read out of
 * {@code immersiveengineering__ImmersiveEngineering-1.21.1-12.4.2-194.jar}, every
 * slot order out of the category's own {@code setRecipe}, disassembled with
 * {@code javap -c}, and every field name out of the serializer class's constant
 * pool ({@code ClocheRecipeSerializer}: {@code input}, {@code soil},
 * {@code fluid}, {@code results}, {@code render}, {@code time};
 * {@code BottlingMachineRecipeSerializer}: {@code results}, {@code input},
 * {@code inputs}, {@code fluid}).
 *
 * <p>Why a sequence and not a field list, page by page:
 *
 * <ul>
 *   <li>{@link Cloche}: the input side is two slots (the seed, then the fluid the
 *       cloche consumes), but the output side is one slot per entry of a
 *       {@code results} array that mixes plain outputs with chance-wrapped ones -
 *       {@code {"chance":0.25,"output":{"id":"minecraft:beetroot_seeds"}}} on 3 of
 *       the 43 entries of the 40 shipped files - and a field list can only name the
 *       array element as a whole, so an edit of a wrapped entry would write
 *       {@code {"id":…,"count":…}} over it and silently turn a 25% chance into
 *       100%. The sequence resolves the element or its nested {@code output} object
 *       per entry, which keeps {@code chance} and {@code conditions} verbatim.</li>
 *   <li>{@link BottlingMachine}: the item input is spelled {@code input} in 23 of
 *       the 28 shipped files and {@code inputs} in the other 5 (one entry of a list
 *       of 2, and one of 3), and the category draws one INPUT slot per entry of the
 *       list the serializer built from whichever key is present - so the page's item
 *       input count is 1, 2 or 3 and the fluid input always comes last. No field
 *       list can name that: a {@code %} repeat must be the last field, and it would
 *       swallow the fluid slot.</li>
 * </ul>
 *
 * <p>The cross-check against what the mod loaded is the serializer id of the
 * recipe the patch targets ({@link Recipe#getSerializer()}), because this module
 * does not compile against Immersive Engineering and therefore cannot name the
 * recipe class. That is weaker than {@link CreateBasinSlotSequence}'s comparison
 * of {@code Ingredient} arrays, and it is why every resolver below refuses a
 * recipe whose JSON does not have exactly the shape its category's derivation
 * reads: a mod update that changes a shape stops the patch instead of writing a
 * guessed field.
 */
final class ImmersiveEngineeringSlotSequences {
    private static final String INPUT = "input";
    private static final String INPUTS = "inputs";
    private static final String FLUID = "fluid";
    private static final String RESULTS = "results";
    private static final String OUTPUT = "output";

    private ImmersiveEngineeringSlotSequences() {
    }

    /**
     * {@code immersiveengineering:cloche} - 2 inputs / 1..2 outputs.
     *
     * <p>{@code ClocheRecipeCategory.setRecipe} adds INPUT(seed), a CATALYST slot
     * for the soil, one OUTPUT per valid entry of {@code outputs}, INPUT(requiredFluid)
     * and finally one RENDER_ONLY slot per {@code ClocheFertilizer}, in that order.
     * The roles the editor models are therefore input[0]=seed, input[1]=requiredFluid
     * and one output per {@code results} entry - the soil and the six fertilizer
     * previews are CATALYST/RENDER_ONLY and carry no declared field, so they survive
     * verbatim.
     *
     * <p>{@code data/immersiveengineering/recipe/cloche/melon.json}:
     * <pre>
     *   {"type":"immersiveengineering:cloche","input":{"item":"minecraft:melon_seeds"},
     *    "render":{"type":"immersiveengineering:stem",…},"results":[{"id":"minecraft:melon"}],
     *    "soil":{"item":"minecraft:dirt"},"time":800}
     * </pre>
     * {@code cloche/crimson_fungus.json} is the only extra shape on the input side:
     * {@code "fluid":{"fluid":"minecraft:lava"}}. The other 38 files carry no
     * {@code fluid} key at all, and the page still draws that INPUT slot - the
     * runtime dump reads {@code input[1]=fluid_stack:1000 minecraft:water} for
     * melon - because {@code ClocheRecipe} defaults the field to water. The
     * sequence therefore always names the {@code fluid} field even when the file
     * omits it: a write creates the key, which is a shape the codec's optional
     * field accepts. Its value shape is an unsized {@code FluidIngredient}
     * ({@code SingleFluidIngredient.CODEC} is
     * {@code FluidStack.FLUID_NON_EMPTY_CODEC.fieldOf("fluid")}, a bare id or tag),
     * so the declared fluid style's {@code amount} key is ignored by the codec -
     * the slot's amount is not part of this recipe, which is why the field is
     * named FLUID and not sized.
     *
     * <p>{@code cloche/beetroot.json}, {@code cloche/hemp.json} and
     * {@code cloche/wheat.json} carry the chance form in their second entry, which is
     * what the nested path below is for.
     */
    static final class Cloche implements DerivedSlotSequence {
        static final Cloche INSTANCE = new Cloche();

        private static final Set<IngredientKind> INPUT_KINDS = Collections.unmodifiableSet(
                new LinkedHashSet<IngredientKind>(
                        Arrays.asList(IngredientKind.ITEM, IngredientKind.FLUID)));
        private static final Set<IngredientKind> OUTPUT_KINDS =
                Collections.singleton(IngredientKind.ITEM);
        private static final int INPUT_COUNT = 2;

        private Cloche() {
        }

        @Override
        public Set<IngredientKind> inputKinds() {
            return INPUT_KINDS;
        }

        @Override
        public Set<IngredientKind> outputKinds() {
            return OUTPUT_KINDS;
        }

        /** The seed and the fluid the cloche consumes: always exactly two. */
        @Override
        public boolean inputCountFits(int count) {
            return count == INPUT_COUNT;
        }

        /** One slot per {@code results} entry; every shipped file carries 1 or 2. */
        @Override
        public boolean outputCountFits(int count) {
            return count >= 1;
        }

        @Override
        public Optional<List<DerivedSlot>> inputSlots(JsonObject recipe, Recipe<?> loaded) {
            if (recipe == null || !isObject(recipe.get(INPUT))) {
                return Optional.empty();
            }
            List<DerivedSlot> slots = new ArrayList<DerivedSlot>();
            slots.add(DerivedSlot.of(IngredientKind.ITEM, INPUT));
            slots.add(DerivedSlot.of(IngredientKind.FLUID, FLUID));
            return Optional.of(slots);
        }

        @Override
        public Optional<List<DerivedSlot>> outputSlots(JsonObject recipe, Recipe<?> loaded) {
            return entrySlots(recipe, RESULTS);
        }
    }

    /**
     * {@code immersiveengineering:bottling_machine} - 2..4 inputs / 1..2 outputs.
     *
     * <p>{@code BottlingMachineRecipeCategory.setRecipe} adds one INPUT per entry of
     * {@code BottlingMachineRecipe.inputs}, then one OUTPUT per entry of
     * {@code output.get()}, then INPUT(fluidInput) last - so the item slots precede
     * the fluid slot, which is the band order the client test checks for every
     * derived page.
     *
     * <p>{@code BottlingMachineRecipeSerializer.CODECS} reads the item side with
     * {@code listOrSingle(IngredientWithSize.CODECS, "input", "inputs")}: the 23
     * files whose item input is a single object spell it {@code input}
     * ({@code bottling/black_concrete_powder.json} is
     * {@code "input":{"item":"minecraft:black_concrete_powder"}}) and the 5 whose
     * input is a list spell it {@code inputs}
     * ({@code bottling/empty_shell.json} is {@code "inputs":[{"item":"minecraft:mold_bullet_casing"},
     * {"basePredicate":{"tag":"c:nuggets/copper"},"count":3}]}). No shipped file
     * carries both keys, and the sequence refuses one that does rather than guessing
     * which of the two the codec took.
     *
     * <p>{@code "fluid":{"amount":125,"tag":"minecraft:water"}} and
     * {@code "results":[{"id":"minecraft:black_concrete"}]}: the fluid is a
     * {@code SizedFluidIngredient} (so the amount-bearing fluid style is the right
     * one) and the output a {@code TagOutputList}, whose entries are plain
     * {@code TagOutput} objects. The latter is also where the chance form would be
     * resolved if a file ever used it - see {@code cloche} - and none of the 28 does.
     */
    static final class BottlingMachine implements DerivedSlotSequence {
        static final BottlingMachine INSTANCE = new BottlingMachine();

        private static final Set<IngredientKind> INPUT_KINDS = Collections.unmodifiableSet(
                new LinkedHashSet<IngredientKind>(
                        Arrays.asList(IngredientKind.ITEM, IngredientKind.FLUID)));
        private static final Set<IngredientKind> OUTPUT_KINDS =
                Collections.singleton(IngredientKind.ITEM);

        private BottlingMachine() {
        }

        @Override
        public Set<IngredientKind> inputKinds() {
            return INPUT_KINDS;
        }

        @Override
        public Set<IngredientKind> outputKinds() {
            return OUTPUT_KINDS;
        }

        /** At least one item and the fluid, which is always last. */
        @Override
        public boolean inputCountFits(int count) {
            return count >= 2;
        }

        @Override
        public boolean outputCountFits(int count) {
            return count >= 1;
        }

        @Override
        public Optional<List<DerivedSlot>> inputSlots(JsonObject recipe, Recipe<?> loaded) {
            if (recipe == null) {
                return Optional.empty();
            }
            boolean single = isObject(recipe.get(INPUT));
            boolean list = recipe.has(INPUTS);
            if (single == list) {
                // Neither key, or both: the codec's listOrSingle reads exactly one of
                // them and the derivation cannot tell which, so the patch is refused.
                return Optional.empty();
            }
            List<DerivedSlot> slots = new ArrayList<DerivedSlot>();
            if (list) {
                Optional<List<JsonElement>> entries = elements(recipe.get(INPUTS));
                if (!entries.isPresent() || entries.get().isEmpty()) {
                    return Optional.empty();
                }
                for (int index = 0; index < entries.get().size(); index++) {
                    if (!isObject(entries.get().get(index))) {
                        return Optional.empty();
                    }
                    slots.add(DerivedSlot.of(IngredientKind.ITEM, INPUTS + "." + index));
                }
            } else {
                slots.add(DerivedSlot.of(IngredientKind.ITEM, INPUT));
            }
            if (!isObject(recipe.get(FLUID))) {
                return Optional.empty();
            }
            slots.add(DerivedSlot.of(IngredientKind.FLUID, FLUID));
            return Optional.of(slots);
        }

        @Override
        public Optional<List<DerivedSlot>> outputSlots(JsonObject recipe, Recipe<?> loaded) {
            return entrySlots(recipe, RESULTS);
        }
    }


    // --- shared shape reading ---------------------------------------------------

    /**
     * One item slot per entry of the array at {@code key}, addressing the nested
     * {@code output} object of an entry that has one and the entry itself otherwise.
     *
     * <p>That is how Immersive Engineering's {@code StackWithChance} is written:
     * {@code {"chance":0.25,"output":{…}}} on the entries that roll, a plain
     * {@code {"id":…,"count":…}} or {@code {"tag":…}} on the ones that always
     * happen. Addressing the nested object is what keeps {@code chance} and
     * {@code conditions} - and refusing a missing or non-object array is what keeps
     * the patch from writing over a shape the derivation does not know.
     */
    private static Optional<List<DerivedSlot>> entrySlots(JsonObject recipe, String key) {
        if (recipe == null) {
            return Optional.empty();
        }
        Optional<List<JsonElement>> entries = elements(recipe.get(key));
        if (!entries.isPresent() || entries.get().isEmpty()) {
            return Optional.empty();
        }
        List<DerivedSlot> slots = new ArrayList<DerivedSlot>();
        for (int index = 0; index < entries.get().size(); index++) {
            JsonElement entry = entries.get().get(index);
            if (!isObject(entry)) {
                return Optional.empty();
            }
            String path = key + "." + index;
            JsonElement nested = entry.getAsJsonObject().get(OUTPUT);
            if (nested != null) {
                if (!isObject(nested)) {
                    return Optional.empty();
                }
                path = path + "." + OUTPUT;
            }
            slots.add(DerivedSlot.of(IngredientKind.ITEM, path));
        }
        return Optional.of(slots);
    }

    private static boolean isObject(JsonElement element) {
        return element != null && element.isJsonObject();
    }

    /** The elements of {@code element} when it is an array, empty otherwise. */
    private static Optional<List<JsonElement>> elements(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return Optional.empty();
        }
        JsonArray array = element.getAsJsonArray();
        List<JsonElement> entries = new ArrayList<JsonElement>(array.size());
        for (JsonElement entry : array) {
            entries.add(entry);
        }
        return Optional.of(entries);
    }
}
