package com.google.geminiclone;

import android.speech.RecognitionService;
import android.content.Intent;

public class GeminiRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        // Fallback stub for system binding
    }

    @Override
    protected void onCancel(Callback listener) {
    }

    @Override
    protected void onStopListening(Callback listener) {
    }
}
