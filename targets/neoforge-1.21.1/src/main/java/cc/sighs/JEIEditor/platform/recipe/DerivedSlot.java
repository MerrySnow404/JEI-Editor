package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One slot of a recipe-derived slot sequence (see {@link DerivedSlotSequence}):
 * the kind of ingredient the page draws there and every recipe JSON path that
 * one edit of that slot has to rewrite.
 *
 * <p>The paths are a list because the mod may have merged several recipe entries
 * into the one slot it draws - Create's {@code BasinCategory} collapses ingredient
 * entries with equal {@code Ingredient.getItems()} - and rewriting only some of
 * them would change the recipe's own multiplicity.
 *
 * <p>An <em>empty</em> path list is the explicit "this slot is read-only" form: the page
 * draws it, no field names it, and a patch addressed to it is refused. Create's mechanical
 * crafting page is the case - it draws one slot per pattern cell and a cell that is a space
 * has no ingredient at all - and the client hears about it through
 * {@link DerivedSlotSequence#fieldBackedInputOrdinals}.
 */
public final class DerivedSlot {
    private final IngredientKind kind;
    private final List<String> paths;

    public DerivedSlot(IngredientKind kind, List<String> paths) {
        if (kind == null || paths == null) {
            throw new IllegalArgumentException("a derived slot needs a kind and a path list");
        }
        this.kind = kind;
        this.paths = Collections.unmodifiableList(new ArrayList<String>(paths));
    }

    /** One derived slot holding {@code kind} at the single path {@code path}. */
    public static DerivedSlot of(IngredientKind kind, String path) {
        return new DerivedSlot(kind, Collections.singletonList(path));
    }

    public IngredientKind kind() {
        return kind;
    }

    /** The recipe JSON paths this slot writes, in the order the mod derived them. */
    public List<String> paths() {
        return paths;
    }
}
