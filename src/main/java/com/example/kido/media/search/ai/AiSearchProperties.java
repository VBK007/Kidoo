package com.example.kido.media.search.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Whether the search box may ask a model, and how.
 *
 * <p>Off by default, and that is the important part. This server runs in somebody's
 * house; it must start, index a disk and answer every search with no account anywhere
 * and no internet. The model is an improvement to one endpoint, never a dependency of
 * it — the same discipline as {@code app.firebase.credentials}, which leaves Google
 * sign-in answering 501 rather than refusing to boot.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.media.ai")
public class AiSearchProperties {

    /**
     * Master switch. With this off nothing in this package is constructed and search is
     * exactly what it was before the model existed.
     */
    private boolean enabled = false;

    /**
     * Read from the environment like every other credential. Blank means off, whatever
     * {@link #enabled} says — a switch turned on without a key is a misconfiguration,
     * and failing every search would be a poor way to report it.
     */
    private String apiKey = "";

    /** Anthropic's most capable model at this tier; see the note on effort below. */
    private String model = "claude-opus-5";

    /**
     * How hard to think about it.
     *
     * <p>{@code low} because this is a short translation into a schema, not a problem —
     * and because a search box is somewhere latency is felt directly. Raise it if a
     * library's vocabulary turns out to be ambiguous enough to need it; that is a
     * measurement, not a guess to make here.
     */
    private String effort = "low";

    /**
     * A search box nobody is going to wait longer than this for.
     *
     * <p>On expiry the deterministic parse is what gets served, so the ceiling is a
     * quality decision rather than a correctness one.
     */
    private Duration timeout = Duration.ofSeconds(8);

    /**
     * How many translations to remember.
     *
     * <p>Households ask the same question repeatedly, and the answer only changes when
     * the library's vocabulary does. Small because the value is in the repeats, not in
     * the long tail.
     */
    private int cacheSize = 200;


    /**
     * The assistant — a separate switch from the search fallback, sharing the key.
     *
     * <p>Separate because they are different bargains. The search fallback is one short
     * call on a sentence the rules could not read; the assistant is a loop that may call
     * tools several times per question. Somebody may reasonably want the first and not
     * the second, and folding them into one flag would take that choice away.
     */
    private boolean assistantEnabled = false;

    /** A loop of lookups, so it is allowed longer than the single-shot translation. */
    private Duration assistantTimeout = Duration.ofSeconds(60);

    /** True when the assistant could actually answer. */
    public boolean isAssistantUsable() {
        return assistantEnabled && apiKey != null && !apiKey.isBlank();
    }
    /** True when a call could actually be made. */
    public boolean isUsable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
