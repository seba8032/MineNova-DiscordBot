package com.minenova.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;

import java.awt.Color;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TicketSystem extends ListenerAdapter {

    private final Map<Long, Long> ticketCreators = new ConcurrentHashMap<>();
    private final Map<Long, String> ticketCategories = new ConcurrentHashMap<>();
    private final String staffRoleId;
    private final String ticketCategoryId;

    public TicketSystem(String staffRoleId, String ticketCategoryId) {
        this.staffRoleId = staffRoleId;
        this.ticketCategoryId = ticketCategoryId;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("ticket")) return;
        String sub = event.getSubcommandName();
        if (sub == null) return;

        switch (sub) {
            case "create" -> {
                if (event.getMember() == null) return;
                showCategoryMenu(event.getMember(), event);
            }
            case "close" -> {
                if (event.getMember() == null || event.getChannel() == null) return;
                closeTicket(event.getChannel().asTextChannel(), event.getMember(), event);
            }
            case "add" -> {
                if (event.getOption("user") == null || event.getChannel() == null) return;
                var target = event.getOption("user").getAsMember();
                if (target == null) return;
                event.getChannel().asTextChannel()
                    .upsertPermissionOverride(target)
                    .grant(EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY))
                    .queue();
                event.reply(target.getAsMention() + " has been added to this ticket.").setEphemeral(true).queue();
            }
            case "remove" -> {
                if (event.getOption("user") == null || event.getChannel() == null) return;
                var target = event.getOption("user").getAsMember();
                if (target == null) return;
                event.getChannel().asTextChannel()
                    .upsertPermissionOverride(target)
                    .deny(EnumSet.of(Permission.VIEW_CHANNEL))
                    .clear(Permission.MESSAGE_SEND)
                    .queue();
                event.reply(target.getAsMention() + " has been removed from this ticket.").setEphemeral(true).queue();
            }
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("ticket_category_")) return;
        if (event.getMember() == null) return;

        String categoryLabel = event.getSelectedOptions().get(0).getLabel();
        String categoryValue = event.getSelectedOptions().get(0).getValue();

        String categoryEmoji = event.getSelectedOptions().get(0).getEmoji() != null
            ? event.getSelectedOptions().get(0).getEmoji().getName() : "";

        event.reply("Ticket created! Check your new channel.")
            .setEphemeral(true)
            .queue();

        createTicketWithCategory(event.getMember(), categoryLabel, categoryEmoji, categoryValue, event);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponent().getId();
        if (id == null) return;

        switch (id) {
            case "ticket_create" -> {
                if (event.getMember() == null) return;
                showCategoryMenu(event.getMember(), event);
            }
            case "ticket_close" -> {
                if (event.getChannel() == null || event.getMember() == null) return;
                closeTicket(event.getChannel().asTextChannel(), event.getMember(), event);
            }
            case "ticket_claim" -> {
                if (event.getMember() == null) return;
                EmbedBuilder eb = new EmbedBuilder();
                eb.setTitle("Ticket Claimed");
                eb.setColor(new Color(0x5865F2));
                eb.setDescription("Claimed by " + event.getMember().getAsMention());
                eb.setTimestamp(Instant.now());
                event.replyEmbeds(eb.build()).setEphemeral(true).queue();
                event.getChannel().sendMessageEmbeds(eb.build()).queue();
            }
        }
    }

    private void showCategoryMenu(net.dv8tion.jda.api.entities.Member member, Object event) {
        StringSelectMenu menu = StringSelectMenu.create("ticket_category_" + member.getId())
            .setPlaceholder("Select a reason for the ticket...")
            .setMinValues(1)
            .setMaxValues(1)
            .addOption("Bug Report", "bug", "🐛 Report a bug or technical issue")
            .addOption("Suggestion", "suggestion", "💡 Share an idea to improve MineNova")
            .addOption("General Support", "general", "❓ Ask questions or get help")
            .addOption("Player Report", "report", "🚨 Report a player breaking the rules")
            .addOption("Technical Help", "technical", "⚙️ Problems with the server, launcher, or connection")
            .addOption("Partnership", "partnership", "🤝 Partnership inquiry or collaboration")
            .build();

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🎫 Open a Ticket");
        embed.setColor(new Color(0x5865F2));
        embed.setDescription("Please select the reason for opening a ticket from the menu below.");
        embed.setTimestamp(Instant.now());

        if (event instanceof SlashCommandInteractionEvent slashEvent) {
            slashEvent.replyEmbeds(embed.build())
                .addComponents(ActionRow.of(menu))
                .setEphemeral(true)
                .queue();
        } else if (event instanceof ButtonInteractionEvent buttonEvent) {
            buttonEvent.replyEmbeds(embed.build())
                .addComponents(ActionRow.of(menu))
                .setEphemeral(true)
                .queue();
        }
    }

    private void createTicketWithCategory(net.dv8tion.jda.api.entities.Member member,
                                           String categoryLabel, String categoryEmoji,
                                           String categoryValue,
                                           StringSelectInteractionEvent event) {
        if (ticketCategoryId == null || ticketCategoryId.isEmpty()) return;

        var guild = member.getGuild();
        var category = guild.getCategoryById(ticketCategoryId);
        if (category == null) return;

        String name = "ticket-" + member.getUser().getName().toLowerCase().replace(" ", "-");

        category.createTextChannel(name)
            .setTopic("Ticket from " + member.getUser().getAsTag() + " | Category: " + categoryLabel)
            .addPermissionOverride(guild.getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL))
            .addPermissionOverride(member, EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY), null)
            .queue(ch -> {
                ticketCreators.put(ch.getIdLong(), member.getIdLong());
                ticketCategories.put(ch.getIdLong(), categoryValue);

                EmbedBuilder welcome = new EmbedBuilder();
                welcome.setTitle("🎫 Welcome to the MineNova Support Ticket!");
                welcome.setColor(new Color(0x5865F2));
                welcome.setDescription(
                    "Hello and welcome to MineNova! 👋\n\n" +
                    "Please describe your issue or question as clearly as possible so our team can help you quickly.\n\n" +
                    "**📝 Information to include:**\n" +
                    "**👤 Minecraft Username:**\n" +
                    "**📌 Type of issue:** " + categoryLabel + "\n" +
                    "**📝 Detailed description:**\n" +
                    "**📸 Screenshots or videos:** (if available)\n\n" +
                    "**📂 Ticket Category**\n" +
                    categoryEmoji + " **" + categoryLabel + "**\n\n" +
                    "**⏳ Please wait patiently for a staff member to respond.**\n\n" +
                    "**⚠️ Rules:**\n" +
                    "• Do not spam\n" +
                    "• Do not ping staff members repeatedly\n" +
                    "• Do not create multiple tickets about the same issue\n\n" +
                    "Thank you for being part of MineNova! 💙"
                );
                welcome.setFooter("Ticket ID: " + ch.getId());
                welcome.setTimestamp(Instant.now());

                Button closeBtn = Button.danger("ticket_close", "Close Ticket");
                Button claimBtn = Button.primary("ticket_claim", "Claim Ticket");

                ch.sendMessageEmbeds(welcome.build())
                    .addComponents(ActionRow.of(closeBtn, claimBtn))
                    .queue();
            });
    }

    private void createTicket(net.dv8tion.jda.api.entities.Member member, Object event) {
        if (ticketCategoryId == null || ticketCategoryId.isEmpty()) return;

        var guild = member.getGuild();
        var category = guild.getCategoryById(ticketCategoryId);
        if (category == null) return;

        String name = "ticket-" + member.getUser().getName().toLowerCase().replace(" ", "-");

        category.createTextChannel(name)
            .setTopic("Ticket from " + member.getUser().getAsTag())
            .addPermissionOverride(guild.getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL))
            .addPermissionOverride(member, EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY), null)
            .queue(ch -> {
                ticketCreators.put(ch.getIdLong(), member.getIdLong());

                EmbedBuilder welcome = new EmbedBuilder();
                welcome.setTitle("🎫 Welcome to the MineNova Support Ticket!");
                welcome.setColor(new Color(0x5865F2));
                welcome.setDescription(
                    "Hello and welcome to MineNova! 👋\n\n" +
                    "Please describe your issue or question as clearly as possible so our team can help you quickly.\n\n" +
                    "**📝 Information to include:**\n" +
                    "**👤 Minecraft Username:**\n" +
                    "**📌 Type of issue:** (Bug, Report, Question, Suggestion, Other)\n" +
                    "**📝 Detailed description:**\n" +
                    "**📸 Screenshots or videos:** (if available)\n\n" +
                    "**📂 Ticket Categories**\n" +
                    "🐛 **Bug Report** – Report a bug or technical issue.\n" +
                    "💡 **Suggestion** – Share an idea to improve MineNova.\n" +
                    "❓ **General Support** – Ask questions or get help.\n" +
                    "🚨 **Player Report** – Report a player breaking the rules.\n" +
                    "⚙️ **Technical Help** – Problems with the server, launcher, or connection.\n\n" +
                    "**⏳ Please wait patiently for a staff member to respond.**\n\n" +
                    "**⚠️ Do not spam, ping staff members repeatedly, or create multiple tickets about the same issue.**\n\n" +
                    "Thank you for being part of MineNova! 💙"
                );
                welcome.setTimestamp(Instant.now());

                Button closeBtn = Button.danger("ticket_close", "Close Ticket");
                Button claimBtn = Button.primary("ticket_claim", "Claim Ticket");

                ch.sendMessageEmbeds(welcome.build())
                    .addComponents(ActionRow.of(closeBtn, claimBtn))
                    .queue();
            });

        if (event instanceof SlashCommandInteractionEvent slashEvent) {
            slashEvent.reply("Ticket created! Check your channels.").setEphemeral(true).queue();
        } else if (event instanceof ButtonInteractionEvent buttonEvent) {
            buttonEvent.reply("Ticket created!").setEphemeral(true).queue();
        }
    }

    private void closeTicket(TextChannel channel, net.dv8tion.jda.api.entities.Member closer, Object event) {
        if (channel == null || closer == null) return;

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("Ticket Closed");
        embed.setColor(new Color(0xED4245));
        embed.setDescription("Closed by " + closer.getAsMention());
        embed.setTimestamp(Instant.now());

        channel.sendMessageEmbeds(embed.build()).queue();
        channel.sendMessage("Channel will be deleted in 10 seconds...").queue();
        channel.delete().queueAfter(10, java.util.concurrent.TimeUnit.SECONDS);

        if (event instanceof SlashCommandInteractionEvent slashEvent) {
            slashEvent.replyEmbeds(embed.build()).setEphemeral(true).queue();
        } else if (event instanceof ButtonInteractionEvent buttonEvent) {
            buttonEvent.replyEmbeds(embed.build()).setEphemeral(true).queue();
        }
    }

    public static void registerCommands(net.dv8tion.jda.api.JDA jda, String guildId) {
        net.dv8tion.jda.api.interactions.commands.build.SlashCommandData cmd =
            net.dv8tion.jda.api.interactions.commands.build.Commands.slash("ticket", "Manage support tickets")
                .addSubcommands(
                    new SubcommandData("create", "Open a new ticket"),
                    new SubcommandData("close", "Close the current ticket"),
                    new SubcommandData("add", "Add a user to the ticket")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "user", "User to add", true),
                    new SubcommandData("remove", "Remove a user from the ticket")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "user", "User to remove", true)
                );
        if (!guildId.isEmpty()) {
            var guild = jda.getGuildById(guildId);
            if (guild != null) {
                guild.updateCommands().addCommands(cmd).queue();
                return;
            }
        }
        jda.updateCommands().addCommands(cmd).queue();
    }

    public static void sendWelcomeEmbed(TextChannel channel) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🎫 Welcome to the MineNova Support Ticket!");
        embed.setColor(new Color(0x5865F2));
        embed.setDescription(
            "Hello and welcome to MineNova! 👋\n\n" +
            "Need help? Open a ticket and our team will assist you!\n\n" +
            "**📝 Information to include:**\n" +
            "• 👤 Minecraft Username\n" +
            "• 📌 Type of issue (Bug, Report, Question, Suggestion, Other)\n" +
            "• 📝 Detailed description\n" +
            "• 📸 Screenshots or videos (if available)\n\n" +
            "**📂 Ticket Categories:**\n" +
            "🐛 **Bug Report** – Report a bug or technical issue\n" +
            "💡 **Suggestion** – Share an idea to improve MineNova\n" +
            "❓ **General Support** – Ask questions or get help\n" +
            "🚨 **Player Report** – Report a player breaking the rules\n" +
            "⚙️ **Technical Help** – Problems with the server, launcher, or connection\n" +
            "🤝 **Partnership** – Partnership inquiry or collaboration\n\n" +
            "**⏳ Please wait patiently for a staff member to respond.**\n\n" +
            "**⚠️ Rules:**\n" +
            "• Do not spam\n" +
            "• Do not ping staff members repeatedly\n" +
            "• Do not create multiple tickets about the same issue\n\n" +
            "Thank you for being part of MineNova! 💙"
        );
        embed.setFooter("MineNova Support");
        embed.setTimestamp(Instant.now());

        Button createBtn = Button.success("ticket_create", "Create Ticket");

        channel.sendMessageEmbeds(embed.build())
            .addComponents(ActionRow.of(createBtn))
            .queue();
    }
}
