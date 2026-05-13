# GitHub Store

Cross-platform app store for GitHub releases. **Kotlin Multiplatform** + **Compose Multiplatform**. Android (min API 26) + Desktop (JVM: Win/macOS/Linux). Package `zed.rainxch.githubstore`. Version 1.8.2 (code 17). Target SDK 36.

## Build

```bash
./gradlew :composeApp:assembleDebug                                    # Android
./gradlew :composeApp:run                                              # Desktop dev
./gradlew :composeApp:packageExe :composeApp:packageMsi                # Win installer
./gradlew :composeApp:packageDmg :composeApp:packagePkg                # macOS
./gradlew :composeApp:packageDeb :composeApp:packageRpm                # Linux
./gradlew build                                                        # full
```

JDK 21+. Android SDK for Android.

## Structure

```
composeApp/            # entry points, navigation, DI wiring (commonMain / androidMain / jvmMain)
core/
  domain/              # interfaces, models, use cases (no framework deps)
  data/                # repos, Ktor, Room, Koin, platform impls
  presentation/        # Material 3 theme + reusable components + 13-locale strings
feature/
  apps auth details dev-profile favourites home profile recently-viewed search starred tweaks
build-logic/convention/  # convention plugins
```

Each feature: up to 3 sub-modules (`domain/`, `data/`, `presentation/`). `favourites`, `starred`, `recently-viewed` are presentation-only.

## Architecture

Clean Architecture + MVVM. Layers: **Domain** (contracts), **Data** (Ktor + Room + Koin DI), **Presentation** (ViewModels with `StateFlow`/`Channel`, Compose).

### State pattern (every screen)

```kotlin
class XViewModel : ViewModel() {
    private val _state = MutableStateFlow(XState())
    val state = _state.asStateFlow()                  // or .stateIn(WhileSubscribed)
    private val _events = Channel<XEvent>()
    val events = _events.receiveAsFlow()
    fun onAction(action: XAction) { ... }
}
```

`State` = data class. `Action` = sealed (user input). `Event` = sealed (one-off effects).

### Navigation

`@Serializable` sealed interface `GithubStoreGraph` in `composeApp/.../app/navigation/`. Routes: `HomeScreen`, `SearchScreen`, `AuthenticationScreen`, `ProfileScreen`, `TweaksScreen`, `FavouritesScreen`, `StarredReposScreen`, `RecentlyViewedScreen`, `AppsScreen`, `SponsorScreen`, `ExternalImportScreen`, `MirrorPickerScreen`, `StarredPickerScreen`, `SkippedUpdatesScreen`, `HiddenRepositoriesScreen`, `WhatsNewHistoryScreen`, `AnnouncementsScreen`, `DetailsScreen(repositoryId, owner, repo, isComingFromUpdate)`, `DeveloperProfileScreen(username)`.

### DI

Koin. Feature modules in `data/di/SharedModule.kt`. ViewModels in `composeApp/.../app/di/ViewModelsModule.kt` (`viewModelOf(::X)` or explicit `viewModel { ... }`). Wired in `initKoin.kt`.

## Core repositories (`core/domain`)

`FavouritesRepository`, `StarredRepository`, `InstalledAppsRepository`, `SeenReposRepository`, `HiddenReposRepository`, `SearchHistoryRepository`, `TweaksRepository`, `AuthenticationState`, `ThemesRepository`, `ProxyRepository`, `RateLimitRepository`, `ExternalImportRepository`, `TelemetryRepository`. Util: `AssetVariant` (token/glob/stem fingerprinting), `assetPlatformOf`. System interfaces: `Installer`, `InstallerStatusProvider`, `PackageMonitor`, `SystemInstallSerializer`.

## Tech

Kotlin 2.3.10, Compose Multiplatform 1.10.3, Ktor 3.4.0, Room 2.8.4, Koin 4.1.1, kotlinx.serialization 1.10.0, DataStore 1.2.0, Landscapist 2.9.5, Kermit 2.0.8, MOKO Permissions 0.20.1, Navigation Compose 2.9.2, multiplatform-markdown-renderer 0.39.2, Shizuku 13.1.5, WorkManager 2.11.1, kotlinx.datetime 0.7.1. Versions in `gradle/libs.versions.toml`.

## Convention plugins (`build-logic/convention/`)

`convention.kmp.library` (domain/data), `convention.cmp.library` (core/presentation), `convention.cmp.feature` (feature presentation), `convention.cmp.application` (main app), `convention.room`, `convention.buildkonfig`.

## Adding a feature

1. `feature/<name>/{domain,data,presentation}/` with appropriate convention plugin
2. `include` in `settings.gradle.kts`
3. Domain interfaces → impl + Koin module in `data/di/SharedModule.kt` → ViewModel + Screen
4. Route in `GithubStoreGraph.kt` + wire in `AppNavigation.kt` + register Koin in `initKoin.kt`

## Key configuration

- **GitHub OAuth:** `GITHUB_CLIENT_ID` in `local.properties`. Callback `githubstore://callback`. Deep link `githubstore://repo`.
- **Shizuku (Android):** silent install via `ShizukuProvider` → AIDL → `pm install -S`. Fallback to standard installer on failure.
- **Desktop logs:** `CrashReporter` (first line of `DesktopApp.main`) tees stdout/stderr to rotating `session.log` + writes `crash-<ts>.log` on uncaught. Paths: `~/Library/Logs/GitHub-Store/` (macOS), `%LOCALAPPDATA%/GitHub-Store/logs/` (Win), `$XDG_STATE_HOME/GitHub-Store/logs/` (Linux). Android = Logcat.
- **`X-GitHub-Token` header:** Client attaches when `TokenStore.currentToken()` is non-null on `/v1/search`, `/v1/search/explore`, `/v1/repo`, `/v1/releases`, `/v1/readme`, `/v1/user`. Backend re-sends as `Authorization: token $token` to GitHub. Without it, backend round-robins a 4-token service pool. Upstream 401 remapped to backend `502` (handled like "GitHub unreachable" — fall back via `shouldFallbackToGithubOrRethrow`). `429` = no fallback (same wall), only backoff. `UnauthorizedInterceptor` only on direct-GitHub client; `AuthenticationStateImpl` debounces consecutive 401s by token snapshot.
- **Device-flow auth proxy:** `feature/auth` calls backend `/v1/auth/device/start` + `/poll` as primary path. Each session picks one `AuthPath` (`Backend`|`Direct`), persists in `SavedStateHandle`, only escalates to `Direct` on infra errors (timeout/5xx); HTTP 4xx + GitHub negative 200-bodies are real answers. Backend rate limits hard: 10 starts/hr, 200 polls/hr per IP — don't add retry loops on top of Ktor's `HttpRequestRetry(maxRetries=2)`. Endpoints in `core/data/network/BackendEndpoints.kt`.
- **Windows installer signing (SignPath Foundation):** CI workflow `.github/workflows/build-desktop-platforms.yml` job `sign-windows` after every push to `generate-installers` branch. Action pinned to commit SHA (not `@v2`). Secrets: `SIGNPATH_API_TOKEN`, `SIGNPATH_ORGANIZATION_ID` (`1ecf111e-...`). Variable `SIGNPATH_SIGNING_POLICY_SLUG` = `test-signing` until prod cert issued; flip to `release-signing`. Project slug `GitHub-Store`, artifact config slug `initial`. Unsigned artifact deleted post-sign; only `windows-installers-signed` reaches the draft release.
- **Gradle:** Config + build cache enabled. 4GB Gradle heap, 3GB Kotlin daemon. Official Kotlin style.

## Active skills (apply on matching domain)

- **caveman** — session default, terse output.
- **karpathy-guidelines** — anti-overcomplication, minimal diffs, surface assumptions, verifiable success criteria. Every coding task.
- **one-skill-to-rule-them-all** — watch for skill-capture opportunities during multi-step work.
- **android-* skills** (`~/.claude/skills/android/`) — auto-fire by description match; apply when in matching domain:
  - `android-compose-ui` — composables, recomposition, animations, modifiers, design system
  - `android-data-layer` — repos, DTOs, Room, Ktor, mappers
  - `android-di-koin` — Koin module setup, ViewModel injection
  - `android-error-handling` — Result wrapper, typed errors
  - `android-module-structure` — feature-layered modules, convention plugins
  - `android-navigation` — type-safe Compose nav
  - `android-presentation-mvi` — State/Action/Event, Root/Screen split, UiText, SavedStateHandle
  - `android-testing` — testing patterns

## Conventions

- Packages `zed.rainxch.{module}.{layer}`
- Private state fields prefix `_state`
- Sealed routes/actions/events
- Repository pattern: interface in `domain/`, impl in `data/`
- Source sets: `commonMain` shared, `androidMain`, `jvmMain`
- **No KDoc, no inline comments** unless the user explicitly asks. No function/class docs. Inline only for non-obvious invariants, tricky concurrency, workarounds. Applies globally.
- Feature-specific guidance in each `feature/*/CLAUDE.md`
