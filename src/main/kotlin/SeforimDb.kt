import java.sql.Connection
import java.sql.DriverManager

class SeforimDb(dbPath: String) : AutoCloseable {

    init {
        Class.forName("org.sqlite.JDBC")
    }

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath").also { c ->
        c.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA query_only=ON")
            s.execute("PRAGMA cache_size=-65536")
        }
    }

    fun searchBooks(query: String, limit: Int = 20): List<BookResult> {
        val sql = """
            SELECT b.id, b.title, c.title AS category, b.isBaseBook, b.totalLines
            FROM book b
            JOIN category c ON b.categoryId = c.id
            WHERE b.title LIKE ?
            ORDER BY b.isBaseBook DESC, b.orderIndex, b.title
            LIMIT ?
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, "%$query%")
            ps.setInt(2, limit)
            val rs = ps.executeQuery()
            buildList {
                while (rs.next()) add(
                    BookResult(
                        id = rs.getInt("id"),
                        title = rs.getString("title"),
                        category = rs.getString("category"),
                        isBaseBook = rs.getInt("isBaseBook") == 1,
                        totalLines = rs.getInt("totalLines")
                    )
                )
            }
        }
    }

    fun getText(bookId: Int, offset: Int = 0, limit: Int = 50): List<LineResult> {
        val sql = """
            SELECT l.id, l.lineIndex, l.content, l.heRef
            FROM line l
            WHERE l.bookId = ?
            ORDER BY l.lineIndex
            LIMIT ? OFFSET ?
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setInt(1, bookId)
            ps.setInt(2, limit)
            ps.setInt(3, offset)
            val rs = ps.executeQuery()
            buildList {
                while (rs.next()) add(
                    LineResult(
                        id = rs.getInt("id"),
                        lineIndex = rs.getInt("lineIndex"),
                        content = rs.getString("content"),
                        heRef = rs.getString("heRef")
                    )
                )
            }
        }
    }

    fun getLinesByIds(lineIds: List<Int>): Map<Int, LineResult> {
        if (lineIds.isEmpty()) return emptyMap()
        val placeholders = lineIds.joinToString(",") { "?" }
        val sql = "SELECT id, lineIndex, content, heRef FROM line WHERE id IN ($placeholders)"
        return conn.prepareStatement(sql).use { ps ->
            lineIds.forEachIndexed { i, id -> ps.setInt(i + 1, id) }
            val rs = ps.executeQuery()
            buildMap {
                while (rs.next()) {
                    val id = rs.getInt("id")
                    put(id, LineResult(id, rs.getInt("lineIndex"), rs.getString("content"), rs.getString("heRef")))
                }
            }
        }
    }

    // Tool 1: get_text_by_ref
    // heRef values in the DB look like "בראשית א, א" — we do a LIKE search.
    // Returns matching lines plus `context` lines before/after each hit.
    fun getTextByRef(ref: String, context: Int = 2, limit: Int = 10): List<RefHit> {
        val sql = """
            SELECT l.id, l.bookId, l.lineIndex, l.content, l.heRef, b.title AS bookTitle
            FROM line l
            JOIN book b ON l.bookId = b.id
            WHERE l.heRef LIKE ?
            ORDER BY b.orderIndex, b.isBaseBook DESC, l.lineIndex
            LIMIT ?
        """.trimIndent()
        val hits = conn.prepareStatement(sql).use { ps ->
            ps.setString(1, "%$ref%")
            ps.setInt(2, limit)
            val rs = ps.executeQuery()
            buildList {
                while (rs.next()) add(
                    RefHit(
                        bookId = rs.getInt("bookId"),
                        bookTitle = rs.getString("bookTitle"),
                        lineId = rs.getInt("id"),
                        lineIndex = rs.getInt("lineIndex"),
                        heRef = rs.getString("heRef") ?: "",
                        content = rs.getString("content"),
                        contextLines = emptyList()
                    )
                )
            }
        }
        if (context == 0 || hits.isEmpty()) return hits

        // Fetch surrounding context lines for each hit
        val ctxSql = """
            SELECT id, lineIndex, content, heRef
            FROM line
            WHERE bookId = ? AND lineIndex BETWEEN ? AND ?
            ORDER BY lineIndex
        """.trimIndent()
        return conn.prepareStatement(ctxSql).use { ps ->
            hits.map { hit ->
                ps.setInt(1, hit.bookId)
                ps.setInt(2, maxOf(0, hit.lineIndex - context))
                ps.setInt(3, hit.lineIndex + context)
                val rs = ps.executeQuery()
                val ctxLines = buildList {
                    while (rs.next()) {
                        val idx = rs.getInt("lineIndex")
                        if (idx != hit.lineIndex) add(
                            LineResult(rs.getInt("id"), idx, rs.getString("content"), rs.getString("heRef"))
                        )
                    }
                }
                hit.copy(contextLines = ctxLines)
            }
        }
    }

    // Tool 2: get_book_toc
    fun getBookToc(bookId: Int): List<TocEntry> {
        val sql = """
            SELECT te.id, te.parentId, te.level, te.lineId, te.hasChildren, tt.text
            FROM tocEntry te
            JOIN tocText tt ON te.textId = tt.id
            WHERE te.bookId = ?
            ORDER BY te.id
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setInt(1, bookId)
            val rs = ps.executeQuery()
            buildList {
                while (rs.next()) add(
                    TocEntry(
                        id = rs.getInt("id"),
                        parentId = rs.getInt("parentId").takeIf { !rs.wasNull() },
                        level = rs.getInt("level"),
                        lineId = rs.getInt("lineId").takeIf { !rs.wasNull() },
                        hasChildren = rs.getInt("hasChildren") == 1,
                        text = rs.getString("text")
                    )
                )
            }
        }
    }

    // Tool 3: get_links — cross-references from/to a book or specific line
    fun getLinks(bookId: Int, lineId: Int? = null, limit: Int = 30, connectionType: String? = null): List<LinkResult> {
        val whereClause = buildString {
            append("(lk.sourceBookId = ? OR lk.targetBookId = ?)")
            if (lineId != null) append(" AND (lk.sourceLineId = ? OR lk.targetLineId = ?)")
            if (connectionType != null) append(" AND ct.name = ?")
        }
        val sql = """
            SELECT
                sb.id AS srcBookId, sb.title AS srcBookTitle,
                sl.heRef AS srcRef, sl.content AS srcContent,
                tb.id AS tgtBookId, tb.title AS tgtBookTitle,
                tl.heRef AS tgtRef, tl.content AS tgtContent,
                ct.name AS connType
            FROM link lk
            JOIN book sb ON lk.sourceBookId = sb.id
            JOIN line sl ON lk.sourceLineId = sl.id
            JOIN book tb ON lk.targetBookId = tb.id
            JOIN line tl ON lk.targetLineId = tl.id
            JOIN connection_type ct ON lk.connectionTypeId = ct.id
            WHERE $whereClause
            LIMIT ?
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setInt(idx++, bookId); ps.setInt(idx++, bookId)
            if (lineId != null) { ps.setInt(idx++, lineId); ps.setInt(idx++, lineId) }
            if (connectionType != null) ps.setString(idx++, connectionType)
            ps.setInt(idx, limit)
            val rs = ps.executeQuery()
            buildList {
                while (rs.next()) add(
                    LinkResult(
                        sourceBookId = rs.getInt("srcBookId"),
                        sourceBookTitle = rs.getString("srcBookTitle"),
                        sourceRef = rs.getString("srcRef") ?: "",
                        sourceContent = rs.getString("srcContent"),
                        targetBookId = rs.getInt("tgtBookId"),
                        targetBookTitle = rs.getString("tgtBookTitle"),
                        targetRef = rs.getString("tgtRef") ?: "",
                        targetContent = rs.getString("tgtContent"),
                        connectionType = rs.getString("connType")
                    )
                )
            }
        }
    }

    override fun close() = conn.close()
}

data class BookResult(val id: Int, val title: String, val category: String, val isBaseBook: Boolean, val totalLines: Int)
data class LineResult(val id: Int, val lineIndex: Int, val content: String, val heRef: String?)
data class RefHit(
    val bookId: Int, val bookTitle: String,
    val lineId: Int, val lineIndex: Int,
    val heRef: String, val content: String,
    val contextLines: List<LineResult>
)
data class TocEntry(val id: Int, val parentId: Int?, val level: Int, val lineId: Int?, val hasChildren: Boolean, val text: String)
data class LinkResult(
    val sourceBookId: Int, val sourceBookTitle: String, val sourceRef: String, val sourceContent: String,
    val targetBookId: Int, val targetBookTitle: String, val targetRef: String, val targetContent: String,
    val connectionType: String
)
