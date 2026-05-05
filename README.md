# Zayit MCP Server

An MCP (Model Context Protocol) server that exposes the [Zayit](https://github.com/kdroidFilter/Zayit) Jewish texts library to AI assistants. Wraps Zayit's SQLite database and Lucene search indices — no network required, fully offline.

## Prerequisites

- **Zayit** installed on the same machine (provides the database and Lucene indices)
- **Java 21+** — required by Lucene 10.x

### Installing Java 21

| Platform | Command |
|----------|---------|
| Windows | `winget install EclipseAdoptium.Temurin.21.JDK` |
| macOS | `brew install --cask temurin@21` |
| Ubuntu/Debian | `sudo apt install temurin-21-jdk` (add [Adoptium repo](https://adoptium.net/installation/linux/) first) |
| Fedora/RHEL | `sudo dnf install temurin-21-jdk` |

## Building

The Gradle wrapper is included — no separate Gradle installation needed.

```bash
# Linux / macOS
chmod +x gradlew
./gradlew shadowJar

# Windows
gradlew.bat shadowJar
```

Output: `build/libs/zayit-mcp.jar` (~35 MB, self-contained)

## Running

### stdio mode (for Claude Desktop / MCP clients)

```bash
# Linux / macOS
chmod +x run-stdio.sh
./run-stdio.sh

# Windows
run-stdio.bat
```

### HTTP / SSE mode (for testing or remote access)

```bash
# Linux / macOS
./run-http.sh 3001

# Windows
run-http.bat 3001
```

The MCP endpoint is at `http://localhost:3001/mcp`.

### Custom data directory

If Zayit stores its data somewhere non-standard, set `ZAYIT_DATA_DIR`:

```bash
ZAYIT_DATA_DIR=/path/to/databases ./run-stdio.sh
```

## Data paths (auto-detected per OS)

| Platform | Path |
|----------|------|
| Windows | `%APPDATA%\io.github.kdroidfilter.seforimapp\databases` |
| macOS | `~/Library/Application Support/io.github.kdroidfilter.seforimapp/databases` |
| Linux | `~/.local/share/io.github.kdroidfilter.seforimapp/databases` (XDG) |

## Claude Desktop configuration

### Windows

`%APPDATA%\Claude\claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "zayit": {
      "command": "C:\\path\\to\\zayit-mcp\\run-stdio.bat"
    }
  }
}
```

### macOS / Linux

`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS)  
`~/.config/Claude/claude_desktop_config.json` (Linux):

```json
{
  "mcpServers": {
    "zayit": {
      "command": "/path/to/zayit-mcp/run-stdio.sh"
    }
  }
}
```

## Tools

### `search_books`
Search for books by title using SQLite LIKE.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `query` | string | required | Hebrew or partial title |
| `limit` | integer | 20 | Max results |

**Returns:** `id | title | category | lines=N [primary]`

**Example:** `query="בראשית"` → בראשית, בראשית רבה, אגדת בראשית …

---

### `search_ref`
Fast prefix lookup via the Lucene reference index.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `query` | string | required | Title prefix (Hebrew) |
| `limit` | integer | 10 | Max results |

**Returns:** `book_id=N | title`

---

### `get_text`
Retrieve lines from a book by numeric ID with pagination.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `book_id` | integer | required | From `search_books` or `search_ref` |
| `offset` | integer | 0 | Line offset |
| `limit` | integer | 50 | Lines to return (max 200) |

---

### `get_text_by_ref`
Retrieve text by Hebrew reference string — the ergonomic alternative to `get_text`.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `ref` | string | required | e.g. `בראשית א, א` or `ברכות ב` |
| `context` | integer | 2 | Lines of context before/after each hit |
| `limit` | integer | 10 | Max hits |

**Returns:** Matched line (marked `►`) with surrounding context, across all books containing that reference.

**Example:** `ref="בראשית א, א"` finds Genesis 1:1 in the base text plus every commentary quoting it.

---

### `get_book_toc`
Get the hierarchical chapter/section table of contents for a book.

| Parameter | Type | Description |
|-----------|------|-------------|
| `book_id` | integer | Book ID |

**Returns:** Indented TOC with `line_id` pointers per section. Use the `line_id` values as `offset` in `get_text`.

**Example output:**
```
▸ בראשית → line_id=1
  • פרק א → line_id=2
  • פרק ב → line_id=34
  ...
```

---

### `get_links`
Cross-references for a book or specific line, from the `link` table.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `book_id` | integer | required | Book to find links for |
| `line_id` | integer | — | Restrict to a specific line |
| `connection_type` | string | — | `Commentary`, `Targum`, `Reference`, `Source`, `Other` |
| `limit` | integer | 30 | Max links |

**Returns:** Source and target refs with 120-char text snippets and connection type.

---

### `text_search`
Full-text search across all ~6,000 books using Lucene. Nikud and teamim are stripped automatically; final letters (ך→כ, ם→מ, ן→נ, ף→פ, ץ→צ) are normalized.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `query` | string | required | Lucene query (see syntax below) |
| `book_id` | integer | — | Restrict to one book |
| `limit` | integer | 20 | Max results |
| `offset` | integer | 0 | Pagination offset |

**Lucene query syntax:**

| Syntax | Example | Meaning |
|--------|---------|---------|
| Terms (AND by default) | `תורה משה` | both words must appear |
| Explicit OR | `אברהם OR יצחק` | either word |
| Exclude | `תורה -משנה` | תורה without משנה |
| Phrase | `"ואהבת לרעך כמוך"` | exact phrase |
| Prefix wildcard | `ברא*` | ברא, בראשית, בראה … |
| Single-char wildcard | `ב?אשית` | |
| Fuzzy | `שלום~` | similar spelling |
| Proximity | `"תורה משה"~5` | within 5 words of each other |
| Grouping | `(אברהם OR יצחק) שרה` | |

---

## Architecture

```
AI client
    │  MCP JSON-RPC (stdio or HTTP/SSE)
    ▼
zayit-mcp.jar
    ├── SQLite (seforim.db)          → search_books, get_text, get_text_by_ref,
    │                                   get_book_toc, get_links
    └── Lucene (seforim.db.lucene,   → text_search, search_ref
                seforim.db.lookup.lucene)
```

**Stack:** Kotlin 2.3 · MCP SDK 0.12.0 · Lucene 10.3.2 · SQLite JDBC 3.49 · Ktor 3.1 (HTTP mode)
