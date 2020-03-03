package com.kaigan.bots.narrator;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.requests.restaction.MessageAction;
import net.dv8tion.jda.api.utils.AttachmentOption;
import sengine.sheets.OnSheetEnded;
import sengine.sheets.ParseException;
import sengine.sheets.SheetFields;
import sengine.sheets.SheetStack;

import java.io.File;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;

@SheetFields(fields = { "message", "embed", "file" })
public class SheetMessageBuilder implements OnSheetEnded {

    @SheetFields(fields = { "color", "description",
            "titleMessage", "titleUrl",
            "timestamp", "footerMessage", "footerIconUrl",
            "thumbnail", "image",
            "authorName", "authorUrl", "authorIconUrl",
            "fields"
    })
    public static class EmbedConfig {

        @SheetFields(fields = { "name", "message", "inline" })
        public static class FieldConfig implements OnSheetEnded {
            public String name;
            public String message;
            public boolean inline;

            @Override
            public void onSheetEnded() {
                if((name == null) != (message == null))
                    throw new ParseException("Either both name and message must be set or unset");
            }
        }

        public int color = 11991952;
        public String description;

        public String titleMessage;
        public String titleUrl;

        public String timestamp;

        public String footerMessage;
        public String footerIconUrl;

        public String thumbnail;

        public String image;

        public String authorName;
        public String authorUrl;
        public String authorIconUrl;

        public FieldConfig[] fields;

        public void title(String title, String url) {
            this.titleMessage = title;
            this.titleUrl = url;
        }

        public void color(String color) {
            this.color = (int) Long.parseLong(color, 16);
        }

        public void footer(String message, String iconUrl) {
            this.footerMessage = message;
            this.footerIconUrl = iconUrl;
        }

        public void author(String author, String url, String iconUrl) {
            this.authorName = author;
            this.authorUrl = url;
            this.authorIconUrl = iconUrl;
        }

        private EmbedBuilder build(Narrator bot, Object ... params) {
            EmbedBuilder builder = new EmbedBuilder();

            builder.setColor(color);

            if(description != null)
                builder.setDescription(bot.format(description, params));

            if(titleMessage != null)
                builder.setTitle(bot.format(titleMessage, params), titleUrl);

            if(timestamp != null)
                builder.setTimestamp(OffsetDateTime.parse(timestamp));

            if(footerMessage != null)
                builder.setFooter(bot.format(footerMessage, params), footerIconUrl);

            if(thumbnail != null)
                builder.setThumbnail(thumbnail);

            if(image != null)
                builder.setImage(image);

            if(authorName != null)
                builder.setAuthor(bot.format(authorName, params), authorUrl, authorIconUrl);

            Optional.ofNullable(fields).stream()
                    .flatMap(Arrays::stream)
                    .forEach(field -> {
                        if(field.name == null || field.message == null)
                            builder.addBlankField(field.inline);
                        else
                            builder.addField(bot.format(field.name, params), bot.format(field.message, params), field.inline);
                    });

            return builder;
        }
    }

    @SheetFields(fields = { "url", "filename", "isSpoiler" }, requiredFields = { "url", "filename" })
    public static class FileConfig {
        public String url;
        public String filename;
        public boolean isSpoiler;

        public void url(String url) {
            this.url = url;

            // Download file now
            Narrator bot = SheetStack.first(NarratorProvider.class).getBot();
            bot.getFile(url);
        }

    }

    public String message;

    public EmbedConfig embed;

    public FileConfig file;

    public MessageAction edit(Narrator bot, Message existing, Object ... params) {
        if(file != null)
            throw new IllegalArgumentException("Cannot edit an existing message with a new file");

        MessageBuilder builder = new MessageBuilder();
        if(message != null)
            builder.append(bot.format(message, params));

        if(embed != null) {
            // Embed message
            EmbedBuilder embedBuilder = embed.build(bot, params);
            builder.setEmbed(embedBuilder.build());
        }

        return existing.editMessage(builder.build());
    }

    public MessageAction build(Narrator bot, TextChannel channel, Object ... params) {
        if(message == null && embed == null) {
            // Sending file only
            if(file.isSpoiler)
                return channel.sendFile(new File(bot.getFile(file.url)), file.filename, AttachmentOption.SPOILER);
            else
                return channel.sendFile(new File(bot.getFile(file.url)), file.filename);
        }

        // Else a combination of message, embed and or file
        MessageBuilder builder = new MessageBuilder();
        if(message != null)
            builder.append(bot.format(message, params));                    // Normal message

        if(embed != null) {
            // Embed message
            EmbedBuilder embedBuilder = embed.build(bot, params);
            builder.setEmbed(embedBuilder.build());
        }

        MessageAction action = channel.sendMessage(builder.build());

        if(file != null) {
            // Sending file as well
            if(file.isSpoiler)
                action = action.addFile(new File(bot.getFile(file.url)), file.filename, AttachmentOption.SPOILER);
            else
                action = action.addFile(new File(bot.getFile(file.url)), file.filename);
        }

        return action;
    }



    @Override
    public void onSheetEnded() {
        if(message == null && embed == null && file == null)
            throw new ParseException("message, embed and file cannot be all null");
    }

}
