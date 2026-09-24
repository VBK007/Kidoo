package com.example.kido.admin.dto;

import java.util.List;
import java.util.Map;

/**
 * Read models for the database browser.
 *
 * <p>Rows are maps rather than typed records, because the whole point of this
 * surface is that it does not know what is in the database — it reads the schema
 * and reports what it finds. The typed thing is the <em>column</em>: every value a
 * client receives arrives beside a description of what it is and how much of it it
 * is being given.
 */
public final class BrowserDtos {

    private BrowserDtos() {}

    /**
     * One column, and what this API will do with it.
     *
     * @param handling {@code PLAIN} shown as stored · {@code PERSONAL} masked in a
     *                 listing and whole in a single row · {@code SECRET} never sent
     *                 at all · {@code LARGE} reported by size
     * @param type     the database's own type name, which is what an operator
     *                 reading a schema expects to see
     * @param searchable whether this column has text to match a sub-string against —
     *                 what the free-text search reads, and the only kind of column a
     *                 {@code contains} filter means anything on. Reported so a client
     *                 can offer the comparisons that will work rather than discover
     *                 the others by being refused.
     */
    public record ColumnDto(
            String name,
            String type,
            boolean nullable,
            boolean primaryKey,
            String handling,
            boolean searchable) {}

    /** One table in the schema, with enough about it to render a picker. */
    public record TableDto(
            String name,
            long rows,
            int columnCount,
            List<String> primaryKey) {}

    /**
     * A page of rows.
     *
     * @param masked true while personal columns are still masked, which is always
     *               true here — a listing never unmasks. The flag exists so the
     *               client can say so on screen rather than imply the data is whole.
     */
    public record RowPageDto(
            String table,
            List<ColumnDto> columns,
            List<Map<String, Object>> rows,
            int page,
            int size,
            long total,
            int totalPages,
            String sort,
            String direction,
            String query,
            String filterColumn,
            String filterOp,
            String filterValue,
            boolean masked) {}

    /**
     * One row, opened deliberately, with its personal columns whole.
     *
     * <p>Secrets stay out of this too. "Reveal" means the email address behind the
     * mask, never the password hash behind the dots.
     */
    public record RowDto(
            String table,
            List<ColumnDto> columns,
            Map<String, Object> values,
            boolean masked) {}
}
