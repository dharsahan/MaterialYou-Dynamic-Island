package fr.angel.dynamicisland.plugins

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import fr.angel.dynamicisland.plugins.battery.BatteryPlugin
import fr.angel.dynamicisland.plugins.media.MediaSessionPlugin
import fr.angel.dynamicisland.plugins.notification.NotificationPlugin
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe PluginManager with proper lifecycle management
 *
 * Key improvements:
 * - Thread-safe plugin operations with proper locking
 * - Coroutine-based async operations
 * - Comprehensive error handling
 * - Proper plugin lifecycle management
 * - Priority-based plugin ordering
 */
class PluginManager(
    private val context: Context,
    private val host: PluginHost
) {
    // Thread-safe plugin storage
    private val pluginMap = ConcurrentHashMap<String, BasePlugin>()
    private val activePluginMap = ConcurrentHashMap<String, BasePlugin>()

    // Compose-observable collections (synchronized externally)
    val activePlugins = mutableStateListOf<BasePlugin>()

    // Thread synchronization
    private val pluginLock = ReentrantReadWriteLock()
    private val isInitialized = AtomicBoolean(false)
    private val isDestroyed = AtomicBoolean(false)

    // Coroutine management
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Plugin priority order (lower number = higher priority)
    private val pluginPriorities = mapOf(
        "notification" to 1,
        "media" to 2,
        "battery" to 3
    )

    val allPlugins: List<BasePlugin>
        get() = pluginLock.read { pluginMap.values.toList() }

    init {
        initializePlugins()
    }

    private fun initializePlugins() {
        pluginLock.write {
            try {
                // Initialize core plugins
                val corePlugins = listOf(
                    NotificationPlugin(),
                    MediaSessionPlugin(),
                    BatteryPlugin()
                )

                corePlugins.forEach { plugin ->
                    pluginMap[plugin.id] = plugin
                    Log.d("PluginManager", "Registered plugin: ${plugin.id}")
                }

                Log.d("PluginManager", "Initialized ${pluginMap.size} plugins")
            } catch (e: Exception) {
                Log.e("PluginManager", "Error initializing plugins", e)
            }
        }
    }

    fun initialize() {
        if (isDestroyed.get()) {
            Log.w("PluginManager", "Cannot initialize destroyed manager")
            return
        }

        managerScope.launch {
            try {
                pluginLock.write {
                    pluginMap.values.forEach { plugin ->
                        try {
                            initializePlugin(plugin)
                        } catch (e: Exception) {
                            Log.e("PluginManager", "Failed to initialize plugin: ${plugin.id}", e)
                        }
                    }
                }

                isInitialized.set(true)
                Log.d("PluginManager", "Plugin manager initialization completed")

            } catch (e: Exception) {
                Log.e("PluginManager", "Error during plugin manager initialization", e)
            }
        }
    }

    private fun initializePlugin(plugin: BasePlugin) {
        try {
            // Check if plugin is enabled
            plugin.enabled.value = plugin.isPluginEnabled(context)

            if (plugin.active) {
                plugin.onCreate(host)
                Log.d("PluginManager", "Plugin ${plugin.id} activated")
            }
        } catch (e: Exception) {
            Log.e("PluginManager", "Error initializing plugin ${plugin.id}", e)
        }
    }

    fun onDestroy() {
        if (isDestroyed.getAndSet(true)) {
            return // Already destroyed
        }

        Log.d("PluginManager", "Destroying plugin manager")

        try {
            // Cancel all coroutines
            managerScope.cancel()

            pluginLock.write {
                try {
                    // Destroy all plugins in reverse priority order
                    pluginMap.values
                        .sortedByDescending { getPluginPriority(it.id) }
                        .forEach { plugin ->
                            try {
                                plugin.onDestroy()
                                Log.d("PluginManager", "Plugin ${plugin.id} destroyed")
                            } catch (e: Exception) {
                                Log.e("PluginManager", "Error destroying plugin ${plugin.id}", e)
                            }
                        }

                    // Clear collections
                    activePlugins.clear()
                    activePluginMap.clear()
                    pluginMap.clear()

                } catch (e: Exception) {
                    Log.e("PluginManager", "Error during plugin destruction", e)
                }
            }

        } catch (e: Exception) {
            Log.e("PluginManager", "Error destroying plugin manager", e)
        }

        isInitialized.set(false)
        Log.d("PluginManager", "Plugin manager destroyed")
    }

    fun requestDisplay(plugin: BasePlugin) {
        if (!isValidForOperation()) return

        managerScope.launch {
            try {
                pluginLock.write {
                    val pluginId = plugin.id

                    // Check if plugin exists and is enabled
                    val registeredPlugin = pluginMap[pluginId]
                    if (registeredPlugin == null) {
                        Log.w("PluginManager", "Plugin not registered: $pluginId")
                        return@write
                    }

                    if (!registeredPlugin.enabled.value) {
                        Log.w("PluginManager", "Plugin not enabled: $pluginId")
                        return@write
                    }

                    // Add to active plugins if not already present
                    if (!activePluginMap.containsKey(pluginId)) {
                        activePluginMap[pluginId] = registeredPlugin

                        // Insert in priority order
                        insertPluginByPriority(registeredPlugin)

                        Log.d("PluginManager", "Plugin $pluginId added to active list")
                    }
                }
            } catch (e: Exception) {
                Log.e("PluginManager", "Error requesting display for plugin ${plugin.id}", e)
            }
        }
    }

    fun requestDismiss(plugin: BasePlugin) {
        if (!isValidForOperation()) return

        managerScope.launch {
            try {
                pluginLock.write {
                    val pluginId = plugin.id

                    activePluginMap.remove(pluginId)?.let { removedPlugin ->
                        activePlugins.remove(removedPlugin)
                        Log.d("PluginManager", "Plugin $pluginId removed from active list")
                    }
                }
            } catch (e: Exception) {
                Log.e("PluginManager", "Error requesting dismiss for plugin ${plugin.id}", e)
            }
        }
    }

    private fun insertPluginByPriority(plugin: BasePlugin) {
        val priority = getPluginPriority(plugin.id)
        val insertIndex = activePlugins.indexOfFirst {
            getPluginPriority(it.id) > priority
        }

        if (insertIndex == -1) {
            activePlugins.add(plugin)
        } else {
            activePlugins.add(insertIndex, plugin)
        }
    }

    private fun getPluginPriority(pluginId: String): Int {
        return pluginPriorities[pluginId] ?: Int.MAX_VALUE
    }

    private fun isValidForOperation(): Boolean {
        if (isDestroyed.get()) {
            Log.w("PluginManager", "Operation attempted on destroyed manager")
            return false
        }

        if (!isInitialized.get()) {
            Log.w("PluginManager", "Operation attempted on uninitialized manager")
            return false
        }

        return true
    }

    /**
     * Get plugin by ID safely
     */
    fun getPlugin(id: String): BasePlugin? {
        return pluginLock.read {
            pluginMap[id]
        }
    }

    /**
     * Check if plugin is active
     */
    fun isPluginActive(id: String): Boolean {
        return pluginLock.read {
            activePluginMap.containsKey(id)
        }
    }

    /**
     * Get all active plugins safely
     */
    fun getActivePlugins(): List<BasePlugin> {
        return pluginLock.read {
            activePlugins.toList()
        }
    }

    /**
     * Force refresh all plugin states
     */
    fun refreshAllPlugins() {
        if (!isValidForOperation()) return

        managerScope.launch {
            try {
                pluginLock.write {
                    pluginMap.values.forEach { plugin ->
                        try {
                            val wasEnabled = plugin.enabled.value
                            plugin.enabled.value = plugin.isPluginEnabled(context)

                            if (wasEnabled != plugin.enabled.value) {
                                Log.d("PluginManager", "Plugin ${plugin.id} enabled state changed to ${plugin.enabled.value}")
                            }
                        } catch (e: Exception) {
                            Log.e("PluginManager", "Error refreshing plugin ${plugin.id}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PluginManager", "Error refreshing plugins", e)
            }
        }
    }
}
