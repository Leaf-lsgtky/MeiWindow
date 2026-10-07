package android.hardware.input;

import android.view.InputMonitor;

/**
 * COMPILE-ONLY STUB of the platform's {@code android.hardware.input.InputManagerGlobal}
 * (@hide). Compile-time only — never packaged (see the other stubs in this module).
 *
 * DECOMPILED BEHAVIOUR THAT MATTERS (this device, work/fw/.../framework.jar classes2.dex
 * → android.hardware.input.InputManagerGlobal:1467, and its server side
 * work/svcdex/classes2.dex → com.android.server.input.InputManagerService:646-672):
 *
 *   monitorGestureInput(name, displayId)
 *     → InputManagerService.monitorGestureInput(new Binder(), name, displayId)
 *     → ALWAYS createSpyWindowGestureMonitor(...) on this MIUI/HyperOS build, which builds a
 *       GestureMonitorSpyWindow with InputWindowHandle.inputConfig = 16388
 *       (GestureMonitorSpyWindow.java:35) — i.e. SPY | DO_NOT_PILFER, plus its own
 *       system_server-owned input surface.
 *
 * That is why a monitor obtained here survives pointer pilfering by other windows, while a
 * self-made WindowManager SPY window (type 2024 + INPUT_FEATURE_SPY, which is what this
 * module used before) does not: inside apps it is dropped from the dispatcher's touch state
 * ~6-22 ms after ACTION_DOWN (device log: SPY_*_DOWN → SPY_*_SYSTEM_CANCEL).
 */
public final class InputManagerGlobal {

    private InputManagerGlobal() {
        throw new UnsupportedOperationException("stub");
    }

    public static InputManagerGlobal getInstance() {
        throw new UnsupportedOperationException("stub");
    }

    public InputMonitor monitorGestureInput(String name, int displayId) {
        throw new UnsupportedOperationException("stub");
    }

    public void pilferPointers(android.os.IBinder inputChannelToken) {
        throw new UnsupportedOperationException("stub");
    }
}
