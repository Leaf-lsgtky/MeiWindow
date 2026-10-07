package android.view;

/**
 * COMPILE-ONLY STUB of the platform's {@code android.view.InputEventReceiver} (@hide).
 * Compile-time only — never packaged (see InputChannel.java in this module).
 */
public abstract class InputEventReceiver {

    public InputEventReceiver(InputChannel inputChannel, android.os.Looper looper) {
        throw new UnsupportedOperationException("stub");
    }

    /** Not abstract in the platform class either (a plain no-op). */
    public void onInputEvent(InputEvent event) {
        throw new UnsupportedOperationException("stub");
    }

    public void onBatchedInputEventPending(int pendingBatchSource) {
        throw new UnsupportedOperationException("stub");
    }

    public final void finishInputEvent(InputEvent event, boolean handled) {
        throw new UnsupportedOperationException("stub");
    }

    public void dispose() {
        throw new UnsupportedOperationException("stub");
    }
}
