package com.example.kido.media.together;

/**
 * Whether the party's shared playhead is advancing.
 *
 * <p>Two states only. Buffering is deliberately not one of them: it is a property of
 * one member's device, not of the party, and promoting it here would mean every stall
 * on the slowest connection rewrote everyone's clock.
 */
public enum PartyClockState {

    PLAYING,

    PAUSED
}
