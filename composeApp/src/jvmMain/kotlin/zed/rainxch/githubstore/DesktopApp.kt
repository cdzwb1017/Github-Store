package zed.rainxch.githubstore

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.GlobalContext
import zed.rainxch.core.data.services.LocalizationManager
import zed.rainxch.core.domain.repository.TweaksRepository
import zed.rainxch.githubstore.app.desktop.KeyboardNavigation
import zed.rainxch.githubstore.app.desktop.KeyboardNavigationEvent
import zed.rainxch.githubstore.app.di.initKoin
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.app_icon
import zed.rainxch.githubstore.core.presentation.res.app_name
import java.awt.Desktop
import kotlin.system.exitProcess

private const val LANGUAGE_PREF_READ_TIMEOUT_MS = 2000L

fun main(args: Array<String>) {
    // Install first so anything that blows up during Koin init or
    // resource loading leaves a diagnosable trail on disk (see
    // `CrashReporter.resolveLogDir` for the per-OS path).
    CrashReporter.install()

    // Guard the AWT EventDispatchThread against a known Compose MP 1.10.x NPE
    // raised by the macOS accessibility bridge (see `A11yCrashGuard`).
    A11yCrashGuard.install()

    // Skiko default backend on Linux is OpenGL, but on hybrid-GPU
    // setups (e.g. AMD iGPU + Nvidia dGPU on Bazzite) the proprietary
    // Nvidia driver path can SEGV the JVM before any Java exception
    // handler runs (see issue #546). Honour an explicit
    // `SKIKO_RENDER_API` env var if the user set one (escape hatch
    // for reporters who can rescue the install with `SOFTWARE`), and
    // otherwise leave Skiko to its default — pinning a specific API
    // unconditionally would regress users who DO have a working
    // accelerated path.
    selectLinuxRenderBackendIfRequested()

    // Reduce JVM DNS cache TTL so network changes (VPN on/off) are picked up quickly.
    // Default JVM caches positive lookups for 30s and negative lookups forever,
    // which breaks connectivity when a VPN changes DNS/routing mid-session.
    java.security.Security.setProperty("networkaddress.cache.ttl", "30")
    java.security.Security.setProperty("networkaddress.cache.negative.ttl", "5")

    initKoin()

    // Apply persisted UI language before any Compose code runs — same
    // reasoning as on Android (see `MainActivity.onCreate`). Desktop
    // Compose has no runtime `recreate()` equivalent, so mid-session
    // language swaps surface as a "restart required" snackbar from the
    // Tweaks screen; this block just covers the cold-start path so
    // users see their chosen language immediately on next launch.
    runBlocking {
        val koin = GlobalContext.get()
        val tweaksRepo = koin.get<TweaksRepository>()
        val localization = koin.get<LocalizationManager>()
        val tag =
            try {
                withTimeoutOrNull(LANGUAGE_PREF_READ_TIMEOUT_MS) {
                    tweaksRepo.getAppLanguage().first()
                }
            } catch (_: Exception) {
                null
            }
        localization.setActiveLanguageTag(tag)
    }

    val deepLinkArg = args.firstOrNull()

    if (deepLinkArg != null && DesktopDeepLink.tryForwardToRunningInstance(deepLinkArg)) {
        exitProcess(0)
    }

    DesktopDeepLink.registerUriSchemeIfNeeded()

    application {
        var deepLinkUri by mutableStateOf(deepLinkArg)

        LaunchedEffect(Unit) {
            DesktopDeepLink.startInstanceListener { uri ->
                deepLinkUri = uri
            }
        }

        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().let { desktop ->
                if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
                    desktop.setOpenURIHandler { event ->
                        deepLinkUri = event.uri.toString()
                    }
                }
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = stringResource(Res.string.app_name),
            icon = painterResource(Res.drawable.app_icon),
            onKeyEvent = { keyEvent ->
                if (keyEvent.key == Key.F && keyEvent.type == KeyEventType.KeyDown) {
                    if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                        KeyboardNavigation.onKeyClicked(KeyboardNavigationEvent.OnCtrlFClick)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            },
        ) {
            App(deepLinkUri = deepLinkUri)
        }
    }
}

/**
 * Honour `SKIKO_RENDER_API` (env) or `-Dskiko.renderApi` (system
 * property) on Linux so users hitting the Nvidia hybrid-GPU SEGV
 * (issue #546) can rescue the install via:
 *
 *     SKIKO_RENDER_API=SOFTWARE ./GitHub-Store-x86_64.AppImage
 *
 * Skiko reads the system property internally; we copy the env var
 * into the property when present so AppImage launchers don't need
 * to construct `-D` flags. Other platforms are skipped — Mac/Win
 * are unaffected by this class of bug.
 */
private fun selectLinuxRenderBackendIfRequested() {
    val osName = System.getProperty("os.name", "").lowercase()
    if (!osName.contains("linux")) return
    if (System.getProperty("skiko.renderApi") != null) return
    val fromEnv = System.getenv("SKIKO_RENDER_API")?.trim().orEmpty()
    if (fromEnv.isEmpty()) return
    System.setProperty("skiko.renderApi", fromEnv)
}
