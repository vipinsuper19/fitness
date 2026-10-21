# android/

Fitness Android app (`com.vipin.fitness`, app name "Fitness").

Stack: Kotlin 2.4 (AGP 9 built-in Kotlin), Jetpack Compose + Material 3, Hilt (KSP). minSdk 26, compile/target SDK 37.
Networking (Retrofit 3 + OkHttp 5 + kotlinx.serialization), encrypted token storage, DataStore and Room landed in T8. WorkManager comes with offline sync (T13/T14).

## Modules
- `app` – `FitnessApplication`, `MainActivity`, Navigation 3 `FitnessNavDisplay` (`Route`: SignIn, Register, Onboarding, Main, Profile), bottom-tab shell.
- `core:common` – `UnitSystem`, `UnitConversion` (kg/lb, cm/in, ft+in, ml/fl oz).
- `core:network` – Retrofit/OkHttp/Json setup, `AuthApi`, `AuthInterceptor`, single-flight `TokenAuthenticator`, `ApiError`/`apiCall`. Base URL: `API_BASE_URL` build config (placeholder).
- `core:datastore` – `EncryptedTokenStore` (Android Keystore AES-GCM), `UserPreferencesRepository` (units).
- `core:data` – `SessionRepository` (sign in/up/out, session expiry), `ProfileRepository`, `Field<T>`/`ProfileChanges` (explicit presence for partial updates).
- `core:testing` – `MainDispatcherRule`, fake repositories.
- `core:database` – `FitnessDatabase` (Room, schemas exported to `core/database/schemas/`), `pending_writes` outbox.
- `core:designsystem` – `FitnessTheme` (Material 3, light/dark, optional dynamic color), typography, shared placeholder.
- `feature:auth` – sign-in and registration.
- `feature:onboarding` – resumable 4-step onboarding **and the profile edit screen** (`ProfileEditRoute`/`ProfileEditViewModel`), which lives here because it reuses the onboarding `ProfileForm`. Move it to a `feature:profile` module if it grows.
- `feature:home`, `feature:workout`, `feature:nutrition`, `feature:progress` – tab screen stubs.

## Build (JDK 21)
```
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```
CI runs the same command (`.github/workflows/android.yml`, path-filtered to `android/**`).
