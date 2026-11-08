package com.medirecords.importjob;

public enum ImportStatus {
    /** Uploaded and handed to the job launcher; waiting for a free worker. */
    QUEUED,
    RUNNING,
    /** The whole file was processed. Some rows may still have been rejected (see the error report). */
    COMPLETED,
    /** The job stopped early (unreadable file, database outage, server restart). Rows committed so far stay. */
    FAILED;

    public boolean isFinished() {
        return this == COMPLETED || this == FAILED;
    }
}
