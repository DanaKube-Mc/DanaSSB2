package com.bgsoftware.superiorskyblock.external.worlds;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.config.SettingsManager;
import com.bgsoftware.superiorskyblock.api.hooks.WorldsProvider;
import com.bgsoftware.superiorskyblock.api.hooks.listener.IWorldLoadListener;
import com.bgsoftware.superiorskyblock.api.hooks.world.WorldLoadFlags;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.wrappers.BlockPosition;
import com.bgsoftware.superiorskyblock.core.SBlockPosition;
import com.bgsoftware.superiorskyblock.core.collections.EnumerateMap;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventType;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsDispatcher;
import com.bgsoftware.superiorskyblock.world.WorldGenerator;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.BlockFace;
import com.bgsoftware.superiorskyblock.api.enums.GeneratorHint;

import java.util.*;

public class WorldsProvider_Default implements WorldsProvider {

    @WorldLoadFlags
    private static final int SUPPORTED_LOAD_FLAGS = WorldLoadFlags.END_DRAGON_FIGHT | WorldLoadFlags.REMOVE_ANTI_XRAY |
            WorldLoadFlags.UPDATE_OCEAN_LEVEL | WorldLoadFlags.LISTEN_BLOCK_CHANGES;

    private final Set<BlockPosition> servedPositions = Sets.newHashSet();
    private final EnumerateMap<Dimension, World> islandWorlds = new EnumerateMap<>(Dimension.values());
    private final Map<UUID, Dimension> islandWorldsToDimensions = new HashMap<>();
    private final List<IWorldLoadListener> worldLoadListenerList = new LinkedList<>();
    private final SuperiorSkyblockPlugin plugin;

    private World islandsWorld;

    public WorldsProvider_Default(SuperiorSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void prepareWorlds() {
        Difficulty difficulty = Difficulty.valueOf(plugin.getSettings().getWorlds().getDifficulty());
        for (Dimension dimension : Dimension.values()) {
            SettingsManager.Worlds.DimensionConfig dimensionConfig = plugin.getSettings().getWorlds().getDimensionConfig(dimension);
            if (dimensionConfig != null && dimensionConfig.isEnabled()) {
                String worldName = dimensionConfig.getName();
                World world = loadWorld(worldName, difficulty, dimension);
                if (dimension == plugin.getSettings().getWorlds().getDefaultWorldDimension())
                    this.islandsWorld = world;
            }
        }
    }

    @Override
    public World getIslandsWorld(Island island, Dimension dimension) {
        Preconditions.checkNotNull(dimension, "dimension parameter cannot be null.");
        return islandWorlds.get(dimension);
    }

    @Override
    public Dimension getIslandsWorldDimension(World world) {
        Preconditions.checkNotNull(world, "world parameter cannot be null.");
        return islandWorldsToDimensions.get(world.getUID());
    }

    @Override
    public boolean isIslandsWorld(World world) {
        Preconditions.checkNotNull(world, "world parameter cannot be null.");
        return islandWorldsToDimensions.containsKey(world.getUID());
    }

    @Override
    public Location getNextLocation(BlockPosition previousPosition, int islandsHeight, int maxIslandSize, UUID islandOwner, UUID islandUUID) {
        Preconditions.checkNotNull(previousPosition, "previousPosition parameter cannot be null.");

        BlockFace islandFace = getIslandFace(previousPosition);

        BlockPosition nextPosition;

        int islandRange = maxIslandSize * 3;

        if (islandFace == BlockFace.NORTH) {
            nextPosition = nextPosition(previousPosition, islandsHeight, islandRange, 0);
        } else if (islandFace == BlockFace.EAST) {
            if (previousPosition.getX() == -previousPosition.getZ())
                nextPosition = nextPosition(previousPosition, islandsHeight, islandRange, 0);
            else if (previousPosition.getX() == previousPosition.getZ())
                nextPosition = nextPosition(previousPosition, islandsHeight, -islandRange, 0);
            else
                nextPosition = nextPosition(previousPosition, islandsHeight, 0, islandRange);
        } else if (islandFace == BlockFace.SOUTH) {
            if (previousPosition.getX() == -previousPosition.getZ())
                nextPosition = nextPosition(previousPosition, islandsHeight, 0, -islandRange);
            else
                nextPosition = nextPosition(previousPosition, islandsHeight, -islandRange, 0);
        } else if (islandFace == BlockFace.WEST) {
            if (previousPosition.getX() == previousPosition.getZ())
                nextPosition = nextPosition(previousPosition, islandsHeight, islandRange, 0);
            else
                nextPosition = nextPosition(previousPosition, islandsHeight, 0, -islandRange);
        } else {
            throw new IllegalStateException();
        }

        Location nextLocation = nextPosition.toLocation(this.islandsWorld);

        if (servedPositions.contains(nextPosition) || plugin.getGrid().getIslandAt(nextLocation) != null) {
            return getNextLocation(nextPosition, islandsHeight, maxIslandSize, islandOwner, islandUUID);
        }

        servedPositions.add(nextPosition);

        return nextLocation;
    }

    @Override
    public void finishIslandCreation(Location islandLocation, UUID islandOwner, UUID islandUUID) {
        Preconditions.checkNotNull(islandLocation, "islandLocation parameter cannot be null.");
        servedPositions.remove(SBlockPosition.of(islandLocation));
    }

    @Override
    public void prepareTeleport(Island island, Location location, Runnable finishCallback) {
        finishCallback.run();
    }

    @Override
    public boolean isDimensionEnabled(Dimension dimension) {
        SettingsManager.Worlds.DimensionConfig dimensionConfig = plugin.getSettings().getWorlds().getDimensionConfig(dimension);
        // If the config is null, it probably means another plugin registered it.
        // Therefore, we register it as enabled.
        return dimensionConfig == null || dimensionConfig.isEnabled();
    }

    @Override
    public boolean isDimensionUnlocked(Dimension dimension) {
        SettingsManager.Worlds.DimensionConfig dimensionConfig = plugin.getSettings().getWorlds().getDimensionConfig(dimension);
        // If the config is null, it probably means another plugin registered it.
        // Therefore, we register it as not unlocked by default.
        return dimensionConfig != null && dimensionConfig.isEnabled() && dimensionConfig.isUnlocked();
    }

    @Override
    public void addWorldLoadListener(IWorldLoadListener worldLoadListener) {
        Preconditions.checkNotNull(worldLoadListener, "worldLoadListener parameter cannot be null");
        this.worldLoadListenerList.add(worldLoadListener);
    }

    private void notifyWorldLoadListeners(World world, Dimension worldDimension) {
        for (IWorldLoadListener worldLoadListener : this.worldLoadListenerList)
            worldLoadListener.onWorldLoad(world, worldDimension, SUPPORTED_LOAD_FLAGS);
    }

    private BlockFace getIslandFace(BlockPosition blockPosition) {
        //Possibilities: North / East
        if (blockPosition.getX() >= blockPosition.getZ()) {
            return -blockPosition.getX() > blockPosition.getZ() ? BlockFace.NORTH : BlockFace.EAST;
        }
        //Possibilities: South / West
        else {
            return -blockPosition.getX() > blockPosition.getZ() ? BlockFace.WEST : BlockFace.SOUTH;
        }
    }

    private World loadWorld(String worldName, Difficulty difficulty, Dimension dimension) {
        World world = getRegisteredServerWorld(worldName);
        
        if (world != null) {
            plugin.getLogger().warning("The world " + worldName + " is already loaded! SuperiorSkyblock will hook into it. Ensure this is not your main survival world!");
        } else {
            SettingsManager.Worlds.DimensionConfig dimensionConfig = plugin.getSettings().getWorlds().getDimensionConfig(dimension);
            boolean useVoidGenerator = dimensionConfig == null || dimensionConfig.getGeneratorHint() == GeneratorHint.VOID;

            WorldCreator worldCreator = WorldCreator.name(worldName)
                    .environment(dimension.getEnvironment());

            if (useVoidGenerator) {
                worldCreator.type(WorldType.FLAT).generator(WorldGenerator.getWorldGenerator(dimension));
            }

            try {
                world = worldCreator.createWorld();
            } catch (IllegalArgumentException ex) {
                world = getRegisteredServerWorld(worldName);
                if (world != null) {
                    plugin.getLogger().warning("The world " + worldName + " was already registered in the server! SuperiorSkyblock will hook into it.");
                } else {
                    throw ex;
                }
            }
        }

        world.setDifficulty(difficulty);
        islandWorlds.put(dimension, world);
        islandWorldsToDimensions.put(world.getUID(), dimension);

        notifyWorldLoadListeners(world, dimension);

        if (Bukkit.getPluginManager().isPluginEnabled("Multiverse-Core")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv import " + worldName + " normal -g " + plugin.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv modify set generator " + plugin.getName() + " " + worldName);
        }

        return world;
    }

    private static World getRegisteredServerWorld(String worldName) {
        if (worldName == null || worldName.isEmpty())
            return null;

        String lowerWorldName = worldName.toLowerCase(Locale.ENGLISH);

        // 1. Direct Bukkit check by name
        World world = Bukkit.getWorld(worldName);
        if (world != null)
            return world;

        world = Bukkit.getWorld(lowerWorldName);
        if (world != null)
            return world;

        // 2. Iterate existing loaded Bukkit worlds
        for (World loadedWorld : Bukkit.getWorlds()) {
            if (loadedWorld.getName().equalsIgnoreCase(worldName) || loadedWorld.getName().equalsIgnoreCase(lowerWorldName)) {
                return loadedWorld;
            }
            try {
                java.lang.reflect.Method getKeyMethod = loadedWorld.getClass().getMethod("getKey");
                Object keyObj = getKeyMethod.invoke(loadedWorld);
                if (keyObj instanceof org.bukkit.NamespacedKey) {
                    org.bukkit.NamespacedKey key = (org.bukkit.NamespacedKey) keyObj;
                    if (key.getKey().equalsIgnoreCase(worldName) || key.toString().equalsIgnoreCase(worldName)) {
                        return loadedWorld;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. Try Bukkit.getWorld(NamespacedKey) via reflection if available
        try {
            Class<?> namespacedKeyClass = Class.forName("org.bukkit.NamespacedKey");
            java.lang.reflect.Method minecraftKeyMethod = namespacedKeyClass.getMethod("minecraft", String.class);
            Object nsKey = minecraftKeyMethod.invoke(null, lowerWorldName);
            if (nsKey != null) {
                java.lang.reflect.Method getWorldMethod = Bukkit.class.getMethod("getWorld", namespacedKeyClass);
                world = (World) getWorldMethod.invoke(null, nsKey);
                if (world != null)
                    return world;
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> namespacedKeyClass = Class.forName("org.bukkit.NamespacedKey");
            java.lang.reflect.Method fromStringMethod = namespacedKeyClass.getMethod("fromString", String.class);
            Object nsKey = fromStringMethod.invoke(null, lowerWorldName);
            if (nsKey != null) {
                java.lang.reflect.Method getWorldMethod = Bukkit.class.getMethod("getWorld", namespacedKeyClass);
                world = (World) getWorldMethod.invoke(null, nsKey);
                if (world != null)
                    return world;
            }
        } catch (Throwable ignored) {
        }

        // 4. Look into NMS MinecraftServer levels
        try {
            org.bukkit.Server server = Bukkit.getServer();
            java.lang.reflect.Method getServerMethod = server.getClass().getMethod("getServer");
            Object minecraftServer = getServerMethod.invoke(server);
            if (minecraftServer == null)
                return null;

            List<Object> serverLevels = new ArrayList<>();

            // Search in methods (getAllLevels, levels, etc.)
            for (Class<?> clazz = minecraftServer.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0) {
                        String methodName = m.getName();
                        if (methodName.equals("getAllLevels") || methodName.equals("levels") || methodName.equals("F") || methodName.equals("J")) {
                            try {
                                m.setAccessible(true);
                                Object result = m.invoke(minecraftServer);
                                if (result instanceof Iterable) {
                                    for (Object obj : (Iterable<?>) result) {
                                        if (obj != null && !serverLevels.contains(obj)) serverLevels.add(obj);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }

            // Search in fields (levels map, etc.)
            for (Class<?> clazz = minecraftServer.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(minecraftServer);
                        if (val instanceof Map) {
                            for (Object entryVal : ((Map<?, ?>) val).values()) {
                                if (entryVal != null && entryVal.getClass().getName().contains("ServerLevel")) {
                                    if (!serverLevels.contains(entryVal)) serverLevels.add(entryVal);
                                }
                            }
                        } else if (val instanceof Iterable) {
                            for (Object obj : (Iterable<?>) val) {
                                if (obj != null && obj.getClass().getName().contains("ServerLevel")) {
                                    if (!serverLevels.contains(obj)) serverLevels.add(obj);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            for (Object level : serverLevels) {
                if (level == null)
                    continue;

                boolean matches = false;
                String dimensionPath = null;
                String dimensionFull = null;

                // Extract dimension ResourceKey / ResourceLocation
                try {
                    for (java.lang.reflect.Method m : level.getClass().getMethods()) {
                        if (m.getName().equals("dimension") && m.getParameterCount() == 0) {
                            Object resourceKey = m.invoke(level);
                            if (resourceKey != null) {
                                for (java.lang.reflect.Method locM : resourceKey.getClass().getMethods()) {
                                    if ((locM.getName().equals("location") || locM.getName().equals("identifier")) && locM.getParameterCount() == 0) {
                                        Object locObj = locM.invoke(resourceKey);
                                        if (locObj != null) {
                                            dimensionFull = locObj.toString().toLowerCase(Locale.ENGLISH);
                                            dimensionPath = dimensionFull.contains(":") ? dimensionFull.substring(dimensionFull.indexOf(':') + 1) : dimensionFull;
                                        }
                                        break;
                                    }
                                }

                                if (dimensionPath == null) {
                                    String rkString = resourceKey.toString().toLowerCase(Locale.ENGLISH);
                                    int lastColon = rkString.lastIndexOf(':');
                                    int lastSlash = rkString.lastIndexOf('/');
                                    int lastSep = Math.max(lastColon, lastSlash);
                                    if (lastSep >= 0 && lastSep < rkString.length() - 1) {
                                        dimensionPath = rkString.substring(lastSep + 1).replace("]", "").trim();
                                    }
                                }
                            }
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }

                if (dimensionPath != null && dimensionPath.equalsIgnoreCase(lowerWorldName)) {
                    matches = true;
                } else if (dimensionFull != null && (dimensionFull.equalsIgnoreCase(lowerWorldName) || dimensionFull.equalsIgnoreCase("minecraft:" + lowerWorldName))) {
                    matches = true;
                }

                // Try to get Bukkit World from level
                World bukkitWorld = null;
                try {
                    for (java.lang.reflect.Method m : level.getClass().getMethods()) {
                        if (m.getName().equals("getWorld") && m.getParameterCount() == 0) {
                            Object res = m.invoke(level);
                            if (res instanceof World) {
                                bukkitWorld = (World) res;
                            }
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }

                if (bukkitWorld != null) {
                    String bwName = bukkitWorld.getName().toLowerCase(Locale.ENGLISH);
                    if (bwName.equalsIgnoreCase(lowerWorldName) || bwName.equalsIgnoreCase("minecraft:" + lowerWorldName) ||
                            bwName.endsWith("/" + lowerWorldName) || bwName.endsWith(":" + lowerWorldName)) {
                        matches = true;
                    }
                }

                if (matches && bukkitWorld != null) {
                    registerWorldInBukkit(server, bukkitWorld, worldName);
                    return bukkitWorld;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerWorldInBukkit(org.bukkit.Server server, World bukkitWorld, String requestedName) {
        if (server == null || bukkitWorld == null)
            return;

        // Try addWorld method on CraftServer
        try {
            java.lang.reflect.Method addWorldMethod = server.getClass().getMethod("addWorld", World.class);
            addWorldMethod.invoke(server, bukkitWorld);
        } catch (Throwable ignored) {
        }

        Object bukkitKey = null;
        try {
            java.lang.reflect.Method getKeyMethod = bukkitWorld.getClass().getMethod("getKey");
            bukkitKey = getKeyMethod.invoke(bukkitWorld);
        } catch (Throwable ignored) {
        }

        Object customMinecraftKey = null;
        try {
            Class<?> namespacedKeyClass = Class.forName("org.bukkit.NamespacedKey");
            java.lang.reflect.Method minecraftKeyMethod = namespacedKeyClass.getMethod("minecraft", String.class);
            customMinecraftKey = minecraftKeyMethod.invoke(null, requestedName.toLowerCase(Locale.ENGLISH));
        } catch (Throwable ignored) {
        }

        // Inject in CraftServer maps and lists
        for (Class<?> clazz = server.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(server);
                    if (val instanceof Map) {
                        Map map = (Map) val;
                        map.put(requestedName.toLowerCase(Locale.ENGLISH), bukkitWorld);
                        map.put(bukkitWorld.getName().toLowerCase(Locale.ENGLISH), bukkitWorld);
                        map.put(bukkitWorld.getUID(), bukkitWorld);
                        if (bukkitKey != null) {
                            map.put(bukkitKey, bukkitWorld);
                        }
                        if (customMinecraftKey != null) {
                            map.put(customMinecraftKey, bukkitWorld);
                        }
                    } else if (val instanceof List) {
                        List list = (List) val;
                        if (!list.contains(bukkitWorld)) {
                            list.add(bukkitWorld);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static BlockPosition nextPosition(BlockPosition previousPosition, int islandsHeight, int offsetX, int offsetZ) {
        return SBlockPosition.of(previousPosition.getX() + offsetX, islandsHeight, previousPosition.getZ() + offsetZ);
    }

    public static void registerListeners(PluginEventsDispatcher dispatcher) {
        dispatcher.registerCallback(PluginEventType.SETTINGS_UPDATE_EVENT, WorldsProvider_Default::onSettingsUpdate);
    }

    private static void onSettingsUpdate() {
        WorldsProvider worldsProvider = SuperiorSkyblockPlugin.getPlugin().getProviders().getWorldsProvider();

        if (!(worldsProvider instanceof WorldsProvider_Default))
            return;

        WorldsProvider_Default worldsProviderDefault = (WorldsProvider_Default) worldsProvider;
        worldsProviderDefault.islandWorlds.values().forEach(SuperiorSkyblockPlugin.getPlugin().getNMSWorld()::setOceanLevel);
    }

}
