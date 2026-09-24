package com.example.kido.admin;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.admin.dto.BrowserDtos.ColumnDto;
import com.example.kido.admin.dto.BrowserDtos.RowDto;
import com.example.kido.admin.dto.BrowserDtos.RowPageDto;
import com.example.kido.admin.dto.BrowserDtos.TableDto;
import com.example.kido.user.AppUser;

/**
 * The database browser: every table in the schema, read-only, a page at a time.
 *
 * <p>This is the widest surface on the server, so it is worth being plain about
 * what holds it in:
 *
 * <ul>
 *   <li><b>Read-only by construction.</b> {@link DatabaseBrowser} builds every
 *       statement itself, all of them {@code SELECT}. There is no endpoint here
 *       that takes SQL, and none that writes.
 *   <li><b>Owner-gated.</b> A {@code PARENT} account <em>and</em> the
 *       {@code X-Admin-Key}, the same pair the rest of the admin surface needs.
 *   <li><b>Never everything.</b> Password and token hashes are not selected at all;
 *       email addresses and IPs are masked in a listing and shown only when a
 *       single row is opened; large objects are described by size rather than sent.
 *   <li><b>Switchable off.</b> {@code app.admin.db-browser.enabled=false} removes
 *       it entirely, for a deployment that would rather it did not exist.
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/db")
public class DatabaseBrowserController {

    private final DatabaseBrowser browser;
    private final AdminAccess access;

    public DatabaseBrowserController(DatabaseBrowser browser, AdminAccess access) {
        this.browser = browser;
        this.access = access;
    }

    /** Every table, with its row count and primary key. */
    @GetMapping("/tables")
    public List<TableDto> tables(@AuthenticationPrincipal AppUser user,
                                 @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        access.require(user, key);
        return browser.tables();
    }

    /** One table's columns, with what this API will do with each. */
    @GetMapping("/tables/{table}/columns")
    public List<ColumnDto> columns(@AuthenticationPrincipal AppUser user,
                                   @RequestHeader(value = "X-Admin-Key", required = false) String key,
                                   @PathVariable String table) {
        access.require(user, key);
        return browser.columns(table);
    }

    /**
     * A page of rows, ordered by the primary key unless told otherwise.
     *
     * @param q           sub-string match across the table's text columns, case-insensitive
     *                    and bound as a parameter — never concatenated into the statement
     * @param filter      one column to compare, matched against the schema; a name that is
     *                    not a column of this table, or one whose values this API refuses
     *                    to hand out, is a 400 rather than a statement
     * @param filterOp    {@code contains} · {@code eq} · {@code ne} · {@code null} ·
     *                    {@code notnull}; defaults to {@code contains}
     * @param filterValue what to compare against, bound as a parameter like {@code q}.
     *                    Ignored by the two null tests, and an empty one means no filter
     *                    at all rather than a filter matching everything
     */
    @GetMapping("/tables/{table}/rows")
    public RowPageDto rows(@AuthenticationPrincipal AppUser user,
                           @RequestHeader(value = "X-Admin-Key", required = false) String key,
                           @PathVariable String table,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "25") int size,
                           @RequestParam(required = false) String sort,
                           @RequestParam(defaultValue = "asc") String direction,
                           @RequestParam(required = false) String q,
                           @RequestParam(required = false) String filter,
                           @RequestParam(required = false) String filterOp,
                           @RequestParam(required = false) String filterValue) {
        access.require(user, key);
        return browser.page(table, page, size, sort, direction, q,
                new DatabaseBrowser.FilterRequest(filter, filterOp, filterValue));
    }

    /**
     * One row by primary key, with its personal columns unmasked.
     *
     * <p>The "reveal" a listing does not do: one record, asked for by name. Secrets
     * stay out of this one too — revealing an address is not revealing a password.
     */
    @GetMapping("/tables/{table}/rows/{id}")
    public RowDto row(@AuthenticationPrincipal AppUser user,
                      @RequestHeader(value = "X-Admin-Key", required = false) String key,
                      @PathVariable String table,
                      @PathVariable String id) {
        access.require(user, key);
        return browser.row(table, id);
    }
}
