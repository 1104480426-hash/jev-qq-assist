package ai.jev.assist;

/** One immutable screen reading. A pending decision owns this input and its statistics. */
public final class CaptureSnapshot {
    public final String transcript;
    public final String packageName;
    public final int windowId;
    public final String header;
    public final int lineCount;
    public final String reading;
    public final String stats;
    public final long capturedAt;

    CaptureSnapshot(String transcript, String packageName, int windowId, String header,
            int lineCount, String reading, String stats, long capturedAt) {
        this.transcript = transcript;
        this.packageName = packageName;
        this.windowId = windowId;
        this.header = header;
        this.lineCount = lineCount;
        this.reading = reading;
        this.stats = stats;
        this.capturedAt = capturedAt;
    }

    public boolean sameContext(CaptureSnapshot other) {
        return other != null && packageName.equals(other.packageName)
                && windowId == other.windowId && header.equals(other.header)
                && transcript.equals(other.transcript);
    }
}
