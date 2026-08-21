package com.minenova.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.awt.Color;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AutoMod extends ListenerAdapter {

    private final Map<Long, List<Long>> spamTracker = new ConcurrentHashMap<>();
    private final Map<Long, Integer> violationCount = new ConcurrentHashMap<>();

    private final List<String> bannedWords;
    private final List<String> allowedLinks;
    private final int maxMessages;
    private final int spamWindowMs;
    private final int maxViolations;

    public AutoMod(List<String> bannedWords, List<String> allowedLinks,
                   int maxMessages, int spamWindowMs, int maxViolations) {
        this.bannedWords = bannedWords != null ? bannedWords : List.of();
        this.allowedLinks = allowedLinks != null ? allowedLinks : List.of();
        this.maxMessages = maxMessages;
        this.spamWindowMs = spamWindowMs;
        this.maxViolations = maxViolations;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (event.getMember() == null) return;
        if (event.getMember().hasPermission(Permission.MESSAGE_MANAGE)) return;

        String content = event.getMessage().getContentRaw();
        Member member = event.getMember();

        if (checkSpam(event, member)) return;
        if (checkBadWords(event, content, member)) return;
        if (checkLinks(event, content, member)) return;
    }

    private boolean checkSpam(MessageReceivedEvent event, Member member) {
        long userId = member.getIdLong();
        long now = System.currentTimeMillis();

        spamTracker.computeIfAbsent(userId, k -> new ArrayList<>());
        List<Long> timestamps = spamTracker.get(userId);
        timestamps.removeIf(t -> now - t > spamWindowMs);
        timestamps.add(now);

        if (timestamps.size() > maxMessages) {
            handleViolation(event, member, "Spam");
            return true;
        }
        return false;
    }

    private boolean checkBadWords(MessageReceivedEvent event, String content, Member member) {
        String lower = content.toLowerCase();
        for (String word : bannedWords) {
            if (!word.isBlank() && lower.contains(word.toLowerCase())) {
                handleViolation(event, member, "Zakazane słowo");
                return true;
            }
        }
        return false;
    }

    private boolean checkLinks(MessageReceivedEvent event, String content, Member member) {
        String lower = content.toLowerCase();
        boolean hasLink = lower.contains("http://") || lower.contains("https://") || lower.contains("www.");

        if (hasLink) {
            for (String allowed : allowedLinks) {
                if (!allowed.isBlank() && lower.contains(allowed.toLowerCase())) {
                    return false;
                }
            }
            handleViolation(event, member, "Linki zablokowane");
            return true;
        }
        return false;
    }

    private void handleViolation(MessageReceivedEvent event, Member member, String reason) {
        long userId = member.getIdLong();
        event.getMessage().delete().queue();

        int violations = violationCount.merge(userId, 1, Integer::sum);

        EmbedBuilder warn = new EmbedBuilder();
        warn.setTitle("AutoMod");
        warn.setColor(new Color(0xFEE75C));
        warn.setDescription(member.getAsMention() + ", przestań!");
        warn.addField("Powód", reason, false);
        warn.addField("Ostrzeżenia", violations + "/" + maxViolations, false);
        warn.setTimestamp(Instant.now());

        event.getChannel().sendMessageEmbeds(warn.build())
            .delay(5, java.util.concurrent.TimeUnit.SECONDS)
            .flatMap(Message::delete)
            .queue();

        if (violations >= maxViolations) {
            OffsetDateTime until = OffsetDateTime.now().plusMinutes(10);
            member.timeoutUntil(until).queue();

            EmbedBuilder mute = new EmbedBuilder();
            mute.setTitle("AutoMod — Wyciszony");
            mute.setColor(new Color(0xED4245));
            mute.setDescription(member.getAsMention() + " wyciszony na 10 min.");
            mute.setTimestamp(Instant.now());

            event.getChannel().sendMessageEmbeds(mute.build()).queue();
            violationCount.put(userId, 0);
        }
    }

    public static AutoMod fromConfig(Map<String, String> config) {
        String wordsStr = config.getOrDefault("automod.banned-words", "");
        String linksStr = config.getOrDefault("automod.allowed-links", "youtube.com,discord.com,github.com");
        int maxMsg = Integer.parseInt(config.getOrDefault("automod.max-messages", "5"));
        int window = Integer.parseInt(config.getOrDefault("automod.spam-window-ms", "3000"));
        int maxViol = Integer.parseInt(config.getOrDefault("automod.max-violations", "3"));

        List<String> words = wordsStr.isBlank() ? List.of() : Arrays.asList(wordsStr.split(","));
        List<String> links = linksStr.isBlank() ? List.of() : Arrays.asList(linksStr.split(","));

        return new AutoMod(words, links, maxMsg, window, maxViol);
    }
}
