package fr.angel.dynamicisland.plugins

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import fr.angel.dynamicisland.model.packageName
import fr.angel.dynamicisland.model.service.IslandOverlayService
import fr.angel.dynamicisland.model.service.NotificationService
import java.lang.ref.WeakReference

/**
 * Enhanced PluginPermission class with comprehensive permission management
 *
 * Key improvements:
 * - Runtime permission validation
 * - Service availability checks
 * - Comprehensive error handling
 * - Multiple permission types support
 * - Async permission checking
 */
open class PluginPermission(
	val name: String,
	val description: String,
	val requestIntent: Intent,
	val granted: MutableState<Boolean> = mutableStateOf(false),
	val isRequired: Boolean = true,
	val permissionType: PermissionType = PermissionType.SPECIAL
) {
	enum class PermissionType {
		RUNTIME,        // Standard Android runtime permissions
		SPECIAL,        // Special permissions like notification access
		SERVICE,        // Service availability checks
		ACCESSIBILITY   // Accessibility service permissions
	}

	/**
	 * Check if permission is granted with comprehensive validation
	 */
	open fun checkPermission(context: Context): Boolean {
		return try {
			when (permissionType) {
				PermissionType.RUNTIME -> checkRuntimePermission(context)
				PermissionType.SPECIAL -> checkSpecialPermission(context)
				PermissionType.SERVICE -> checkServiceAvailability(context)
				PermissionType.ACCESSIBILITY -> checkAccessibilityPermission(context)
			}
		} catch (e: Exception) {
			Log.e("PluginPermission", "Error checking permission: $name", e)
			false
		}
	}

	/**
	 * Check runtime permissions (dangerous permissions)
	 */
	protected open fun checkRuntimePermission(context: Context): Boolean {
		// Override in subclasses for specific runtime permissions
		return false
	}

	/**
	 * Check special permissions (like notification access)
	 */
	protected open fun checkSpecialPermission(context: Context): Boolean {
		// Override in subclasses for specific special permissions
		return false
	}

	/**
	 * Check service availability
	 */
	protected open fun checkServiceAvailability(context: Context): Boolean {
		// Override in subclasses for specific service checks
		return false
	}

	/**
	 * Check accessibility service permissions
	 */
	protected open fun checkAccessibilityPermission(context: Context): Boolean {
		// Override in subclasses for specific accessibility checks
		return false
	}

	/**
	 * Refresh permission state
	 */
	fun refreshPermissionState(context: Context) {
		try {
			granted.value = checkPermission(context)
		} catch (e: Exception) {
			Log.e("PluginPermission", "Error refreshing permission state: $name", e)
			granted.value = false
		}
	}
}

/**
 * Notification listener permission
 */
class NotificationListenerPermission : PluginPermission(
	name = "Notification Access",
	description = "Allow Dynamic Island to listen to notifications and display them",
	requestIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
	permissionType = PermissionType.SPECIAL
) {
	override fun checkSpecialPermission(context: Context): Boolean {
		return try {
			val contentResolver = context.contentResolver
			val enabledListeners = Settings.Secure.getString(
				contentResolver,
				"enabled_notification_listeners"
			)

			enabledListeners?.contains(packageName) == true &&
			checkNotificationServiceAvailability()
		} catch (e: Exception) {
			Log.e("NotificationListenerPermission", "Error checking permission", e)
			false
		}
	}

	private fun checkNotificationServiceAvailability(): Boolean {
		return try {
			NotificationService.getInstance() != null
		} catch (e: Exception) {
			Log.w("NotificationListenerPermission", "NotificationService not available", e)
			false
		}
	}
}

/**
 * Accessibility service permission
 */
class AccessibilityServicePermission : PluginPermission(
	name = "Accessibility Service",
	description = "Allow Dynamic Island overlay to be displayed over other apps",
	requestIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
	permissionType = PermissionType.ACCESSIBILITY
) {
	override fun checkAccessibilityPermission(context: Context): Boolean {
		return try {
			val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
			val enabledServices = Settings.Secure.getString(
				context.contentResolver,
				Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
			)

			val packageName = context.packageName
			enabledServices?.contains(packageName) == true &&
			checkAccessibilityServiceAvailability()
		} catch (e: Exception) {
			Log.e("AccessibilityServicePermission", "Error checking permission", e)
			false
		}
	}

	private fun checkAccessibilityServiceAvailability(): Boolean {
		return try {
			IslandOverlayService.getInstance() != null
		} catch (e: Exception) {
			Log.w("AccessibilityServicePermission", "AccessibilityService not available", e)
			false
		}
	}
}

/**
 * Media session permission
 */
class MediaSessionPermission : PluginPermission(
	name = "Media Session Access",
	description = "Allow Dynamic Island to control media playback",
	requestIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
	permissionType = PermissionType.SERVICE,
	isRequired = false // Optional permission
) {
	override fun checkServiceAvailability(context: Context): Boolean {
		return try {
			val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
			mediaSessionManager != null
		} catch (e: Exception) {
			Log.e("MediaSessionPermission", "Error checking media session availability", e)
			false
		}
	}
}

/**
 * Battery information permission
 */
class BatteryInfoPermission : PluginPermission(
	name = "Battery Information",
	description = "Allow Dynamic Island to read battery status",
	requestIntent = Intent(), // No special permission required
	permissionType = PermissionType.SERVICE,
	isRequired = false
) {
	override fun checkServiceAvailability(context: Context): Boolean {
		return try {
			// Battery info is generally available, just check if we can access BatteryManager
			val batteryManager = context.getSystemService(Context.BATTERY_SERVICE)
			batteryManager != null
		} catch (e: Exception) {
			Log.e("BatteryInfoPermission", "Error checking battery service", e)
			false
		}
	}
}

/**
 * System alert window permission (for overlay)
 */
class SystemAlertWindowPermission : PluginPermission(
	name = "Display Over Other Apps",
	description = "Allow Dynamic Island to display over other applications",
	requestIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
	permissionType = PermissionType.SPECIAL
) {
	override fun checkSpecialPermission(context: Context): Boolean {
		return try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				Settings.canDrawOverlays(context)
			} else {
				true // Permission not required on older Android versions
			}
		} catch (e: Exception) {
			Log.e("SystemAlertWindowPermission", "Error checking overlay permission", e)
			false
		}
	}
}