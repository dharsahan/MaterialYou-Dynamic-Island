package fr.angel.dynamicisland.model.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import fr.angel.dynamicisland.model.ACTION_CLOSE
import fr.angel.dynamicisland.model.ACTION_OPEN_CLOSE
import fr.angel.dynamicisland.model.NOTIFICATION_POSTED
import fr.angel.dynamicisland.model.NOTIFICATION_REMOVED
import fr.angel.dynamicisland.island.IslandSettings
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue


/**
 * Fixed NotificationService with proper memory management
 *
 * Key improvements:
 * - Removed unsafe singleton pattern
 * - Bounded notification list with automatic cleanup
 * - Thread-safe operations
 * - Proper resource cleanup
 * - Enhanced error handling
 */
class NotificationService : NotificationListenerService() {

	// Thread-safe notification storage with automatic size management
	private val notificationQueue = ConcurrentLinkedQueue<StatusBarNotification>()
	var notifications = mutableStateListOf<StatusBarNotification>()
		private set

	private var isBroadcastReceiverRegistered = false

	companion object {
		private const val MAX_NOTIFICATIONS = 50 // Prevent unbounded growth

		// Thread-safe instance management with WeakReference
		private val instanceMap = ConcurrentHashMap<String, WeakReference<NotificationService>>()
		private const val DEFAULT_INSTANCE_KEY = "default"

		/**
		 * Get the current instance safely
		 * Returns null if no instance exists or if the instance has been garbage collected
		 */
		fun getInstance(): NotificationService? {
			return instanceMap[DEFAULT_INSTANCE_KEY]?.get()
		}

		/**
		 * Internal method to register instance - only called from onCreate
		 */
		private fun registerInstance(instance: NotificationService) {
			instanceMap[DEFAULT_INSTANCE_KEY] = WeakReference(instance)
		}

		/**
		 * Internal method to unregister instance - only called from onDestroy
		 */
		private fun unregisterInstance() {
			instanceMap.remove(DEFAULT_INSTANCE_KEY)
		}
	}

	private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			try {
				val notificationId = intent.getIntExtra("id", 0)
				val statusBarNotification = findNotificationById(notificationId)

				if (statusBarNotification == null) {
					Log.w("NotificationService", "Notification not found for id: $notificationId")
					return
				}

				Log.d("NotificationService", "onReceive: ${statusBarNotification.id}, action: ${intent.action}")
				val notification = statusBarNotification.notification

				when (intent.action) {
					ACTION_OPEN_CLOSE -> {
						handleNotificationOpenClose(statusBarNotification, notification)
					}
					ACTION_CLOSE -> {
						handleNotificationClose(statusBarNotification, notification)
					}
				}
			} catch (e: Exception) {
				Log.e("NotificationService", "Error handling broadcast", e)
			}
		}
	}

	private fun findNotificationById(id: Int): StatusBarNotification? {
		return notifications.firstOrNull { it.id == id }
	}

	private fun handleNotificationOpenClose(statusBarNotification: StatusBarNotification, notification: Notification) {
		try {
			// Remove notification first
			removeNotificationSafely(statusBarNotification, notification)

			// Then start content intent
			notification.contentIntent?.send() ?: run {
				Log.w("NotificationService", "No content intent available for notification")
			}
		} catch (e: Exception) {
			Log.e("NotificationService", "Error handling open/close", e)
		}
	}

	private fun handleNotificationClose(statusBarNotification: StatusBarNotification, notification: Notification) {
		try {
			removeNotificationSafely(statusBarNotification, notification)
		} catch (e: Exception) {
			Log.e("NotificationService", "Error handling close", e)
		}
	}

	private fun removeNotificationSafely(statusBarNotification: StatusBarNotification, notification: Notification) {
		try {
			if (notification.deleteIntent != null) {
				notification.deleteIntent.send()
			} else {
				cancelNotification(statusBarNotification.key)
			}
		} catch (e: Exception) {
			Log.e("NotificationService", "Error removing notification", e)
			// Try alternative removal method
			try {
				cancelNotification(statusBarNotification.key)
			} catch (e2: Exception) {
				Log.e("NotificationService", "Failed to cancel notification", e2)
			}
		}
	}

	override fun onCreate() {
		super.onCreate()
		Log.d("NotificationService", "Service creating")

		try {
			registerInstance(this)
			registerBroadcastReceiver()
		} catch (e: Exception) {
			Log.e("NotificationService", "Failed to create service", e)
		}
	}

	private fun registerBroadcastReceiver() {
		try {
			if (!isBroadcastReceiverRegistered) {
				registerReceiver(mBroadcastReceiver, IntentFilter().apply {
					addAction(ACTION_OPEN_CLOSE)
					addAction(ACTION_CLOSE)
				}, RECEIVER_EXPORTED)
				isBroadcastReceiverRegistered = true
				Log.d("NotificationService", "Broadcast receiver registered")
			}
		} catch (e: Exception) {
			Log.e("NotificationService", "Failed to register broadcast receiver", e)
		}
	}

	private fun unregisterBroadcastReceiver() {
		try {
			if (isBroadcastReceiverRegistered) {
				unregisterReceiver(mBroadcastReceiver)
				isBroadcastReceiverRegistered = false
				Log.d("NotificationService", "Broadcast receiver unregistered")
			}
		} catch (e: Exception) {
			Log.e("NotificationService", "Failed to unregister broadcast receiver", e)
		}
	}

	override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
		super.onNotificationPosted(statusBarNotification)

		try {
			val notification = statusBarNotification.notification

			// Check if notification should be processed
			if (!shouldProcessNotification(statusBarNotification, notification)) {
				return
			}

			Log.d("NotificationService", "Processing notification: ${statusBarNotification.packageName}")

			// Add notification with size management
			addNotificationSafely(statusBarNotification)

			// Send broadcast with error handling
			sendNotificationBroadcast(statusBarNotification, notification, NOTIFICATION_POSTED)

		} catch (e: Exception) {
			Log.e("NotificationService", "Error processing posted notification", e)
		}
	}

	private fun shouldProcessNotification(statusBarNotification: StatusBarNotification, notification: Notification): Boolean {
		// Check if notification is in the enabled apps list
		val enabledApps = try {
			IslandSettings.instance.enabledApps
		} catch (e: Exception) {
			Log.w("NotificationService", "Could not access enabled apps, allowing all", e)
			emptySet<String>()
		}

		if (enabledApps.isNotEmpty() && statusBarNotification.packageName !in enabledApps) {
			return false
		}

		// Ignore system notifications
		when (notification.category) {
			Notification.CATEGORY_SYSTEM,
			Notification.CATEGORY_SERVICE,
			Notification.CATEGORY_TRANSPORT -> {
				Log.d("NotificationService", "Ignoring system notification: ${notification.category}")
				return false
			}
		}

		return true
	}

	private fun addNotificationSafely(statusBarNotification: StatusBarNotification) {
		try {
			// Remove any existing notification with the same ID first
			notifications.removeAll { it.id == statusBarNotification.id }

			// Add to queue and list
			notificationQueue.offer(statusBarNotification)
			notifications.add(statusBarNotification)

			// Manage size to prevent unbounded growth
			while (notifications.size > MAX_NOTIFICATIONS) {
				val oldest = notificationQueue.poll()
				if (oldest != null) {
					notifications.remove(oldest)
				} else {
					// Fallback: remove first item
					if (notifications.isNotEmpty()) {
						notifications.removeAt(0)
					}
				}
			}

			Log.d("NotificationService", "Notifications count: ${notifications.size}")

		} catch (e: Exception) {
			Log.e("NotificationService", "Error adding notification", e)
		}
	}

	private fun sendNotificationBroadcast(statusBarNotification: StatusBarNotification, notification: Notification, action: String) {
		try {
			sendBroadcast(Intent(action).apply {
				putExtra("id", statusBarNotification.id)
				putExtra("package_name", statusBarNotification.packageName)
				putExtra("category", notification.category)
				putExtra("time", statusBarNotification.postTime)

				// Safely extract notification content
				try {
					putExtra("icon_large", notification.getLargeIcon())
				} catch (e: Exception) {
					Log.w("NotificationService", "Could not get large icon", e)
				}

				try {
					putExtra("icon_small", notification.smallIcon)
				} catch (e: Exception) {
					Log.w("NotificationService", "Could not get small icon", e)
				}

				putExtra("title", notification.extras?.getString("android.title") ?: "")
				putExtra("body", notification.extras?.getString("android.text") ?: "")
			})
		} catch (e: Exception) {
			Log.e("NotificationService", "Error sending notification broadcast", e)
		}
	}

	override fun onNotificationRemoved(statusBarNotification: StatusBarNotification) {
		try {
			// Remove from both queue and list
			notificationQueue.removeIf { it.id == statusBarNotification.id }
			notifications.removeIf { it.id == statusBarNotification.id }

			Log.d("NotificationService", "Removed notification, remaining: ${notifications.size}")

			// Send broadcast with error handling
			sendBroadcast(Intent(NOTIFICATION_REMOVED).apply {
				putExtra("id", statusBarNotification.id)
			})

		} catch (e: Exception) {
			Log.e("NotificationService", "Error removing notification", e)
		}
	}

	override fun onDestroy() {
		Log.d("NotificationService", "Service destroying - cleaning up resources")

		try {
			// Clean up broadcast receiver
			unregisterBroadcastReceiver()

			// Clear notification collections
			notifications.clear()
			notificationQueue.clear()

			// Unregister instance
			unregisterInstance()

		} catch (e: Exception) {
			Log.e("NotificationService", "Error during cleanup", e)
		}

		super.onDestroy()
	}

	/**
	 * Public method to safely clear all notifications (useful for testing or manual cleanup)
	 */
	fun clearAllNotifications() {
		try {
			notifications.clear()
			notificationQueue.clear()
			Log.d("NotificationService", "All notifications cleared")
		} catch (e: Exception) {
			Log.e("NotificationService", "Error clearing notifications", e)
		}
	}
}