package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Declares how one mod recipe type maps onto the editor's slot model.
 *
 * <p>A declaration only names the recipe JSON fields. The client model is built
 * from the slot views of the displayed JEI layout, and the server writes back
 * into a copy of the recipe's original JSON, so neither side needs per-mod code.
 * Types whose JSON cannot be expressed this way (nested ingredient objects,
 * non-item outputs) must not be declared.
 */
public interface ModdedRecipeAdapter {
    /**
     * Whether this declaration answers for {@code declarationKey} - its scope key,
     * which is {@link #pageUid()} when it is scoped to one page and its serializer
     * id otherwise. A page-scoped declaration answers for the page uid and for
     * nothing else, so the page it does not cover stays undeclared rather than
     * being modelled with the other direction's slot kinds.
     */
    boolean supports(String declarationKey);

    /**
     * The serializer id of the recipe type this declaration describes.
     *
     * <p>Not the only key a declaration can be found by: a mod that pushes one
     * serializer's recipes into two JEI pages with different slot roles (Mekanism's
     * {@code mekanism:rotary}: {@code condensentrating} draws a chemical input and a
     * fluid output, {@code decondensentrating} the reverse) needs one declaration per
     * page, because the slot kinds - and the JSON field each slot is written to -
     * differ while the serializer does not. Such a declaration names its page with
     * {@link #pageUid()} and is then found by that uid.
     */
    String serializerId();

    /**
     * The JEI recipe-type uid this declaration is scoped to, or null when it is
     * keyed by its serializer and covers every page of it.
     *
     * <p>That uid is the only thing that separates two pages of one serializer, so
     * it is also what the client sends as the patch's serializer id for such a page
     * (the model cannot carry a serializer that names no single direction). See
     * {@link #supports(String)}.
     */
    default String pageUid() {
        return null;
    }

    /**
     * The key this declaration is found by: its page uid when it is page-scoped,
     * its serializer id otherwise. It is the id an editor model of the page carries
     * and the id its patches are written with.
     */
    default String scopeKey() {
        String page = pageUid();
        return page != null ? page : serializerId();
    }

    /**
     * Recipe JSON field per JEI INPUT slot, in JEI display order. A field
     * containing {@code %d} is repeatable and names every remaining slot, so one
     * declaration covers a page whose input count varies per recipe.
     *
     * <p>Empty when {@link #derivedSlots()} describes the page's inputs instead:
     * such a page's slots are not named by a field at all.
     */
    List<String> inputFields();

    /**
     * Recipe JSON field per JEI OUTPUT slot, in JEI display order, with the same
     * repeatable {@code %d} rule as the inputs. A trailing field no recipe
     * carries - Horse Powered's optional {@code secondary} - may stay unused, so
     * the page may expose fewer output slots than this list names but never
     * more. Empty when the page has no output at all, which no declaration
     * currently needs - and when {@link #derivedSlots()} describes the outputs.
     */
    List<String> outputFields();

    /**
     * Whether {@link #outputFields()} is an ordered segment list instead of a
     * positional list.
     *
     * <p>A segment list is concatenated against the recipe JSON to form the
     * effective output sequence: a plain path ({@code result}, {@code slag})
     * contributes one slot when the recipe carries it, a repeating pattern
     * ({@code results.%d}, {@code secondaries.%d.output}) one slot per array
     * element. That is what lets one declaration name a page whose
     * ordinal-to-field mapping depends on the recipe - Immersive Engineering's
     * sawmill draws {@code OUTPUT(stripped)} only when {@code stripped} is
     * present, so its first OUTPUT slot is {@code stripped} on some recipes and
     * {@code result} on the rest. The client cannot resolve the concatenation (it
     * has no recipe JSON) and only checks the slot count; the server resolves it
     * and refuses a patch whose ordinal the concatenation does not reach.
     *
     * <p>The segments need not share one ingredient kind: a mixed list is the form
     * for a page whose outputs are an optional item and an optional chemical, like
     * Mekanism's {@code mekanism:reaction} ({@code item_output} then
     * {@code chemical_output}, each drawn only when the recipe carries it). The
     * client cannot tell which segment an ordinal is either, so it names the slot
     * after the ingredient the page actually shows there and the server checks the
     * patch kind against the kind of the field the concatenation resolves to.
     */
    default boolean concatenatedOutputs() {
        return false;
    }

    /**
     * The recipe-derived slot sequence this declaration models, or null when its
     * slots are named by {@link #inputFields()} / {@link #outputFields()} instead.
     *
     * <p>Both field lists are empty for such a declaration: the sequence is the
     * page's whole slot layout, it is resolved against the recipe JSON on the
     * server, and the client only checks the counts the sequence itself promises
     * and the kinds it can hold. See {@link DerivedSlotSequence}.
     */
    default DerivedSlotSequence derivedSlots() {
        return null;
    }

    /**
     * Whether the declared item field {@code path} was named by a repeatable
     * ({@code %d}) pattern, i.e. it is one element of a variable-arity array.
     *
     * <p>Only such an element may be <em>removed</em> to express "this one is gone":
     * a cleared element of a fixed array field has no empty form the mods' codecs
     * accept ({@code minecraft:air} with count 0 is refused by the recipe loader), so
     * the write path refuses that patch instead. Create's
     * {@code create:item_application} is what that guards: its page draws two inputs
     * and {@code ItemApplicationRecipe.getRequiredHeldItem()} throws
     * {@code "Item Application Recipe has no tool!"} when the decoded ingredient list
     * holds fewer than two entries, which takes JEI's whole recipe area down with it.
     */
    default boolean isRepeatField(String path) {
        return false;
    }

    /**
     * Whether the declared item field {@code path} is a <em>nested</em> ingredient
     * node whose editable base has to be rewritten in place, keeping every other
     * part of the node.
     *
     * <p>Mekanism's painting input is the case: 160 of its 176 shipped files write
     * {@code item_input} as
     * {@code {"type":"neoforge:difference","base":{…},"count":1,"subtracted":{…}}},
     * whose page shows exactly the items of base-minus-subtracted. Replacing the
     * whole node with {@code {"item":…,"count":…}} - what an ordinary declared item
     * field does, and does deliberately for a tag - would drop the difference. A
     * writer for this field rewrites only {@code base} with the edited item and
     * leaves {@code type}, {@code count} and {@code subtracted} untouched; a field
     * whose JSON is a plain item node is written the ordinary way.
     */
    default boolean preservesIngredientBase(String path) {
        return false;
    }

    /**
     * The ingredient id a declared field is fixed to, or null when the field names
     * its own ingredient.
     *
     * <p>Only a field whose JSON value cannot carry an id needs this: Immersive
     * Engineering's coke oven writes its creosote by-product as the bare amount
     * {@code "creosote": 250}, so the fluid is implied by the field name. Both sides
     * then refuse any other id, because writing the amount of the dragged fluid
     * would be read back as creosote.
     */
    default String fixedIdOfField(String path) {
        return null;
    }

    /**
     * Page uids that share this serializer but must stay read-only even when
     * their slot shape matches, because the server cannot write those recipes
     * back (they are generated at reload time instead of coming from a recipe
     * JSON). Every other page of the serializer is declared.
     */
    default Set<String> readOnlyPageUids() {
        return Collections.emptySet();
    }

    ItemJsonStyle inputStyle();

    ItemJsonStyle outputStyle();

    /**
     * The kind of ingredient the declared JSON field {@code path} holds, defaulting
     * to {@link IngredientKind#ITEM}.
     *
     * <p>Only a declaration that overrides the field
     * ({@code ModdedRecipeDeclaration.withFieldKind}) answers anything else. This is
     * what lets both sides refuse a mismatch instead of writing a fluid into an item
     * field: the client only offers a slot for the kind its declared field holds,
     * and the server refuses a patch whose kind differs from the declared one.
     */
    default IngredientKind kindOfField(String path) {
        return IngredientKind.ITEM;
    }

    /**
     * The JSON value shape of a fluid field, {@code {"fluid":…,"amount":…}} by
     * default. Override when the mod writes its fluids differently, and note that
     * {@code output} selects the result-side shape, which is not always the same
     * as the ingredient-side one.
     */
    default ModdedJsonStyle fluidStyle(boolean output) {
        return ModdedJsonStyle.FLUID;
    }

    /**
     * The JSON value shape of a chemical field, {@code {"chemical":…,"amount":…}} by
     * default - Mekanism writes a chemical stack with the same keys on both sides.
     */
    default ModdedJsonStyle chemicalStyle(boolean output) {
        return ModdedJsonStyle.CHEMICAL;
    }

    /** The value shape of one declared field of {@code kind}. */
    default ModdedJsonStyle styleFor(IngredientKind kind, boolean output) {
        if (kind == IngredientKind.FLUID) {
            return fluidStyle(output);
        }
        if (kind == IngredientKind.CHEMICAL) {
            return chemicalStyle(output);
        }
        return ModdedJsonStyle.of(output ? outputStyle() : inputStyle());
    }

    /** The JSON shape of one item entry. */
    enum ItemJsonStyle {
        /** {@code {"item": "<id>", "count": <n>}} */
        ITEM,
        /** {@code {"id": "<id>", "count": <n>}} */
        ID
    }
}
