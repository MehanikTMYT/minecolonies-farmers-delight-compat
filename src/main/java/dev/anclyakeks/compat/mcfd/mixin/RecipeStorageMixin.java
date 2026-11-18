package dev.anclyakeks.compat.mcfd.mixin;

import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.crafting.RecipeStorage;
import dev.anclyakeks.compat.mcfd.ingredient.IngredientEquivalents;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies ingredient equivalence while checking and consuming a crafting recipe.
 *
 * <p>Upstream lambda numbering differs from the upstream MineColonies build:
 * our 26.1.2 jar compiles the three-argument "can take" predicate as
 * {@code lambda$fullfillRecipeAndCopy$2} (upstream: {@code $4}) and the
 * four-argument "extract and count" predicate as
 * {@code lambda$fullfillRecipeAndCopy$0} (upstream: {@code $2}).
 * Both handlers below keep the upstream parameter shapes, verified against
 * {@code minecolonies-1.1.1397-26.1.2.jar} with javap.</p>
 */
@Mixin(RecipeStorage.class)
abstract class RecipeStorageMixin {
    @Inject(
        method = {
            "lambda$canFullFillRecipe$0",
            "lambda$canFullFillRecipe$1",
            "lambda$fullfillRecipeAndCopy$2"
        },
        at = @At("HEAD"),
        cancellable = true
    )
    private static void mcfdCompat$matchEquivalentIngredient(
        final ItemStack expected,
        final ItemStorage definition,
        final ItemStack candidate,
        final CallbackInfoReturnable<Boolean> callback
    ) {
        if (IngredientEquivalents.areEquivalent(definition.getItemStack(), candidate)
            || IngredientEquivalents.areEquivalent(expected, candidate)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "lambda$fullfillRecipeAndCopy$0", at = @At("HEAD"), cancellable = true)
    private static void mcfdCompat$consumeEquivalentIngredient(
        final ItemStack expected,
        final ItemStorage definition,
        final boolean damageTool,
        final ItemStack candidate,
        final CallbackInfoReturnable<Boolean> callback
    ) {
        if (IngredientEquivalents.areEquivalent(definition.getItemStack(), candidate)
            || IngredientEquivalents.areEquivalent(expected, candidate)) {
            callback.setReturnValue(true);
        }
    }
}
