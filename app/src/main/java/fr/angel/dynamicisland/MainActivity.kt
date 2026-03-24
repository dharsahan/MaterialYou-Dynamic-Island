package fr.angel.dynamicisland

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fr.angel.dynamicisland.model.SETTINGS_KEY
import fr.angel.dynamicisland.model.SETTINGS_THEME_INVERTED
import fr.angel.dynamicisland.model.THEME_INVERTED
import fr.angel.dynamicisland.navigation.*
import fr.angel.dynamicisland.plugins.ExportedPlugins
import fr.angel.dynamicisland.island.IslandSettings
import fr.angel.dynamicisland.model.DISCLOSURE_ACCEPTED
import fr.angel.dynamicisland.ui.disclosure.DisclosureScreen
import fr.angel.dynamicisland.ui.settings.settings
import fr.angel.dynamicisland.ui.theme.DynamicIslandTheme
import fr.angel.dynamicisland.ui.theme.Theme
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * Enhanced MainActivity with proper theme management and lifecycle handling
 *
 * Key improvements:
 * - Removed theme toggling anti-pattern
 * - Proper lifecycle management without redundant operations
 * - Thread-safe instance management with WeakReference
 * - Centralized theme state management
 * - Efficient SharedPreferences usage
 * - Comprehensive error handling
 */
class MainActivity : ComponentActivity() {

	private lateinit var settingsPreferences: SharedPreferences
	private val isThemeInitialized = AtomicBoolean(false)
	private val isDestroyed = AtomicBoolean(false)

	var actions = mutableStateListOf<@Composable () -> Unit>()

	companion object {
		// Thread-safe instance management with WeakReference
		private val instanceMap = ConcurrentHashMap<String, WeakReference<MainActivity>>()
		private const val DEFAULT_INSTANCE_KEY = "default"

		/**
		 * Get current instance safely
		 */
		fun getInstance(): MainActivity? {
			return instanceMap[DEFAULT_INSTANCE_KEY]?.get()
		}

		/**
		 * Internal method to register instance
		 */
		private fun registerInstance(instance: MainActivity) {
			instanceMap[DEFAULT_INSTANCE_KEY] = WeakReference(instance)
		}

		/**
		 * Internal method to unregister instance
		 */
		private fun unregisterInstance() {
			instanceMap.remove(DEFAULT_INSTANCE_KEY)
		}
	}

	@OptIn(ExperimentalMaterial3Api::class)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		try {
			// Register instance safely
			registerInstance(this)

			// Initialize preferences
			settingsPreferences = getSharedPreferences(SETTINGS_KEY, Context.MODE_PRIVATE)

			// Setup window
			WindowCompat.setDecorFitsSystemWindows(window, false)

			// Initialize theme state ONCE
			initializeThemeState()

			setContent {
				AppContent()
			}

			Log.d("MainActivity", "MainActivity created successfully")

		} catch (e: Exception) {
			Log.e("MainActivity", "Error in onCreate", e)
			finish()
		}
	}

	/**
	 * Initialize theme state properly without anti-patterns
	 */
	private fun initializeThemeState() {
		try {
			if (isThemeInitialized.compareAndSet(false, true)) {
				// Set theme inverted for app UI ONCE
				settingsPreferences.edit().putBoolean(THEME_INVERTED, true).apply()
				sendBroadcast(Intent(SETTINGS_THEME_INVERTED))
				Log.d("MainActivity", "Theme state initialized")
			}
		} catch (e: Exception) {
			Log.e("MainActivity", "Error initializing theme state", e)
		}
	}

	/**
	 * Cleanup theme state properly
	 */
	private fun cleanupThemeState() {
		try {
			if (isThemeInitialized.compareAndSet(true, false)) {
				// Revert theme inversion ONCE
				settingsPreferences.edit().putBoolean(THEME_INVERTED, false).apply()
				sendBroadcast(Intent(SETTINGS_THEME_INVERTED))
				Log.d("MainActivity", "Theme state cleaned up")
			}
		} catch (e: Exception) {
			Log.e("MainActivity", "Error cleaning up theme state", e)
		}
	}

	@Composable
	private fun AppContent() {
		try {
			// Setup plugins with error handling
			ExportedPlugins.setupPermissions(LocalContext.current)

			// Initialize theme and settings
			Theme.instance.Init()
			IslandSettings.instance.loadSettings(this@MainActivity)

			// Check disclosure acceptance
			val disclosureAccepted by remember {
				mutableStateOf(
					settingsPreferences.getBoolean(DISCLOSURE_ACCEPTED, false)
				)
			}

			if (!disclosureAccepted) {
				// Navigate to disclosure and finish this activity
				LaunchedEffect(Unit) {
					try {
						startActivity(Intent(this@MainActivity, DisclosureActivity::class.java))
						finish()
					} catch (e: Exception) {
						Log.e("MainActivity", "Error starting DisclosureActivity", e)
					}
				}
				return
			}

			// Main app content
			DynamicIslandTheme(
				darkTheme = Theme.instance.isDarkTheme,
			) {
				Surface(
					modifier = Modifier.fillMaxSize(),
					color = MaterialTheme.colorScheme.background
				) {
					NavigationContent()
				}
			}

		} catch (e: Exception) {
			Log.e("MainActivity", "Error in app content", e)
		}
	}

	@OptIn(ExperimentalMaterial3Api::class)
	@Composable
	private fun NavigationContent() {
		try {
			val settingsRoutes = settings.map { (it as IslandDestination).route }
			val navController = rememberNavController()
			val currentBackStack by navController.currentBackStackEntryAsState()
			val currentDestination = currentBackStack?.destination

			val currentScreen: IslandDestination = bottomDestinations.find {
				it.route == currentDestination?.route
			} ?: (settings.find {
				(it as IslandDestination).route == currentDestination?.route
			} ?: if (currentDestination?.route == IslandPluginSettings.routeWithArgs) {
				IslandPluginSettings
			} else {
				IslandHome
			}) as IslandDestination

			// Top app bar configuration
			val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

			LaunchedEffect(currentScreen) {
				actions.clear()
			}

			Scaffold(
				modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
				topBar = {
					CenterAlignedTopAppBar(
						title = {
							Crossfade(
								targetState = currentScreen,
								label = "TopBarTitle"
							) { screen ->
								Text(
									text = if (screen == IslandHome) {
										stringResource(id = R.string.app_name)
									} else {
										screen.title
									},
									textAlign = TextAlign.Center,
									modifier = Modifier.fillMaxWidth()
								)
							}
						},
						navigationIcon = {
							if (currentDestination?.route in settingsRoutes ||
								currentDestination?.route == IslandPluginSettings.routeWithArgs) {
								IconButton(
									onClick = {
										try {
											navController.popBackStack()
										} catch (e: Exception) {
											Log.e("MainActivity", "Error navigating back", e)
										}
									}
								) {
									Icon(
										imageVector = Icons.Default.ArrowBack,
										contentDescription = "Back"
									)
								}
							}
						},
						actions = {
							actions.forEach { action ->
								try {
									action()
								} catch (e: Exception) {
									Log.e("MainActivity", "Error in action", e)
								}
							}
						},
						scrollBehavior = scrollBehavior
					)
				},
				bottomBar = {
					NavigationBar {
						bottomDestinations.forEach { destination ->
							NavigationBarItem(
								icon = { Icon(destination.icon, contentDescription = null) },
								label = { Text(destination.title) },
								selected = currentScreen == destination ||
									(destination == fr.angel.dynamicisland.navigation.IslandSettings && settings.contains(currentScreen)) ||
									(destination == IslandPlugins && currentScreen == IslandPluginSettings),
								onClick = {
									try {
										navController.navigateSingleTopTo(destination.route)
									} catch (e: Exception) {
										Log.e("MainActivity", "Error navigating to ${destination.route}", e)
									}
								}
							)
						}
					}
				},
			) { paddingValues ->
				IslandNavHost(
					modifier = Modifier
						.padding(paddingValues)
						.fillMaxSize(),
					navController = navController
				)
			}

		} catch (e: Exception) {
			Log.e("MainActivity", "Error in navigation content", e)
		}
	}

	override fun onDestroy() {
		Log.d("MainActivity", "MainActivity destroying")

		try {
			// Mark as destroyed
			isDestroyed.set(true)

			// Cleanup theme state once
			cleanupThemeState()

			// Unregister instance
			unregisterInstance()

		} catch (e: Exception) {
			Log.e("MainActivity", "Error in onDestroy", e)
		}

		super.onDestroy()
	}

	override fun onStop() {
		super.onStop()
		Log.d("MainActivity", "MainActivity stopped")
		// NO theme toggling - removed anti-pattern
	}

	override fun onPause() {
		super.onPause()
		Log.d("MainActivity", "MainActivity paused")
		// NO theme toggling - removed anti-pattern
	}

	override fun onResume() {
		super.onResume()
		Log.d("MainActivity", "MainActivity resumed")

		try {
			// Refresh permissions when resuming (user might have granted permissions in settings)
			ExportedPlugins.refreshPermissions(this)
		} catch (e: Exception) {
			Log.e("MainActivity", "Error refreshing permissions on resume", e)
		}
	}

	override fun onStart() {
		super.onStart()
		Log.d("MainActivity", "MainActivity started")
	}

	/**
	 * Add action to top bar safely
	 */
	fun addTopBarAction(action: @Composable () -> Unit) {
		try {
			if (!isDestroyed.get()) {
				actions.add(action)
			}
		} catch (e: Exception) {
			Log.e("MainActivity", "Error adding top bar action", e)
		}
	}

	/**
	 * Remove action from top bar safely
	 */
	fun removeTopBarAction(action: @Composable () -> Unit) {
		try {
			actions.remove(action)
		} catch (e: Exception) {
			Log.e("MainActivity", "Error removing top bar action", e)
		}
	}

	/**
	 * Clear all top bar actions safely
	 */
	fun clearTopBarActions() {
		try {
			actions.clear()
		} catch (e: Exception) {
			Log.e("MainActivity", "Error clearing top bar actions", e)
		}
	}
}