package com.google.geminiclone;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;

public class ApiKeyDialog {

    public static void show(Context context, ApiKeyManager keyManager, Runnable onSaved) {
        BottomSheetDialog dialog = new BottomSheetDialog(context, R.style.BottomSheetDialogTheme);
        View view = View.inflate(context, R.layout.dialog_api, null);
        dialog.setContentView(view);

        EditText etApiKey = view.findViewById(R.id.etApiKeyInput);
        ImageButton btnToggle = view.findViewById(R.id.btnToggleVisibility);
        TextView btnPaste = view.findViewById(R.id.btnPasteKey);
        TextView tvGetApiKeyLink = view.findViewById(R.id.tvGetApiKeyLink);
        Button btnTest = view.findViewById(R.id.btnTestKey);
        ProgressBar pbTesting = view.findViewById(R.id.pbTesting);
        TextView tvTestStatus = view.findViewById(R.id.tvTestStatus);
        Button btnSave = view.findViewById(R.id.btnSaveSettings);

        etApiKey.setText(keyManager.getApiKey());

        // Toggle password visibility
        final boolean[] isPasswordVisible = {false};
        btnToggle.setOnClickListener(v -> {
            isPasswordVisible[0] = !isPasswordVisible[0];
            if (isPasswordVisible[0]) {
                etApiKey.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
            } else {
                etApiKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
            }
            etApiKey.setSelection(etApiKey.getText().length());
        });

        // Paste from clipboard
        btnPaste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clipData = clipboard.getPrimaryClip();
                if (clipData != null && clipData.getItemCount() > 0) {
                    CharSequence text = clipData.getItemAt(0).getText();
                    if (text != null) {
                        etApiKey.setText(text.toString().trim());
                        etApiKey.setSelection(etApiKey.getText().length());
                        Toast.makeText(context, "API Key pegada", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        // Link to Google AI Studio
        tvGetApiKeyLink.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"));
            try {
                context.startActivity(intent);
            } catch (Exception ignored) {}
        });

        // Test connection
        btnTest.setOnClickListener(v -> {
            String key = etApiKey.getText().toString().trim();
            if (key.isEmpty()) {
                tvTestStatus.setText("Ingresa una clave primero");
                tvTestStatus.setTextColor(Color.parseColor("#EA4335"));
                return;
            }

            // Validate format via C++ native engine
            if (!GeminiNative.validateApiKey(key)) {
                tvTestStatus.setText("Formato de clave inválido (debe comenzar con AIzaSy)");
                tvTestStatus.setTextColor(Color.parseColor("#EA4335"));
                return;
            }

            pbTesting.setVisibility(View.VISIBLE);
            tvTestStatus.setText("Probando conexión con Google AI...");
            tvTestStatus.setTextColor(Color.parseColor("#59C0F0"));
            btnTest.setEnabled(false);

            String selectedModel = keyManager.getModel();
            String selectedThinking = keyManager.getThinkingLevel();

            new GeminiApiClient().testConnection(key, selectedModel, selectedThinking, new GeminiApiClient.ApiCallback() {
                @Override
                public void onSuccess(String responseText) {
                    pbTesting.setVisibility(View.GONE);
                    btnTest.setEnabled(true);
                    tvTestStatus.setText("¡Conexión exitosa! Modelo listo.");
                    tvTestStatus.setTextColor(Color.parseColor("#34A853"));
                }

                @Override
                public void onError(String errorMessage) {
                    pbTesting.setVisibility(View.GONE);
                    btnTest.setEnabled(true);
                    tvTestStatus.setText(errorMessage);
                    tvTestStatus.setTextColor(Color.parseColor("#EA4335"));
                }
            });
        });

        // Test connection
        btnTest.setOnClickListener(v -> {
            String key = etApiKey.getText().toString().trim();
            if (key.isEmpty()) {
                tvTestStatus.setText("Ingresa una clave primero");
                tvTestStatus.setTextColor(Color.parseColor("#EA4335"));
                return;
            }

            // Validate format via C++ native engine
            if (!GeminiNative.validateApiKey(key)) {
                tvTestStatus.setText("Formato de clave inválido (debe comenzar con AIzaSy)");
                tvTestStatus.setTextColor(Color.parseColor("#EA4335"));
                return;
            }

            pbTesting.setVisibility(View.VISIBLE);
            tvTestStatus.setText("Probando conexión con Google AI...");
            tvTestStatus.setTextColor(Color.parseColor("#59C0F0"));
            btnTest.setEnabled(false);

            String selectedModel = keyManager.getModel();
            String selectedThinking = keyManager.getThinkingLevel();

            new GeminiApiClient().testConnection(key, selectedModel, selectedThinking, new GeminiApiClient.ApiCallback() {
                @Override
                public void onSuccess(String responseText) {
                    pbTesting.setVisibility(View.GONE);
                    btnTest.setEnabled(true);
                    tvTestStatus.setText("¡Conexión exitosa! Modelo listo.");
                    tvTestStatus.setTextColor(Color.parseColor("#34A853"));
                }

                @Override
                public void onError(String errorMessage) {
                    pbTesting.setVisibility(View.GONE);
                    btnTest.setEnabled(true);
                    tvTestStatus.setText(errorMessage);
                    tvTestStatus.setTextColor(Color.parseColor("#EA4335"));
                }
            });
        });

        btnSave.setOnClickListener(v -> {
            keyManager.setApiKey(etApiKey.getText().toString().trim());
            Toast.makeText(context, "API Key guardada", Toast.LENGTH_SHORT).show();
            if (onSaved != null) onSaved.run();
            dialog.dismiss();
        });

        dialog.show();
    }
}
