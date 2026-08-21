package com.minenova.discord;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;

public class BotMain {

    private static BotMain instance;
    private final Map<String, String> config = new LinkedHashMap<>();

    public static void main(String[] args) {
        instance = new BotMain();
        instance.start();
    }

    private void start() {
        loadConfig();

        String token = env("DISCORD_TOKEN", config.getOrDefault("discord.token", ""));
        String aiEndpoint = env("AI_ENDPOINT", config.getOrDefault("ai.endpoint", "https://generativelanguage.googleapis.com/v1beta/openai"));
        String aiKey = env("AI_API_KEY", config.getOrDefault("ai.api-key", ""));
        String aiModel = env("AI_MODEL", config.getOrDefault("ai.model", "gemini-3.6-flash"));
        String allowedChannel = env("DISCORD_CHANNEL_ID", config.getOrDefault("discord.channel-id", ""));
        String guildId = env("DISCORD_GUILD_ID", config.getOrDefault("discord.guild-id", ""));

        boolean ticketsEnabled = Boolean.parseBoolean(env("TICKETS_ENABLED", config.getOrDefault("tickets.enabled", "true")));
        String staffRoleId = env("TICKETS_STAFF_ROLE_ID", config.getOrDefault("tickets.staff-role-id", ""));
        String ticketCategoryId = env("TICKETS_CATEGORY_ID", config.getOrDefault("tickets.category-id", ""));
        String welcomeChannelId = env("TICKETS_WELCOME_CHANNEL_ID", config.getOrDefault("tickets.welcome-channel-id", ""));

        boolean automodEnabled = Boolean.parseBoolean(env("AUTOMOD_ENABLED", config.getOrDefault("automod.enabled", "true")));
        String ownerId = env("DISCORD_OWNER_ID", config.getOrDefault("discord.owner-id", ""));
        int maxUsesPerUser = Integer.parseInt(env("AI_MAX_USES_PER_USER", config.getOrDefault("ai.max-uses-per-user", "20")));

        if (token.isBlank()) {
            System.err.println("[BotMain] Discord token not set! Set DISCORD_TOKEN env var or fill config.yml");
            System.exit(1);
            return;
        }

        AIService aiService = new AIService(aiEndpoint, aiKey, aiModel);
        System.out.println("[BotMain] AI endpoint: " + aiEndpoint);
        System.out.println("[BotMain] AI model: " + aiModel);
        System.out.println("[BotMain] AI key: " + (aiKey.length() > 10 ? aiKey.substring(0, 10) + "..." : "EMPTY"));
        System.out.println("[BotMain] AI owner: " + (ownerId.isEmpty() ? "NONE (no limits)" : ownerId));
        System.out.println("[BotMain] AI max uses/user: " + (maxUsesPerUser == 0 ? "UNLIMITED" : maxUsesPerUser));
        ChatHandler chatHandler = new ChatHandler(aiService, allowedChannel, ownerId, maxUsesPerUser, loadResource("config-reference.txt"));

        try {
            net.dv8tion.jda.api.JDABuilder builder = net.dv8tion.jda.api.JDABuilder.createDefault(token)
                .enableIntents(net.dv8tion.jda.api.requests.GatewayIntent.GUILD_MESSAGES,
                    net.dv8tion.jda.api.requests.GatewayIntent.MESSAGE_CONTENT,
                    net.dv8tion.jda.api.requests.GatewayIntent.GUILD_MEMBERS)
                .setMemberCachePolicy(net.dv8tion.jda.api.utils.MemberCachePolicy.ALL)
                .setStatus(net.dv8tion.jda.api.OnlineStatus.ONLINE)
                .setActivity(net.dv8tion.jda.api.entities.Activity.playing("/ai | MineNova-AI"));

            builder.addEventListeners(chatHandler);

            if (ticketsEnabled) {
                TicketSystem ticketSystem = new TicketSystem(staffRoleId, ticketCategoryId);
                builder.addEventListeners(ticketSystem);
                System.out.println("[BotMain] Tickets enabled (category: " + ticketCategoryId + ")");
            }

            if (automodEnabled) {
                AutoMod autoMod = AutoMod.fromConfig(config);
                builder.addEventListeners(autoMod);
                System.out.println("[BotMain] AutoMod enabled");
            }

            var jda = builder.build();
            jda.awaitReady();

            if (guildId.isEmpty() && !welcomeChannelId.isEmpty()) {
                var ch = jda.getChannelById(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class, welcomeChannelId);
                if (ch != null) guildId = ch.getGuild().getId();
            }
            if (guildId.isEmpty() && !jda.getGuilds().isEmpty()) {
                guildId = jda.getGuilds().get(0).getId();
            }

            ChatHandler.registerCommands(jda, guildId);
            System.out.println("[BotMain] Chat slash commands registered" + (guildId.isEmpty() ? " (global)" : " (guild)"));

            if (ticketsEnabled) {
                TicketSystem.registerCommands(jda, guildId);
                System.out.println("[BotMain] Ticket slash commands registered" + (guildId.isEmpty() ? " (global)" : " (guild)"));

                if (!welcomeChannelId.isEmpty()) {
                    try {
                        var channel = jda.getChannelById(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class, welcomeChannelId);
                        if (channel != null) {
                            TicketSystem.sendWelcomeEmbed(channel);
                            System.out.println("[BotMain] Ticket welcome embed sent to: " + welcomeChannelId);
                        } else {
                            System.err.println("[BotMain] Welcome channel not found: " + welcomeChannelId);
                        }
                    } catch (Exception e) {
                        System.err.println("[BotMain] Failed to send welcome embed: " + e.getMessage());
                        System.err.println("[BotMain] Make sure bot has SEND_MESSAGES permission on the welcome channel!");
                    }
                }
            }

            System.out.println("[BotMain] Bot logged in successfully!");
            System.out.println("[BotMain] Listening on channel: " + (allowedChannel.isEmpty() ? "ALL" : allowedChannel));
        } catch (Exception e) {
            System.err.println("[BotMain] Failed to start bot: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void loadConfig() {
        Path configPath = Path.of("config.yml");
        if (!Files.exists(configPath)) {
            try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                if (in != null) {
                    Files.copy(in, configPath);
                    System.out.println("[BotMain] Created default config.yml");
                }
            } catch (IOException e) {
                System.err.println("[BotMain] Failed to create default config: " + e.getMessage());
            }
        }

        try {
            java.util.Deque<String> keyStack = new java.util.ArrayDeque<>();
            for (String line : Files.readAllLines(configPath)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                int spaces = 0;
                for (int i = 0; i < line.length(); i++) {
                    if (line.charAt(i) == ' ') spaces++;
                    else break;
                }

                int colonIdx = trimmed.indexOf(':');
                if (colonIdx < 0) continue;

                String keyPart = trimmed.substring(0, colonIdx).trim();
                String value = trimmed.substring(colonIdx + 1).trim();

                while (!keyStack.isEmpty() && keyStack.size() * 2 > spaces) {
                    keyStack.removeLast();
                }

                if (value.isEmpty()) {
                    keyStack.addLast(keyPart);
                    continue;
                }

                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1);
                }

                StringBuilder fullKey = new StringBuilder();
                for (String k : keyStack) {
                    fullKey.append(k).append(".");
                }
                fullKey.append(keyPart);

                config.put(fullKey.toString(), value);
            }
            System.out.println("[BotMain] Config loaded (" + config.size() + " keys)");
        } catch (IOException e) {
            System.err.println("[BotMain] Failed to load config.yml: " + e.getMessage());
        }
    }

    public static BotMain getInstance() { return instance; }
    public Map<String, String> getConfig() { return config; }

    public static String loadResource(String name) {
        try (InputStream in = BotMain.class.getResourceAsStream("/" + name)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[BotMain] Failed to load resource: " + name);
            return "";
        }
    }

    private static String env(String key, String fallback) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : fallback;
    }
}
