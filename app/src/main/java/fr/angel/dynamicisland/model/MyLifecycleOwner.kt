package fr.angel.dynamicisland.model

import android.os.Bundle
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * Enhanced MyLifecycleOwner with proper lifecycle management
 *
 * Key improvements:
 * - Proper lifecycle state transitions with validation
 * - Thread-safe lifecycle event handling
 * - Comprehensive error handling
 * - Observer management
 * - Resource cleanup tracking
 * - Proper saved state management
 */
internal class MyLifecycleOwner : SavedStateRegistryOwner {
	private var mLifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
	private var mSavedStateRegistryController: SavedStateRegistryController = SavedStateRegistryController.create(this)

	// Thread safety and state tracking
	private val isDestroyed = AtomicBoolean(false)
	private val isInitialized = AtomicBoolean(false)
	private val observers = ConcurrentHashMap<LifecycleObserver, Boolean>()

	// Lifecycle state validation
	private val validTransitions = mapOf(
		Lifecycle.State.INITIALIZED to setOf(Lifecycle.State.CREATED, Lifecycle.State.DESTROYED),
		Lifecycle.State.CREATED to setOf(Lifecycle.State.STARTED, Lifecycle.State.DESTROYED),
		Lifecycle.State.STARTED to setOf(Lifecycle.State.RESUMED, Lifecycle.State.CREATED),
		Lifecycle.State.RESUMED to setOf(Lifecycle.State.STARTED),
		Lifecycle.State.DESTROYED to emptySet()
	)

	/**
	 * Check if the Lifecycle has been properly initialized
	 */
	val isInitialized: Boolean
		get() = this.isInitialized.get() && !isDestroyed.get()

	/**
	 * Get current lifecycle state safely
	 */
	val currentState: Lifecycle.State
		get() = try {
			if (isDestroyed.get()) {
				Lifecycle.State.DESTROYED
			} else {
				mLifecycleRegistry.currentState
			}
		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error getting current state", e)
			Lifecycle.State.DESTROYED
		}

	/**
	 * Set lifecycle state with validation
	 */
	fun setCurrentState(state: Lifecycle.State) {
		if (isDestroyed.get() && state != Lifecycle.State.DESTROYED) {
			Log.w("MyLifecycleOwner", "Cannot set state $state on destroyed lifecycle")
			return
		}

		try {
			val currentState = mLifecycleRegistry.currentState

			// Validate state transition
			if (!isValidTransition(currentState, state)) {
				Log.w("MyLifecycleOwner", "Invalid state transition: $currentState -> $state")
				return
			}

			mLifecycleRegistry.currentState = state
			Log.d("MyLifecycleOwner", "Lifecycle state changed: $currentState -> $state")

			// Mark as destroyed if transitioning to destroyed state
			if (state == Lifecycle.State.DESTROYED) {
				isDestroyed.set(true)
				cleanup()
			}

		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error setting lifecycle state to $state", e)
		}
	}

	/**
	 * Validate if state transition is allowed
	 */
	private fun isValidTransition(from: Lifecycle.State, to: Lifecycle.State): Boolean {
		// Always allow transition to DESTROYED
		if (to == Lifecycle.State.DESTROYED) return true

		// Don't allow transitions from DESTROYED
		if (from == Lifecycle.State.DESTROYED) return false

		return validTransitions[from]?.contains(to) ?: false
	}

	/**
	 * Handle lifecycle events with proper error handling and validation
	 */
	fun handleLifecycleEvent(event: Lifecycle.Event) {
		if (isDestroyed.get() && event != Lifecycle.Event.ON_DESTROY) {
			Log.w("MyLifecycleOwner", "Cannot handle event $event on destroyed lifecycle")
			return
		}

		try {
			Log.d("MyLifecycleOwner", "Handling lifecycle event: $event")

			when (event) {
				Lifecycle.Event.ON_CREATE -> {
					if (!isInitialized.getAndSet(true)) {
						mLifecycleRegistry.handleLifecycleEvent(event)
						Log.d("MyLifecycleOwner", "Lifecycle created and initialized")
					} else {
						Log.w("MyLifecycleOwner", "Lifecycle already initialized")
					}
				}
				Lifecycle.Event.ON_DESTROY -> {
					mLifecycleRegistry.handleLifecycleEvent(event)
					isDestroyed.set(true)
					cleanup()
					Log.d("MyLifecycleOwner", "Lifecycle destroyed")
				}
				else -> {
					// Validate current state allows this event
					if (canHandleEvent(event)) {
						mLifecycleRegistry.handleLifecycleEvent(event)
					} else {
						Log.w("MyLifecycleOwner", "Cannot handle event $event in current state ${mLifecycleRegistry.currentState}")
					}
				}
			}

		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error handling lifecycle event $event", e)
		}
	}

	/**
	 * Check if the current state allows handling the given event
	 */
	private fun canHandleEvent(event: Lifecycle.Event): Boolean {
		val currentState = mLifecycleRegistry.currentState

		return when (event) {
			Lifecycle.Event.ON_CREATE -> currentState == Lifecycle.State.INITIALIZED
			Lifecycle.Event.ON_START -> currentState == Lifecycle.State.CREATED
			Lifecycle.Event.ON_RESUME -> currentState == Lifecycle.State.STARTED
			Lifecycle.Event.ON_PAUSE -> currentState == Lifecycle.State.RESUMED
			Lifecycle.Event.ON_STOP -> currentState == Lifecycle.State.STARTED || currentState == Lifecycle.State.CREATED
			Lifecycle.Event.ON_DESTROY -> true // Always allow destroy
			else -> true
		}
	}

	/**
	 * Add lifecycle observer safely
	 */
	fun addObserver(observer: LifecycleObserver) {
		if (isDestroyed.get()) {
			Log.w("MyLifecycleOwner", "Cannot add observer to destroyed lifecycle")
			return
		}

		try {
			mLifecycleRegistry.addObserver(observer)
			observers[observer] = true
			Log.d("MyLifecycleOwner", "Added lifecycle observer: ${observer::class.simpleName}")
		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error adding lifecycle observer", e)
		}
	}

	/**
	 * Remove lifecycle observer safely
	 */
	fun removeObserver(observer: LifecycleObserver) {
		try {
			mLifecycleRegistry.removeObserver(observer)
			observers.remove(observer)
			Log.d("MyLifecycleOwner", "Removed lifecycle observer: ${observer::class.simpleName}")
		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error removing lifecycle observer", e)
		}
	}

	/**
	 * Clean up all observers and resources
	 */
	private fun cleanup() {
		try {
			// Remove all observers
			observers.keys.forEach { observer ->
				try {
					mLifecycleRegistry.removeObserver(observer)
				} catch (e: Exception) {
					Log.e("MyLifecycleOwner", "Error removing observer during cleanup", e)
				}
			}
			observers.clear()

			Log.d("MyLifecycleOwner", "Lifecycle cleanup completed")
		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error during lifecycle cleanup", e)
		}
	}

	/**
	 * Override lifecycle property with error handling
	 */
	override val lifecycle: Lifecycle
		get() = try {
			mLifecycleRegistry
		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error accessing lifecycle", e)
			// Return a minimal lifecycle that's always destroyed
			LifecycleRegistry(this).apply {
				currentState = Lifecycle.State.DESTROYED
			}
		}

	/**
	 * Override savedStateRegistry with error handling
	 */
	override val savedStateRegistry: SavedStateRegistry
		get() = try {
			mSavedStateRegistryController.savedStateRegistry
		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error accessing saved state registry", e)
			// Create a new controller as fallback
			SavedStateRegistryController.create(this).savedStateRegistry
		}

	/**
	 * Enhanced performRestore with error handling
	 */
	fun performRestore(savedState: Bundle?) {
		if (isDestroyed.get()) {
			Log.w("MyLifecycleOwner", "Cannot perform restore on destroyed lifecycle")
			return
		}

		try {
			mSavedStateRegistryController.performRestore(savedState)
			Log.d("MyLifecycleOwner", "Performed state restore")
		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error performing state restore", e)
			// Continue without restored state rather than crash
		}
	}

	/**
	 * Enhanced performSave with error handling
	 */
	fun performSave(outBundle: Bundle) {
		try {
			if (!isDestroyed.get()) {
				mSavedStateRegistryController.performSave(outBundle)
				Log.d("MyLifecycleOwner", "Performed state save")
			} else {
				Log.w("MyLifecycleOwner", "Cannot perform save on destroyed lifecycle")
			}
		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error performing state save", e)
		}
	}

	/**
	 * Get the number of active observers
	 */
	fun getObserverCount(): Int {
		return observers.size
	}

	/**
	 * Check if lifecycle is in a stable state
	 */
	fun isInStableState(): Boolean {
		return try {
			val state = mLifecycleRegistry.currentState
			state != Lifecycle.State.DESTROYED && isInitialized.get()
		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error checking stable state", e)
			false
		}
	}

	/**
	 * Force destroy the lifecycle (for emergency cleanup)
	 */
	fun forceDestroy() {
		try {
			Log.w("MyLifecycleOwner", "Force destroying lifecycle")

			if (!isDestroyed.get()) {
				mLifecycleRegistry.currentState = Lifecycle.State.DESTROYED
				isDestroyed.set(true)
				cleanup()
			}
		} catch (e: Exception) {
			Log.e("MyLifecycleOwner", "Error during force destroy", e)
		}
	}
}