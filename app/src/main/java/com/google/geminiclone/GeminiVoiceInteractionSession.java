package com.google.geminiclone;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;

public class GeminiVoiceInteractionSession extends VoiceInteractionSession {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable delayedHide = this::hide;

    public GeminiVoiceInteractionSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        // Drop any stale screenshot from a previous invocation
        AssistantScreenshotHolder.clear();

        // Launch the floating AssistantOverlayActivity when the assistant key/gesture is triggered
        Intent intent = new Intent(getContext(), AssistantOverlayActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("trigger_source", "voice_assistant_session");
        getContext().startActivity(intent);

        // Keep the session alive briefly so the system can deliver the screenshot
        handler.removeCallbacks(delayedHide);
        handler.postDelayed(delayedHide, 2500);
    }

    @Override
    public void onHandleScreenshot(Bitmap screenshot) {
        super.onHandleScreenshot(screenshot);
        if (screenshot != null) {
            AssistantScreenshotHolder.set(screenshot);
        }
        handler.removeCallbacks(delayedHide);
        handler.postDelayed(delayedHide, 300);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(delayedHide);
        super.onDestroy();
    }
}
