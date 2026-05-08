package zed.rainxch.tweaks.presentation

import co.touchlab.kermit.Logger
import kotlin.system.exitProcess

/**
 * Best-effort "relaunch this JVM" for the Desktop language-change
 * flow. In `jpackage`-built installers (DMG/MSI/DEB) the current
 * process's command line is a clean invocation of the app launcher,
 * and [ProcessHandle] reliably gives us the executable path plus the
 * original arguments — `ProcessBuilder` can spawn a fresh instance
 * from that and we just exit this one. From IDE runs / `./gradlew
 * run` the command line reflects the Gradle-managed forked JVM,
 * which may or may not relaunch cleanly depending on classpath and
 * stdout wiring; if the spawn fails we still want to exit so the
 * user can reopen manually rather than be stuck in a half-applied
 * state.
 *
 * [inheritIO] so the relaunched process shares our stdin/stdout/
 * stderr — mostly relevant in terminal runs; packaged apps have no
 * attached terminal so it's a no-op there.
 */
actual fun restartAppAfterLanguageChange() {
    try {
        val info = ProcessHandle.current().info()
        val command = info.command().orElse(null)
        if (command != null) {
            val arguments = info.arguments().orElse(emptyArray())
            ProcessBuilder(listOf(command) + arguments.toList())
                .inheritIO()
                .start()
        } else {
            Logger.w {
                "restartAppAfterLanguageChange: ProcessHandle has no command; exiting without relaunch"
            }
        }
    } catch (t: Throwable) {
        // Swallow: we'd rather exit cleanly than leave the user in a
        // limbo where the app is stuck with the old locale because
        // the relaunch errored out.
        Logger.w(t) {
            "restartAppAfterLanguageChange: relaunch failed, falling back to plain exit"
        }
    }
    exitProcess(0)
}
