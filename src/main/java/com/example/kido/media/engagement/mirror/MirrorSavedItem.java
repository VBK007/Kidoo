package com.example.kido.media.engagement.mirror;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One mirror title a profile has put on their list.
 *
 * <p>The watchlist the catalogue never had. A mirror title lives in an external
 * catalogue, not in this server's {@code media_items} graph, so "save this for later"
 * cannot be a flag on a row we own — there is no row. It is its own thing: a
 * {@code (profile, mirrorId)} pair, keyed on profile like a like so a household sharing
 * one login keeps separate lists, with a small snapshot of the title alongside.
 *
 * <p>The snapshot — name, poster, year, which service it came from — is what makes the
 * list renderable on its own. The mirror detail endpoint is an ad-gated round trip per
 * title; a list of twenty would be twenty of them just to draw the posters. So the fields
 * the My List rail needs are copied in at save time and read straight back, exactly as a
 * mirror comment snapshots its author's name. They are a snapshot, not a mirror of the
 * source: if the title's art changes upstream the saved tile keeps the art it was saved
 * with until it is removed and re-added, which for a "things I meant to watch" list is no
 * loss at all.
 *
 * <p>Nothing here is a foreign key. The row stands whether or not the mirror still carries
 * the title, the same as a like or a comment on it does.
 */
@Entity
@Table(name = "mirror_saved_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mirror_saved_profile_item", columnNames = {"profile_id", "mirror_id"}),
        indexes = {
                @Index(name = "idx_mirror_saved_profile", columnList = "profile_id, created_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MirrorSavedItem {

    /** Title names run long with a country and year tacked on; this never bites one. */
    public static final int NAME_MAX = 512;

    /** Poster URLs carry query strings and signed tokens; generous on purpose. */
    public static final int POSTER_MAX = 2048;

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "profile_id", nullable = false)
    private String profileId;

    /** The external mirror title saved — never a {@code media_items} id. */
    @Column(name = "mirror_id", nullable = false, length = MirrorIds.MAX_LENGTH)
    private String mirrorId;

    /** The title's name as it stood when saved, so the list draws without a round trip. */
    @Column(nullable = false, length = NAME_MAX)
    private String name;

    /** Poster art URL, snapshotted. Null renders the gradient placeholder, as elsewhere. */
    @Column(name = "poster_url", length = POSTER_MAX)
    private String posterUrl;

    /** Release year, where the tile carried one. */
    @Column(name = "release_year")
    private Integer year;

    /** Which service it came from — "Netflix", "Hotstar", "Prime" — where known. */
    @Column(length = 64)
    private String ott;

    /** Film or series, as the mirror classified it. A label, not an enum we own. */
    @Column(length = 64)
    private String type;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
