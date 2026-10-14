package dev.zxin.unboundedheight.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.zxin.unboundedheight.Bridge;
import dev.zxin.unboundedheight.core.HeightLimits;
import dev.zxin.unboundedheight.core.HeightManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

//#if MC>=12111
// 1.21.11 / 26.x：原版把 int 权限等级换成了 PermissionSet 体系
import net.minecraft.server.permissions.Permissions;
//#endif

/**
 * {@code /height} 指令树。
 *
 * <pre>
 *   /height get                      查看所有维度的高度范围
 *   /height set &lt;维度ID&gt; &lt;min_y&gt; &lt;max_y&gt;   设置指定维度
 *   /height setall &lt;min_y&gt; &lt;max_y&gt;        设置所有维度
 *   /height reset &lt;维度ID&gt;            恢复指定维度的默认高度
 *   /height resetall                 恢复所有维度
 *   /height compat                   列出已注册维度并标记原版 / 模组
 *   /height reload                   重新读盘配置
 * </pre>
 *
 * <p>权限等级由配置 {@code command.permissionLevel} 决定（默认 2）。
 * 维度 ID 用字符串参数接收（而不是 {@code ResourceLocationArgument}），
 * 因为 1.21.11/26.x 把 {@code ResourceLocation} 改名成了 {@code Identifier}、
 * 参数类也随之变动 —— 字符串参数在 22 个目标上零差异。
 */
public final class HeightCommand {

    private HeightCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("height")
                .requires(HeightCommand::allowed)
                .executes(ctx -> help(ctx))
                .then(Commands.literal("get").executes(HeightCommand::get))
                .then(Commands.literal("set")
                        .then(Commands.argument("dimension", StringArgumentType.string())
                                .then(Commands.argument("min_y", IntegerArgumentType.integer())
                                        .then(Commands.argument("max_y", IntegerArgumentType.integer())
                                                .executes(HeightCommand::set)))))
                .then(Commands.literal("setall")
                        .then(Commands.argument("min_y", IntegerArgumentType.integer())
                                .then(Commands.argument("max_y", IntegerArgumentType.integer())
                                        .executes(HeightCommand::setAll))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("dimension", StringArgumentType.string())
                                .executes(HeightCommand::reset)))
                .then(Commands.literal("resetall").executes(HeightCommand::resetAll))
                .then(Commands.literal("compat").executes(HeightCommand::compat))
                .then(Commands.literal("reload").executes(HeightCommand::reload));
        dispatcher.register(root);
    }

    // ------------------------------------------------------------------ 权限

    private static boolean allowed(CommandSourceStack source) {
        int level = HeightManager.config().command.permissionLevel;
        if (level <= 0) {
            return true;
        }
//#if MC>=12111
        switch (level) {
            case 1:
                return source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
            case 2:
                return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
            case 3:
                return source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
            default:
                return source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
        }
//#else
        return source.hasPermission(level);
//#endif
    }

    // ------------------------------------------------------------------ 子指令

    private static int help(CommandContext<CommandSourceStack> ctx) {
        header(ctx);
        line(ctx, "get · 查看所有维度的高度范围", ChatFormatting.GRAY);
        line(ctx, "set <维度ID> <min_y> <max_y> · 设置指定维度", ChatFormatting.GRAY);
        line(ctx, "setall <min_y> <max_y> · 设置所有维度", ChatFormatting.GRAY);
        line(ctx, "reset <维度ID> · 恢复指定维度默认", ChatFormatting.GRAY);
        line(ctx, "resetall · 恢复所有维度默认", ChatFormatting.GRAY);
        line(ctx, "compat · 列出已注册维度并标记原版 / 模组", ChatFormatting.GRAY);
        line(ctx, "reload · 重新读取配置", ChatFormatting.GRAY);
        line(ctx, "结构硬限制: Y ∈ [" + HeightLimits.HARD_MIN_Y + ", " + HeightLimits.HARD_MAX_Y
                + "]，总高 ≤ " + HeightLimits.HARD_MAX_SPAN, ChatFormatting.DARK_GRAY);
        return 1;
    }

    private static int get(CommandContext<CommandSourceStack> ctx) {
        header(ctx);
        List<String[]> rows = HeightManager.snapshot();
        if (rows.isEmpty()) {
            line(ctx, "还没有登记到任何维度（世界尚未加载？）", ChatFormatting.YELLOW);
            return 0;
        }
        for (String[] row : rows) {
            ChatFormatting color = Boolean.parseBoolean(row[6]) ? ChatFormatting.GREEN : ChatFormatting.GRAY;
            line(ctx, row[0] + "  Y " + row[3] + " .. " + row[4]
                    + (Boolean.parseBoolean(row[6]) ? "  (原版 " + row[1] + ".." + row[2] + ")" : ""), color);
        }
        return rows.size();
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "dimension");
        int minY = IntegerArgumentType.getInteger(ctx, "min_y");
        int maxY = IntegerArgumentType.getInteger(ctx, "max_y");
        String result = HeightManager.setDimension(id, minY, maxY);
        line(ctx, result, result.startsWith("拒绝") ? ChatFormatting.RED : ChatFormatting.GREEN);
        sync(ctx);
        return result.startsWith("拒绝") ? 0 : 1;
    }

    private static int setAll(CommandContext<CommandSourceStack> ctx) {
        int minY = IntegerArgumentType.getInteger(ctx, "min_y");
        int maxY = IntegerArgumentType.getInteger(ctx, "max_y");
        String result = HeightManager.setAll(minY, maxY);
        line(ctx, result, result.startsWith("拒绝") ? ChatFormatting.RED : ChatFormatting.GREEN);
        line(ctx, "提示：已生成的区块不受影响；新高度范围在区块重新加载后生效。", ChatFormatting.YELLOW);
        sync(ctx);
        return result.startsWith("拒绝") ? 0 : 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "dimension");
        line(ctx, HeightManager.resetDimension(id), ChatFormatting.GREEN);
        sync(ctx);
        return 1;
    }

    private static int resetAll(CommandContext<CommandSourceStack> ctx) {
        line(ctx, HeightManager.resetAll(), ChatFormatting.GREEN);
        sync(ctx);
        return 1;
    }

    private static int compat(CommandContext<CommandSourceStack> ctx) {
        header(ctx);
        List<String[]> rows = HeightManager.snapshot();
        if (rows.isEmpty()) {
            line(ctx, "还没有登记到任何维度（世界尚未加载？）", ChatFormatting.YELLOW);
            return 0;
        }
        int vanilla = 0;
        int modded = 0;
        for (String[] row : rows) {
            boolean isVanilla = Boolean.parseBoolean(row[5]);
            boolean changed = Boolean.parseBoolean(row[6]);
            if (isVanilla) {
                vanilla++;
            } else {
                modded++;
            }
            String tag = isVanilla ? "[原版]" : "[模组/数据包]";
            String state = changed
                    ? "已扩展 → Y " + row[3] + " .. " + row[4]
                    : "原生 → Y " + row[1] + " .. " + row[2];
            line(ctx, tag + " " + row[0] + "  " + state,
                    changed ? ChatFormatting.GREEN : ChatFormatting.GRAY);
        }
        line(ctx, "共 " + rows.size() + " 个维度（原版 " + vanilla + " / 模组 " + modded + "）",
                ChatFormatting.AQUA);
        line(ctx, "策略: autoApply=" + HeightManager.config().compat.autoApplyToNewDimensions
                + " respectNative=" + HeightManager.config().compat.respectNativeHeight
                + " forceAll=" + HeightManager.config().compat.forceOverrideAll, ChatFormatting.DARK_GRAY);
        return rows.size();
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        HeightManager.reload();
        line(ctx, "配置已重载", ChatFormatting.GREEN);
        sync(ctx);
        return 1;
    }

    // ------------------------------------------------------------------ 输出工具

    private static void header(CommandContext<CommandSourceStack> ctx) {
        line(ctx, "=== 无界高度 Unbounded Height ===", ChatFormatting.GOLD);
    }

    private static void line(CommandContext<CommandSourceStack> ctx, String text, ChatFormatting color) {
        ctx.getSource().sendSuccess(() -> Component.literal(text).withStyle(color), false);
    }

    /** 配置变更后把新的高度表同步给所有在线玩家。 */
    private static void sync(CommandContext<CommandSourceStack> ctx) {
        Bridge.info("高度表已变更: " + HeightManager.snapshotText().replace('\n', ';'));
    }
}
