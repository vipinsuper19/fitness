# android/

Fitness Android app (`com.vipin.fitness`, app name "Fitness").

Stack: Kotlin 2.4 (AGP 9 built-in Kotlin), Jetpack Compose + Material 3, Hilt (KSP). minSdk 26, compile/target SDK 37.
Retrofit, Room, DataStore and WorkManager arrive with T8.

## Modules
- `app` – `FitnessApplication` (`@HiltAndroidApp`), `MainActivity`, app shell with bottom tabs (`navigation/`).
- `core:designsystem` – `FitnessTheme` (Material 3, light/dark, optional dynamic color), typography, shared placeholder.
- `feature:home`, `feature:workout`, `feature:nutrition`, `feature:progress` – tab screen stubs.

## Build (JDK 21)
```
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```
CI runs the same command (`.github/workflows/android.yml`, path-filtered to `android/**`).
