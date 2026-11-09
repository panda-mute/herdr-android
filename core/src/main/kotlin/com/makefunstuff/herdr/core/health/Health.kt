package com.makefunstuff.herdr.core.health

/** RAM and swap, in KiB. [availableKb] is what new programs can get without swapping (`MemAvailable`). */
data class Memory(val totalKb: Long, val availableKb: Long, val swapTotalKb: Long = 0, val swapFreeKb: Long = 0) {
    val usedKb: Long get() = (totalKb - availableKb).coerceAtLeast(0)
    val swapUsedKb: Long get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0)

    /** 0–100. */
    val availablePercent: Double get() = if (totalKb > 0) availableKb * 100.0 / totalKb else 100.0
    val usedPercent: Double get() = 100.0 - availablePercent
    val swapUsedPercent: Double get() = if (swapTotalKb > 0) swapUsedKb * 100.0 / swapTotalKb else 0.0
}

/** One mounted filesystem, in KiB, as `df` counts it ([usedKb] + [availKb] is less than [totalKb] by what root keeps back). */
data class Disk(val mount: String, val device: String, val type: String, val totalKb: Long, val usedKb: Long, val availKb: Long) {
    /** 0–100, as `df`'s Capacity: used of what the account could fill. */
    val usedPercent: Double get() = if (usedKb + availKb > 0) usedKb * 100.0 / (usedKb + availKb) else 0.0
    val availPercent: Double get() = 100.0 - usedPercent
}

/**
 * One process, as the probe saw it. [cpu] is the share of one core over the probe's one-second look
 * (200 is two busy cores), or the system's own average where it can't look twice (macOS).
 * [cwd], [launchDir] (the `PWD` it was started with) and the herdr pane it runs in are only known for
 * the account's own processes on Linux.
 */
data class Proc(
    val pid: Int,
    val ppid: Int,
    val uid: Int,
    val rssKb: Long,
    val cpu: Double,
    /** Seconds since it started. */
    val ageSeconds: Long,
    val comm: String,
    val args: String,
    val cwd: String = "",
    val launchDir: String = "",
    val paneId: String = "",
    val workspaceId: String = "",
)

/** One look at a machine (`HealthCommands.probe`). [at] is the machine's clock, in seconds. */
data class HealthSample(
    val at: Long,
    val os: String,
    val uid: Int,
    val home: String,
    val cores: Int,
    val memory: Memory?,
    val load1: Double,
    val load5: Double,
    val load15: Double,
    /** How busy the CPUs were over the look, 0–100; null where it couldn't be measured. */
    val cpuBusy: Double?,
    val disks: List<Disk>,
    /** The processes worth showing: the big ones, the busy ones, and the account's own in worktrees. */
    val procs: List<Proc>,
) {
    /** The load average as a share of the cores, 0–100 (and more when overloaded). */
    val loadPercent: Double get() = if (cores > 0) load1 * 100.0 / cores else 0.0

    fun own(p: Proc): Boolean = p.uid == uid

    /** Whether this is a Windows machine (Git Bash reports MINGW, MSYS or CYGWIN as its system). */
    val windows: Boolean get() = listOf("MINGW", "MSYS", "CYGWIN").any { os.startsWith(it) }
}

/** One point of the last hour's chart. Percentages 0–100; [at] in seconds, the machine's clock. */
data class HealthPoint(val at: Long, val memUsed: Double, val swapUsed: Double, val cpu: Double?) {
    companion object {
        fun of(s: HealthSample): HealthPoint? = s.memory?.let {
            HealthPoint(s.at, it.usedPercent, it.swapUsedPercent, s.cpuBusy ?: s.loadPercent.coerceAtMost(100.0))
        }
    }
}

/** A worktree on the machine's disk (`HealthCommands.worktrees`). [sizeKb] is null when it took too long to count. */
data class WorktreeDir(val path: String, val sizeKb: Long?, val dirty: Boolean) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
    val repo: String get() = path.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
}

/** Byte sizes as people read them: "812 MB", "3.4 GB". */
object Sizes {
    fun kb(kb: Long): String {
        val mb = kb / 1024.0
        return when {
            mb < 1 -> "$kb KB"
            mb < 1000 -> "${mb.toInt()} MB"
            mb < 1024 * 100 -> String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024)
            mb < 1024 * 1000 -> "${(mb / 1024).toInt()} GB"
            else -> String.format(java.util.Locale.ROOT, "%.1f TB", mb / 1024 / 1024)
        }
    }

    fun percent(p: Double): String = "${p.toInt().coerceIn(0, 999)}%"

    /** "3 h", "12 min", "40 s", "2 d". */
    fun age(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86400 * 2 -> "${seconds / 3600} h"
        else -> "${seconds / 86400} d"
    }
}
