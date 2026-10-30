package com.google.geminiclone;

import android.graphics.Bitmap;

/**
 * Holds the screenshot of the screen rendered behind the assistant.
 * It is delivered by the system to GeminiVoiceInteractionSession right before the
 * assistant overlay appears, and consumed by AssistantOverlayActivity.
 */
public final class AssistantScreenshotHolder {

    public interface Listener {
        void onScreenshotAvailable(Bitmap bitmap);
    }

    private static Bitmap screenshot;
    private static Listener listener;

    private AssistantScreenshotHolder() {}

    public static synchronized void clear() {
        screenshot = null;
    }

    public static synchronized void set(Bitmap bitmap) {
        screenshot = bitmap;
        if (listener != null && bitmap != null) {
            listener.onScreenshotAvailable(bitmap);
        }
    }

    public static synchronized Bitmap get() {
        return screenshot;
    }

    public static synchronized void setListener(Listener l) {
        listener = l;
    }
}
