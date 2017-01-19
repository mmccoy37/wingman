package com.wingman.client.plugin;

import com.google.common.base.Throwables;
import com.google.common.io.Files;
import com.wingman.client.ClientSettings;
import com.wingman.client.api.event.Event;
import com.wingman.client.api.event.EventCallback;
import com.wingman.client.api.event.EventListener;
import com.wingman.client.api.event.EventListenerList;
import com.wingman.client.api.plugin.Plugin;
import com.wingman.client.api.plugin.PluginDependency;
import com.wingman.client.api.settings.PropertiesSettings;
import com.wingman.client.classloader.PluginClassLoader;
import com.wingman.client.plugin.exceptions.PluginLoadingException;
import com.wingman.client.plugin.exceptions.PluginRefreshingException;
import com.wingman.client.plugin.exceptions.PluginSetupException;
import com.wingman.client.plugin.toposort.PluginNode;
import com.wingman.client.ui.Client;
import org.reflections.Reflections;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.text.MessageFormat;
import java.util.*;

public class PluginManager {

    private static List<PluginContainer> plugins;

    private static Set<Class<? extends Event>> eventClasses = new HashSet<>();

    /**
     * Loads all plugins annotated with {@link Plugin} found by the client and plugin class loader. <br>
     * This method should only be called once ever.
     */
    public static void findAndSetupPlugins() throws Exception {
        System.out.println("Finding and setting up plugins");

        Set<Class<?>> pluginClasses = new Reflections(getPluginClassLoader()).getTypesAnnotatedWith(Plugin.class);
        plugins = parsePlugins(pluginClasses);
        plugins = parsePluginDependencies(plugins);
        plugins = sortPlugins(plugins);
        setupPlugins();
        bakeEventListeners();
    }

    /**
     * Adds all folders in {@link ClientSettings#PLUGINS_DIR}, and all of its containing JARs to a {@link PluginClassLoader}.
     *
     * @return a class loader with the supposed plugins within {@link ClientSettings#PLUGINS_DIR}
     * @throws IOException
     */
    private static PluginClassLoader getPluginClassLoader() throws IOException {
        Set<URL> pluginUrls = new HashSet<>();

        // Search for folders in the root path
        File[] pluginsRootFolderFiles = ClientSettings.PLUGINS_DIR.toFile().listFiles();
        if (pluginsRootFolderFiles != null) {
            for (File file : pluginsRootFolderFiles) {
                if (file.isDirectory()) {
                    pluginUrls.add(file.toURI().toURL());
                }
            }
        }

        // Search for JARed files
        for (File file : Files.fileTreeTraverser().preOrderTraversal(ClientSettings.PLUGINS_DIR.toFile())) {
            if (Files.getFileExtension(file.getName()).equalsIgnoreCase("jar")) {
                pluginUrls.add(new URL("jar:" + file.toURI().toString() + "!/"));
            }
        }

        return new PluginClassLoader(pluginUrls.toArray(new URL[pluginUrls.size()]),
                Plugin.class.getClassLoader());
    }

    /**
     * Constructs a list of {@link PluginContainer} out of a collection of classes supposed to be plugin classes.
     *
     * @param plugins a collection of plugin classes
     * @return a list containing {@link PluginContainer}-wrapped classes
     */
    private static List<PluginContainer> parsePlugins(Collection<Class<?>> plugins) {
        List<PluginContainer> pluginContainers = new LinkedList<>();
        Set<String> parsedPlugins = new HashSet<>();

        for (Class clazz : plugins) {
            PluginContainer pluginContainer = new PluginContainer(clazz);

            if (!parsedPlugins.contains(pluginContainer.pluginData.id())) {
                parsedPlugins.add(pluginContainer.pluginData.id());
                pluginContainers.add(pluginContainer);
            } else {
                System.out.println(MessageFormat.format("{0} was not loaded, because a plugin with the same ID had already been loaded.",
                        pluginContainer.pluginData.id()));
            }
        }

        return pluginContainers;
    }

    /**
     * Parses dependencies ({@link PluginDependency}) of plugins and attempts to match them with loaded plugins.
     */
    private static List<PluginContainer> parsePluginDependencies(List<PluginContainer> plugins) {
        Map<String, PluginContainer> pluginContainerMap = new HashMap<>();

        for (PluginContainer pluginContainer : plugins) {
            pluginContainerMap.put(pluginContainer.pluginData.id(), pluginContainer);
        }

        Iterator<PluginContainer> pluginIterator = plugins.iterator();
        while (pluginIterator.hasNext()) {
            PluginContainer pluginContainer = pluginIterator.next();
            try {
                for (PluginDependency pluginDependency : pluginContainer.originalDependencies) {
                    try {
                        PluginContainer dependencyContainer = pluginContainerMap.get(pluginDependency.id());
                        if (dependencyContainer != null) {
                            int version = Integer.parseInt(pluginDependency.minVersion().replace(".", ""));
                            if (dependencyContainer.pluginData.id().equals(pluginDependency.id())) {
                                try {
                                    int dependencyVersion = Integer.parseInt(dependencyContainer.pluginData.version().replace(".", ""));
                                    if (dependencyVersion >= version) {
                                        pluginContainer.dependencies.add(dependencyContainer);
                                    } else {
                                        throw new PluginLoadingException(pluginContainer.pluginData.id(),
                                                MessageFormat.format("Dependency plugin {0} version {1} is outdated, minimum version required: {2}",
                                                        dependencyContainer.pluginData.id(),
                                                        dependencyContainer.pluginData.version(),
                                                        pluginDependency.minVersion()));
                                    }
                                } catch (NumberFormatException e) {
                                    throw new PluginLoadingException(pluginContainer.pluginData.id(),
                                            MessageFormat.format("Could not parse version for plugin {0}: {1}",
                                                    dependencyContainer.pluginData.id(),
                                                    dependencyContainer.pluginData.version()));
                                }
                            }
                        } else {
                            throw new PluginLoadingException(pluginContainer.pluginData.id(),
                                    MessageFormat.format("The expected dependency plugin {0} of minimum version {1} is not installed.",
                                            pluginDependency.id(),
                                            pluginDependency.minVersion()));
                        }
                    } catch (NumberFormatException e) {
                        throw new PluginLoadingException(pluginContainer.pluginData.id(),
                                MessageFormat.format("Could not parse version for plugin dependency {0}: {1}",
                                        pluginDependency.id(),
                                        pluginDependency.minVersion()));
                    }
                }
            } catch (PluginLoadingException e) {
                e.printStackTrace();
                pluginIterator.remove();
            }
        }

        return plugins;
    }

    /**
     * Sort the loading order of plugins topologically based on dependencies, as to introduce loading dependencies before dependants.
     */
    private static List<PluginContainer> sortPlugins(List<PluginContainer> plugins) {
        Map<String, PluginNode> pluginNodes = new HashMap<>();
        for (PluginContainer pluginContainer : plugins) {
            pluginNodes.put(pluginContainer.pluginData.id(), new PluginNode(pluginContainer));
        }

        plugins = new ArrayList<>(pluginNodes.size() + 1);

        for (PluginNode pluginNode : pluginNodes.values()) {
            for (PluginContainer dependencyPlugin : pluginNode.pluginContainer.dependencies) {
                pluginNode.addChild(pluginNodes.get(dependencyPlugin.pluginData.id()));
            }
        }

        List<PluginNode> stack = new ArrayList<>(pluginNodes.size() + 1);

        for (PluginNode pluginNode : pluginNodes.values()) {
            if (!pluginNode.isVisited && !pluginNode.isDependent) {
                sort(pluginNode, stack);
            }
        }

        for (PluginNode pluginNode : stack) {
            plugins.add(pluginNode.pluginContainer);
        }

        return plugins;
    }

    /**
     * Sort the loading order of plugins topologically based on dependencies, as to introduce loading dependencies before dependants.
     */
    private static void sort(PluginNode pluginNode, List<PluginNode> stack) {
        for (PluginNode children : pluginNode.children) {
            if (!children.isVisited) {
                sort(children, stack);
            }
        }
        stack.add(pluginNode);
        pluginNode.isVisited = true;
    }

    /**
     * Bakes event listeners, transforming every set of event listeners into arrays for faster read access. <br>
     * This method should only be called once ever.
     */
    private static void bakeEventListeners() {
        for (Class<? extends Event> eventClass : eventClasses) {
            try {
                EventListenerList eventListenerList = (EventListenerList) eventClass
                        .getDeclaredField("eventListenerList")
                        .get(null);
                eventListenerList.bake();
            } catch (NoSuchFieldException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Registers a class that contains {@link EventCallback} annotated methods, and maps those methods correctly. <br>
     * This method must only be called within {@link com.wingman.client.api.plugin.Plugin.Setup} or in a constructor/initializer of a plugin.
     *
     * @param instance an instance of a class containing {@link EventCallback} annotated methods
     */
    public static void registerEventClass(final Object instance) {
        for (final Method method : instance.getClass().getMethods()) {
            if (method.isAnnotationPresent(EventCallback.class)) {
                try {
                    Class<?>[] argTypes = method.getParameterTypes();
                    if (argTypes.length > 0) {
                        Class<?> checkClass;
                        if (Event.class.isAssignableFrom(checkClass = argTypes[0])) {
                            final Class<? extends Event> eventClass = checkClass.asSubclass(Event.class);

                            EventListenerList eventListenerList = (EventListenerList) eventClass
                                    .getDeclaredField("eventListenerList")
                                    .get(null);

                            eventClasses.add(eventClass);

                            eventListenerList.register(new EventListener() {
                                @Override
                                public void runEvent(Event event) {
                                    try {
                                        method.invoke(instance, event);
                                    } catch (IllegalAccessException | InvocationTargetException e) {
                                        Throwables.propagate(e);
                                    }
                                }
                            });
                        }
                    }
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    Throwables.propagate(e);
                }
            }
        }
    }

    /**
     * Propagates an {@link Event} to its event listeners.
     *
     * @param event an instance of {@link Event}
     */
    public static void callEvent(Event event) {
        EventListener[] listeners = event.getListeners();
        if (listeners != null) {
            for (EventListener eventListener : listeners) {
                if (eventListener != null) {
                    eventListener.runEvent(event);
                }
            }
        }
    }

    /**
     * Sets all plugins up. <br>
     * Safely invokes ({@link PluginContainer#setupMethod}) of all detected plugins.
     */
    private static void setupPlugins() {
        for (PluginContainer plugin : plugins) {
            try {
                plugin.invokeSetupMethod();
            } catch (InvocationTargetException | IllegalAccessException e) {
                new PluginSetupException(plugin.pluginData.id(), e.toString())
                        .printStackTrace();
            }
        }
    }

    /**
     * Activate all plugins. <br>
     * Attempts to activate plugins from the directory. If the plugin is
     * toggleable then it will add a new item to the settings panel.
     */
    public static void activatePlugins() {
        if (plugins == null)
        {
            System.out.println("Plugins were not activated, because none had been loaded.");
            return;
        }
        //try to enable plugin
        PropertiesSettings activePluginSettings;
        try
        {
            activePluginSettings = new PluginSettings();
            for (PluginContainer plugin : plugins)
            {
                //plugins requests a GUI toggle
                if (plugin.pluginData.canToggle().equalsIgnoreCase("true"))
                {
                    Client.addPluginToggle(plugin);
                }
                //if PropertySetting is (not null && true) or Plugin's defaultToggle=true
                if ((activePluginSettings.get(plugin.pluginData.id()) != null &&
                        activePluginSettings.get(plugin.pluginData.id()).equalsIgnoreCase("true")) ||
                        plugin.pluginData.defaultToggle().equalsIgnoreCase("true"))
                {
                    try
                    {
                        activatePlugin(plugin);
                    }
                    catch (InvocationTargetException | IllegalAccessException e)
                    {
                        new PluginLoadingException(plugin.pluginData.id(), e.toString())
                                .printStackTrace();
                        //set to disabled state so plugin toggle doesnt use defaultToggle
                        activePluginSettings.update(plugin.pluginData.id(), "false");
                        activePluginSettings.save();
                    }
                }
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Activate a specific plugin <br>
     * Safely invokes ({@link PluginContainer#activateMethod}) of all loaded plugins.
     *
     * If the plugin fails to activate the settings toggle will be disabled.
     *
     * @param plugin what we are activating
     * @return true if success
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     */
    public static boolean activatePlugin(PluginContainer plugin) throws InvocationTargetException,IllegalAccessException {
        plugin.invokeActivateMethod();
        System.out.println(MessageFormat.format("Activated plugin {0} ({1} {2})",
                plugin.pluginData.name(),
                plugin.pluginData.id(),
                plugin.pluginData.version()));
        return true;
    }

    /**
     * Deactivate all plugins.
     */
    public static void deactivatePlugins() {
        if (plugins == null) {
            System.out.println("Plugins were not deactivated, because none had been loaded.");
            return;
        }
        for (PluginContainer plugin : plugins) {
            deactivatePlugin(plugin);
        }
    }

    /**
     * Deactivate a specific plugin <br>
     * Safely invokes ({@link PluginContainer#deactivateMethod}) of all loaded plugins.
     * @param plugin what we are deactivating
     */
    public static void deactivatePlugin(PluginContainer plugin) {
        try {
            plugin.invokeDeactivateMethod();
            System.out.println(MessageFormat.format("Deactivated plugin {0} ({1} {2})",
                    plugin.pluginData.name(),
                    plugin.pluginData.id(),
                    plugin.pluginData.version()));
        } catch (Exception e) {
            new PluginLoadingException(plugin.pluginData.id(), e.toString())
                    .printStackTrace();
        }
    }



    /**
     * Refresh all plugins. <br>
     * Safely invokes ({@link PluginContainer#refreshMethod}) of all loaded plugins.
     */
    public static void refreshPlugins() {
        for (PluginContainer plugin : plugins) {
            try {
                plugin.invokeRefreshMethod();
            } catch (InvocationTargetException | IllegalAccessException e) {
                new PluginRefreshingException(plugin.pluginData.id(), e.toString())
                        .printStackTrace();
            }
        }
    }
}
