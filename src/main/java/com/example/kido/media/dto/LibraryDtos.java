package com.example.kido.media.dto;

import com.example.kido.media.library.ScanStatus;

/** Library administration read models. */
public final class LibraryDtos {

    private LibraryDtos() {}

    /** Snapshot of a scan, polled by the app while a scan is in progress. */
    public record ScanStatusDto(
            boolean running,
            String startedAt,
            String finishedAt,
            String currentFile,
            String error,
            int filesSeen,
            int added,
            int updated,
            int unchanged,
            int markedMissing,
            int failed,
            long moviesInLibrary,
            int rootsConfigured) {

        public static ScanStatusDto from(ScanStatus status, long movieCount, int roots) {
            return new ScanStatusDto(
                    status.isRunning(),
                    status.getStartedAt() == null ? null : status.getStartedAt().toString(),
                    status.getFinishedAt() == null ? null : status.getFinishedAt().toString(),
                    status.getCurrentFile(),
                    status.getError(),
                    status.getFilesSeen().get(),
                    status.getAdded().get(),
                    status.getUpdated().get(),
                    status.getUnchanged().get(),
                    status.getMarkedMissing().get(),
                    status.getFailed().get(),
                    movieCount,
                    roots);
        }
    }
}
