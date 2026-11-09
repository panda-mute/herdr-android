package com.makefunstuff.herdr.core.ssh

/**
 * The Python 3 the host scripts run with, found by a piece of `sh` the commands carry. `python3` is not
 * always the one to run: on Windows it is often the Microsoft Store's alias, which only prints a hint and
 * exits 9009, and on an old Linux `python` can be a Python 2. So each candidate is run once, and the
 * first that is Python 3.6 or newer is the one used.
 */
object HostPython {
    private const val CHECK = "import sys; sys.exit(0 if sys.version_info >= (3, 6) else 1)"

    /**
     * A one-line `sh` fragment that leaves the interpreter in `P` and the arguments it needs before the
     * script in `PA` (empty, or `-3` for the Windows launcher `py`), to be run as `exec "$P" $PA "$FILE" "$@"`.
     * It tries `python3`, `python` and `py`, each on the PATH and then as a login shell finds it. It also
     * sets `n`, `a`, `w` and `q`. Without an interpreter it exits [noPythonExit] after saying so, or, when
     * that is null, leaves `P` empty.
     */
    fun find(noPythonExit: Int?): String =
        "P=; PA=; for n in python3 python py; do " +
            "a=; if [ \"\$n\" = py ]; then a=-3; fi; " +
            "for w in 1 2; do " +
            "if [ \$w = 1 ]; then q=\$(command -v \$n 2>/dev/null); " +
            "else q=\$(\"\${SHELL:-/bin/sh}\" -lc \"command -v \$n\" </dev/null 2>/dev/null | tail -n 1); fi; " +
            "if [ -n \"\$q\" ] && \"\$q\" \$a -c '$CHECK' </dev/null >/dev/null 2>&1; then P=\$q; PA=\$a; break 2; fi; " +
            "done; done" +
            (noPythonExit?.let { "; [ -n \"\$P\" ] || { echo 'Python 3.6 or newer is not installed' >&2; exit $it; }" } ?: "")
}
