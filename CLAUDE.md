# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Material You Dynamic Island is an Android application that recreates iPhone's Dynamic Island feature on Android devices running API 31+ (Android 12+). The app is built with Jetpack Compose and uses a plugin-based architecture to display contextual information and controls in an overlay UI resembling Apple's Dynamic Island.

## Essential Development Commands

### Building and Testing
```bash
# Build the project
./gradlew build

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumented tests on connected device/emulator
./gradlew connectedAndroidTest

# Run a specific test class
./gradlew test --tests "ExampleUnitTest"

# Clean build artifacts
./gradlew clean
```

### Development Setup
- Minimum SDK: API 31 (Android 12)
- Target SDK: API 36
- Compile SDK: API 36
- Java version: 19
- Kotlin version: 2.3.0

## Core Architecture

### Plugin System
The app uses a plugin-based architecture where different features are implemented as plugins that extend `BasePlugin`:

- **BasePlugin**: Abstract class defining plugin interface with composable UI, permissions, settings, and lifecycle methods
- **ExportedPlugins**: Registry containing all available plugins and their permissions
- **PluginManager**: Manages plugin lifecycle, state, and interactions
- **PluginHost**: Interface implemented by `IslandOverlayService` to provide plugin execution environment

Current plugins:
- `NotificationPlugin`: Shows notification popups in the island
- `MediaSessionPlugin`: Displays media controls when music is playing
- `BatteryPlugin`: Shows charging status and battery percentage

### Overlay Service Architecture
The core functionality is implemented through `IslandOverlayService` (AccessibilityService):
- Creates system overlay windows for the Dynamic Island UI
- Manages plugin lifecycle and state
- Handles screen orientation, power state changes
- Provides Compose integration for overlay rendering

### Navigation Structure
Uses Jetpack Navigation with a bottom navigation design:
- **Home**: Main dashboard and onboarding
- **Plugins**: Plugin management and configuration
- **Settings**: App configuration with multiple setting pages

### State Management
- Uses Compose State for UI state management
- `IslandState`: Manages island visibility, expansion state, and current plugin
- `Island`: Singleton for global state (screen state, orientation)
- SharedPreferences for persistent settings storage

### Theme System
Implements Material 3 theming with custom theme support:
- `Theme.ThemeStyle`: Enum defining available themes (Material You, Black, Quinacridone Magenta)
- Dynamic color scheme support via Material You
- Light/dark theme switching
- Custom theme inversion for app vs overlay consistency

### UI Components
- **Island Composables**: Core island UI with expansion animations
- **Shared Transitions**: Uses Compose shared element transitions between island states
- **Material 3**: Follows Material Design 3 principles with dynamic theming
- **Accompanist**: Uses various Accompanist libraries for system UI control, animations

## Key Integration Points

### Android System Integration
- **Accessibility Service**: Required for system overlay permissions and global UI interaction
- **Notification Listener**: For accessing and displaying notifications
- **Media Session**: For media playback control integration
- **Battery Manager**: For charging status and battery level monitoring

### Permissions Model
The plugin system includes a structured permission model:
- Each plugin declares required permissions
- `PluginPermission`: Base class for permission checking and requesting
- Settings integration for managing notification access and other permissions

### Settings Architecture
Multi-layered settings system:
- Global app settings (theme, behavior, position/size)
- Per-plugin settings via `PluginSettingsItem`
- Settings persistence through SharedPreferences
- Broadcast system for settings change notifications

## Development Notes

### Testing Strategy
- Unit tests in `androidTest/` for integration testing
- Compose UI tests using `ui-test-junit4`
- Manual testing required for overlay service and system integration features

### Compose Integration
- Uses Compose BOM version 2026.01.00
- Material 3 components with alpha version 1.4.0 for ModalBottomSheet
- Accompanist libraries for system UI integration
- Custom animations with Lottie and wave loading effects

### Firebase Integration
- Authentication and Firestore integration present
- Used for user settings sync and app analytics

### Build Configuration
- ProGuard disabled for release builds (minifyEnabled = false)
- Vector drawable support enabled
- Compose compiler extension version 1.5.15