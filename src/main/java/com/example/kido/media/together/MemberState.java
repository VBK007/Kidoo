package com.example.kido.media.together;

/**
 * Where someone is in the join sequence.
 *
 * <p>Persisted rather than inferred so a denial sticks: a guest who was refused can be
 * told so on their next poll instead of silently retrying, and the host does not see
 * the same name reappear in the pending list every two seconds.
 */
public enum MemberState {

    /** Asked to join and is waiting on the host. Holds no token yet. */
    PENDING,

    /** In the party. The only state that may open a socket or stream. */
    ADMITTED,

    /** The host said no, or nobody answered before the request timed out. */
    DENIED,

    /**
     * Was in and left. The row is kept rather than deleted so rejoining reuses it —
     * one row per person per party keeps the unique constraint meaningful and the
     * member list free of duplicates.
     */
    LEFT
}
