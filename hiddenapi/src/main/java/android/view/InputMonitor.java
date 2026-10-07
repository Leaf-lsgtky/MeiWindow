package android.view;

/**
 * COMPILE-ONLY STUB of the platform's {@code android.view.InputMonitor} (@hide).
 * Compile-time only — never packaged (see InputChannel.java in this module).
 *
 * Shapes verified against InputMonitorCompat.java:25/31/47 of this device's
 * decompiled SystemUI (work/... out/SystemUI1703_src).
 */
public final class InputMonitor implements AutoCloseable {

    private InputMonitor() {
        throw new UnsupportedOperationException("stub");
    }

    public InputChannel getInputChannel() {
        throw new UnsupportedOperationException("stub");
    }

    /** Zero-arg pilfer: makes this monitor's channel the touch target. */
    public void pilferPointers() {
        throw new UnsupportedOperationException("stub");
    }

    public void dispose() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("stub");
    }
}
