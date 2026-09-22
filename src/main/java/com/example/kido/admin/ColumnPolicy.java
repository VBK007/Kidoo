package com.example.kido.admin;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a column may show, decided from its name and type rather than from a
 * hand-kept list of every table in the schema.
 *
 * <p>A hand-kept list is the wrong shape for this: it is correct exactly until
 * someone adds a column, and the column it will miss is the one that matters. The
 * rules here are the other way round — a column has to look harmless to be shown in
 * full, so a new {@code reset_password_token} is hidden the day it is added and
 * nobody has to remember anything.
 *
 * <p>Three outcomes, in order of severity:
 *
 * <ul>
 *   <li>{@link Handling#SECRET} — never leaves the server, in any mode. A console
 *       has no use for a bcrypt hash or a session token, and every use it could be
 *       put to is one this server should not help with.
 *   <li>{@link Handling#PERSONAL} — masked in the listing, shown in full only when a
 *       single row is opened deliberately. The listing is the thing that ends up on
 *       a screen share with twenty rows of other people's email addresses on it.
 *   <li>{@link Handling#LARGE} — a blob, a large object or a long document, reported
 *       by size in the listing. Not sensitive; just useless in a table cell and
 *       expensive to ship a page of.
 * </ul>
 */
final class ColumnPolicy {

    enum Handling {
        PLAIN,
        PERSONAL,
        SECRET,
        LARGE
    }

    /**
     * Name tokens that make a column a secret. Matched whole, between underscores,
     * so {@code token} hides {@code guest_token_id} while {@code broken_at} — were
     * there such a column — stays visible.
     */
    private static final Set<String> SECRET_TOKENS =
            Set.of("password", "passwd", "secret", "token", "apikey", "credential", "pin");

    /** Same idea, for the columns that describe a person rather than a thing. */
    private static final Set<String> PERSONAL_TOKENS =
            Set.of("email", "ip", "phone", "msisdn", "address");

    /** Two words that only ever appear together, so they are matched as a pair. */
    private static final List<String> PERSONAL_PHRASES = List.of("user_agent", "useragent");

    private ColumnPolicy() {}

    static Handling of(String columnName, int jdbcType, String typeName, int size) {
        String lower = columnName.toLowerCase(Locale.ROOT);

        // A hash is the stored form of something nobody should be handed back, and
        // every hash in this schema is of exactly that: a password, a PIN, a token.
        if (lower.endsWith("_hash") || lower.equals("hash")) {
            return Handling.SECRET;
        }
        List<String> parts = Arrays.asList(lower.split("_"));
        if (parts.stream().anyMatch(SECRET_TOKENS::contains)) {
            return Handling.SECRET;
        }
        // Only text can be masked into something still worth reading. A name match
        // on a column that cannot hold an address is a false positive —
        // `weekly_email_summary` is a boolean preference, and "t***" would hide a
        // harmless setting while protecting nobody.
        boolean textual = isTextual(jdbcType);
        if (textual
                && (PERSONAL_PHRASES.contains(lower)
                        || parts.stream().anyMatch(PERSONAL_TOKENS::contains))) {
            return Handling.PERSONAL;
        }
        if (isLarge(jdbcType, typeName, size)) {
            return Handling.LARGE;
        }
        return Handling.PLAIN;
    }

    /**
     * Whether a value is too big to belong in a table cell.
     *
     * <p>{@code oid} earns its own case: a {@code @Lob String} is a large object on
     * PostgreSQL, so the column holds a pointer, and printing it would put a number
     * with no meaning where a document ought to be.
     */
    private static boolean isLarge(int jdbcType, String typeName, int size) {
        if ("oid".equalsIgnoreCase(typeName)) {
            return true;
        }
        // A poster layout is a whole design in one cell. It is a document by any
        // other name, and both dialects agree on what it is called.
        if ("json".equalsIgnoreCase(typeName) || "jsonb".equalsIgnoreCase(typeName)) {
            return true;
        }
        return switch (jdbcType) {
            case Types.BLOB, Types.CLOB, Types.NCLOB, Types.LONGVARBINARY, Types.LONGVARCHAR,
                    Types.LONGNVARCHAR, Types.BINARY, Types.VARBINARY, Types.SQLXML -> true;
            // A varchar wide enough to hold a document usually does. The cut-off is
            // the point past which no cell renders it usefully anyway.
            case Types.VARCHAR, Types.NVARCHAR, Types.CHAR -> size > 4000;
            default -> false;
        };
    }

    private static boolean isTextual(int jdbcType) {
        return switch (jdbcType) {
            case Types.VARCHAR, Types.NVARCHAR, Types.CHAR, Types.NCHAR, Types.LONGVARCHAR,
                    Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> true;
            default -> false;
        };
    }

    /**
     * Whether a column can be searched with a LIKE.
     *
     * <p>Text only. Casting a timestamp or a numeric to a string to match a substring
     * of it finds nothing anyone meant to look for, and on a large object it is a
     * read of the whole thing per row.
     */
    static boolean isSearchable(int jdbcType, Handling handling) {
        if (handling == Handling.SECRET || handling == Handling.LARGE) {
            return false;
        }
        return switch (jdbcType) {
            case Types.VARCHAR, Types.NVARCHAR, Types.CHAR, Types.NCHAR -> true;
            default -> false;
        };
    }

    /**
     * Enough of a value to recognise a row by, and not enough to collect.
     *
     * <p>An email keeps its domain: which provider an account is on is a thing an
     * operator legitimately reads at a glance, and it identifies nobody on its own.
     * An address keeps the half that says where, never the half that says whose.
     */
    static String mask(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        int at = raw.indexOf('@');
        if (at > 0) {
            return raw.charAt(0) + "***" + raw.substring(at);
        }
        if (raw.indexOf('.') > 0 && raw.chars().filter(c -> c == '.').count() == 3) {
            // Dotted quad: the network half locates it, the host half names a device.
            String[] octets = raw.split("\\.");
            return octets[0] + "." + octets[1] + ".***.***";
        }
        if (raw.contains(":")) {
            String[] groups = raw.split(":");
            return groups.length > 2 ? groups[0] + ":" + groups[1] + ":***" : "***";
        }
        return raw.length() <= 2 ? "***" : raw.charAt(0) + "***";
    }
}
