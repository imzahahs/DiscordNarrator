package com.kaigan.bots.narrator;

import com.neovisionaries.ws.client.WebSocketFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.DisconnectEvent;
import net.dv8tion.jda.api.events.ReconnectedEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import sengine.mass.MassFile;
import sengine.mass.io.Input;
import sengine.mass.io.Output;
import sengine.sheets.ParseException;

import javax.annotation.Nonnull;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

    public static final OkHttpClient okHttpClient = new OkHttpClient();

    private static final Pattern RE = Pattern.compile(
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

    private final MassFile saved = new MassFile();

    private final NarratorService queuedSaveService = new NarratorService() {
        @Override
        public long processService(Narrator bot) {
            // Write save
            Path path = Paths.get(builder.saveFilePath);
            try {
                // Mkdirs
                if (path.getParent() != null)
                    Files.createDirectories(path.getParent());
                try (FileOutputStream saveFile = new FileOutputStream(path.toString(), false)) {
                    saved.rebuild();
                    saved.save(new Output(saveFile), builder.key);
                }
            }
            catch(Throwable e) {
                log.error("Unable to save: " + builder.saveFilePath, e);
            }
            return -1;
        }
    };

    public <T> Optional<T> getSave(String name) {
        return Optional.ofNullable(saved.get(name));
    }

    public void putSave(String name, Object object) {
        saved.add(name, object);

        // Queue save if havent yet
        long delay = getServiceDelay(queuedSaveService);
        if(delay == -1)
            scheduleService(queuedSaveService, builder.saveFileInterval);
    }

    public void reloadSave() {
        // Read save
        Path path = Paths.get(builder.saveFilePath);
        if(Files.exists(path)) {
            // Load save
            try(FileInputStream saveFile = new FileInputStream(builder.saveFilePath)) {
                saved.load(new Input(saveFile), builder.key);
            } catch(Throwable e) {
                throw new RuntimeException("Unable to load save: " + builder.saveFilePath, e);
            }
        }
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

    public interface FormatResolver {
        String resolve(String identifier);
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
        // Store parameters
        int length = params.length;
        FormatResolver resolver = null;
        if(length % 2 == 1) {
            // The last parameter must be a FormatResolver
            resolver = (FormatResolver) params[length - 1];
            length--;
        }
        for(int c = 0; c < length; c+= 2) {
            String name = (String) params[c];
            Object value = params[c + 1];
            textFormatLookup.put(name, value);
        }
        FormatResolver finalResolver = resolver;
        String formatted = RE.matcher(format).replaceAll(match -> {
                    String lookup = match.group(2);
                    if(lookup.startsWith(":") && lookup.endsWith(":")) {
                        // Resolving emote
                        String emote = lookup.substring(1, lookup.length() - 1);
                        List<Emote> emotes = guild.getEmotesByName(emote, false);
                        if(!emotes.isEmpty())
                            return emotes.get(0).getAsMention();
                    }
                    // Use given format lookup
                    Object resolvedLookup = textFormatLookup.get(lookup);
                    if(resolvedLookup != null)
                        return resolvedLookup.toString();
                    // Else us resolver if available
                    if(finalResolver != null) {
                        String resolved = finalResolver.resolve(lookup);
                        if(resolved != null)
                            return resolved;
                    }
                    // Else wasn't able to resolve, just return unchanged
                    return match.group(0);
                }
        );
        // Reset
        textFormatLookup.clear();
        return formatted;
    }

    public Optional<Emote> getChoiceEmote(int index) {
        return getEmote("choice" + (index + 1));
    }

    public Optional<Emote> getEmote(String name) {
        return guild.getEmotesByName(name, false).stream().findAny();
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
                    Category category = channel.getParentCategory();
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
            // TODO: Workaround for certain JDK distributions
            WebSocketFactory webSocketFactory = new WebSocketFactory()
                    .setVerifyHostname(false);

            jda = JDABuilder.createDefault(token)
//                    .setStatus(OnlineStatus.INVISIBLE)
                    .setChunkingFilter(ChunkingFilter.ALL) // enable member chunking for all guilds
                    .setMemberCachePolicy(MemberCachePolicy.ALL) // ignored if chunking enabled
                    .enableIntents(GatewayIntent.GUILD_MEMBERS)
                    .setWebsocketFactory(webSocketFactory)
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
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        // Ignore if message is from a bot
        if(event.getAuthor().isBot())
            return;

        if (event.getChannelType() == ChannelType.PRIVATE) {
            // Serialize all execution on a single thread
            scheduler.execute(() -> {
                // Cleanup message
                ProcessedMessage message = new ProcessedMessage(event.getMessage().getContentDisplay());

                // Inform services
                servicesIterator.clear();
                servicesIterator.addAll(services);
                for(NarratorService service : servicesIterator) {
                    if(service.processPrivateMessage(this, event, message))
                        return;     // absorbed
                }
            });
        }
        else {
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
    }

    @Override
    public void onMessageReactionAdd(@NotNull MessageReactionAddEvent event) {
        // Ignore if is bot
        if(event.getUser() == null || event.getUser().isBot())
            return;

        if(event.getReactionEmote().isEmoji())
            return;     // ignore if normal emoji reaction

        if (event.getChannelType() == ChannelType.PRIVATE)
        {
            // Serialize all execution on a single thread
            scheduler.execute(() -> {
                // Inform services
                servicesIterator.clear();
                servicesIterator.addAll(services);
                for(NarratorService service : servicesIterator) {
                    if(service.processPrivateReactionAdded(this, event))
                        return;     // absorbed
                }
            });
        }
        else
        {
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
    }

    @Override
    public void onMessageReactionRemove(@NotNull MessageReactionRemoveEvent event) {
        // Ignore if is bot
        if(event.getUser() == null || event.getUser().isBot())
            return;

        if (event.getChannelType() == ChannelType.PRIVATE)
        {
            // Serialize all execution on a single thread
            scheduler.execute(() -> {
                // Inform services
                servicesIterator.clear();
                servicesIterator.addAll(services);
                for(NarratorService service : servicesIterator) {
                    if(service.processPrivateReactionRemoved(this, event))
                        return;     // absorbed
                }
            });
        }
        else
        {
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
    public void onReconnected(@NotNull ReconnectedEvent event) {
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
