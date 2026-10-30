package com.google.geminiclone;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

public class GeminiVoiceInteractionSession extends VoiceInteractionSession {

    public GeminiVoiceInteractionSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        // Launch the floating AssistantOverlayActivity when the assistant key/gesture is triggered
        Intent intent = new Intent(getContext(), AssistantOverlayActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("trigger_source", "voice_assistant_session");
        getContext().startActivity(intent);
        hide();
    }
}
