import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*

fun main(args: Array<String>): Unit {
    // Capture real stdout before any library initializes and can pollute it.
    // Libraries like kotlin-logging print to System.out on init, which corrupts
    // the MCP stdio JSON-RPC protocol. We redirect System.out → stderr so those
    // prints are harmless, then pass the captured stdout to the MCP transport.
    val realStdout = System.out
    System.setOut(System.err)

    // Determine mode: --port N  or  MCP_PORT env var → HTTP, otherwise stdio
    val port = parsePort(args) ?: System.getenv("MCP_PORT")?.toIntOrNull()

    val baseDir = resolveDataDir()

    if (port != null) {
        runHttp(port, baseDir)
    } else {
        runStdio(baseDir, realStdout)
    }
}

private fun resolveDataDir(): String {
    // Explicit override takes precedence on all platforms
    System.getenv("ZAYIT_DATA_DIR")?.let { return it }

    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val appId = "io.github.kdroidfilter.seforimapp"

    return when {
        os.contains("win") -> {
            val appData = System.getenv("APPDATA") ?: "$home/AppData/Roaming"
            "$appData/$appId/databases"
        }
        os.contains("mac") -> "$home/Library/Application Support/$appId/databases"
        else -> {
            // Linux — XDG Base Directory spec
            val xdgData = System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
            "$xdgData/$appId/databases"
        }
    }
}

private fun parsePort(args: Array<String>): Int? {
    val idx = args.indexOf("--port")
    return if (idx >= 0 && idx + 1 < args.size) args[idx + 1].toIntOrNull() else null
}

private fun runStdio(baseDir: String, stdout: java.io.PrintStream): Unit = runBlocking {
    val db = SeforimDb("$baseDir/seforim.db")
    val lucene = LuceneSearch(
        ftsIndexPath = "$baseDir/seforim.db.lucene",
        lookupIndexPath = "$baseDir/seforim.db.lookup.lucene"
    )
    try {
        val server = buildServer(db, lucene)
        val done = CompletableDeferred<Unit>()
        val session = server.createSession(
            StdioServerTransport(
                inputStream = System.`in`.asSource().buffered(),
                outputStream = stdout.asSink().buffered()
            )
        )
        session.onClose { done.complete(Unit) }
        done.await()
    } finally {
        db.close()
        lucene.close()
    }
}

private fun runHttp(port: Int, baseDir: String) {
    val db = SeforimDb("$baseDir/seforim.db")
    val lucene = LuceneSearch(
        ftsIndexPath = "$baseDir/seforim.db.lucene",
        lookupIndexPath = "$baseDir/seforim.db.lookup.lucene"
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        db.close()
        lucene.close()
    })
    embeddedServer(CIO, port = port) {
        mcpStreamableHttp {
            buildServer(db, lucene)
        }
    }.start(wait = true)
}

private fun buildServer(db: SeforimDb, lucene: LuceneSearch): Server {
    val server = Server(
        serverInfo = Implementation(name = "zayit-mcp", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools()
            )
        )
    )

    server.addTool(
        name = "search_books",
        description = "Search for books by title. Returns book ID, title, category, and total lines. Use the book ID with get_text to retrieve content.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Hebrew or partial title to search"))
                })
                put("limit", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Maximum results (default: 20)"))
                })
            },
            required = listOf("query")
        )
    ) { request ->
        val args = request.params.arguments
        val query = args?.get("query")?.jsonPrimitive?.content ?: ""
        val limit = args?.get("limit")?.jsonPrimitive?.intOrNull ?: 20
        val results = db.searchBooks(query, limit)
        val text = if (results.isEmpty()) {
            "No books found for \"$query\""
        } else {
            results.joinToString("\n") { b ->
                "id=${b.id} | ${b.title} | ${b.category} | lines=${b.totalLines}${if (b.isBaseBook) " [primary]" else ""}"
            }
        }
        CallToolResult(content = listOf(TextContent(text = text)))
    }

    server.addTool(
        name = "search_ref",
        description = "Search for books by title prefix using the Lucene lookup index. Faster prefix-based lookup than search_books.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Title prefix to search for (Hebrew)"))
                })
                put("limit", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Maximum results (default: 10)"))
                })
            },
            required = listOf("query")
        )
    ) { request ->
        val args = request.params.arguments
        val query = args?.get("query")?.jsonPrimitive?.content ?: ""
        val limit = args?.get("limit")?.jsonPrimitive?.intOrNull ?: 10
        val results = lucene.searchRef(query, limit)
        val text = if (results.isEmpty()) {
            "No references found for \"$query\""
        } else {
            results.joinToString("\n") { r -> "book_id=${r.bookId} | ${r.title}" }
        }
        CallToolResult(content = listOf(TextContent(text = text)))
    }

    server.addTool(
        name = "get_text",
        description = "Retrieve text lines from a book by its ID. Use search_books or search_ref first to get the book ID. Supports pagination via offset.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("book_id", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Book ID from search_books or search_ref"))
                })
                put("offset", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Line offset to start from (default: 0)"))
                })
                put("limit", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Lines to return (default: 50, max: 200)"))
                })
            },
            required = listOf("book_id")
        )
    ) { request ->
        val args = request.params.arguments
        val bookId = args?.get("book_id")?.jsonPrimitive?.intOrNull ?: 0
        val offset = args?.get("offset")?.jsonPrimitive?.intOrNull ?: 0
        val limit = (args?.get("limit")?.jsonPrimitive?.intOrNull ?: 50).coerceAtMost(200)
        val lines = db.getText(bookId, offset, limit)
        val text = if (lines.isEmpty()) {
            "No lines found for book_id=$bookId at offset=$offset"
        } else {
            lines.joinToString("\n") { l ->
                buildString {
                    l.heRef?.let { append("[$it] ") }
                    append(l.content)
                }
            }
        }
        CallToolResult(content = listOf(TextContent(text = text)))
    }

    server.addTool(
        name = "text_search",
        description = """Full-text search using Lucene query language. Nikud and teamim are stripped automatically; final letters (ך→כ etc.) are normalized.

Lucene query syntax examples:
• Simple terms (AND by default): בראשית ברא  →  lines containing both words
• Explicit OR: אברהם OR יצחק
• Exclude term: תורה -משנה
• Phrase: "ואהבת לרעך כמוך"
• Wildcard: ברא*  (prefix),  ב?אשית  (single char)
• Fuzzy: שלום~  (similar spelling)
• Proximity: "תורה משה"~5  (within 5 words)
• Field-specific: text:אלהים
All terms are required by default; use OR / parentheses to relax.""",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Hebrew text to search (nikud optional, all terms required)"))
                })
                put("book_id", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Optional: restrict search to a specific book ID"))
                })
                put("limit", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Maximum results (default: 20)"))
                })
                put("offset", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Pagination offset (default: 0)"))
                })
            },
            required = listOf("query")
        )
    ) { request ->
        val args = request.params.arguments
        val query = args?.get("query")?.jsonPrimitive?.content ?: ""
        val bookId = args?.get("book_id")?.jsonPrimitive?.intOrNull
        val limit = args?.get("limit")?.jsonPrimitive?.intOrNull ?: 20
        val offset = args?.get("offset")?.jsonPrimitive?.intOrNull ?: 0
        val results = lucene.textSearch(query, bookId, limit, offset)
        val text = if (results.isEmpty()) {
            "No results found for \"$query\""
        } else {
            // Fetch actual content from SQLite (text_raw not stored in Lucene index)
            val lineContents = db.getLinesByIds(results.map { it.lineId })
            results.joinToString("\n\n") { r ->
                val content = lineContents[r.lineId]
                val ref = content?.heRef?.let { "[$it] " } ?: ""
                "book_id=${r.bookId} | ${r.bookTitle} | line_id=${r.lineId}\n$ref${content?.content ?: ""}"
            }
        }
        CallToolResult(content = listOf(TextContent(text = text)))
    }

    server.addTool(
        name = "get_text_by_ref",
        description = "Retrieve text by Hebrew reference string (heRef). Searches the heRef column which contains references like 'בראשית א, א' or 'ברכות ב, א'. Returns matching lines plus surrounding context. Use this instead of get_text when you know the reference but not the book_id.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("ref", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Hebrew reference to search (e.g. 'בראשית א, א', 'ברכות ב')"))
                })
                put("context", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Lines of context before/after each hit (default: 2)"))
                })
                put("limit", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Maximum hits to return (default: 10)"))
                })
            },
            required = listOf("ref")
        )
    ) { request ->
        val args = request.params.arguments
        val ref = args?.get("ref")?.jsonPrimitive?.content ?: ""
        val context = args?.get("context")?.jsonPrimitive?.intOrNull ?: 2
        val limit = args?.get("limit")?.jsonPrimitive?.intOrNull ?: 10
        val hits = db.getTextByRef(ref, context, limit)
        val text = if (hits.isEmpty()) {
            "No lines found matching ref \"$ref\""
        } else {
            hits.joinToString("\n\n---\n\n") { hit ->
                buildString {
                    append("book_id=${hit.bookId} | ${hit.bookTitle} | line_id=${hit.lineId}\n")
                    // context before
                    hit.contextLines.filter { it.lineIndex < hit.lineIndex }.forEach { c ->
                        append("  ${c.heRef?.let { r -> "[$r] " } ?: ""}${c.content}\n")
                    }
                    // the matched line
                    append("► [${hit.heRef}] ${hit.content}\n")
                    // context after
                    hit.contextLines.filter { it.lineIndex > hit.lineIndex }.forEach { c ->
                        append("  ${c.heRef?.let { r -> "[$r] " } ?: ""}${c.content}\n")
                    }
                }.trimEnd()
            }
        }
        CallToolResult(content = listOf(TextContent(text = text)))
    }

    server.addTool(
        name = "get_book_toc",
        description = "Get the table of contents for a book. Returns the hierarchical chapter/section structure with line IDs you can pass to get_text.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("book_id", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Book ID from search_books or search_ref"))
                })
            },
            required = listOf("book_id")
        )
    ) { request ->
        val bookId = request.params.arguments?.get("book_id")?.jsonPrimitive?.intOrNull ?: 0
        val entries = db.getBookToc(bookId)
        val text = if (entries.isEmpty()) {
            "No TOC found for book_id=$bookId"
        } else {
            entries.joinToString("\n") { e ->
                val indent = "  ".repeat(e.level)
                val lineRef = e.lineId?.let { " → line_id=$it" } ?: ""
                val marker = if (e.hasChildren) "▸" else "•"
                "$indent$marker ${e.text}$lineRef"
            }
        }
        CallToolResult(content = listOf(TextContent(text = text)))
    }

    server.addTool(
        name = "get_links",
        description = "Get cross-references (links) for a book or specific line. Connection types include: Commentary, Targum, Reference, Source, Other. Returns source and target references with text snippets.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("book_id", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Book ID to find links for"))
                })
                put("line_id", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Optional: restrict to a specific line ID"))
                })
                put("connection_type", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional filter: Commentary, Targum, Reference, Source, Other"))
                })
                put("limit", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Maximum links to return (default: 30)"))
                })
            },
            required = listOf("book_id")
        )
    ) { request ->
        val args = request.params.arguments
        val bookId = args?.get("book_id")?.jsonPrimitive?.intOrNull ?: 0
        val lineId = args?.get("line_id")?.jsonPrimitive?.intOrNull
        val connType = args?.get("connection_type")?.jsonPrimitive?.contentOrNull
        val limit = args?.get("limit")?.jsonPrimitive?.intOrNull ?: 30
        val links = db.getLinks(bookId, lineId, limit, connType)
        val text = if (links.isEmpty()) {
            "No links found for book_id=$bookId${lineId?.let { ", line_id=$it" } ?: ""}"
        } else {
            links.joinToString("\n\n") { lk ->
                "[${lk.connectionType}]\n" +
                "  SRC book_id=${lk.sourceBookId} | ${lk.sourceBookTitle} | ${lk.sourceRef}\n" +
                "      ${lk.sourceContent.take(120)}\n" +
                "  TGT book_id=${lk.targetBookId} | ${lk.targetBookTitle} | ${lk.targetRef}\n" +
                "      ${lk.targetContent.take(120)}"
            }
        }
        CallToolResult(content = listOf(TextContent(text = text)))
    }

    return server
}
