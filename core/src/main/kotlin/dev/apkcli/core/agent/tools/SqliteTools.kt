package dev.apkcli.core.agent.tools

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.apkcli.core.agent.Schema
import dev.apkcli.core.agent.Schema.integer
import dev.apkcli.core.agent.Schema.string
import dev.apkcli.core.agent.Tool
import dev.apkcli.core.agent.ToolContext
import dev.apkcli.core.agent.ToolResult
import dev.apkcli.core.sched.TaskClass
import dev.apkcli.core.util.int
import dev.apkcli.core.util.str
import kotlinx.serialization.json.JsonObject

/* ----------------------------------------------------------- sqlite_query */

/**
 * Read-only SQL on a database file in the project, with Android's own SQLite:
 * nothing to install, on either build.
 */
class SqliteQueryTool : Tool {
    override val name = "sqlite_query"
    override val readOnly = true
    override val parallelSafe = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Run read-only SQL on an SQLite database file and return the rows as a table.

        When to use it: looking inside a .db, .sqlite or .sqlite3 file — its tables
        (`SELECT name, sql FROM sqlite_master`), a count, the rows a bug is about. The
        file is opened read-only, so INSERT, UPDATE and schema changes fail; make those
        from the project's own code, or with sh_exec where sqlite3 is installed.

        Output: the column names, then up to maxRows rows, one per line, values
        separated by " | "; long values are cut and blobs shown by size. It says when
        there were more rows than it showed.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("path", "sql")) {
        string("path", "The database file, absolute or relative to the project root.")
        string("sql", "One statement, e.g. SELECT * FROM users LIMIT 20.")
        integer("maxRows", "Rows to show at most.", default = 100, maximum = 1_000)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.error("'path' is required.")
        val sql = args.str("sql")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.error("'sql' is required.")
        val file = ctx.resolve(path) ?: return outsideSandbox(ctx, path)
        if (!file.isFile) return ToolResult.error("${ctx.display(file)} is not a file.")
        // ATTACH would open another file, outside what the path was checked for.
        if (ATTACH.containsMatchIn(sql)) return ToolResult.refused("ATTACH is not allowed: query one database file per call.")
        val maxRows = args.int("maxRows", 100).coerceIn(1, 1_000)

        return runCatching {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { db ->
                db.rawQuery(sql, null).use { cursor -> ToolResult.ok(table(cursor, maxRows)) }
            }
        }.getOrElse { ToolResult.error("${ctx.display(file)}: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun table(cursor: Cursor, maxRows: Int): String {
        val columns = cursor.columnNames.toList()
        val rows = ArrayList<List<String>>()
        while (rows.size < maxRows && cursor.moveToNext()) {
            rows += columns.indices.map { cell(cursor, it) }
        }
        val more = rows.size == maxRows && cursor.moveToNext()
        return render(columns, rows, more)
    }

    private fun cell(cursor: Cursor, column: Int): String = when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> "NULL"
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column).toString()
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toString()
        Cursor.FIELD_TYPE_BLOB -> "<blob ${cursor.getBlob(column).size} bytes>"
        else -> cursor.getString(column).orEmpty()
    }

    companion object {
        private val ATTACH = Regex("""\battach\b""", RegexOption.IGNORE_CASE)
        private const val MAX_CELL = 200

        /** [rows] under [columns], one per line; [more] when the query had rows past them. */
        internal fun render(columns: List<String>, rows: List<List<String>>, more: Boolean): String = buildString {
            if (columns.isEmpty()) {
                append("The statement returned no columns.")
                return@buildString
            }
            appendLine(columns.joinToString(" | "))
            rows.forEach { row -> appendLine(row.joinToString(" | ") { value -> clip(value) }) }
            append(
                when {
                    rows.isEmpty() -> "(no rows)"
                    more -> "(${rows.size} rows shown; there are more: narrow the query or raise maxRows)"
                    else -> "(${rows.size} row${if (rows.size == 1) "" else "s"})"
                },
            )
        }

        /** One line, at most [MAX_CELL] characters. */
        private fun clip(value: String): String {
            val line = value.replace("\r\n", "\\n").replace("\n", "\\n")
            return if (line.length <= MAX_CELL) line else line.take(MAX_CELL - 1) + "…"
        }
    }
}
