package com.example.kido.mymirror;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which OTT catalogue the mirror answers from — Netflix ({@code nf}), Prime Video ({@code pv})
 * or Hotstar ({@code dp}, the upstream's Disney+ Hotstar token; its CDN namespace is {@code hs}).
 *
 * <p>The caller's choice travels as an {@code ott} cookie entry. The real app flips catalogue by
 * POSTing {@code ott=<value>} to {@code /mobile/setting.php} (Hotstar: {@code /mobile/hs/setting.php})
 * then loading that catalogue's browse paths. The three catalogues do <em>not</em> share one browse
 * namespace:
 * <ul>
 *   <li>{@code nf} (Netflix) rides the shared {@code /mobile/*} paths ({@code search.php},
 *       {@code post.php}, {@code episodes.php}, {@code playlist.php}). Its old {@code /mobile/home?app=1}
 *       landing page was retired upstream (now 404), so the backend composes the Netflix home itself.</li>
 *   <li>{@code pv} (Prime Video) has its own {@code /pv/*} and {@code /mobile/pv/*} endpoints, routed
 *       by {@link #isPrime}.</li>
 *   <li>{@code dp} (Disney+ Hotstar) rides a parallel {@code /mobile/hs/*} namespace
 *       ({@code hs/search.php}, {@code hs/post.php}, {@code hs/episodes.php}, {@code hs/playlist.php},
 *       with masters under {@code /mobile/hs/hls/}), routed by {@link #isHotstar}. It has no home or
 *       trending route of its own, so the backend composes the Hotstar home from seed searches and the
 *       detail {@code suggest[]} graph, exactly as it does for Netflix. Verified live on upstream
 *       2026-11-23.</li>
 * </ul>
 *
 * <p>The backend holds one global session shared by every caller, so the choice cannot live in that
 * session — two people browsing different catalogues would trample each other. Instead the caller
 * names it per request in the {@code X-Mirror-Ott} header, and it is folded into the cookie sent
 * upstream for that request only.
 */
public final class MirrorOtt {

    public static final String HEADER = "X-Mirror-Ott";
    public static final Set<String> VALUES = Set.of("nf", "pv", "dp");

    public static final String PRIME = "pv";
    public static final String HOTSTAR = "dp";

    // Prime Video ids are 26 upper-case alphanumerics starting with 0 (0RTZ57DQ6PBHH29UN5JS7U7CW4),
    // titles and episodes alike; Netflix ids are all digits. Seen 2026-11-21.
    private static final Pattern PRIME_ID = Pattern.compile("0[A-Z0-9]{25}");

    // Hotstar ids are 10-digit all-numeric, beginning with 1 (1271714396 "Avatar: Seven Havens",
    // 1260161168 "Mangalavaaram", 1971001340 "The Italian Job"); Netflix numeric ids run shorter
    // (8-9 digits, e.g. 81xxxxxxx). Seen live on upstream 2026-11-23. Used only as a cross-catalogue
    // safety net, the way PRIME_ID is — Hotstar routing is driven primarily by the {@code ott=dp}
    // cookie, since Netflix and Hotstar otherwise share the same all-digit id space.
    private static final Pattern HOTSTAR_ID = Pattern.compile("1[0-9]{9}");

    private MirrorOtt() {}

    /** The {@code ott} value a cookie carries, or {@code null}. */
    public static String of(String cookie) {
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("ott=")) return p.substring(4).trim().toLowerCase();
        }
        return null;
    }

    /**
     * Whether the mirror's Prime Video catalogue answers for this call. Prime alone serves from its
     * own paths ({@code /pv/post.php}, {@code /mobile/pv/playlist.php}, ...) rather than the shared
     * {@code /mobile/*} ones, so the caller must pick the path; Netflix and Hotstar both ride the
     * shared paths and switch on the {@code ott} cookie. An id decides by its shape, which keeps a
     * Prime title playable even when the request named another catalogue; without an id the cookie's
     * choice decides.
     */
    public static boolean isPrime(String id, String cookie) {
        if (id != null && !id.isBlank()) {
            String v = id.trim();
            if (PRIME_ID.matcher(v).matches()) return true;
            if (v.chars().allMatch(Character::isDigit)) return false;
        }
        return PRIME.equals(of(cookie));
    }

    /**
     * Whether the mirror's Disney+ Hotstar catalogue answers for this call. Hotstar rides its own
     * {@code /mobile/hs/*} paths rather than the shared {@code /mobile/*} ones, so — like Prime — the
     * caller must pick the path. Unlike Prime, a Hotstar id is not distinguishable from a Netflix id
     * by shape alone (both are all-digit), so the decision is driven primarily by the {@code ott=dp}
     * cookie the caller sets; the 10-digit id shape is only a safety net that keeps a Hotstar title
     * routed to {@code hs/} even if a stray call arrives without the {@code dp} cookie. Prime's
     * distinctive ids still win, so a Prime id is never treated as Hotstar.
     */
    public static boolean isHotstar(String id, String cookie) {
        if (isPrime(id, cookie)) return false;
        if (id != null && !id.isBlank() && HOTSTAR_ID.matcher(id.trim()).matches()) return true;
        return HOTSTAR.equals(of(cookie));
    }

    /**
     * {@code cookie} with its {@code ott} entry set to {@code ott}. An absent or unrecognised
     * value leaves the cookie untouched, so the mirror falls back to whatever it defaults to.
     */
    public static String apply(String cookie, String ott) {
        if (cookie == null || ott == null) return cookie;
        String value = ott.trim().toLowerCase();
        if (!VALUES.contains(value)) return cookie;
        StringBuilder out = new StringBuilder();
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.isEmpty() || p.startsWith("ott=")) continue;
            if (!out.isEmpty()) out.append("; ");
            out.append(p);
        }
        if (!out.isEmpty()) out.append("; ");
        return out.append("ott=").append(value).toString();
    }
}
