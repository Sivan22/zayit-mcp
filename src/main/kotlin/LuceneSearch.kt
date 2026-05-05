import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.IntPoint
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.queryparser.classic.QueryParserBase
import org.apache.lucene.search.*
import org.apache.lucene.store.FSDirectory
import java.nio.file.Paths

class LuceneSearch(
    ftsIndexPath: String,
    lookupIndexPath: String
) : AutoCloseable {

    private val ftsDir = FSDirectory.open(Paths.get(ftsIndexPath))
    private val lookupDir = FSDirectory.open(Paths.get(lookupIndexPath))
    private val ftsReader = DirectoryReader.open(ftsDir)
    private val lookupReader = DirectoryReader.open(lookupDir)
    private val ftsSearcher = IndexSearcher(ftsReader)
    private val lookupSearcher = IndexSearcher(lookupReader)
    private val analyzer = StandardAnalyzer()

    fun searchRef(query: String, limit: Int = 10): List<RefResult> {
        val normalized = normalizeHebrew(query.lowercase())
        val q = PrefixQuery(Term("title", normalized))
        val hits = lookupSearcher.search(q, limit)
        val storedFields = lookupSearcher.storedFields()
        return hits.scoreDocs.map { sd ->
            val doc = storedFields.document(sd.doc)
            RefResult(
                bookId = doc.get("book_id")?.toIntOrNull() ?: 0,
                title = doc.get("title") ?: ""
            )
        }
    }

    fun textSearch(
        query: String,
        bookId: Int? = null,
        limit: Int = 20,
        offset: Int = 0
    ): List<SearchResult> {
        // Pre-normalize Hebrew: strip nikud/teamim, normalize final letters
        // This is safe with Lucene query syntax because those are ASCII characters
        val normalized = normalizeHebrew(query)
        if (normalized.isBlank()) return emptyList()

        val parser = QueryParser("text", analyzer).also {
            // Default: all terms must appear (AND semantics) unless user specifies OR
            it.defaultOperator = QueryParser.Operator.AND
            it.allowLeadingWildcard = true
        }

        val parsedQuery = try {
            parser.parse(normalized)
        } catch (e: Exception) {
            // Fall back to simple term search on parse failure
            val terms = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (terms.isEmpty()) return emptyList()
            BooleanQuery.Builder().apply {
                terms.forEach { add(TermQuery(Term("text", it)), BooleanClause.Occur.MUST) }
            }.build()
        }

        val finalQuery = if (bookId != null) {
            BooleanQuery.Builder()
                .add(parsedQuery, BooleanClause.Occur.MUST)
                .add(IntPoint.newExactQuery("book_id", bookId), BooleanClause.Occur.FILTER)
                .build()
        } else {
            parsedQuery
        }

        val totalNeeded = offset + limit
        val hits = ftsSearcher.search(finalQuery, totalNeeded)
        val storedFields = ftsSearcher.storedFields()

        return hits.scoreDocs.drop(offset).map { sd ->
            val doc = storedFields.document(sd.doc)
            SearchResult(
                bookId = doc.get("book_id")?.toIntOrNull() ?: 0,
                bookTitle = doc.get("book_title") ?: "",
                lineId = doc.get("line_id")?.toIntOrNull() ?: 0,
                text = doc.get("text_raw") ?: "",
                score = sd.score
            )
        }
    }

    override fun close() {
        analyzer.close()
        ftsReader.close()
        lookupReader.close()
        ftsDir.close()
        lookupDir.close()
    }
}

data class RefResult(val bookId: Int, val title: String)

data class SearchResult(
    val bookId: Int,
    val bookTitle: String,
    val lineId: Int,
    val text: String,
    val score: Float
)

// Mirrors Zayit's HebrewTextUtils: strip nikud/teamim, replace maqaf, normalize final letters.
// Safe to apply to the full Lucene query string since Lucene syntax uses only ASCII characters.
fun normalizeHebrew(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text) {
        when {
            c == '־' -> sb.append(' ')            // maqaf → space
            c.code in 0x0591..0x05C7 -> Unit            // strip Hebrew diacritics & teamim
            c == 'ך' -> sb.append('כ')
            c == 'ם' -> sb.append('מ')
            c == 'ן' -> sb.append('נ')
            c == 'ף' -> sb.append('פ')
            c == 'ץ' -> sb.append('צ')
            else -> sb.append(c)
        }
    }
    return sb.toString().trim()
}
