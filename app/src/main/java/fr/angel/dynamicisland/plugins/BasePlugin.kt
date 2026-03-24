package fr.angel.dynamicisland.plugins

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import fr.angel.dynamicisland.model.SETTINGS_CHANGED
import fr.angel.dynamicisland.model.SETTINGS_KEY
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enhanced BasePlugin with comprehensive permission and lifecycle management
 *
 * Key improvements:
 * - Runtime permission validation before operations
 * - Thread-safe lifecycle management
 * - Comprehensive error handling
 * - Better resource cleanup
 * - Enhanced plugin state tracking
 */
abstract class BasePlugin {
	abstract val id: String
	abstract val name: String
	abstract val description: String
	abstract val permissions: ArrayList<String>
	abstract var enabled: MutableState<Boolean>
	abstract var pluginSettings: MutableMap<String, PluginSettingsItem>

	protected var host: PluginHost? = null
	private val isCreated = AtomicBoolean(false)
	private val isDestroyed = AtomicBoolean(false)

	/**
	 * Plugin is active if enabled and has all required permissions
	 * Now includes runtime permission validation
	 */
	val active: Boolean
		get() = try {
			enabled.value &&
			allPermissionsGranted &&
			!isDestroyed.get() &&
			validateRuntimePermissions()
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error checking plugin active state for $id", e)
			false
		}

	/**
	 * Check if plugin can expand with permission validation
	 */
	fun canExpandSafely(): Boolean {
		return try {
			active && canExpand()
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error checking expand capability for $id", e)
			false
		}
	}

	abstract fun canExpand(): Boolean

	/**
	 * Enhanced onCreate with proper error handling and permission validation
	 */
	fun onCreate(host: PluginHost) {
		if (isDestroyed.get()) {
			Log.w("BasePlugin", "Cannot create destroyed plugin: $id")
			return
		}

		try {
			// Validate permissions before creation
			if (!validatePermissionsForCreation()) {
				Log.w("BasePlugin", "Plugin $id does not have required permissions for creation")
				return
			}

			this.host = host

			if (isCreated.compareAndSet(false, true)) {
				onPluginCreate()
				Log.d("BasePlugin", "Plugin $id created successfully")
			} else {
				Log.w("BasePlugin", "Plugin $id already created")
			}
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error creating plugin $id", e)
			isCreated.set(false)
		}
	}

	/**
	 * Validate permissions required for plugin creation
	 */
	private fun validatePermissionsForCreation(): Boolean {
		return try {
			if (permissions.isEmpty()) return true

			val missingPermissions = permissions.filter { permission ->
				!(ExportedPlugins.permissions[permission]?.granted?.value ?: false)
			}

			if (missingPermissions.isNotEmpty()) {
				Log.w("BasePlugin", "Plugin $id missing permissions: $missingPermissions")
				return false
			}

			true
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error validating permissions for plugin $id", e)
			false
		}
	}

	/**
	 * Runtime permission validation
	 */
	private fun validateRuntimePermissions(): Boolean {
		return try {
			// Check if all required permissions are still valid
			permissions.all { permission ->
				ExportedPlugins.getPermission(permission)?.let { pluginPermission ->
					pluginPermission.granted.value
				} ?: false
			}
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error validating runtime permissions for $id", e)
			false
		}
	}

	/**
	 * Enhanced plugin creation with permission validation
	 */
	abstract fun onPluginCreate()

	@OptIn(ExperimentalSharedTransitionApi::class)
	@Composable
	abstract fun Composable(
		sharedTransitionScope: SharedTransitionScope,
		animatedContentScope: AnimatedContentScope
	)

	/**
	 * Safe onClick with permission validation
	 */
	fun onClickSafe() {
		try {
			if (!active) {
				Log.w("BasePlugin", "Plugin $id not active, ignoring click")
				return
			}

			if (!validateRuntimePermissions()) {
				Log.w("BasePlugin", "Plugin $id permissions invalid, ignoring click")
				return
			}

			onClick()
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error handling click for plugin $id", e)
		}
	}

	abstract fun onClick()

	/**
	 * Enhanced onDestroy with proper cleanup
	 */
	fun destroyPlugin() {
		if (isDestroyed.getAndSet(true)) {
			Log.w("BasePlugin", "Plugin $id already destroyed")
			return
		}

		try {
			Log.d("BasePlugin", "Destroying plugin: $id")

			// Call plugin-specific cleanup
			onDestroy()

			// Clear references
			host = null
			isCreated.set(false)

			Log.d("BasePlugin", "Plugin $id destroyed successfully")
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error destroying plugin $id", e)
		}
	}

	abstract fun onDestroy()

	@Composable
	abstract fun PermissionsRequired()

	@OptIn(ExperimentalSharedTransitionApi::class)
	@Composable
	abstract fun LeftOpenedComposable(
		sharedTransitionScope: SharedTransitionScope,
		animatedContentScope: AnimatedContentScope
	)

	@OptIn(ExperimentalSharedTransitionApi::class)
	@Composable
	abstract fun RightOpenedComposable(
		sharedTransitionScope: SharedTransitionScope,
		animatedContentScope: AnimatedContentScope
	)

	/**
	 * Safe swipe handlers with permission validation
	 */
	fun onRightSwipeSafe() {
		try {
			if (active && validateRuntimePermissions()) {
				onRightSwipe()
			}
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error handling right swipe for plugin $id", e)
		}
	}

	fun onLeftSwipeSafe() {
		try {
			if (active && validateRuntimePermissions()) {
				onLeftSwipe()
			}
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error handling left swipe for plugin $id", e)
		}
	}

	abstract fun onRightSwipe()
	abstract fun onLeftSwipe()

	/**
	 * Enhanced permission checking with error handling
	 */
	val allPermissionsGranted: Boolean
		get() = try {
			if (permissions.isEmpty()) return true

			permissions.all { permission ->
				ExportedPlugins.permissions[permission]?.granted?.value ?: false
			}
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error checking permissions for plugin $id", e)
			false
		}

	/**
	 * Get missing permissions for this plugin
	 */
	fun getMissingPermissions(): List<String> {
		return try {
			permissions.filter { permission ->
				!(ExportedPlugins.permissions[permission]?.granted?.value ?: false)
			}
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error getting missing permissions for plugin $id", e)
			emptyList()
		}
	}

	/**
	 * Enhanced plugin enabled check with error handling
	 */
	fun isPluginEnabled(context: Context): Boolean {
		return try {
			val preferences = context.getSharedPreferences(SETTINGS_KEY, Context.MODE_PRIVATE)
			preferences.getBoolean(id, false) && allPermissionsGranted
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error checking if plugin $id is enabled", e)
			false
		}
	}

	/**
	 * Enhanced switch enabled with comprehensive validation
	 */
	fun switchEnabled(context: Context, enabled: Boolean = !this.enabled.value): Boolean {
		try {
			// Don't disable if plugin is destroyed
			if (isDestroyed.get() && enabled) {
				Log.w("BasePlugin", "Cannot enable destroyed plugin: $id")
				return false
			}

			// Check permissions if trying to enable
			if (enabled && !allPermissionsGranted) {
				Log.w("BasePlugin", "Cannot enable plugin $id: missing permissions")
				return false
			}

			// Save to preferences
			val editor = context.getSharedPreferences(SETTINGS_KEY, Context.MODE_PRIVATE).edit()
			editor.putBoolean(id, enabled)
			editor.apply()

			// Update state
			this.enabled.value = enabled

			// Send broadcast
			context.sendBroadcast(Intent(SETTINGS_CHANGED))

			Log.d("BasePlugin", "Plugin $id enabled state changed to: $enabled")
			return true

		} catch (e: Exception) {
			Log.e("BasePlugin", "Error switching enabled state for plugin $id", e)
			return false
		}
	}

	/**
	 * Check if plugin is in valid state for operations
	 */
	fun isValidForOperation(): Boolean {
		return try {
			!isDestroyed.get() &&
			isCreated.get() &&
			host != null &&
			allPermissionsGranted
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error checking operation validity for plugin $id", e)
			false
		}
	}

	/**
	 * Request permission refresh for this plugin
	 */
	fun refreshPermissions(context: Context) {
		try {
			permissions.forEach { permission ->
				ExportedPlugins.refreshSpecificPermission(context, permission)
			}
		} catch (e: Exception) {
			Log.e("BasePlugin", "Error refreshing permissions for plugin $id", e)
		}
	}
}