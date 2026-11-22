package com.example.kido.mymirror;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which OTT catalogue the mirror answers from — Netflix ({@code nf}), Prime Video ({@code pv})
 * or Hotstar ({@code dp}, the upstream's Disney+ Hotstar token; its CDN namespace is {@code hs}).
 *
 * <p>The caller's choice travels as an {@code ott} cookie entry, and upstream <em>does</em> switch
 * on it for the shared {@code /mobile/home?app=1} page: {@code nf} serves the Netflix catalogue and
 * {@code dp} the Hotstar one from that same path (observed on device 2026-11-21 — the real app
 * flips between them by POSTing {@code ott=<value>} to {@code /mobile/setting.php} then reloading
 * home). Prime Video alone has its own endpoints ({@code /pv/homepage.php}, {@code /pv/post.php}),
 * which the repositories route to by {@link #isPrime}. The backend holds one global session shared
 * by every caller, so the choice cannot live in that session — two people browsing different
 * catalogues would trample each other. Instead the caller names it per request in the
 * {@code X-Mirror-Ott} header, and it is folded into the cookie sent upstream for that request only.
 */
public final class MirrorOtt {

    public static final String HEADER = "X-Mirror-Ott";
    public static final Set<String> VALUES = Set.of("nf", "pv", "dp");

    public static final String PRIME = "pv";

    // Prime Video ids are 26 upper-case alphanumerics starting with 0 (0RTZ57DQ6PBHH29UN5JS7U7CW4),
    // titles and episodes alike; Netflix ids are all digits. Seen 2026-11-21.
    private static final Pattern PRIME_ID = Pattern.compile("0[A-Z0-9]{25}");

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
