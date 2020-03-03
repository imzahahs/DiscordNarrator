package com.kaigan.bots.narrator;

import com.kaigan.bots.narrator.save.SaveObject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Category;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.DisconnectEvent;
import net.dv8tion.jda.api.events.ReconnectedEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.RestAction;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sengine.sheets.ParseException;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Narrator extends ListenerAdapter {
    private static final Logger log = LogManager.getLogger("Narrator");

    private static final long QUEUE_RETRY_INTERVAL = 10 * 1000;       // 10 seconds
    private static final long QUEUE_MAX_TRIES = 6;         // 6 times

    private static final OkHttpClient okHttpClient = new OkHttpClient();

    private static final Pattern RE = Pattern.compile(
            "\\\\(.)" +         // Treat any character after a backslash literally
            "|" +
            "(%\\(([^)]+)\\))"  // Look for %(keys) to replace
    );

    public final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1) {
        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            if (t == null && r instanceof Future<?>) {
                Future<?> future = (Future<?>) r;
                if(future.isCancelled() || !future.isDone())
                    return;     // tasks are cancelled or not done, not sure why it wouldnt be done here, just following official javadoc sample
                // Else check exception
                try {
                    // Extract exception from future if exists
                    ((Future<?>) r).get();
                } catch (Throwable e) {
                    t = e;
                }
            }
            if (t != null)
                log.error("Exception in scheduler", t);
        }
    };
    public final ExecutorService executor = Executors.newCachedThreadPool();

    public final NarratorBuilder builder;

    public final String token;
    public final String serverName;

    public final JDA jda;
    public final Guild guild;

    private final Map<NarratorService, ScheduledFuture<?>> scheduledServices = new HashMap<>();
    private final List<NarratorService> services = new ArrayList<>();
    private final List<NarratorService> servicesIterator = new ArrayList<>();

    private final Map<String, Object> textFormatLookup = new HashMap<>();

    private final Map<String, SaveObject> saved = new HashMap<>();

    public <T extends SaveObject> T getSave(String name) {
        return (T) saved.get(name);
    }

    public void addSave(String name, SaveObject saveObject) {
        saved.put(name, saveObject);
    }

    public String getFile(String url) {
        String id = DigestUtils.sha256Hex(url);

        // Check if file exists
        Path path = Paths.get(builder.downloadPath, id);
        if(Files.exists(path))
            return path.toString();

        log.info("Downloading file: " + url);

        // Else download now
        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful())
                throw new ParseException("Error " + response + " while downloading from " + url);

            // Mkdirs
            if(path.getParent() != null)
                Files.createDirectories(path.getParent());

            try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                long size = file.transferFrom(Channels.newChannel(response.body().byteStream()), 0, builder.downloadMaxSize + 1);
                if(size == 0)
                    throw new ParseException("Empty file from " + url);
                if(file.position() > builder.downloadMaxSize)
                    throw new ParseException("File from " + url + " exceeds max limit of " + builder.downloadMaxSize + " bytes");

                log.info("Downloaded file " + id + " with " + size + " bytes");
            }

        } catch (Throwable e) {
            throw new ParseException("Failed to download file from " + url, e);
        }

        return path.toString();
    }

    /**
     * Expands format strings containing <code>%(keys)</code>.
     *
     * <p>Examples:</p>
     *
     * <ul>
     * <li><code>NamedFormatter.format("Hello, %(name)!", Map.of("name", "200_success"))</code> → <code>"Hello, 200_success!"</code></li>
     * <li><code>NamedFormatter.format("Hello, \%(name)!", Map.of("name", "200_success"))</code> → <code>"Hello, %(name)!"</code></li>
     * <li><code>NamedFormatter.format("Hello, %(name)!", Map.of("foo", "bar"))</code> → <code>"Hello, %(name)!"</code></li>
     * </ul>
     *
     * @param format The format string.  Any character in the format string that
     *            follows a backslash is treated literally.  Any
     *            <code>%(key)</code> is replaced by its corresponding value
     *            in the <code>values</code> map.  If the key does not exist
     *            in the <code>values</code> map, then it is left unsubstituted.
     *
     * @return The formatted string.
     */
    public String format(String format, Object ... params) {
        if(params.length > 0)
            setFormatParameters(params);
        return RE.matcher(format).replaceAll(match ->
                match.group(1) != null ?
                        match.group(1) :
                        textFormatLookup.getOrDefault(match.group(3), match.group(2)).toString()
        );
    }

    public void save() {
        // Compile all saves
        for(NarratorService service : services)
            service.save(saved);
        String json = SaveObject.toJson(saved);
        // Save to file
        try {
            Files.write(Paths.get("save.json"), json.getBytes());
            log.info("Written {} saved entries", saved.size());
        } catch (IOException e) {
            throw new RuntimeException("Failed to save", e);
        }
    }

//    public String formatChoices(List<String> options) {
//        StringBuilder sb = new StringBuilder();
//        for (int i = 0; i < options.size(); i++) {
//            if(i > 0)
//                sb.append('\n');
//            sb.append(choiceEmoteMentions[i]).append(" ").append(options.get(i));
//        }
//        return sb.toString();
//    }

    public void setFormatParameters(Object ... params) {
        if(params.length % 2 != 0)
            throw new IllegalArgumentException("Invalid layout of parameters");
        for(int c = 0; c < params.length; c+= 2) {
            String name = (String) params[c];
            Object value = params[c + 1];
            textFormatLookup.put(name, value);
        }
    }

    public <T> void queue(Supplier<RestAction<T>> restActionSupplier, Logger log, String description) {
        schedule(-1, restActionSupplier, log, description, null);
    }

    public <T> void queue(Supplier<RestAction<T>> restActionSupplier, Logger log, String description, Consumer<T> success) {
        schedule(-1, restActionSupplier, log, description, success);
    }

    public <T> void schedule(long delay, Supplier<RestAction<T>> restActionSupplier, Logger log, String description) {
        schedule(delay, restActionSupplier, log, description, null);
    }

    public <T> void schedule(long delay, Supplier<RestAction<T>> restActionSupplier, Logger log, String description, Consumer<T> success) {
        new NarratorService() {
            int tries = 0;

            void send() {
                if(delay <= 0)
                    processService(Narrator.this);
                else
                    scheduleService(this, delay);
            }

            @Override
            public long processService(Narrator bot) {
                // Try
                RestAction<T> action = restActionSupplier.get();
                if(action == null)
                    return -1;
                try {
                    T outcome = action.complete();
                    if(success != null)
                        success.accept(outcome);
                    return -1;
                } catch (Throwable e) {
                    // Else failed
                    tries++;
                    if(tries >= QUEUE_MAX_TRIES) {
                        // Failed and give up
                        log.error("Action failed after " + tries + " tries for: " + description, e);
                        return -1;      // stop
                    }
                    // Else try again
                    log.warn("Waiting and retrying " + (tries + 1) + " times for: " + description, e);
                    return QUEUE_RETRY_INTERVAL;
                }
            }
        }.send();
    }

    public boolean hasServiceStarted(NarratorService service) {
        return services.contains(service);
    }

    public long getServiceDelay(NarratorService service) {
        ScheduledFuture<?> future = scheduledServices.get(service);
        if(future != null)
            return future.getDelay(TimeUnit.MILLISECONDS);
        return -1;      // not found or has run
    }

    public void addService(NarratorService service) {
        long initialDelay = service.onServiceStart(this);
        if(!scheduledServices.containsKey(service))
            scheduleService(service, initialDelay);
        services.add(service);
    }

    public void scheduleService(NarratorService service, long delay) {
        // Stop existing scheduled service if exists
        ScheduledFuture<?> scheduled = scheduledServices.remove(service);
        if(scheduled != null)
            scheduled.cancel(false);        // no need to interrupt as should be same thread
        // Reschedule next
        if(delay >= 0) {
            scheduled = scheduler.schedule(() -> {
                // Remove scheduled future first
                scheduledServices.remove(service);
                long nextDelay = service.processService(this);
                if(nextDelay >= 0) {
                    // Reschedule again
                    scheduleService(service, nextDelay);
                }
            }, delay, TimeUnit.MILLISECONDS);
            scheduledServices.put(service, scheduled);
        }
    }

    public boolean removeService(NarratorService service) {
        if(!service.onServiceStop(this))
            return false;
        scheduleService(service, -1);       // stop scheduled
        services.remove(service);
        return true;
    }

    public <T extends NarratorService> Stream<T> getServices(Class<T> type) {
        return services.stream()
                .filter(service -> service.getClass() == type)
                .map(service -> (T)service)
                ;
    }

    public <T extends NarratorService> T getService(Class<T> type) {
        return getServices(type).findFirst().orElse(null);
    }


    public Optional<TextChannel> resolveTextChannel(String name) {
        String categoryName;
        String[] names = name.split("/");
        if(names.length > 2)
            throw new IllegalArgumentException("Invalid channel name: " + name);
        else if(names.length == 2) {
            categoryName = names[0];
            name = names[1];
        }
        else
            categoryName = null;
        return guild.getTextChannelsByName(name, true).stream()
                .filter(channel -> {
                    Category category = channel.getParent();
                    if(category != null)
                        return category.getName().equalsIgnoreCase(categoryName);
                    else
                        return categoryName == null;
                })
                .findFirst();
    }

    public Narrator(NarratorBuilder builder, String token, String serverName) {

        this.builder = builder;
        this.token = token;
        this.serverName = serverName;

        // Login and prepare all data
        try {
            // Read save        // TODO
            if(Files.exists(Paths.get("save.json"))) {
                String json = new String(Files.readAllBytes(Paths.get("save.json")));
                saved.putAll(SaveObject.fromJson(json));
                log.info("Read {} saved entries", saved.size());
            }

            jda = new JDABuilder(token)
//                    .setStatus(OnlineStatus.INVISIBLE)
                    .build().awaitReady();

            // Log guilds discovered for security (someone is able to get the bot invite screen, not sure if it can be added though)
            jda.getGuilds().forEach(guild -> log.info("Found guild: {}", guild.getName()));

            // Get guild
            guild = jda.getGuilds().stream().filter(guild -> guild.getName().equals(serverName)).findAny().orElseThrow(() -> {
                throw new RuntimeException("Failed to find guild");
            });

            // Setup listener
            jda.addEventListener(this);

        } catch (Throwable e) {
            throw new RuntimeException("Failed to setup discord session", e);
        }
    }


    @Override
    public void onGuildMessageReceived(GuildMessageReceivedEvent event) {
        // Ignore if message is from a bot
        if(event.getAuthor().isBot())
            return;

        // Serialize all execution on a single thread
        scheduler.execute(() -> {
            // Cleanup message
            ProcessedMessage message = new ProcessedMessage(event.getMessage().getContentDisplay());

            // Inform services
            servicesIterator.clear();
            servicesIterator.addAll(services);
            for(NarratorService service : servicesIterator) {
                if(service.processMessage(this, event, message))
                    return;     // absorbed
            }
        });
    }

    @Override
    public void onGuildMessageReactionAdd(GuildMessageReactionAddEvent event) {
        // Ignore if is bot
        if(event.getMember().getUser().isBot())
            return;

        if(event.getReactionEmote().isEmoji())
            return;     // ignore if normal emoji reaction

        // Serialize all execution on a single thread
        scheduler.execute(() -> {
            // Inform services
            servicesIterator.clear();
            servicesIterator.addAll(services);
            for(NarratorService service : servicesIterator) {
                if(service.processReactionAdded(this, event))
                    return;     // absorbed
            }
        });
    }

    @Override
    public void onGuildMessageReactionRemove(GuildMessageReactionRemoveEvent event) {
        // Ignore if is bot
        if(event.getMember() == null || event.getMember().getUser().isBot())
            return;

        // Serialize all execution on a single thread
        scheduler.execute(() -> {
            // Inform services
            servicesIterator.clear();
            servicesIterator.addAll(services);
            for(NarratorService service : servicesIterator) {
                if(service.processReactionRemoved(this, event))
                    return;     // absorbed
            }
        });
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        scheduler.execute(() -> {
            log.info("Member joined: " + event.getMember().getEffectiveName());

            // Inform services
            servicesIterator.clear();
            servicesIterator.addAll(services);
            for(NarratorService service : servicesIterator) {
                if(service.processMemberJoined(this, event))
                    return;     // absorbed
            }
        });
    }

    @Override
    public void onGuildMemberLeave(GuildMemberLeaveEvent event) {
        scheduler.execute(() -> {
            log.info("Member left: " + event.getMember().getEffectiveName());

            // Inform services
            servicesIterator.clear();
            servicesIterator.addAll(services);
            for(NarratorService service : servicesIterator) {
                if(service.processMemberLeft(this, event))
                    break;     // absorbed
            }
        });
    }

    @Override
    public void onGuildMemberUpdateNickname(@Nonnull GuildMemberUpdateNicknameEvent event) {
        // Ignore if is bot
        if(event.getMember().getUser().isBot())
            return;

        scheduler.execute(() -> {
            // Inform services
            servicesIterator.clear();
            servicesIterator.addAll(services);
            for(NarratorService service : servicesIterator) {
                if(service.processNickChange(this, event))
                    return;     // absorbed
            }
        });
    }

    @Override
    public void onDisconnect(DisconnectEvent event) {
        scheduler.execute(() -> {
            // Hold scheduler until reconnection
            try {
                jda.awaitReady();
            } catch (Throwable e) {
                log.error("Failed to wait for discord reconnection", e);
            }
        });
    }

    @Override
    public void onReconnect(ReconnectedEvent event) {
        save();
        log.error("Reconnected with state loss, restarting bot");
        // Restart builder
        jda.shutdownNow();
        scheduler.shutdownNow();
        // Start new
        new Thread(() -> {
            NarratorBuilder newBuilder = new NarratorBuilder(builder.sheetFilename, builder.googleDocId, builder.mainSheetName);
            newBuilder.build(token, serverName);
        }).start();
    }
}
