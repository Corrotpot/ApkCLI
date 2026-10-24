package dev.apkcli.ui.chat

/**
 * Commands typed into the composer: design/prototype/app.js COMMANDS and
 * slashOf.
 *
 * "/" at the start of the words opens the commands plaque over the composer,
 * in the changes bar's place, with the commands the words can still become;
 * a tap runs one, and so does sending the words once they are one. Anything
 * else that starts with "/" — a path, "/etc/hosts" — is an ordinary message,
 * and the plaque stays away.
 */
object ChatCommands {

    enum class Command(val word: String) {
        /** `/compact [what to keep]`: the conversation folded into a summary for the agent. */
        COMPACT("compact"),
    }

    /**
     * Words that are, or begin, a command: the name so far, the words after
     * it, the command once the name is whole, and the commands it can still
     * become (only its own once words follow it).
     */
    class Typed(val name: String, val args: String, val exact: Command?, val matches: List<Command>)

    /** What [text] is as a command; null when it is not one. */
    fun parse(text: String): Typed? {
        val words = text.trimStart()
        if (!words.startsWith('/')) return null
        var end = 1
        while (end < words.length && words[end].isAsciiLetter()) end++
        // "/etc/hosts", "/2": a path or a number, not a command.
        if (end < words.length && !words[end].isWhitespace()) return null
        val name = words.substring(1, end).lowercase()
        val exact = Command.entries.firstOrNull { it.word == name }
        val followed = end < words.length
        val matches = if (followed) listOfNotNull(exact) else Command.entries.filter { it.word.startsWith(name) }
        return Typed(name, if (followed) words.substring(end).trim() else "", exact, matches)
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}
