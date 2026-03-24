package fr.angel.dynamicisland.plugins

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import fr.angel.dynamicisland.plugins.battery.BatteryPlugin
import fr.angel.dynamicisland.plugins.media.MediaSessionPlugin
import fr.angel.dynamicisland.plugins.notification.NotificationPlugin
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enhanced ExportedPlugins with comprehensive permission management
 *
 * Key improvements:
 * - Thread-safe permission management
 * - Async permission checking
 * - Comprehensive error handling
 * - Automatic permission refresh
 * - Better plugin lifecycle management
 */
class ExportedPlugins {

	companion object {
		private val isInitialized = AtomicBoolean(false)
		private val permissionCheckScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
		private val mainHandler = Handler(Looper.getMainLooper())

		// Thread-safe permission storage
		val permissions: SnapshotStateMap<String, PluginPermission> = mutableStateMapOf()

		// Thread-safe plugin storage
		private val pluginMap = ConcurrentHashMap<String, BasePlugin>()

		val plugins: List<BasePlugin>
			get() = pluginMap.values.toList()

		init {
			initializePermissions()
			initializePlugins()
		}

		private fun initializePermissions() {
			try {
				// Core permissions required for the app to function
				permissions[Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS] = NotificationListenerPermission()
				permissions[Settings.ACTION_ACCESSIBILITY_SETTINGS] = AccessibilityServicePermission()
				permissions["SYSTEM_ALERT_WINDOW"] = SystemAlertWindowPermission()

				// Optional permissions for enhanced functionality
				permissions["MEDIA_SESSION"] = MediaSessionPermission()
				permissions["BATTERY_INFO"] = BatteryInfoPermission()

				Log.d("ExportedPlugins", "Initialized ${permissions.size} permissions")
			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error initializing permissions", e)
			}
		}

		private fun initializePlugins() {
			try {
				val corePlugins = listOf(
					NotificationPlugin(),
					MediaSessionPlugin(),
					BatteryPlugin()
				)

				corePlugins.forEach { plugin ->
					pluginMap[plugin.id] = plugin
				}

				Log.d("ExportedPlugins", "Initialized ${pluginMap.size} plugins")
			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error initializing plugins", e)
			}
		}

		/**
		 * Setup permissions with comprehensive error handling and async checking
		 */
		fun setupPermissions(context: Context) {
			if (isInitialized.getAndSet(true)) {
				// Already initialized, just refresh
				refreshPermissions(context)
				return
			}

			try {
				Log.d("ExportedPlugins", "Setting up permissions...")

				// Initial synchronous check for critical permissions
				permissions.forEach { (id, permission) ->
					try {
						if (permission.isRequired) {
							permission.granted.value = permission.checkPermission(context)
							Log.d("ExportedPlugins", "Permission $id: ${permission.granted.value}")
						}
					} catch (e: Exception) {
						Log.e("ExportedPlugins", "Error checking permission $id", e)
						permission.granted.value = false
					}
				}

				// Async check for optional permissions
				permissionCheckScope.launch {
					checkOptionalPermissionsAsync(context)
				}

			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error setting up permissions", e)
			}
		}

		/**
		 * Refresh all permissions asynchronously
		 */
		fun refreshPermissions(context: Context) {
			permissionCheckScope.launch {
				try {
					permissions.forEach { (id, permission) ->
						try {
							val wasGranted = permission.granted.value
							val isGranted = permission.checkPermission(context)

							mainHandler.post {
								permission.granted.value = isGranted
							}

							if (wasGranted != isGranted) {
								Log.d("ExportedPlugins", "Permission $id changed: $wasGranted -> $isGranted")
							}
						} catch (e: Exception) {
							Log.e("ExportedPlugins", "Error refreshing permission $id", e)
						}
					}
				} catch (e: Exception) {
					Log.e("ExportedPlugins", "Error during permission refresh", e)
				}
			}
		}

		private suspend fun checkOptionalPermissionsAsync(context: Context) {
			try {
				permissions.forEach { (id, permission) ->
					if (!permission.isRequired) {
						try {
							val isGranted = withContext(Dispatchers.Main) {
								permission.checkPermission(context)
							}

							mainHandler.post {
								permission.granted.value = isGranted
							}

						} catch (e: Exception) {
							Log.e("ExportedPlugins", "Error checking optional permission $id", e)
						}
					}
				}
			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error in async permission check", e)
			}
		}

		/**
		 * Get plugin safely with error handling
		 */
		fun getPlugin(pluginId: String): BasePlugin? {
			return try {
				pluginMap[pluginId]
			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error getting plugin $pluginId", e)
				null
			}
		}

		/**
		 * Check if all required permissions are granted
		 */
		fun areRequiredPermissionsGranted(): Boolean {
			return try {
				permissions.values.all { permission ->
					!permission.isRequired || permission.granted.value
				}
			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error checking required permissions", e)
				false
			}
		}

		/**
		 * Get list of missing required permissions
		 */
		fun getMissingRequiredPermissions(): List<PluginPermission> {
			return try {
				permissions.values.filter { permission ->
					permission.isRequired && !permission.granted.value
				}
			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error getting missing permissions", e)
				emptyList()
			}
		}

		/**
		 * Check specific permission by key
		 */
		fun isPermissionGranted(permissionKey: String): Boolean {
			return try {
				permissions[permissionKey]?.granted?.value ?: false
			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error checking specific permission $permissionKey", e)
				false
			}
		}

		/**
		 * Request permission refresh for specific permission
		 */
		fun refreshSpecificPermission(context: Context, permissionKey: String) {
			permissionCheckScope.launch {
				try {
					permissions[permissionKey]?.let { permission ->
						val isGranted = permission.checkPermission(context)
						mainHandler.post {
							permission.granted.value = isGranted
						}
						Log.d("ExportedPlugins", "Refreshed permission $permissionKey: $isGranted")
					}
				} catch (e: Exception) {
					Log.e("ExportedPlugins", "Error refreshing permission $permissionKey", e)
				}
			}
		}

		/**
		 * Get permission by key safely
		 */
		fun getPermission(permissionKey: String): PluginPermission? {
			return try {
				permissions[permissionKey]
			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error getting permission $permissionKey", e)
				null
			}
		}

		/**
		 * Clean up resources
		 */
		fun cleanup() {
			try {
				permissionCheckScope.cancel()
				Log.d("ExportedPlugins", "Cleaned up ExportedPlugins resources")
			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error during cleanup", e)
			}
		}

		/**
		 * Check if plugin has all required permissions
		 */
		fun hasPluginPermissions(plugin: BasePlugin): Boolean {
			return try {
				plugin.permissions.all { permissionKey ->
					permissions[permissionKey]?.granted?.value ?: false
				}
			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error checking plugin permissions for ${plugin.id}", e)
				false
			}
		}

		/**
		 * Get missing permissions for a specific plugin
		 */
		fun getPluginMissingPermissions(plugin: BasePlugin): List<String> {
			return try {
				plugin.permissions.filter { permissionKey ->
					permissions[permissionKey]?.granted?.value != true
				}
			} catch (e: Exception) {
				Log.e("ExportedPlugins", "Error getting missing permissions for ${plugin.id}", e)
				emptyList()
			}
		}
	}
}