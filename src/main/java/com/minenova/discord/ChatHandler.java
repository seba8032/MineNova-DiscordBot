package com.minenova.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.awt.Color;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class ChatHandler extends ListenerAdapter {

    private final AIService aiService;
    private final String allowedChannelId;
    private final String ownerId;
    private final int maxUsesPerUser;
    private final String configReference;
    private final Map<Long, CompletableFuture<?>> activeRequests = new ConcurrentHashMap<>();
    private final Map<Long, List<Map<String, String>>> conversationHistory = new ConcurrentHashMap<>();
    private final Map<Long, Integer> usageCount = new ConcurrentHashMap<>();

    public ChatHandler(AIService aiService, String allowedChannelId, String ownerId, int maxUsesPerUser, String configReference) {
        this.aiService = aiService;
        this.allowedChannelId = allowedChannelId;
        this.ownerId = ownerId;
        this.maxUsesPerUser = maxUsesPerUser;
        this.configReference = configReference;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getMember() == null) return;

        switch (event.getName()) {
            case "ai" -> handleAI(event);
            case "help" -> sendHelp(event);
            case "commands" -> sendCommands(event);
            case "features" -> sendFeatures(event);
            case "config" -> sendConfig(event);
        }
    }

    private void handleAI(SlashCommandInteractionEvent event) {
        if (!aiService.isConfigured()) {
            event.reply("AI service is not configured.").setEphemeral(true).queue();
            return;
        }

        String channelId = event.getChannel().getId();
        if (!allowedChannelId.isEmpty() && !channelId.equals(allowedChannelId)) {
            event.reply("You can use AI only in the designated channel.").setEphemeral(true).queue();
            return;
        }

        String question = event.getOption("question") != null ? event.getOption("question").getAsString() : "";
        if (question.isBlank()) {
            event.reply("Please provide a question.").setEphemeral(true).queue();
            return;
        }

        long userId = event.getUser().getIdLong();

        boolean isOwner = ownerId.isEmpty() || event.getUser().getId().equals(ownerId);
        if (!isOwner && maxUsesPerUser > 0) {
            int used = usageCount.getOrDefault(userId, 0);
            if (used >= maxUsesPerUser) {
                event.reply("You have reached the daily AI usage limit (" + maxUsesPerUser + " uses).")
                    .setEphemeral(true).queue();
                return;
            }
        }

        long channelIdLong = event.getChannel().getIdLong();
        if (activeRequests.containsKey(channelIdLong)) {
            event.reply("Please wait for the previous answer...").setEphemeral(true).queue();
            return;
        }

        event.reply(" ").setEphemeral(true).queue();

        List<Map<String, String>> history = conversationHistory.computeIfAbsent(userId, k -> new ArrayList<>());
        String systemPrompt = getSystemPrompt();

        CompletableFuture<?> future = aiService.askWithContextAsync(systemPrompt, question, history)
            .thenAccept(response -> {
                activeRequests.remove(channelIdLong);

                history.add(Map.of("role", "user", "content", question));
                history.add(Map.of("role", "assistant", "content", response));
                if (history.size() > 20) {
                    history.subList(0, history.size() - 20).clear();
                }

                if (!isOwner && maxUsesPerUser > 0) {
                    usageCount.merge(userId, 1, Integer::sum);
                }

                String cleaned = stripThinkTags(response);
                List<String> parts = splitMessage(cleaned, 4096);

                EmbedBuilder firstEmbed = new EmbedBuilder();
                firstEmbed.setTitle("MineNova-AI Bot", null);
                firstEmbed.setColor(new Color(0x5865F2));
                firstEmbed.setDescription(parts.get(0));
                if (parts.size() == 1) {
                    firstEmbed.setFooter("Question: " + question, null);
                } else {
                    firstEmbed.setFooter("1/" + parts.size() + " • Question: " + question, null);
                }
                firstEmbed.setTimestamp(Instant.now());
                event.getHook().editOriginalEmbeds(firstEmbed.build()).queue();

                for (int i = 1; i < parts.size(); i++) {
                    int idx = i;
                    EmbedBuilder partEmbed = new EmbedBuilder();
                    partEmbed.setColor(new Color(0x5865F2));
                    partEmbed.setDescription(parts.get(i));
                    partEmbed.setFooter((idx + 1) + "/" + parts.size(), null);
                    event.getHook().sendMessageEmbeds(partEmbed.build()).setEphemeral(true).queue();
                }
            })
            .exceptionally(ex -> {
                activeRequests.remove(channelIdLong);
                EmbedBuilder err = new EmbedBuilder();
                err.setTitle("Error", null);
                err.setColor(new Color(0xED4245));
                err.setDescription("AI Error: " + ex.getMessage());
                event.getHook().editOriginalEmbeds(err.build()).queue();
                return null;
            });
        activeRequests.put(channelIdLong, future);
    }

    private void sendHelp(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("MineNova-AI Bot — Help", null);
        embed.setColor(new Color(0x57F287));
        embed.setDescription("Bot answers questions about the **MineNova-AI** plugin.\n\n" +
            "**Commands:**\n" +
            "`/ai <question>` — Ask about the plugin\n" +
            "`/commands` — Plugin commands list\n" +
            "`/features` — Plugin features list\n" +
            "`/config` — Configuration info\n" +
            "`/help` — This message\n\n" +
            "**Examples:**\n" +
            "`/ai How to install MineNova-AI?`\n" +
            "`/ai What AI models are supported?`\n" +
            "`/ai How to configure the Discord bot?`");
        embed.setFooter("MineNova-AI Bot • v1.0.0");
        embed.setTimestamp(Instant.now());
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    private void sendCommands(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("MineNova-AI — Commands", null);
        embed.setColor(new Color(0xFEE75C));
        embed.setDescription("**Main:**\n" +
            "`/minenova` or `/mn` — Main plugin command\n" +
            "`/ai <message>` — Ask AI\n\n" +
            "**Admin:**\n" +
            "`/mn setup` — Configuration wizard\n" +
            "`/mn provider` — AI provider selection\n" +
            "`/mn model` — AI model selection\n" +
            "`/mn reload` — Reload configuration\n" +
            "`/mn info` — Plugin info\n\n" +
            "**Memory & Knowledge:**\n" +
            "`/mn memory` — AI memory management\n" +
            "`/mn knowledge` — AI knowledge base\n" +
            "`/mn members` — Server member list\n\n" +
            "**Tools:**\n" +
            "`/mn tools` — AI tools list\n" +
            "`/mn history` — Conversation history\n" +
            "`/mn logs` — Server logs\n\n" +
            "**Security:**\n" +
            "`/mn api-key` — API key management\n" +
            "`/mn confirm` — Confirm AI action\n" +
            "`/mn cancel` — Cancel AI action");
        embed.setFooter("MineNova-AI Bot • v1.0.0");
        embed.setTimestamp(Instant.now());
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    private void sendFeatures(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("MineNova-AI — Features", null);
        embed.setColor(new Color(0x5865F2));
        embed.setDescription("**Advanced AI Chat:**\n" +
            "Talk to AI directly on the server. Supports multiple languages, remembers conversation context.\n\n" +
            "**Multiple AI Providers:**\n" +
            "OpenAI, Claude, Gemini, Ollama, LM Studio and more. Automatic fallback between providers.\n\n" +
            "**Tool System:**\n" +
            "AI can execute commands, read/write files, build structures, plan tasks.\n\n" +
            "**Memory Management:**\n" +
            "AI remembers player info, server settings and preferences. Knowledge base and conversation history.\n\n" +
            "**GUI (Graphical Panel):**\n" +
            "Inventory-based interface for managing providers, models, memory and configuration.\n\n" +
            "**Discord Bot:**\n" +
            "Server notifications, status, chat between server and Discord.\n\n" +
            "**Setup Wizard:**\n" +
            "Automatic configuration wizard on first run.\n\n" +
            "**Permissions & Security:**\n" +
            "Role-based access control, dangerous command blocking, audit logs.\n\n" +
            "**Custom Instructions:**\n" +
            "Configurable AI personality for each role (owner, admin, player).\n\n" +
            "**Compatibility:**\n" +
            "Paper 1.20.4+, Spigot, Purpur. Java 17+.");
        embed.setFooter("MineNova-AI Bot • v1.0.0");
        embed.setTimestamp(Instant.now());
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    private void sendConfig(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("MineNova-AI — Configuration", null);
        embed.setColor(new Color(0xEB459E));
        embed.setDescription("**Config files:**\n" +
            "`config.yml` — Main settings (database, logging)\n" +
            "`ai.yml` — AI providers, models, endpoints\n" +
            "`gui.yml` — GUI settings\n" +
            "`permissions.yml` — Player permissions\n" +
            "`discord_bot.yml` — Discord bot integration\n" +
            "`ai-instructions.yml` — Custom AI instructions\n" +
            "`npc.yml` — NPC system (NPC version only)\n\n" +
            "**Basic setup:**\n" +
            "1. Run `/mn setup` on server\n" +
            "2. Choose AI provider (e.g. OpenAI)\n" +
            "3. Enter API key\n" +
            "4. Choose AI model\n" +
            "5. Done! Type `AI <question>` in chat\n\n" +
            "**AI Configuration:**\n" +
            "Edit `plugins/MineNova-AI/ai.yml` to add custom AI providers with any OpenAI-compatible endpoint.");
        embed.setFooter("MineNova-AI Bot • v1.0.0");
        embed.setTimestamp(Instant.now());
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    public void clearHistory(long userId) {
        conversationHistory.remove(userId);
    }

    public void resetDailyUsage() {
        usageCount.clear();
    }

    private List<String> splitMessage(String text, int maxLen) {
        List<String> parts = new ArrayList<>();
        while (text.length() > maxLen) {
            int splitIdx = text.lastIndexOf("\n\n", maxLen);
            if (splitIdx <= 0) splitIdx = text.lastIndexOf("\n", maxLen);
            if (splitIdx <= 0) splitIdx = text.lastIndexOf(". ", maxLen);
            if (splitIdx <= 0) splitIdx = text.lastIndexOf(" ", maxLen);
            if (splitIdx <= 0) splitIdx = maxLen;
            else splitIdx += 1;
            parts.add(text.substring(0, splitIdx).trim());
            text = text.substring(splitIdx).trim();
        }
        if (!text.isEmpty()) parts.add(text);
        return parts.isEmpty() ? List.of("") : parts;
    }

    private String getSystemPrompt() {
        return "You are a helpful bot that answers questions about **MineNova** — the Minecraft plugin, the Discord bot, and the website (https://minenova.eu).\n" +
            "Be accurate, concise, and use Discord formatting (bold, code blocks).\n" +
            "If you don't know the answer, say \"I don't have that information\" — never guess.\n" +
            "Answer in the same language as the question.\n\n" +
            "You have access to the exact contents of ALL plugin config files AND website/pricing info.\n" +
            "When a user asks about configuration, limits, permissions, features, pricing, or the website,\n" +
            "reference the EXACT values below. Quote config keys and values precisely.\n\n" +
            "MineNova website: https://minenova.eu\n" +
            "Initial price: $29.99 USD. After more interest: $44.99 USD.\n\n" +
            "═══════════════════════════════════════════════════════════════\n" +
            "# PLUGIN CONFIG REFERENCE\n" +
            "═══════════════════════════════════════════════════════════════\n\n" +
            configReference;
    }

    public static void registerCommands(net.dv8tion.jda.api.JDA jda, String guildId) {
        var commands = jda.updateCommands().addCommands(
            Commands.slash("ai", "Ask the MineNova-AI bot a question")
                .addOption(OptionType.STRING, "question", "Your question about MineNova-AI", true),
            Commands.slash("help", "Show help and available commands"),
            Commands.slash("commands", "List all plugin commands"),
            Commands.slash("features", "List all plugin features"),
            Commands.slash("config", "Show configuration information")
        );
        if (!guildId.isEmpty()) {
            var guild = jda.getGuildById(guildId);
            if (guild != null) {
                commands = guild.updateCommands().addCommands(
                    Commands.slash("ai", "Ask the MineNova-AI bot a question")
                        .addOption(OptionType.STRING, "question", "Your question about MineNova-AI", true),
                    Commands.slash("help", "Show help and available commands"),
                    Commands.slash("commands", "List all plugin commands"),
                    Commands.slash("features", "List all plugin features"),
                    Commands.slash("config", "Show configuration information")
                );
            }
        }
        commands.queue();
    }

    private String stripThinkTags(String text) {
        if (text == null) return "";
        int start = text.indexOf("<think>");
        if (start == -1) return text.trim();
        int end = text.indexOf("</think>", start);
        if (end == -1) return text.trim();
        String after = text.substring(end + "</think>".length());
        return after.trim();
    }
}
