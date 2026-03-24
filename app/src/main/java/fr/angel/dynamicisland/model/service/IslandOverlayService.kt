package fr.angel.dynamicisland.model.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.*
import android.content.Intent.ACTION_SCREEN_OFF
import android.content.Intent.ACTION_SCREEN_ON
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.util.Log
import android.view.*
import android.view.WindowManager.LayoutParams.*
import android.view.accessibility.AccessibilityEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.*
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import fr.angel.dynamicisland.R
import fr.angel.dynamicisland.island.Island
import fr.angel.dynamicisland.island.IslandState
import fr.angel.dynamicisland.island.IslandViewState
import fr.angel.dynamicisland.model.*
import fr.angel.dynamicisland.plugins.BasePlugin
import fr.angel.dynamicisland.plugins.ExportedPlugins
import fr.angel.dynamicisland.plugins.PluginHost
import fr.angel.dynamicisland.plugins.PluginManager
import fr.angel.dynamicisland.ui.island.*
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap


/**
 * Fixed IslandOverlayService with proper memory management
 *
 * Key improvements:
 * - Removed unsafe singleton pattern
 * - Proper resource cleanup in onDestroy
 * - Thread-safe instance management
 * - Proper coroutine and compose cleanup
 */
class IslandOverlayService : AccessibilityService(), PluginHost {

	private val params = WindowManager.LayoutParams(
		MATCH_PARENT,
		WRAP_CONTENT,
		TYPE_ACCESSIBILITY_OVERLAY,
		FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS or FLAG_NOT_TOUCH_MODAL or FLAG_NOT_FOCUSABLE,
		PixelFormat.TRANSLUCENT
	).apply {
		gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
	}

	private lateinit var settingsPreferences: SharedPreferences
	private var windowManager: WindowManager? = null
	private var composeView: ComposeView? = null
	private var lifecycleOwner: MyLifecycleOwner? = null
	private var viewModelStore: ViewModelStore? = null
	private var recomposer: Recomposer? = null
	private var recomposeScope: CoroutineScope? = null
	private var isBroadcastReceiverRegistered = false

	// State of the overlay
	var islandState : IslandState by mutableStateOf(IslandViewState.Closed)
		private set

	// Plugins - use lateinit to ensure proper initialization order
	lateinit var pluginManager: PluginManager
		private set

	// Theme
	var invertedTheme by mutableStateOf(false)
		private set

	companion object {
		// Thread-safe instance management with WeakReference to prevent memory leaks
		private val instanceMap = ConcurrentHashMap<String, WeakReference<IslandOverlayService>>()
		private const val DEFAULT_INSTANCE_KEY = "default"

		/**
		 * Get the current instance safely
		 * Returns null if no instance exists or if the instance has been garbage collected
		 */
		fun getInstance(): IslandOverlayService? {
			return instanceMap[DEFAULT_INSTANCE_KEY]?.get()
		}

		/**
		 * Internal method to register instance - only called from onServiceConnected
		 */
		private fun registerInstance(instance: IslandOverlayService) {
			instanceMap[DEFAULT_INSTANCE_KEY] = WeakReference(instance)
		}

		/**
		 * Internal method to unregister instance - only called from onUnbind
		 */
		private fun unregisterInstance() {
			instanceMap.remove(DEFAULT_INSTANCE_KEY)
		}
	}

	private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			when (intent.action) {
				SETTINGS_CHANGED -> {
					init()
				}
				SETTINGS_THEME_INVERTED -> {
					val settingsPreferences = getSharedPreferences(SETTINGS_KEY, Context.MODE_PRIVATE)
					invertedTheme = settingsPreferences.getBoolean(THEME_INVERTED, false)
				}
				ACTION_SCREEN_ON -> {
					Island.isScreenOn = true
				}
				ACTION_SCREEN_OFF -> {
					Island.isScreenOn = false
				}
			}
		}
	}

	override fun onServiceConnected() {
		super.onServiceConnected()
		try {
			setTheme(R.style.Theme_DynamicIsland)
			registerInstance(this)
			settingsPreferences = getSharedPreferences(SETTINGS_KEY, Context.MODE_PRIVATE)

			// Register broadcast receiver with proper error handling
			registerBroadcastReceiver()

			// Setup plugins (check if they are enabled) with error handling
			try {
				pluginManager = PluginManager(this, this)
				pluginManager.initialize()
			} catch (e: Exception) {
				Log.e("IslandOverlayService", "Failed to initialize plugin manager", e)
				// Continue without plugins rather than crash
			}

			// Setup
			init()

			windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
			windowManager?.let { wm ->
				showOverlay(wm, params)
			} ?: run {
				Log.e("IslandOverlayService", "WindowManager is null, cannot show overlay")
			}
		} catch (e: Exception) {
			Log.e("IslandOverlayService", "Failed to connect service", e)
		}
	}

	private fun registerBroadcastReceiver() {
		try {
			if (!isBroadcastReceiverRegistered) {
				registerReceiver(mBroadcastReceiver, IntentFilter().apply {
					addAction(SETTINGS_CHANGED)
					addAction(SETTINGS_THEME_INVERTED)
					addAction(ACTION_SCREEN_ON)
					addAction(ACTION_SCREEN_OFF)
				}, RECEIVER_EXPORTED)
				isBroadcastReceiverRegistered = true
			}
		} catch (e: Exception) {
			Log.e("IslandOverlayService", "Failed to register broadcast receiver", e)
		}
	}

	private fun unregisterBroadcastReceiver() {
		try {
			if (isBroadcastReceiverRegistered) {
				unregisterReceiver(mBroadcastReceiver)
				isBroadcastReceiverRegistered = false
			}
		} catch (e: Exception) {
			Log.e("IslandOverlayService", "Failed to unregister broadcast receiver", e)
		}
	}

	fun init() {
		try {
			// Initialize the plugins with null check
			if (::pluginManager.isInitialized) {
				pluginManager.initialize()
			}

			// Setup inverted theme
			invertedTheme = settingsPreferences.getBoolean(THEME_INVERTED, false)
		} catch (e: Exception) {
			Log.e("IslandOverlayService", "Failed to initialize", e)
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun showOverlay(
		windowManager: WindowManager,
		params: WindowManager.LayoutParams
	) {
		try {
			// Clean up any existing view first
			cleanupComposeView()

			composeView = ComposeView(this)

			composeView?.setContent {
				// Listen for plugin changes with null safety
				LaunchedEffect(if (::pluginManager.isInitialized) pluginManager.activePlugins.firstOrNull() else null) {
					islandState = if (::pluginManager.isInitialized && pluginManager.activePlugins.firstOrNull() != null) {
						IslandViewState.Opened
					} else {
						IslandViewState.Closed
					}
					Log.d("OverlayService", "Plugins changed: ${if (::pluginManager.isInitialized) pluginManager.activePlugins else emptyList()}")
				}

				IslandApp(
					islandOverlayService = this@IslandOverlayService,
				)
			}

			// Setup lifecycle management with proper cleanup tracking
			setupLifecycleManagement()

			// Setup composition context with proper cleanup
			setupCompositionContext()

			// Add the view to the window
			windowManager.addView(composeView, params)

		} catch (e: Exception) {
			Log.e("IslandOverlayService", "Failed to show overlay", e)
			cleanupComposeView()
		}
	}

	private fun setupLifecycleManagement() {
		try {
			// Clean up existing components first
			cleanupLifecycleComponents()

			viewModelStore = ViewModelStore()
			val viewModelStoreOwner = object : ViewModelStoreOwner {
				override val viewModelStore: ViewModelStore
					get() = this@IslandOverlayService.viewModelStore ?: ViewModelStore()
			}

			lifecycleOwner = MyLifecycleOwner()
			lifecycleOwner?.performRestore(null)
			lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

			composeView?.apply {
				setViewTreeLifecycleOwner(lifecycleOwner)
				setViewTreeSavedStateRegistryOwner(lifecycleOwner)
				setViewTreeViewModelStoreOwner(viewModelStoreOwner)
			}
		} catch (e: Exception) {
			Log.e("IslandOverlayService", "Failed to setup lifecycle management", e)
		}
	}

	private fun setupCompositionContext() {
		try {
			// Clean up existing recomposer first
			cleanupCompositionContext()

			val coroutineContext = AndroidUiDispatcher.CurrentThread
			recomposeScope = CoroutineScope(coroutineContext + SupervisorJob())
			recomposer = Recomposer(coroutineContext)

			composeView?.compositionContext = recomposer

			recomposeScope?.launch {
				try {
					recomposer?.runRecomposeAndApplyChanges()
				} catch (e: Exception) {
					Log.e("IslandOverlayService", "Recomposition failed", e)
				}
			}
		} catch (e: Exception) {
			Log.e("IslandOverlayService", "Failed to setup composition context", e)
		}
	}

	private fun cleanupComposeView() {
		try {
			composeView?.let { view ->
				windowManager?.removeView(view)
			}
			composeView = null
		} catch (e: Exception) {
			Log.e("IslandOverlayService", "Failed to cleanup compose view", e)
		}
	}

	private fun cleanupLifecycleComponents() {
		try {
			lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
			lifecycleOwner = null

			viewModelStore?.clear()
			viewModelStore = null
		} catch (e: Exception) {
			Log.e("IslandOverlayService", "Failed to cleanup lifecycle components", e)
		}
	}

	private fun cleanupCompositionContext() {
		try {
			recomposeScope?.cancel()
			recomposeScope = null

			recomposer?.cancel()
			recomposer = null
		} catch (e: Exception) {
			Log.e("IslandOverlayService", "Failed to cleanup composition context", e)
		}
	}

	override fun onDestroy() {
		Log.d("IslandOverlayService", "Service destroying - cleaning up resources")

		// Clean up in reverse order of creation
		try {
			// Clean up plugins first
			if (::pluginManager.isInitialized) {
				pluginManager.onDestroy()
			}
		} catch (e: Exception) {
			Log.e("IslandOverlayService", "Failed to destroy plugin manager", e)
		}

		// Clean up broadcast receiver
		unregisterBroadcastReceiver()

		// Clean up compose and lifecycle components
		cleanupComposeView()
		cleanupLifecycleComponents()
		cleanupCompositionContext()

		super.onDestroy()
	}

	override fun onUnbind(intent: Intent?): Boolean {
		Log.d("IslandOverlayService", "Service unbinding")

		// Unregister instance
		unregisterInstance()

		return super.onUnbind(intent)
	}

	fun expand() {
		islandState = IslandViewState.Expanded(configuration = resources.configuration)
	}

	fun shrink() {
		islandState = IslandViewState.Opened
	}

	override fun requestDisplay(plugin: BasePlugin) {
		if (::pluginManager.isInitialized) {
			pluginManager.requestDisplay(plugin)
		}
	}

	override fun requestDismiss(plugin: BasePlugin) {
		if (::pluginManager.isInitialized) {
			pluginManager.requestDismiss(plugin)
		}
	}

	override fun requestExpand() {
		expand()
	}

	override fun requestShrink() {
		shrink()
	}

	override fun onUnbind(intent: Intent?): Boolean {
		Log.d("IslandOverlayService", "Service unbinding")

		// Unregister instance
		unregisterInstance()

		return super.onUnbind(intent)
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		Island.isInLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
	}

	override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
	override fun onInterrupt() {}
}