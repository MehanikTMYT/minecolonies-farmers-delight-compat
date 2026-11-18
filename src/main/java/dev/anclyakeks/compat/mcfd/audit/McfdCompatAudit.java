package dev.anclyakeks.compat.mcfd.audit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.crafting.IRecipeStorage;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.util.FoodUtils;
import com.minecolonies.core.colony.crafting.CustomRecipe;
import com.minecolonies.core.colony.crafting.CustomRecipeManager;
import com.minecolonies.core.entity.ai.workers.crafting.AbstractEntityAICrafting;
import com.minecolonies.core.entity.ai.workers.production.agriculture.EntityAIWorkFarmer;
import dev.anclyakeks.compat.mcfd.McfdCompatMod;
import dev.anclyakeks.compat.mcfd.config.McfdCompatConfig;
import dev.anclyakeks.compat.mcfd.food.FarmerDelightMealValues;
import dev.anclyakeks.compat.mcfd.ingredient.IngredientEquivalents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import vectorwing.farmersdelight.common.registry.ModBlocks;
import vectorwing.farmersdelight.common.registry.ModItems;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Self-audit replacing the upstream gametest suite. The upstream tests used
 * {@code net.neoforged.neoforge.gametest.GameTestHolder}, which does not exist
 * in the 26.1.2 neoforge build; the same checks run instead as a load-time
 * server audit and via {@code /mcfd_audit} on a live server.
 *
 * <p>The audit validates the shipped resource files against loaded registries,
 * MineColonies' parsed recipe objects and its real inventory fulfillment code,
 * the applied mixin hooks and the food/equivalence behaviour. Ground-support
 * checks that would mutate a world (upstream farmland/Rich-Soil gametests) are
 * covered by the farmer AI and must be verified in-game.</p>
 */
public final class McfdCompatAudit {
    private static final String RECIPE_ROOT = "crafterrecipes";
    private static final int EXPECTED_RECIPE_COUNT = 89;
    private static final Set<String> SUPPORTED_CRAFTERS = Set.of(
        "baker_crafting",
        "chef_crafting",
        "chef_smelting",
        "farmer_crafting",
        "fletcher_crafting",
        "planter_crafting"
    );

    private McfdCompatAudit() {
    }

    /** Validates the shipped crafterrecipes JSON files against loaded registries. */
    public static List<String> auditResources(final MinecraftServer server, final Map<String, Integer> usedCrafters) {
        final List<String> problems = new ArrayList<>();
        final Map<Identifier, JsonObject> recipes = loadRecipeFiles(server, problems);
        final Set<Identifier> finishedMealOutputs = new HashSet<>();

        for (final Map.Entry<Identifier, JsonObject> entry : recipes.entrySet()) {
            final Identifier location = entry.getKey();
            final JsonObject recipe = entry.getValue();

            if (!recipe.has("inputs") || recipe.getAsJsonArray("inputs").isEmpty()) {
                problems.add("Recipe has no ingredients: " + location);
            }
            if (!recipe.has("result") || !recipe.getAsJsonObject("result").has("id")) {
                problems.add("Recipe has no result: " + location);
            }

            final String crafter = recipe.get("crafter").getAsString();
            if (!SUPPORTED_CRAFTERS.contains(crafter)) {
                problems.add("Unexpected crafter type in " + location + ": " + crafter);
            }
            usedCrafters.merge(crafter, 1, Integer::sum);

            if ("chef_smelting".equals(crafter)
                && (!recipe.has("intermediate") || !"minecraft:furnace".equals(recipe.get("intermediate").getAsString()))) {
                problems.add("Chef smelting recipe must use Kitchen's furnace module: " + location);
            }

            for (final var input : recipe.getAsJsonArray("inputs")) {
                final String id = input.getAsJsonObject().get("id").getAsString();
                if ("minecraft:air".equals(id) || item(id) == null) {
                    problems.add("Invalid ingredient in " + location + ": " + id);
                }
            }

            final String resultId = recipe.getAsJsonObject("result").get("id").getAsString();
            if ("minecraft:air".equals(resultId) || item(resultId) == null) {
                problems.add("Invalid result in " + location + ": " + resultId);
            } else if (resultId.startsWith("farmersdelight:")) {
                finishedMealOutputs.add(Identifier.parse(resultId));
            }
        }

        if (recipes.size() != EXPECTED_RECIPE_COUNT) {
            problems.add("Expected " + EXPECTED_RECIPE_COUNT + " compatibility recipes, found " + recipes.size());
        }
        for (final String crafter : SUPPORTED_CRAFTERS) {
            if (!usedCrafters.containsKey(crafter)) {
                problems.add("MineColonies worker type has no compatibility recipes: " + crafter);
            }
        }
        for (final Identifier meal : FarmerDelightMealValues.getCompatibilityMeals()) {
            if (!finishedMealOutputs.contains(meal)) {
                problems.add("A finished Farmer's Delight meal has no MineColonies recipe: " + meal);
            }
        }
        return problems;
    }

    /** Validates MineColonies' parsed recipe objects for the compatibility namespace. */
    public static List<String> auditParsedRecipes() {
        final List<String> problems = new ArrayList<>();
        final List<CustomRecipe> recipes = CustomRecipeManager.getInstance().getAllRecipes().values().stream()
            .flatMap(byId -> byId.values().stream())
            .filter(recipe -> recipe.getRecipeId().getNamespace().equals(McfdCompatMod.MOD_ID))
            .toList();

        if (recipes.size() != EXPECTED_RECIPE_COUNT) {
            problems.add("MineColonies did not parse all " + EXPECTED_RECIPE_COUNT
                + " compatibility recipes; parsed " + recipes.size());
        }

        for (final CustomRecipe recipe : recipes) {
            final IRecipeStorage storage = recipe.getRecipeStorage();
            if (storage.getInput().isEmpty()) {
                problems.add("Parsed recipe has no inputs: " + recipe.getRecipeId());
            }
            if (storage.getPrimaryOutput().isEmpty()) {
                problems.add("Parsed recipe has no primary output: " + recipe.getRecipeId());
            }
        }
        return problems;
    }

    /**
     * Exercises MineColonies' actual inventory fulfillment code for every parsed
     * compatibility recipe; finding the JSON files alone is not enough. Uses the
     * entity-free fulfillment overload (empty loot context, like MineColonies'
     * own console/QA paths), so it also runs on a dedicated server with no
     * player online; the citizen-only tool-damage branch stays unexercised.
     */
    public static List<String> auditLiveCrafting(final ServerLevel level) {
        final List<String> problems = new ArrayList<>();
        final List<CustomRecipe> recipes = CustomRecipeManager.getInstance().getAllRecipes().values().stream()
            .flatMap(byId -> byId.values().stream())
            .filter(recipe -> recipe.getRecipeId().getNamespace().equals(McfdCompatMod.MOD_ID))
            .toList();

        for (final CustomRecipe recipe : recipes) {
            final IRecipeStorage storage = recipe.getRecipeStorage();
            final List<ItemStorage> inputs = storage.getInput();
            final ItemStackHandler inventory = new ItemStackHandler(inputs.size() + storage.getCraftingTools().size() + 1);
            int slot = 0;
            for (final ItemStorage input : inputs) {
                final ItemStack stack = input.getItemStack().copy();
                stack.setCount(input.getAmount());
                inventory.setStackInSlot(slot++, stack);
            }
            for (final ItemStack tool : storage.getCraftingTools()) {
                inventory.setStackInSlot(slot++, tool.copy());
            }

            final List<ItemStack> outputs = storage.fullfillRecipeAndCopy(level, List.of(inventory), false);
            final boolean crafted = !outputs.isEmpty() && outputs.stream().anyMatch(
                stack -> ItemStack.isSameItemSameComponents(stack, recipe.getPrimaryOutput()));
            if (!crafted) {
                problems.add("MineColonies could not craft " + recipe.getRecipeId() + " for " + recipe.getCrafter());
            }
        }
        return problems;
    }

    /** Checks the live MineColonies food scale against the compatibility values. */
    public static List<String> auditFoodValues() {
        final List<String> problems = new ArrayList<>();
        if (!McfdCompatConfig.MINECOLONIES_NUTRITION_FOR_FD_MEALS.get()) {
            problems.add("MineColonies nutrition compatibility is disabled");
            return problems;
        }

        final ItemStack meal = new ItemStack(item("farmersdelight:honey_glazed_ham"));
        final FoodProperties food = meal.get(DataComponents.FOOD);
        if (food == null) {
            problems.add("The tested Farmer's Delight meal has no food properties");
            return problems;
        }
        if (!FarmerDelightMealValues.isCompatibilityMeal(meal)) {
            problems.add("Finished meal is not registered for compatibility");
        }
        if (FoodUtils.getFoodTier(meal) != FarmerDelightMealValues.getMinecoloniesTier(meal, food)) {
            problems.add("Live MineColonies food tier does not use the compatibility value");
        }
        if (FoodUtils.getFoodValue(meal, food, 0.0D) != FarmerDelightMealValues.getCitizenFoodValue(meal, food, 0.0D)) {
            problems.add("Live MineColonies food value does not use the compatibility value");
        }
        return problems;
    }

    /** Checks ingredient equivalence in direct comparison and in live request matching. */
    public static List<String> auditEquivalence() {
        final List<String> problems = new ArrayList<>();
        assertEquivalent(problems, "farmersdelight:rice", "minecolonies:rice");
        assertEquivalent(problems, "farmersdelight:tomato", "minecolonies:tomato");
        assertEquivalent(problems, "farmersdelight:onion", "minecolonies:onion");
        assertEquivalent(problems, "farmersdelight:cabbage", "minecolonies:cabbage");
        assertEquivalent(problems, "farmersdelight:wheat_dough", "minecolonies:bread_dough");
        return problems;
    }

    /** Verifies the runtime hooks the gameplay mixins add to MineColonies classes. */
    public static List<String> auditHooks() {
        final List<String> problems = new ArrayList<>();
        final Map<Class<?>, List<String>> expectedHooks = new HashMap<>();
        expectedHooks.put(EntityAIWorkFarmer.class, List.of(
            "mcfdCompat$acceptRichSoilFarmland",
            "mcfdCompat$prepareSpecialCropGround",
            "mcfdCompat$findSpecialPlantableSurface",
            "mcfdCompat$findSpecialHarvest"
        ));
        expectedHooks.put(com.minecolonies.api.util.FoodUtils.class, List.of(
            "mcfdCompat$useMinecoloniesFoodValue",
            "mcfdCompat$useMinecoloniesFoodTier"
        ));
        expectedHooks.put(com.minecolonies.api.util.ItemStackUtils.class, List.of(
            "mcfdCompat$applyGreatFoodBonus"
        ));
        expectedHooks.put(com.minecolonies.api.crafting.RecipeStorage.class, List.of(
            "mcfdCompat$matchEquivalentIngredient",
            "mcfdCompat$consumeEquivalentIngredient"
        ));
        expectedHooks.put(com.minecolonies.core.util.WorkerUtil.class, List.of(
            "mcfdCompat$keepEquivalentIngredient"
        ));
        expectedHooks.put(AbstractEntityAICrafting.class, List.of(
            "mcfdCompat$countEquivalentIngredient"
        ));

        for (final Map.Entry<Class<?>, List<String>> entry : expectedHooks.entrySet()) {
            for (final String hookName : entry.getValue()) {
                if (!hasAppliedHook(entry.getKey(), hookName)) {
                    problems.add("Mixin hook was not applied: " + entry.getKey().getSimpleName() + "." + hookName);
                }
            }
        }
        return problems;
    }

    /** Verifies that the registries both mods are wired against are loaded. */
    public static List<String> auditRegistries() {
        final List<String> problems = new ArrayList<>();
        final List<String> requiredIds = List.of(
            "farmersdelight:rice",
            "farmersdelight:rich_soil",
            "farmersdelight:rich_soil_farmland",
            "farmersdelight:rice_crop",
            "farmersdelight:cabbage_seeds",
            "farmersdelight:tomato_seeds",
            "farmersdelight:onion",
            "farmersdelight:wheat_dough",
            "minecolonies:rice",
            "minecolonies:tomato",
            "minecolonies:onion",
            "minecolonies:cabbage",
            "minecolonies:bread_dough"
        );
        for (final String requiredId : requiredIds) {
            if (item(requiredId) == null) {
                problems.add("Required item is missing from the registry: " + requiredId);
            }
        }

        // Supplying constants double-checks the Farmer's Delight port's registry fields.
        try {
            final Object ignored = ModBlocks.RICE_CROP.get();
            final Object ignored2 = ModBlocks.RICH_SOIL_FARMLAND.get();
            final Object ignored3 = ModItems.RICE.get();
        } catch (final RuntimeException exception) {
            problems.add("Farmer's Delight registry access failed: " + exception.getMessage());
        }
        return problems;
    }

    private static Map<Identifier, JsonObject> loadRecipeFiles(final MinecraftServer server, final List<String> problems) {
        final Map<Identifier, JsonObject> recipes = new HashMap<>();
        final Map<Identifier, net.minecraft.server.packs.resources.Resource> resources =
            server.getResourceManager().listResources(
                RECIPE_ROOT,
                location -> location.getNamespace().equals(McfdCompatMod.MOD_ID) && location.getPath().endsWith(".json")
            );

        for (final Map.Entry<Identifier, net.minecraft.server.packs.resources.Resource> entry : resources.entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                recipes.put(entry.getKey(), JsonParser.parseReader(reader).getAsJsonObject());
            } catch (final Exception exception) {
                problems.add("Could not load recipe " + entry.getKey() + ": " + exception.getMessage());
            }
        }
        return recipes;
    }

    private static void assertEquivalent(final List<String> problems, final String firstId, final String secondId) {
        final ItemStack first = new ItemStack(item(firstId));
        final ItemStack second = new ItemStack(item(secondId));
        if (!IngredientEquivalents.areEquivalent(first, second)) {
            problems.add("Direct equivalence failed: " + firstId + " / " + secondId);
        }
        if (!new Stack(first).matches(second)) {
            problems.add("MineColonies request did not accept " + secondId + " for " + firstId);
        }
        if (!new Stack(second).matches(first)) {
            problems.add("MineColonies request did not accept " + firstId + " for " + secondId);
        }
    }

    private static boolean hasAppliedHook(final Class<?> owner, final String hookName) {
        // sponge-mixin 0.17.4 (FML 11) переименовывает применённые хендлеры:
        // handler$<sort>$<modid>$mcfdCompat$<hook>. Проверяем суффикс.
        for (final var method : owner.getDeclaredMethods()) {
            if (method.getName().endsWith(hookName)) {
                return true;
            }
        }
        return false;
    }

    private static net.minecraft.world.item.Item item(final String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }
}
