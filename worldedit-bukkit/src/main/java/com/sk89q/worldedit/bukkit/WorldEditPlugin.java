/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.bukkit;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Joiner;
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.wepif.PermissionsResolverManager;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.adapter.AdapterLoadException;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplLoader;
import com.sk89q.worldedit.event.platform.CommandEvent;
import com.sk89q.worldedit.event.platform.CommandSuggestionEvent;
import com.sk89q.worldedit.event.platform.PlatformReadyEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Platform;
import com.sk89q.worldedit.extent.inventory.BlockBag;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;

import javax.annotation.Nullable;

/**
 * Plugin for Bukkit.
 */
public class WorldEditPlugin extends JavaPlugin implements TabCompleter {

    private static final Logger log = Logger.getLogger(WorldEditPlugin.class.getCanonicalName());
    private static final String CUI_PLUGIN_CHANNEL = "worldedit:cui";
    private static WorldEditPlugin INSTANCE;

    private BukkitImplAdapter bukkitAdapter;
    private BukkitServerInterface server;
    private BukkitConfiguration config;

    private static Map<String, Plugin> lookupNames;
    static {
        {   // Disable AWE as otherwise both fail to load
            PluginManager manager = Bukkit.getPluginManager();
            try {
                Field pluginsField = manager.getClass().getDeclaredField("plugins");
                Field lookupNamesField = manager.getClass().getDeclaredField("lookupNames");
                pluginsField.setAccessible(true);
                lookupNamesField.setAccessible(true);
                List<Plugin> plugins = (List<Plugin>) pluginsField.get(manager);
                lookupNames = (Map<String, Plugin>) lookupNamesField.get(manager);
                pluginsField.set(manager, plugins = new ArrayList<Plugin>(plugins) {
                    @Override
                    public boolean add(Plugin plugin) {
                        if (plugin.getName().startsWith("AsyncWorldEdit")) {
                            Fawe.debug("Disabling `" + plugin.getName() + "` as it is incompatible");
                        } else if (plugin.getName().startsWith("BetterShutdown")) {
                            Fawe.debug("Disabling `" + plugin.getName() + "` as it is incompatible (Improperly shaded classes from com.sk89q.minecraft.util.commands)");
                        } else {
                            return super.add(plugin);
                        }
                        return false;
                    }
                });
                lookupNamesField.set(manager, lookupNames = new ConcurrentHashMap<String, Plugin>(lookupNames) {
                    @Override
                    public Plugin put(String key, Plugin plugin) {
                        if (plugin.getName().startsWith("AsyncWorldEdit") || plugin.getName().startsWith("BetterShutdown")) {
                            return null;
                        }
                        return super.put(key, plugin);
                    }
                });
            } catch (Throwable ignore) {}
        }
    }

    public WorldEditPlugin() {
        if (lookupNames != null) lookupNames.putIfAbsent("WorldEdit".toLowerCase(Locale.ENGLISH), this);
    }

    public WorldEditPlugin(JavaPluginLoader loader, PluginDescriptionFile desc, File dataFolder, File jarFile) {
        if (lookupNames != null) lookupNames.putIfAbsent("WorldEdit".toLowerCase(Locale.ENGLISH), this);
    }

    public static String getCuiPluginChannel() {
        return CUI_PLUGIN_CHANNEL;
    }

    /**
     * Called on plugin enable.
     */
    @SuppressWarnings("AccessStaticViaInstance")
    @Override
    public void onEnable() {
        this.INSTANCE = this;
        FaweBukkit imp = new FaweBukkit(this);

        //noinspection ResultOfMethodCallIgnored
        getDataFolder().mkdirs();

        WorldEdit worldEdit = WorldEdit.getInstance();

        loadAdapter(); // Need an adapter to work with special blocks with NBT data

        // Setup platform
        server = new BukkitServerInterface(this, getServer());
        worldEdit.getPlatformManager().register(server);
        worldEdit.loadMappings();

        loadConfig(); // Load configuration
        fail(() -> PermissionsResolverManager.initialize(INSTANCE), "Failed to initialize permissions resolver");


        // Register CUI
        fail(() -> {
            getServer().getMessenger().registerIncomingPluginChannel(INSTANCE, CUI_PLUGIN_CHANNEL, new CUIChannelListener(INSTANCE));
            getServer().getMessenger().registerOutgoingPluginChannel(INSTANCE, CUI_PLUGIN_CHANNEL);
        }, "Failed to register CUI");

        // Now we can register events
        getServer().getPluginManager().registerEvents(new WorldEditListener(this), this);

        // If we are on MCPC+/Cauldron, then Forge will have already loaded
        // Forge WorldEdit and there's (probably) not going to be any other
        // platforms to be worried about... at the current time of writing
        WorldEdit.getInstance().getEventBus().post(new PlatformReadyEvent());

        { // Register 1.13 Material ids with LegacyMapper
            LegacyMapper legacyMapper = LegacyMapper.getInstance();
            for (Material m : Material.values()) {
                if (!m.isLegacy() && m.isBlock()) {
                    legacyMapper.register(m.getId(), 0, BukkitAdapter.adapt(m).getDefaultState());
                }
            }
        }
    }

    private void fail(Runnable run, String message) {
        try {
            run.run();
        } catch (Throwable e) {
            log.log(Level.SEVERE, message);
            e.printStackTrace();
        }
    }

    private void loadConfig() {
        createDefaultConfiguration("config-legacy.yml"); // Create the default configuration file
        try {
            config = new BukkitConfiguration(new YAMLProcessor(new File(getDataFolder(), "config-legacy.yml"), true), this);
            config.load();
        } catch (Throwable e) {
            log.log(Level.SEVERE, "Failed to load config.yml");
            e.printStackTrace();
        }
    }

    private void loadAdapter() {
        WorldEdit worldEdit = WorldEdit.getInstance();

        // Attempt to load a Bukkit adapter
        BukkitImplLoader adapterLoader = new BukkitImplLoader();
        adapterLoader.addClass(Spigot_v1_13_R1.class);

        try {
            adapterLoader.addFromPath(getClass().getClassLoader());
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to search path for Bukkit adapters");
        }

        try {
            adapterLoader.addFromJar(getFile());
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to search " + getFile() + " for Bukkit adapters", e);
        }
        try {
            bukkitAdapter = adapterLoader.loadAdapter();
            log.log(Level.INFO, "Using " + bukkitAdapter.getClass().getCanonicalName() + " as the Bukkit adapter");
        } catch (AdapterLoadException e) {
            try {
                Platform platform = worldEdit.getPlatformManager().queryCapability(Capability.WORLD_EDITING);
                if (platform instanceof BukkitServerInterface) {
                    log.log(Level.WARNING, e.getMessage());
                    return;
                } else {
                    log.log(Level.INFO, "WorldEdit could not find a Bukkit adapter for this MC version, " +
                            "but it seems that you have another implementation of WorldEdit installed (" + platform.getPlatformName() + ") " +
                            "that handles the world editing.");
                }
            } catch (NoCapablePlatformException ignore) {}
            log.log(Level.INFO, "WorldEdit could not find a Bukkit adapter for this MC version");
        }
    }

    /**
     * Called on plugin disable.
     */
    @Override
    public void onDisable() {
        Fawe.get().onDisable();
        WorldEdit worldEdit = WorldEdit.getInstance();
        worldEdit.getSessionManager().clear();
        worldEdit.getPlatformManager().unregister(server);
        if (config != null) {
            config.unload();
        }
        if (server != null) {
            server.unregisterCommands();
        }
        this.getServer().getScheduler().cancelTasks(this);
    }

    /**
     * Loads and reloads all configuration.
     */
    protected void loadConfiguration() {
        config.unload();
        config.load();
        getPermissionsResolver().load();
    }

    /**
     * Create a default configuration file from the .jar.
     *
     * @param name the filename
     */
    protected void createDefaultConfiguration(String name) {
        File actual = new File(getDataFolder(), name);
        if (!actual.exists()) {
            InputStream input = null;
            try {
                JarFile file = new JarFile(getFile());
                ZipEntry copy = file.getEntry("defaults/" + name);
                if (copy == null) throw new FileNotFoundException();
                input = file.getInputStream(copy);
            } catch (IOException e) {
                getLogger().severe("Unable to read default configuration: " + name);
            }
            if (input != null) {
                FileOutputStream output = null;

                try {
                    output = new FileOutputStream(actual);
                    byte[] buf = new byte[8192];
                    int length;
                    while ((length = input.read(buf)) > 0) {
                        output.write(buf, 0, length);
                    }

                    getLogger().info("Default configuration file written: " + name);
                } catch (IOException e) {
                    getLogger().log(Level.WARNING, "Failed to write default config file", e);
                } finally {
                    try {
                        input.close();
                    } catch (IOException ignored) {}

                    try {
                        if (output != null) {
                            output.close();
                        }
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
        // Add the command to the array because the underlying command handling
        // code of WorldEdit expects it
        String[] split = new String[args.length + 1];
        System.arraycopy(args, 0, split, 1, args.length);
        split[0] = cmd.getName();

        CommandEvent event = new CommandEvent(wrapCommandSender(sender), Joiner.on(" ").join(split));
        getWorldEdit().getEventBus().post(event);

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String commandLabel, String[] args) {
        // Add the command to the array because the underlying command handling
        // code of WorldEdit expects it
        String[] split = new String[args.length + 1];
        System.arraycopy(args, 0, split, 1, args.length);
        split[0] = cmd.getName();

        CommandSuggestionEvent event = new CommandSuggestionEvent(wrapCommandSender(sender), Joiner.on(" ").join(split));
        getWorldEdit().getEventBus().post(event);
        return event.getSuggestions();
    }

    /**
     * Gets the session for the player.
     *
     * @param player a player
     * @return a session
     */
    public LocalSession getSession(Player player) {
        return WorldEdit.getInstance().getSessionManager().get(wrapPlayer(player));
    }

    /**
     * Gets the session for the player.
     *
     * @param player a player
     * @return a session
     */
    public EditSession createEditSession(Player player) {
        com.sk89q.worldedit.entity.Player wePlayer = wrapPlayer(player);
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(wePlayer);
        BlockBag blockBag = session.getBlockBag(wePlayer);

        EditSession editSession = WorldEdit.getInstance().getEditSessionFactory()
                .getEditSession(wePlayer.getWorld(), session.getBlockChangeLimit(), blockBag, wePlayer);
        editSession.enableQueue();

        return editSession;
    }

    /**
     * Remember an edit session.
     *
     * @param player a player
     * @param editSession an edit session
     */
    public void remember(Player player, EditSession editSession) {
        com.sk89q.worldedit.entity.Player wePlayer = wrapPlayer(player);
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(wePlayer);

        session.remember(editSession);
        editSession.flushQueue();

        WorldEdit.getInstance().flushBlockBag(wePlayer, editSession);
    }

    /**
     * Returns the configuration used by WorldEdit.
     *
     * @return the configuration
     */
    public BukkitConfiguration getLocalConfiguration() {
        return config;
    }

    /**
     * Get the permissions resolver in use.
     *
     * @return the permissions resolver
     */
    public PermissionsResolverManager getPermissionsResolver() {
        return PermissionsResolverManager.getInstance();
    }

    /**
     * Used to wrap a Bukkit Player as a WorldEdit Player.
     *
     * @param player a player
     * @return a wrapped player
     */
    public BukkitPlayer wrapPlayer(Player player) {
        return new BukkitPlayer(this, player);
    }

    public Actor wrapCommandSender(CommandSender sender) {
        if (sender instanceof Player) {
            return wrapPlayer((Player) sender);
        }

        return new BukkitCommandSender(this, sender);
    }

    BukkitServerInterface getInternalPlatform() {
        return server;
    }

    /**
     * Get WorldEdit.
     *
     * @return an instance
     */
    public WorldEdit getWorldEdit() {
        return WorldEdit.getInstance();
    }

    /**
     * Gets the instance of this plugin.
     *
     * @return an instance of the plugin
     * @throws NullPointerException if the plugin hasn't been enabled
     */
    public static WorldEditPlugin getInstance() {
        return checkNotNull(INSTANCE);
    }

    /**
     * Get the Bukkit implementation adapter.
     *
     * @return the adapter
     */
    @Nullable
    public BukkitImplAdapter getBukkitImplAdapter() {
        return bukkitAdapter;
    }

}
