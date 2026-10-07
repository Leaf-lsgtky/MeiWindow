package android.view;

/**
 * COMPILE-ONLY STUB of the platform's {@code android.view.InputChannel} (@hide).
 *
 * This module is consumed as {@code compileOnly}: none of these classes is ever
 * packaged into the APK, so at runtime the real boot-classpath class is used.
 * Same technique and shapes as MiuiBackGestureHook/hidden-api (verified against
 * this device's framework.jar — see work/jadxfw + work/jadxsvc dumps).
 */
public final class InputChannel {

    public InputChannel() {
        throw new UnsupportedOperationException("stub");
    }

    /** InputChannel.getToken() — the dispatcher's channel identity. */
    public android.os.IBinder getToken() {
        throw new UnsupportedOperationException("stub");
    }

    public void copyTo(InputChannel outParameter) {
        throw new UnsupportedOperationException("stub");
    }

    public void dispose() {
        throw new UnsupportedOperationException("stub");
    }
}
