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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;

public class ApiKeyDialog {

    private static String resolveSelectedModel(RadioButton rbDefault35Lite) {
        return (rbDefault35Lite != null && rbDefault35Lite.isChecked())
                ? ApiKeyManager.MODEL_35_FLASH_LITE
                : ApiKeyManager.MODEL_38_FLASH;
    }

    private static String resolveSelectedThinking(
            RadioButton rbHigh,
            RadioButton rbMedium,
            RadioButton rbLow,
            RadioButton rbMinimal,
            String selectedModel) {
        if (rbMinimal != null && rbMinimal.isChecked()
                && ApiKeyManager.MODEL_35_FLASH_LITE.equals(selectedModel)) {
            return ApiKeyManager.THINKING_MINIMAL;
        }
        if (rbHigh != null && rbHigh.isChecked()) return ApiKeyManager.THINKING_HIGH;
        if (rbLow != null && rbLow.isChecked()) return ApiKeyManager.THINKING_LOW;
        if (rbMedium != null && rbMedium.isChecked()) return ApiKeyManager.THINKING_MEDIUM;
        return ApiKeyManager.DEFAULT_THINKING;
    }

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
        RadioGroup rgDefaultModel = view.findViewById(R.id.rgDefaultModel);
        RadioButton rbDefault35Lite = view.findViewById(R.id.rbDefaultModel35Lite);
        RadioButton rbDefault38Flash = view.findViewById(R.id.rbDefaultModel38Flash);
        RadioButton rbDefaultHigh = view.findViewById(R.id.rbDefaultThinkingHigh);
        RadioButton rbDefaultMedium = view.findViewById(R.id.rbDefaultThinkingMedium);
        RadioButton rbDefaultLow = view.findViewById(R.id.rbDefaultThinkingLow);
        RadioButton rbDefaultMinimal = view.findViewById(R.id.rbDefaultThinkingMinimal);
        androidx.appcompat.widget.SwitchCompat switchWebSearch = view.findViewById(R.id.switchWebSearch);
        View rowWebSearchToggle = view.findViewById(R.id.rowWebSearchToggle);
        EditText etSystemPrompt = view.findViewById(R.id.etSystemPromptInput);
        Button btnSave = view.findViewById(R.id.btnSaveSettings);

        etApiKey.setText(keyManager.getApiKey());

        // Populate default model and thinking level
        String currentDefaultModel = keyManager.getDefaultModel();
        boolean isDefault35Lite = ApiKeyManager.MODEL_35_FLASH_LITE.equals(currentDefaultModel);
        if (rbDefault35Lite != null && rbDefault38Flash != null) {
            if (isDefault35Lite) {
                rbDefault35Lite.setChecked(true);
            } else {
                rbDefault38Flash.setChecked(true);
            }
        }
        if (rbDefaultMinimal != null) {
            rbDefaultMinimal.setVisibility(isDefault35Lite ? View.VISIBLE : View.GONE);
        }

        String currentDefaultThinking = keyManager.getDefaultThinkingLevel();
        if (ApiKeyManager.THINKING_MINIMAL.equals(currentDefaultThinking) && isDefault35Lite && rbDefaultMinimal != null) {
            rbDefaultMinimal.setChecked(true);
        } else if (ApiKeyManager.THINKING_HIGH.equals(currentDefaultThinking) && rbDefaultHigh != null) {
            rbDefaultHigh.setChecked(true);
        } else if (ApiKeyManager.THINKING_LOW.equals(currentDefaultThinking) && rbDefaultLow != null) {
            rbDefaultLow.setChecked(true);
        } else if (rbDefaultMedium != null) {
            rbDefaultMedium.setChecked(true);
        }

        if (rgDefaultModel != null && rbDefaultMinimal != null) {
            rgDefaultModel.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.rbDefaultModel35Lite) {
                    rbDefaultMinimal.setVisibility(View.VISIBLE);
                } else {
                    rbDefaultMinimal.setVisibility(View.GONE);
                    if (rbDefaultMinimal.isChecked() && rbDefaultMedium != null) {
                        rbDefaultMedium.setChecked(true);
                    }
                }
            });
        }

        if (switchWebSearch != null) {
            switchWebSearch.setChecked(keyManager.isWebSearchEnabled());
        }
        if (rowWebSearchToggle != null && switchWebSearch != null) {
            rowWebSearchToggle.setOnClickListener(v -> switchWebSearch.setChecked(!switchWebSearch.isChecked()));
        }
        if (etSystemPrompt != null) {
            etSystemPrompt.setText(keyManager.getSystemPrompt());
        }

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

            pbTesting.setVisibility(View.VISIBLE);
            tvTestStatus.setText("Probando conexión con Google AI...");
            tvTestStatus.setTextColor(Color.parseColor("#59C0F0"));
            btnTest.setEnabled(false);

            String selectedModel = resolveSelectedModel(rbDefault35Lite);
            String selectedThinking = resolveSelectedThinking(
                    rbDefaultHigh, rbDefaultMedium, rbDefaultLow, rbDefaultMinimal, selectedModel);

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
            String selectedModel = resolveSelectedModel(rbDefault35Lite);
            String selectedThinking = resolveSelectedThinking(
                    rbDefaultHigh, rbDefaultMedium, rbDefaultLow, rbDefaultMinimal, selectedModel);
            keyManager.setDefaultModelAndThinking(selectedModel, selectedThinking);
            if (switchWebSearch != null) {
                keyManager.setWebSearchEnabled(switchWebSearch.isChecked());
            }
            if (etSystemPrompt != null) {
                keyManager.setSystemPrompt(etSystemPrompt.getText().toString().trim());
            }
            Toast.makeText(context, "Configuración guardada", Toast.LENGTH_SHORT).show();
            if (onSaved != null) onSaved.run();
            dialog.dismiss();
        });

        dialog.show();
    }
}
