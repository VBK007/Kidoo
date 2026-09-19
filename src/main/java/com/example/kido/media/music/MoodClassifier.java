package com.example.kido.media.music;

/**
 * Maps a track's raw audio features to a mood/activity rail placement.
 *
 * <p>Fixed thresholds rather than per-scan percentile ranking: ranking a track against
 * "the whole library so far" would mean the same track's placement shifts every time
 * more tracks are scanned, and would need a second whole-library pass to recompute
 * everyone's rank whenever one new track arrives — the ingest pipeline processes one
 * file at a time and has no such pass. The cutoffs below are not arbitrary, though:
 * they are the 33rd/40th/55th/66th percentiles of energy, tempo and spectral centroid
 * measured across this app's own ~740-track real library (mostly Tamil film songs),
 * captured 2026-09-19. A library with a very different sound (a metal or classical
 * collection, say) would eventually want these recalibrated the same way.
 *
 * <p>Energy is weighted over tempo in the intensity score deliberately: calibrating
 * against known tracks (a slow romantic melody vs. two up-tempo dance/folk numbers)
 * showed tempo tracking is noisy on syncopated film-song rhythm — it under-detects by
 * exactly half on some tracks — while RMS energy cleanly separated the same tracks.
 * The half-tempo correction below fixes the common case rather than the rarer
 * double-time one, since a home library's dance numbers skew toward being missed low.
 */
public final class MoodClassifier {

    private MoodClassifier() {}

    private static final double ENERGY_P33 = 0.13660;
    private static final double ENERGY_P66 = 0.18608;
    private static final double TEMPO_P33 = 96.116;
    private static final double TEMPO_P66 = 121.776;
    private static final double CENTROID_P40 = 92.486;
    private static final double CENTROID_P55 = 103.352;

    /** Autocorrelation tempo trackers commonly halve the true tempo on syncopated beats. */
    private static final double LIKELY_HALF_TEMPO_BELOW = 75.0;

    public record Result(String mood, String activity, double intensityPercentileEstimate) {}

    /**
     * @return a placement, or empty if the track has no usable energy reading (a
     *         spectral centroid or tempo reading, being secondary signals, is optional)
     */
    public static java.util.Optional<Result> classify(Double bpm, Double energyRms, Double spectralCentroid) {
        if (energyRms == null) {
            return java.util.Optional.empty();
        }
        Double adjustedBpm = bpm == null ? null : (bpm < LIKELY_HALF_TEMPO_BELOW ? bpm * 2 : bpm);

        double energyScore = percentileAgainstFixedPoints(energyRms, ENERGY_P33, ENERGY_P66);
        double tempoScore = adjustedBpm == null
                ? energyScore // no tempo reading: energy alone carries the score
                : percentileAgainstFixedPoints(adjustedBpm, TEMPO_P33, TEMPO_P66);
        double intensity = 0.65 * energyScore + 0.35 * tempoScore;

        Double centroidScore = spectralCentroid == null
                ? null
                : percentileAgainstFixedPoints(spectralCentroid, CENTROID_P40, CENTROID_P55);

        String mood;
        String activity;
        if (intensity >= 66) {
            mood = "Energetic";
            activity = centroidScore != null && centroidScore < 50 ? "Workout" : "Party";
        } else if (intensity <= 33) {
            if (centroidScore != null && centroidScore < 40) {
                mood = "Sad";
                activity = "Relax";
            } else {
                mood = "Romantic";
                activity = "Relax";
            }
        } else {
            if (centroidScore != null && centroidScore >= 55) {
                mood = "Chill";
                activity = "Travel";
            } else {
                mood = "Romantic";
                activity = "Relax";
            }
        }
        return java.util.Optional.of(new Result(mood, activity, intensity));
    }

    /**
     * Places a raw value on a 0/33/66/100 scale against the library's own calibrated
     * 33rd/66th percentile points, linearly interpolating between them — a cheap stand-in
     * for a true percentile rank that needs no corpus scan to compute per track.
     */
    private static double percentileAgainstFixedPoints(double value, double p33, double p66) {
        if (value <= p33) {
            return p33 <= 0 ? 33 : 33 * Math.max(0, value / p33);
        }
        if (value >= p66) {
            return 66 + Math.min(34, (value - p66) / (p66 - p33) * 33);
        }
        return 33 + (value - p33) / (p66 - p33) * 33;
    }
}
