package dev.anclyakeks.compat.mcfd.audit;

import com.mojang.logging.LogUtils;
import dev.anclyakeks.compat.mcfd.McfdCompatMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the compatibility audit when a server boots (registries, resource
 * files, MineColonies parsing and applied mixins) and exposes the full audit,
 * including live recipe crafting, as {@code /mcfd_audit} for an operator.
 */
@EventBusSubscriber(modid = McfdCompatMod.MOD_ID)
public final class McfdAuditEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private McfdAuditEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(final ServerStartedEvent event) {
        final MinecraftServer server = event.getServer();
        final Map<String, Integer> usedCrafters = new LinkedHashMap<>();
        final List<String> problems = new ArrayList<>(McfdCompatAudit.auditResources(server, usedCrafters));
        problems.addAll(McfdCompatAudit.auditParsedRecipes());
        problems.addAll(McfdCompatAudit.auditHooks());
        problems.addAll(McfdCompatAudit.auditRegistries());

        if (problems.isEmpty()) {
            final int total = usedCrafters.values().stream().mapToInt(Integer::intValue).sum();
            LOGGER.info("[{}] Compatibility audit passed: {} resource recipes, crafters {}", McfdCompatMod.MOD_ID, total, usedCrafters);
        } else {
            problems.forEach(problem -> LOGGER.error("[{}] Compatibility audit problem: {}", McfdCompatMod.MOD_ID, problem));
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mcfd_audit")
            .requires(source -> source.hasPermission(2))
            .executes(context -> runFullAudit(context.getSource()))
        );
    }

    private static int runFullAudit(final CommandSourceStack source) {
        final List<String> problems = new ArrayList<>();
        final Map<String, Integer> usedCrafters = new LinkedHashMap<>();

        problems.addAll(McfdCompatAudit.auditResources(source.getServer(), usedCrafters));
        problems.addAll(McfdCompatAudit.auditParsedRecipes());
        problems.addAll(McfdCompatAudit.auditHooks());
        problems.addAll(McfdCompatAudit.auditRegistries());
        problems.addAll(McfdCompatAudit.auditFoodValues());
        problems.addAll(McfdCompatAudit.auditEquivalence());

        final ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.literal("[mcfd_compat] Live recipe crafting needs a player; run /mcfd_audit in-game."),
                false);
        } else {
            problems.addAll(McfdCompatAudit.auditLiveCrafting((ServerLevel) player.level(), player));
        }

        final StringBuilder summary = new StringBuilder();
        usedCrafters.forEach((crafter, count) -> summary.append(crafter).append('(').append(count).append(") "));
        source.sendSuccess(() -> Component.literal("[mcfd_compat] Recipe crafters: " + summary.toString().trim()), false);

        if (problems.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "[mcfd_compat] Compatibility audit: PASS (" + usedCrafters.size() + " crafters used)"), false);
            return 1;
        }

        source.sendFailure(Component.literal("[mcfd_compat] Compatibility audit: " + problems.size() + " problem(s)"));
        problems.forEach(problem -> source.sendFailure(Component.literal("[mcfd_compat] - " + problem)));
        return 0;
    }
}
