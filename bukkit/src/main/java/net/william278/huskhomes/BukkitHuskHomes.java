package net.william278.huskhomes;

import io.papermc.lib.PaperLib;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.william278.annotaml.Annotaml;
import net.william278.desertwell.Version;
import net.william278.huskhomes.command.BukkitCommand;
import net.william278.huskhomes.command.Command;
import net.william278.huskhomes.command.DisabledCommand;
import net.william278.huskhomes.config.Locales;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.config.Spawn;
import net.william278.huskhomes.database.Database;
import net.william278.huskhomes.database.MySqlDatabase;
import net.william278.huskhomes.database.SqLiteDatabase;
import net.william278.huskhomes.event.BukkitEventDispatcher;
import net.william278.huskhomes.event.EventDispatcher;
import net.william278.huskhomes.hook.*;
import net.william278.huskhomes.listener.BukkitEventListener;
import net.william278.huskhomes.listener.EventListener;
import net.william278.huskhomes.manager.Manager;
import net.william278.huskhomes.network.Broker;
import net.william278.huskhomes.network.PluginMessageBroker;
import net.william278.huskhomes.network.RedisBroker;
import net.william278.huskhomes.position.Location;
import net.william278.huskhomes.position.Server;
import net.william278.huskhomes.position.World;
import net.william278.huskhomes.random.NormalDistributionEngine;
import net.william278.huskhomes.random.RandomTeleportEngine;
import net.william278.huskhomes.user.BukkitUser;
import net.william278.huskhomes.user.ConsoleUser;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.util.*;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class BukkitHuskHomes extends JavaPlugin implements HuskHomes, BukkitTaskRunner, BukkitEventDispatcher, PluginMessageListener {

    /**
     * Metrics ID for <a href="https://bstats.org/plugin/bukkit/HuskHomes/8430">HuskHomes on Bukkit</a>.
     */
    private static final int METRICS_ID = 8430;
    private Settings settings;
    private Locales locales;
    private Database database;
    private Cache cache;
    private Validator validator;
    private Manager manager;
    private EventListener eventListener;
    private RandomTeleportEngine randomTeleportEngine;
    private Spawn serverSpawn;
    private UnsafeBlocks unsafeBlocks;
    private Set<PluginHook> pluginHooks;
    private List<Command> registeredCommands;
    private Server server;

    @Nullable
    private Broker broker;

    // Adventure audience
    private BukkitAudiences audiences;

    // Instance of the plugin
    private static BukkitHuskHomes instance;

    public static BukkitHuskHomes getInstance() {
        return instance;
    }

    @Override
    public void onLoad() {
        // Set the instance
        instance = this;
    }

    @Override
    public void onEnable() {
        // Initialize HuskHomes
        final AtomicBoolean initialized = new AtomicBoolean(true);
        try {
            // Create adventure audience
            this.audiences = BukkitAudiences.create(this);

            // Load settings and locales
            log(Level.INFO, "Loading plugin configuration settings & locales...");
            initialized.set(reload());
            if (initialized.get()) {
                log(Level.INFO, "Successfully loaded plugin configuration settings & locales");
            } else {
                throw new IllegalStateException("Failed to load plugin configuration settings and/or locales");
            }

            // Initialize the database
            log(Level.INFO, "Attempting to establish connection to the database...");
            final Database.DatabaseType databaseType = settings.getDatabaseType();
            this.database = switch (databaseType == null ? Database.DatabaseType.MYSQL : databaseType) {
                case MYSQL -> new MySqlDatabase(this);
                case SQLITE -> new SqLiteDatabase(this);
            };
            initialized.set(this.database.initialize());
            if (initialized.get()) {
                log(Level.INFO, "Successfully established a connection to the database");
            } else {
                throw new IllegalStateException("Failed to establish a connection to the database. " + "Please check the supplied database credentials in the config file");
            }

            // Initialize the network messenger if proxy mode is enabled
            if (getSettings().isCrossServer()) {
                log(Level.INFO, "Initializing the network messenger...");
                broker = switch (settings.getBrokerType()) {
                    case PLUGIN_MESSAGE -> new PluginMessageBroker(this);
                    case REDIS -> new RedisBroker(this);
                };
                broker.initialize();
                log(Level.INFO, "Successfully initialized the network messenger.");
            }

            // Prepare the validator
            this.validator = new Validator(this);

            // Initialize the cache
            this.cache = new Cache(this);

            // Prepare the home and warp position manager
            this.manager = new Manager(this);

            // Initialize the RTP engine with the default normal distribution engine
            setRandomTeleportEngine(new NormalDistributionEngine(this));

            // Register plugin hooks (Economy, Maps, Plan)
            this.pluginHooks = new HashSet<>();
            if (settings.doEconomy()) {
                if (Bukkit.getPluginManager().getPlugin("RedisEconomy") != null) {
                    pluginHooks.add(new RedisEconomyHook(this));
                } else if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
                    pluginHooks.add(new VaultEconomyHook(this));
                }
            }
            if (settings.doMapHook()) {
                switch (settings.getMappingPlugin()) {
                    case DYNMAP -> {
                        final Plugin dynmapPlugin = Bukkit.getPluginManager().getPlugin("Dynmap");
                        if (dynmapPlugin != null) {
                            pluginHooks.add(new DynMapHook(this, dynmapPlugin));
                        }
                    }
                    case BLUEMAP -> {
                        if (Bukkit.getPluginManager().getPlugin("BlueMap") != null) {
                            pluginHooks.add(new BlueMapHook(this));
                        }
                    }
                }
            }
            if (Bukkit.getPluginManager().getPlugin("Plan") != null) {
                pluginHooks.add(new PlanHook(this));
            }

            if (pluginHooks.size() > 0) {
                pluginHooks.forEach(PluginHook::initialize);
                log(Level.INFO, "Registered " + pluginHooks.size() + " plugin hooks: " + pluginHooks.stream().map(PluginHook::getHookName).collect(Collectors.joining(", ")));
            }

            // Register events
            log(Level.INFO, "Registering events...");
            this.eventListener = new BukkitEventListener(this);
            log(Level.INFO, "Successfully registered events listener");

            // Register permissions
            log(Level.INFO, "Registering permissions & commands...");
            Arrays.stream(Permission.values()).forEach(permission -> getServer().getPluginManager().addPermission(new org.bukkit.permissions.Permission(permission.node, switch (permission.defaultAccess) {
                case EVERYONE -> PermissionDefault.TRUE;
                case NOBODY -> PermissionDefault.FALSE;
                case OPERATORS -> PermissionDefault.OP;
            })));

            // Register commands
            this.registeredCommands = new ArrayList<>();
            Arrays.stream(BukkitCommand.Type.values()).forEach(commandType -> {
                // If the command is disabled, use the disabled CommandBase
                if (settings.getDisabledCommands().stream().anyMatch(disabledCommand -> {
                    final String command = (disabledCommand.startsWith("/") ? disabledCommand.substring(1) : disabledCommand);
                    return command.equalsIgnoreCase(commandType.getCommand().getName()) || commandType.getCommand().getAliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(command));
                })) {
                    new BukkitCommand(new DisabledCommand(this), this).register();
                    return;
                }

                // Otherwise, register the command
                final Command command = commandType.getCommand();
                this.registeredCommands.add(command);
                new BukkitCommand(command, this).register();
            });
            log(Level.INFO, "Successfully registered permissions & commands.");

            // Hook into bStats metrics
            registerMetrics(METRICS_ID);

            // Check for updates
            if (settings.doCheckForUpdates()) {
                log(Level.INFO, "Checking for updates...");
                getLatestVersionIfOutdated().thenAccept(newestVersion -> newestVersion.ifPresent(newVersion -> log(Level.WARNING, "An update is available for HuskHomes, v" + newVersion + " (Currently running v" + getVersion() + ")")));
            }
        } catch (IllegalStateException exception) {
            log(Level.SEVERE, exception.getMessage());
            initialized.set(false);
        } catch (Exception exception) {
            log(Level.SEVERE, "An unhandled exception occurred initializing HuskHomes!", exception);
            initialized.set(false);
        } finally {
            // Validate initialization
            if (initialized.get()) {
                log(Level.INFO, "Successfully enabled HuskHomes v" + getVersion());
            } else {
                log(Level.SEVERE, "Failed to initialize HuskHomes. The plugin will now be disabled");
                getServer().getPluginManager().disablePlugin(this);
            }
        }
    }

    @Override
    public void onDisable() {
        if (this.eventListener != null) {
            this.eventListener.handlePluginDisable();
        }
        if (database != null) {
            database.terminate();
        }
        if (broker != null) {
            broker.close();
        }
        if (audiences != null) {
            audiences.close();
            audiences = null;
        }
    }

    /**
     * Returns the adventure Bukkit audiences
     *
     * @return The adventure Bukkit audiences
     */
    public BukkitAudiences getAudiences() {
        return audiences;
    }

    @Override
    @NotNull
    public ConsoleUser getConsole() {
        return new ConsoleUser(audiences.console());
    }

    @NotNull
    @Override
    public List<OnlineUser> getOnlineUsers() {
        return Bukkit.getOnlinePlayers().stream().map(player -> (OnlineUser) BukkitUser.adapt(player)).toList();
    }

    @NotNull
    @Override
    public Settings getSettings() {
        return settings;
    }

    @NotNull
    @Override
    public Locales getLocales() {
        return locales;
    }

    @Override
    @NotNull
    public Database getDatabase() {
        return database;
    }

    @NotNull
    @Override
    public Cache getCache() {
        return cache;
    }

    @Override
    @NotNull
    public Validator getValidator() {
        return validator;
    }

    @NotNull
    @Override
    public Manager getManager() {
        return manager;
    }

    @NotNull
    @Override
    public Broker getMessenger() {
        if (broker == null) {
            throw new IllegalStateException("Attempted to access network messenger when it was not initialized");
        }
        return broker;
    }

    @NotNull
    @Override
    public RandomTeleportEngine getRandomTeleportEngine() {
        return randomTeleportEngine;
    }

    @Override
    public void setRandomTeleportEngine(@NotNull RandomTeleportEngine randomTeleportEngine) {
        this.randomTeleportEngine = randomTeleportEngine;
    }

    @Override
    public Optional<Spawn> getLocalCachedSpawn() {
        return Optional.ofNullable(serverSpawn);
    }

    @Override
    public void setServerSpawn(@NotNull Location location) {
        final Spawn newSpawn = new Spawn(location);
        this.serverSpawn = newSpawn;
        try {
            Annotaml.create(new File(getDataFolder(), "spawn.yml"), newSpawn);
        } catch (IOException e) {
            log(Level.WARNING, "Failed to save server spawn to disk", e);
        }

        // Update the world spawn location, too
        BukkitAdapter.adaptLocation(location).ifPresent(bukkitLocation -> {
            assert bukkitLocation.getWorld() != null;
            bukkitLocation.getWorld().setSpawnLocation(bukkitLocation);
        });
    }

    @Override
    @NotNull
    public Set<PluginHook> getPluginHooks() {
        return pluginHooks;
    }

    @Override
    public CompletableFuture<Optional<Location>> resolveSafeGroundLocation(@NotNull Location location) {
        final org.bukkit.Location bukkitLocation = BukkitAdapter.adaptLocation(location).orElse(null);
        if (bukkitLocation == null || bukkitLocation.getWorld() == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        final CompletableFuture<Optional<Location>> locationFuture = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> PaperLib.getChunkAtAsync(bukkitLocation).thenApply(chunk -> {
            final ChunkSnapshot snapshot = chunk.getChunkSnapshot();
            final int chunkX = bukkitLocation.getBlockX() & 0xF;
            final int chunkZ = bukkitLocation.getBlockZ() & 0xF;

            for (int dX = -1; dX <= 2; dX++) {
                for (int dZ = -1; dZ <= 2; dZ++) {
                    final int x = chunkX + dX;
                    final int z = chunkZ + dZ;
                    if (x < 0 || x >= 16 || z < 0 || z >= 16) {
                        continue;
                    }
                    final int y = snapshot.getHighestBlockYAt(x, z);
                    final Material blockType = snapshot.getBlockType(chunkX, y, chunkZ);
                    if (!isBlockUnsafe(blockType.getKey().toString())) {
                        return new Location((location.getX() + dX) + 0.5d, y + 1.25d, (location.getZ() + dZ) + 0.5d, location.getWorld());
                    }
                }
            }
            return null;
        }).thenAccept(resolved -> locationFuture.complete(Optional.ofNullable(resolved))));
        return locationFuture;
    }

    @Override
    @NotNull
    public Version getVersion() {
        return Version.fromString(getDescription().getVersion(), "-");
    }

    @Override
    @NotNull
    public List<Command> getCommands() {
        return registeredCommands;
    }

    @Override
    @NotNull
    public String getServerName() {
        return server.getName();
    }

    @Override
    @NotNull
    public List<World> getWorlds() {
        return getServer().getWorlds().stream()
                .filter(world -> BukkitAdapter.adaptWorld(world).isPresent())
                .map(world -> BukkitAdapter.adaptWorld(world).orElse(null))
                .toList();
    }

    @Override
    public boolean reload() {
        try {
            // Load settings
            this.settings = Annotaml.create(new File(getDataFolder(), "config.yml"), Settings.class).get();

            // Load locales from language preset default
            final Locales languagePresets = Annotaml.create(Locales.class, Objects.requireNonNull(getResource("locales/" + settings.getLanguage() + ".yml"))).get();
            this.locales = Annotaml.create(new File(getDataFolder(), "messages_" + settings.getLanguage() + ".yml"), languagePresets).get();

            // Load server from file
            if (settings.isCrossServer()) {
                this.server = Annotaml.create(new File(getDataFolder(), "server.yml"), Server.class).get();
            } else {
                this.server = Server.getDefault();
            }

            // Load spawn location from file
            final File spawnFile = new File(getDataFolder(), "spawn.yml");
            if (spawnFile.exists()) {
                this.serverSpawn = Annotaml.create(spawnFile, Spawn.class).get();
            }

            // Load unsafe blocks from resources
            final InputStream blocksResource = getResource("safety/unsafe_blocks.yml");
            this.unsafeBlocks = Annotaml.create(new UnsafeBlocks(), Objects.requireNonNull(blocksResource)).get();

            return true;
        } catch (IOException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            log(Level.SEVERE, "Failed to reload HuskHomes config or messages file", e);
        }
        return false;
    }

    @Override
    public boolean isBlockUnsafe(@NotNull String blockId) {
        return unsafeBlocks.isUnsafe(blockId);
    }

    @Override
    public void registerMetrics(int metricsId) {
        try {
            final Metrics metrics = new Metrics(this, metricsId);
            metrics.addCustomChart(new SimplePie("bungee_mode", () -> Boolean.toString(getSettings().isCrossServer())));
            if (getSettings().isCrossServer()) {
                metrics.addCustomChart(new SimplePie("messenger_type", () -> getSettings().getBrokerType().getDisplayName()));
            }
            metrics.addCustomChart(new SimplePie("language", () -> getSettings().getLanguage().toLowerCase()));
            metrics.addCustomChart(new SimplePie("database_type", () -> getSettings().getDatabaseType().displayName));
            metrics.addCustomChart(new SimplePie("using_economy", () -> Boolean.toString(getSettings().doEconomy())));
            metrics.addCustomChart(new SimplePie("using_map", () -> Boolean.toString(getSettings().doMapHook())));
            if (getSettings().doMapHook()) {
                metrics.addCustomChart(new SimplePie("map_type", () -> getSettings().getMappingPlugin().displayName));
            }
        } catch (Exception e) {
            log(Level.WARNING, "Failed to register metrics", e);
        }
    }

    @Override
    public void initializePluginChannels() {
        Bukkit.getMessenger().registerIncomingPluginChannel(this, PluginMessageBroker.BUNGEE_CHANNEL_ID, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, PluginMessageBroker.BUNGEE_CHANNEL_ID);
    }

    @Override
    public void log(@NotNull Level level, @NotNull String message, @NotNull Throwable... exceptions) {
        if (exceptions.length > 0) {
            getLogger().log(level, message, exceptions[0]);
            return;
        }
        getLogger().log(level, message);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (broker != null && broker instanceof PluginMessageBroker pluginMessenger
            && getSettings().getBrokerType() == Broker.Type.PLUGIN_MESSAGE) {
            pluginMessenger.onReceive(channel, BukkitUser.adapt(player), message);
        }
    }

    @Override
    @NotNull
    public HuskHomes getPlugin() {
        return this;
    }

    // Default constructor
    @SuppressWarnings("unused")
    public BukkitHuskHomes() {
        super();
    }

    // Super constructor for unit testing
    @SuppressWarnings("unused")
    protected BukkitHuskHomes(@NotNull JavaPluginLoader loader, @NotNull PluginDescriptionFile description, @NotNull File dataFolder, @NotNull File file) {
        super(loader, description, dataFolder, file);
    }

}
