package com.example.kido.admin;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.admin.ColumnPolicy.Handling;
import com.example.kido.admin.dto.BrowserDtos.ColumnDto;
import com.example.kido.admin.dto.BrowserDtos.RowDto;
import com.example.kido.admin.dto.BrowserDtos.RowPageDto;
import com.example.kido.admin.dto.BrowserDtos.TableDto;
import com.example.kido.common.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * A read-only window onto the schema: every table, paged, searchable, sortable.
 *
 * <p><b>Read-only by construction, not by promise.</b> There is no code path here
 * that writes: every statement is assembled in this class from {@code SELECT}, and
 * no caller-supplied text ever reaches one as SQL. A table name, a sort column and
 * a direction are matched against the schema this server read at startup and the
 * <em>schema's own</em> spelling is what gets quoted into the statement, so a name
 * that is not a real column cannot be one. The search term is a bound parameter.
 *
 * <p>The three things it will not show, whatever is asked of it, are in
 * {@link ColumnPolicy}: secrets never leave the server, personal columns are masked
 * until a single row is opened, and large objects are described rather than sent.
 *
 * <p>The schema is read once and cached. Flyway runs before this is first touched
 * and the schema does not change under a running server, so re-reading it per
 * request would buy nothing; a migration means a restart, which is already true of
 * everything else that maps to these tables.
 */
@Slf4j
@Service
public class DatabaseBrowser {

    /** A page cap. The browser is for reading rows, not for exporting a table. */
    private static final int MAX_PAGE_SIZE = 200;

    private static final int DEFAULT_PAGE_SIZE = 25;

    /** How much of a large value a single-row view shows before it is cut. */
    private static final int LARGE_PREVIEW_CHARS = 4000;

    /** How much of an ordinary text value a listing cell shows. */
    private static final int CELL_CHARS = 300;

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    /** Lower-cased table name to its description. Built once, then read-only. */
    private volatile Map<String, Table> catalog;

    /** The quote character this database wants around an identifier. */
    private volatile String quote = "\"";

    public DatabaseBrowser(JdbcTemplate jdbc,
                           @Value("${app.admin.db-browser.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    /**
     * A column as the database spells it, which is not how this API reports it.
     *
     * <p>{@link #name} is the real identifier and the only spelling that goes into
     * a statement. Everything a client sees is lower-cased by {@link #reported()},
     * because the two databases disagree: an unquoted {@code create table users} is
     * {@code USERS} on H2 and {@code users} on PostgreSQL, and a console — or any
     * client keying on a column name — should not change shape when the deployment
     * does.
     */
    record Column(String name, String typeName, int jdbcType, boolean nullable,
                  boolean primaryKey, Handling handling, boolean searchable) {

        String reported() {
            return name.toLowerCase(Locale.ROOT);
        }

        ColumnDto toDto() {
            return new ColumnDto(reported(), typeName, nullable, primaryKey, handling.name());
        }
    }

    record Table(String name, List<Column> columns, List<String> primaryKey) {

        String reported() {
            return name.toLowerCase(Locale.ROOT);
        }

        List<String> reportedPrimaryKey() {
            return primaryKey.stream().map(key -> key.toLowerCase(Locale.ROOT)).toList();
        }
    }

    // --- catalog ---

    /** Every table, alphabetically, each with its live row count. */
    @Transactional(readOnly = true)
    public List<TableDto> tables() {
        return catalog().values().stream()
                .map(table -> new TableDto(
                        table.reported(),
                        count(table, null),
                        table.columns().size(),
                        table.reportedPrimaryKey()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ColumnDto> columns(String tableName) {
        return table(tableName).columns().stream().map(Column::toDto).toList();
    }

    // --- rows ---

    /**
     * One page of a table.
     *
     * <p>Ordered by the primary key when nothing else is asked for. An unordered
     * page is not a page: without an ORDER BY the database may return the same row
     * twice across two requests and never return another, which for someone paging
     * through looking for one record is a bug that looks like missing data.
     */
    @Transactional(readOnly = true)
    public RowPageDto page(String tableName, int page, int size, String sort, String direction,
                           String query) {
        Table table = table(tableName);
        int pageNumber = Math.max(0, page);
        int pageSize = Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE);
        Column sortColumn = sortColumn(table, sort);
        boolean descending = "desc".equalsIgnoreCase(direction);
        String search = query == null || query.isBlank() ? null : query.trim();

        // Secrets are not selected at all, so they cannot be logged, cached or
        // leaked by a later mistake in this file; large values are not selected
        // either, because a page of them is a page of megabytes nobody can read.
        List<Column> shown = table.columns().stream()
                .filter(column -> column.handling() != Handling.SECRET)
                .filter(column -> column.handling() != Handling.LARGE)
                .toList();

        long total = count(table, search);
        List<Map<String, Object>> rows = List.of();
        if (total > 0 && pageNumber * (long) pageSize < total) {
            StringBuilder sql = new StringBuilder("select ")
                    .append(selectList(shown))
                    .append(" from ").append(quoted(table.name()));
            List<Object> params = new ArrayList<>();
            appendSearch(sql, params, table, search);
            sql.append(" order by ").append(quoted(sortColumn.name()))
                    .append(descending ? " desc" : " asc")
                    .append(" limit ? offset ?");
            params.add(pageSize);
            params.add(pageNumber * (long) pageSize);

            Set<String> selected = namesOf(shown);
            rows = jdbc.query(sql.toString(),
                    (resultSet, index) -> readRow(resultSet, table.columns(), selected, false),
                    params.toArray());
        }

        return new RowPageDto(
                table.reported(),
                table.columns().stream().map(Column::toDto).toList(),
                rows,
                pageNumber,
                pageSize,
                total,
                (int) Math.ceil(total / (double) pageSize),
                sortColumn.reported(),
                descending ? "desc" : "asc",
                search,
                true);
    }

    /**
     * One row by its primary key, with personal columns unmasked — the "reveal"
     * behind a click, deliberate and one record at a time.
     *
     * <p>Only tables with a single-column primary key can be opened this way. A join
     * table keyed on a pair has no single value to address a row by, and inventing
     * one (an offset, say) would address a different row the moment anything is
     * inserted.
     */
    @Transactional(readOnly = true)
    public RowDto row(String tableName, String id) {
        Table table = table(tableName);
        if (table.primaryKey().size() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, table.primaryKey().isEmpty()
                    ? "Table '" + table.name() + "' has no primary key, so a single row cannot be addressed"
                    : "Table '" + table.name() + "' has a composite primary key ("
                            + String.join(", ", table.primaryKey()) + "), which this view cannot address");
        }
        String key = table.primaryKey().getFirst();
        Column keyColumn = table.columns().stream()
                .filter(column -> column.name().equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("primary key column missing from catalog"));

        // Large values are selected here and nowhere else: this is one row, opened
        // on purpose, and a document is often the reason for opening it.
        List<Column> shown = table.columns().stream()
                .filter(column -> column.handling() != Handling.SECRET)
                .toList();

        String sql = "select " + selectList(shown) + " from " + quoted(table.name())
                + " where " + castToText(keyColumn) + " = ?";
        List<Map<String, Object>> found =
                jdbc.query(sql,
                        (resultSet, index) -> readRow(resultSet, table.columns(), namesOf(shown), true),
                        id);

        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "No row in '" + table.name() + "' with " + key + " = " + id);
        }
        return new RowDto(
                table.reported(),
                table.columns().stream().map(Column::toDto).toList(),
                found.getFirst(),
                false);
    }

    // --- statement building ---
    //
    // Every identifier below comes from the catalog this server read out of the
    // database itself, never from the request. A request only ever chooses between
    // names that already exist; anything else is refused before a statement exists.

    private String selectList(List<Column> columns) {
        return columns.stream().map(column -> quoted(column.name()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("*");
    }

    private void appendSearch(StringBuilder sql, List<Object> params, Table table, String search) {
        if (search == null) {
            return;
        }
        List<Column> searchable = table.columns().stream().filter(Column::searchable).toList();
        if (searchable.isEmpty()) {
            return;
        }
        sql.append(" where (");
        for (int index = 0; index < searchable.size(); index++) {
            if (index > 0) {
                sql.append(" or ");
            }
            sql.append("lower(").append(quoted(searchable.get(index).name())).append(") like ?");
            params.add("%" + search.toLowerCase(Locale.ROOT) + "%");
        }
        sql.append(")");
    }

    private long count(Table table, String search) {
        StringBuilder sql = new StringBuilder("select count(*) from ").append(quoted(table.name()));
        List<Object> params = new ArrayList<>();
        appendSearch(sql, params, table, search);
        Long total = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return total == null ? 0 : total;
    }

    /**
     * A key is compared as text so one path serves a uuid, a varchar and a bigint
     * alike. The cast is on the column, which costs an index on a large table — the
     * tables here are keyed on generated string uuids, so in practice it costs
     * nothing, and correctness across three key types is worth more than a plan.
     */
    private String castToText(Column keyColumn) {
        return switch (keyColumn.jdbcType()) {
            case java.sql.Types.VARCHAR, java.sql.Types.NVARCHAR, java.sql.Types.CHAR,
                    java.sql.Types.NCHAR -> quoted(keyColumn.name());
            default -> "cast(" + quoted(keyColumn.name()) + " as varchar(255))";
        };
    }

    private String quoted(String identifier) {
        // Doubling an embedded quote is the SQL standard escape. No identifier in
        // this schema contains one; the escape is here so that the statement stays
        // well-formed rather than clever if one ever does.
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    // --- values ---

    /**
     * A row carries a key for every column of the table, including the ones this
     * request did not select — those come back null.
     *
     * <p>A grid renders columns against rows, so a key that is simply absent makes
     * the cell under "password_hash" depend on which row is being drawn. Present and
     * null, beside a column that says {@code SECRET}, says the one true thing: there
     * is a column here and you are not being given it.
     *
     * @param all      every column of the table, in schema order
     * @param selected the ones actually in this result set
     */
    private Map<String, Object> readRow(ResultSet resultSet, List<Column> all,
                                        Set<String> selected, boolean reveal) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Column column : all) {
            row.put(column.reported(),
                    selected.contains(column.name()) ? value(resultSet, column, reveal) : null);
        }
        return row;
    }

    private static Set<String> namesOf(List<Column> columns) {
        return columns.stream().map(Column::name).collect(java.util.stream.Collectors.toSet());
    }

    private Object value(ResultSet resultSet, Column column, boolean reveal) throws SQLException {
        if (column.handling() == Handling.LARGE) {
            return large(resultSet, column, reveal);
        }
        Object raw = resultSet.getObject(column.name());
        if (raw == null || resultSet.wasNull()) {
            return null;
        }
        Object simple = simplify(raw);
        if (column.handling() == Handling.PERSONAL && !reveal) {
            return ColumnPolicy.mask(String.valueOf(simple));
        }
        if (simple instanceof String text && text.length() > CELL_CHARS) {
            return text.substring(0, CELL_CHARS) + "… (" + text.length() + " chars)";
        }
        return simple;
    }

    /**
     * A large column, described rather than shipped.
     *
     * <p>In a listing it is not even selected, so this only runs for a single row,
     * where the value is usually the reason the row was opened — a poster layout, a
     * content body. It is still cut: a console is not a file download.
     */
    private Object large(ResultSet resultSet, Column column, boolean reveal) throws SQLException {
        if (!reveal) {
            return null;
        }
        Object raw;
        try {
            raw = resultSet.getObject(column.name());
        } catch (SQLException failure) {
            // A large object read can fail on its own terms (a missing oid, a closed
            // stream). That is worth reporting in the cell, not worth failing the row.
            log.debug("Could not read large column {}: {}", column.name(), failure.getMessage());
            return "<unreadable>";
        }
        if (raw == null) {
            return null;
        }
        if ("oid".equalsIgnoreCase(column.typeName())) {
            return "<large object #" + raw + ">";
        }
        // A JSON column comes back as bytes on H2 and as a string on PostgreSQL.
        // It is a document either way, and a document is readable — so it is
        // decoded rather than reported as a byte count like a real blob.
        String text;
        if (raw instanceof byte[] blob) {
            text = isJson(column) ? new String(blob, java.nio.charset.StandardCharsets.UTF_8)
                    : "<" + blob.length + " bytes>";
        } else {
            text = String.valueOf(simplify(raw));
        }
        return text.length() > LARGE_PREVIEW_CHARS
                ? text.substring(0, LARGE_PREVIEW_CHARS) + "… (" + text.length() + " chars)"
                : text;
    }

    private static boolean isJson(Column column) {
        return "json".equalsIgnoreCase(column.typeName()) || "jsonb".equalsIgnoreCase(column.typeName());
    }

    /** JDBC types Jackson would render badly, turned into the obvious thing. */
    private static Object simplify(Object raw) {
        if (raw instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (raw instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (raw instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (raw instanceof Instant || raw instanceof LocalDate || raw instanceof LocalDateTime) {
            return raw.toString();
        }
        if (raw instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes>";
        }
        if (raw instanceof java.sql.Array || raw instanceof java.sql.Struct) {
            return String.valueOf(raw);
        }
        return raw;
    }

    // --- schema ---

    private Table table(String name) {
        Table table = catalog().get(name.toLowerCase(Locale.ROOT));
        if (table == null) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "No table named '" + name + "'. Ask GET /api/admin/db/tables for the list.");
        }
        return table;
    }

    private Column sortColumn(Table table, String requested) {
        if (requested != null && !requested.isBlank()) {
            return table.columns().stream()
                    .filter(column -> column.name().equalsIgnoreCase(requested.trim()))
                    .filter(column -> column.handling() != Handling.SECRET)
                    .filter(column -> column.handling() != Handling.LARGE)
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                            "'" + requested + "' is not a column of '" + table.name()
                                    + "' that can be sorted on"));
        }
        if (!table.primaryKey().isEmpty()) {
            String key = table.primaryKey().getFirst();
            return table.columns().stream()
                    .filter(column -> column.name().equalsIgnoreCase(key))
                    .findFirst()
                    .orElse(table.columns().getFirst());
        }
        return table.columns().getFirst();
    }

    private Map<String, Table> catalog() {
        if (!enabled) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "The database browser is switched off (app.admin.db-browser.enabled)");
        }
        Map<String, Table> current = catalog;
        if (current == null) {
            synchronized (this) {
                current = catalog;
                if (current == null) {
                    current = readSchema();
                    catalog = current;
                }
            }
        }
        return current;
    }

    /**
     * Reads the schema through JDBC metadata rather than {@code information_schema}.
     *
     * <p>The two databases this runs on disagree about that view — its casing, its
     * columns, what a "table" is in it — and the driver's own metadata is the
     * portable answer to exactly this question.
     */
    private Map<String, Table> readSchema() {
        Map<String, Table> found = new TreeMap<>();
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            DatabaseMetaData meta = connection.getMetaData();
            quote = blankToDefault(meta.getIdentifierQuoteString());
            String catalogName = connection.getCatalog();
            String schema = connection.getSchema();

            List<String> names = new ArrayList<>();
            try (ResultSet tables = meta.getTables(catalogName, schema, "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    names.add(tables.getString("TABLE_NAME"));
                }
            }
            for (String name : names) {
                Set<String> keys = primaryKeys(meta, catalogName, schema, name);
                List<Column> columns = columns(meta, catalogName, schema, name, keys);
                if (!columns.isEmpty()) {
                    found.put(name.toLowerCase(Locale.ROOT),
                            new Table(name, columns, List.copyOf(keys)));
                }
            }
            return null;
        });
        log.info("Database browser catalogued {} table(s)", found.size());
        return Collections.unmodifiableMap(found);
    }

    private static Set<String> primaryKeys(DatabaseMetaData meta, String catalog, String schema,
                                           String table) throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    private static List<Column> columns(DatabaseMetaData meta, String catalog, String schema,
                                        String table, Set<String> keys) throws SQLException {
        List<Column> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                int jdbcType = rs.getInt("DATA_TYPE");
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                Handling handling = ColumnPolicy.of(name, jdbcType, typeName, size);
                columns.add(new Column(
                        name,
                        typeName,
                        jdbcType,
                        "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                        keys.contains(name),
                        handling,
                        ColumnPolicy.isSearchable(jdbcType, handling)));
            }
        }
        return columns;
    }

    private static String blankToDefault(String identifierQuote) {
        // The JDBC contract says a driver with no quoting returns a single space.
        return identifierQuote == null || identifierQuote.isBlank() ? "\"" : identifierQuote;
    }
}
