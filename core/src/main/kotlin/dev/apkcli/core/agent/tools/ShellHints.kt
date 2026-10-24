package dev.apkcli.core.agent.tools

/**
 * A line for the model after the two shell results it most often misreads.
 *
 *  * Exit 127, "not found": the command is not installed. Where packages can
 *    be installed, which one to ask for; models otherwise conclude the task
 *    is impossible, or reach for `apt`.
 *  * `nc` with exit 0 and no output: toybox nc shuts down its sending side as
 *    soon as its stdin ends. Behind the emulator's user-mode network, and
 *    behind proxies that do not keep half-closed connections, the reply is
 *    then dropped. Reproduced on an API 34 emulator: `printf 'GET / …' | nc
 *    example.com 80` prints nothing and exits 0, while the same request with
 *    stdin held open gets `200 OK`. Models read that as "no internet", which
 *    it is not.
 *
 * Kept free of the tool plumbing so it can be checked on the JVM.
 */
internal object ShellHints {

    /**
     * mksh: `/system/bin/sh: curl: inaccessible or not found`; dash and
     * busybox: `sh: 1: curl: not found`. The name is the field before the
     * message.
     */
    private val COMMAND_NOT_FOUND = Regex("""([^\s:]+): (?:inaccessible or )?not found""")

    /** `nc` or `netcat` as a command word, not inside another name (`rsync`, `ncdu`). */
    private val NETCAT = Regex("""(?:^|[\s|;&(`])(?:toybox\s+)?(?:nc|netcat)\s""")

    /**
     * The Termux package for commands models commonly reach for. Every
     * package name was checked against the termux-main index (October 2026);
     * anything else gets a pkg_search suggestion rather than a guess.
     */
    internal val PROVIDERS: Map<String, String> = buildMap {
        fun provide(pkg: String, vararg commands: String) = commands.forEach { put(it, pkg) }
        provide("python", "python", "python3", "pip", "pip3")
        provide("nodejs", "node", "npm", "npx")
        provide("git", "git")
        provide("curl", "curl")
        provide("wget", "wget")
        provide("make", "make")
        provide("cmake", "cmake")
        provide("ninja", "ninja")
        provide("clang", "clang", "clang++", "cc", "c++", "gcc", "g++")
        provide("openjdk-21", "java", "javac", "jar")
        provide("kotlin", "kotlinc", "kotlin")
        provide("gradle", "gradle")
        provide("maven", "mvn")
        provide("golang", "go", "gofmt")
        provide("rust", "cargo", "rustc")
        provide("zip", "zip")
        provide("unzip", "unzip")
        provide("jq", "jq")
        provide("ripgrep", "rg")
        provide("fd", "fd")
        provide("file", "file")
        provide("tree", "tree")
        provide("less", "less")
        provide("vim", "vim", "vi")
        provide("nano", "nano")
        provide("openssh", "ssh", "scp", "sftp", "ssh-keygen")
        provide("bash", "bash")
        provide("perl", "perl")
        provide("ruby", "ruby", "gem")
        provide("php", "php")
        provide("lua54", "lua")
        provide("sqlite", "sqlite3")
        provide("gawk", "awk", "gawk")
        provide("dnsutils", "dig", "nslookup")
        provide("rsync", "rsync")
        provide("openssl-tool", "openssl")
        provide("socat", "socat")
        provide("strace", "strace")
        provide("lsof", "lsof")
        provide("bc", "bc")
        provide("xz-utils", "xz", "unxz")
        provide("zstd", "zstd")
        provide("htop", "htop")
        provide("nmap", "nmap")
        provide("iproute2", "ip", "ss")
        provide("net-tools", "ifconfig", "netstat", "route")
        provide("deno", "deno")
        provide("bun", "bun")
        provide("yarn", "yarn")
        provide("pnpm", "pnpm")
    }

    /**
     * PyPI projects with native code that Termux ships prebuilt, by
     * normalised project name. Checked against the termux-main index
     * (October 2026, Python 3.14). pip sees an installed one as satisfying
     * a requirement, so installing it first spares a compile.
     */
    internal val PYTHON_PACKAGES: Map<String, String> = linkedMapOf(
        "numpy" to "python-numpy",
        "pillow" to "python-pillow",
        "cryptography" to "python-cryptography",
        "lxml" to "python-lxml",
        "scipy" to "python-scipy",
        "grpcio" to "python-grpcio",
        "psutil" to "python-psutil",
        "bcrypt" to "python-bcrypt",
        "greenlet" to "python-greenlet",
        "msgpack" to "python-msgpack",
        "rpds-py" to "python-rpds-py",
        "pyarrow" to "python-pyarrow",
        "pynacl" to "python-pynacl",
        "torch" to "python-torch",
        "brotli" to "python-brotli",
        "contourpy" to "python-contourpy",
        "llvmlite" to "python-llvmlite",
        "onnxruntime" to "python-onnxruntime",
        "pycryptodomex" to "python-pycryptodomex",
        "pymupdf" to "python-pymupdf",
        "apsw" to "python-apsw",
        "torchvision" to "python-torchvision",
        "torchaudio" to "python-torchaudio",
        "ruff" to "python-ruff",
        "yt-dlp" to "python-yt-dlp",
    )

    /** `pip install`, `pip3 install`, `python -m pip install`, as a command word. */
    private val PIP_INSTALL = Regex("""(?:^|[\s|;&(`/])(?:pip3?(?:\.\d+)?|python3?(?:\.\d+)?\s+-m\s+pip)\s+install\b""")

    /**
     * What pip failed to build: "Failed building wheel for X", and the
     * summary of newer pips, "Failed to build X" or "Failed to build
     * installable wheels for some pyproject.toml based projects (X, Y)".
     */
    private val PIP_BUILD_FAILED = listOf(
        Regex("""Failed building wheel for ([A-Za-z0-9_.\-]+)"""),
        Regex("""Failed to build installable wheels for some pyproject\.toml based projects \(([^)]+)\)"""),
        Regex("""^(?:ERROR: )?Failed to build (?!installable)([A-Za-z0-9_.\-]+(?: [A-Za-z0-9_.\-]+)*)\s*$""", RegexOption.MULTILINE),
    )

    private fun normalise(project: String) = project.lowercase().replace(Regex("[-_.]+"), "-")

    /**
     * @param canInstall whether pkg_install exists in this build.
     * @return the hint, or null when the result needs none.
     */
    fun afterRun(command: String, exitCode: Int?, stdout: String, stderr: String, canInstall: Boolean): String? {
        if (exitCode != null && exitCode != 0 && canInstall && PIP_INSTALL.containsMatchIn(command)) {
            pipBuildHint("$stdout\n$stderr")?.let { return it }
        }
        if (exitCode == 127) {
            // The last report is the command that ended the script.
            val missing = COMMAND_NOT_FOUND.findAll(stderr).lastOrNull()?.groupValues?.get(1)
                // A path ("./build.sh: not found") is a wrong path, not a package.
                ?.takeIf { name -> '/' !in name }
                ?: return null
            if (!canInstall) {
                return "`$missing` is not installed, and this build cannot install programs. " +
                    "Work with the tools listed in the environment section."
            }
            val provider = PROVIDERS[missing]
            return if (provider != null) {
                "`$missing` is not installed. Install it with pkg_install(packages: [\"$provider\"]), " +
                    "then run the command again."
            } else {
                "`$missing` is not installed. Find the package that provides it with " +
                    "pkg_search(query: \"$missing\"), install it with pkg_install, then run the command again."
            }
        }
        return netcatHint(command, exitCode, stdout, canInstall)
    }

    /** After a pip install that failed compiling: what to install instead. */
    internal fun pipBuildHint(output: String): String? {
        val failed = PIP_BUILD_FAILED
            .flatMap { pattern -> pattern.findAll(output).map { it.groupValues[1] }.toList() }
            .flatMap { it.split(',', ' ') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::normalise)
            .distinct()
        if (failed.isEmpty()) return null
        val prebuilt = failed.mapNotNull { name -> PYTHON_PACKAGES[name] }
        val names = failed.joinToString(", ")
        return buildString {
            append("pip could not compile $names: PyPI has no Android wheel for it, so pip built it from source here. ")
            if (prebuilt.isNotEmpty()) {
                val packages = prebuilt.joinToString(", ", "[", "]") { "\"$it\"" }
                append("Termux ships it prebuilt: pkg_install(packages: $packages), ")
                append("then run the pip command again; pip treats it as installed. ")
            } else {
                append("Check for a prebuilt one with pkg_search(query: \"python-${failed.first()}\"). ")
            }
            append("Otherwise pin an older release that has an Android wheel (`pip index versions <name>` lists ")
            append("releases), or install the C compiler a C extension needs (pkg_install clang).")
        }
    }

    private fun netcatHint(command: String, exitCode: Int?, stdout: String, canInstall: Boolean): String? {
        if (exitCode == 0 && stdout.isBlank() && NETCAT.containsMatchIn(command)) {
            return "nc printed nothing. That is not proof of no network: toybox nc closes its sending " +
                "side when its stdin ends, and on the emulator (and behind some proxies) the reply is " +
                "then lost. For HTTP use web_fetch, or curl" + (if (canInstall) " (pkg_install curl)" else "") +
                ". With nc, keep stdin open: " +
                "(printf 'GET / HTTP/1.0\\r\\nHost: example.com\\r\\n\\r\\n'; sleep 3) | nc -W 5 example.com 80"
        }
        return null
    }
}
