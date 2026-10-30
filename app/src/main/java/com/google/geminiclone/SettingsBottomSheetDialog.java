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

public class SettingsBottomSheetDialog {

    public interface OnSettingsSavedListener {
        void onSettingsSaved();
    }

    public static void show(Context context, ApiKeyManager keyManager, OnSettingsSavedListener listener) {
        BottomSheetDialog dialog = new BottomSheetDialog(context, R.style.BottomSheetDialogTheme);
        View view = View.inflate(context, R.layout.dialog_settings, null);
        dialog.setContentView(view);


        RadioGroup rgModels = view.findViewById(R.id.rgModels);
        RadioButton rb38Flash = view.findViewById(R.id.rbModel38Flash);
        RadioButton rb35FlashLite = view.findViewById(R.id.rbModel35FlashLite);

        RadioGroup rgThinking = view.findViewById(R.id.rgThinkingLevels);
        RadioButton rbHigh = view.findViewById(R.id.rbThinkingHigh);
        RadioButton rbMedium = view.findViewById(R.id.rbThinkingMedium);
        RadioButton rbLow = view.findViewById(R.id.rbThinkingLow);
        RadioButton rbMinimal = view.findViewById(R.id.rbThinkingMinimal);

        Button btnSave = view.findViewById(R.id.btnSaveSettings);

        // 2. Populate current model
        String currentModel = keyManager.getModel();
        if (ApiKeyManager.MODEL_35_FLASH_LITE.equals(currentModel)) {
            rb35FlashLite.setChecked(true);
            rbMinimal.setVisibility(View.VISIBLE);
        } else {
            rb38Flash.setChecked(true);
            rbMinimal.setVisibility(View.GONE);
        }

        // 3. Populate current thinking level
        String currentThinking = keyManager.getThinkingLevel();
        if (ApiKeyManager.THINKING_MINIMAL.equals(currentThinking) && rb35FlashLite.isChecked()) {
            rbMinimal.setChecked(true);
        } else if (ApiKeyManager.THINKING_LOW.equals(currentThinking)) {
            rbLow.setChecked(true);
        } else if (ApiKeyManager.THINKING_MEDIUM.equals(currentThinking)) {
            rbMedium.setChecked(true);
        } else {
            rbHigh.setChecked(true);
        }

        // Handle model change: "minimal solo para flash lite"
        rgModels.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbModel35FlashLite) {
                rbMinimal.setVisibility(View.VISIBLE);
            } else {
                // Switching to 3.8 Flash: hide minimal and revert to high if minimal was selected
                rbMinimal.setVisibility(View.GONE);
                if (rbMinimal.isChecked()) {
                    rbHigh.setChecked(true);
                }
            }
        });

        // Save settings
        btnSave.setOnClickListener(v -> {
            String selectedModel = rb35FlashLite.isChecked()
                    ? ApiKeyManager.MODEL_35_FLASH_LITE
                    : ApiKeyManager.MODEL_38_FLASH;
            keyManager.setModel(selectedModel);

            String selectedThinking = ApiKeyManager.THINKING_HIGH;
            if (rbMedium.isChecked()) selectedThinking = ApiKeyManager.THINKING_MEDIUM;
            else if (rbLow.isChecked()) selectedThinking = ApiKeyManager.THINKING_LOW;
            else if (rbMinimal.isChecked() && rb35FlashLite.isChecked()) selectedThinking = ApiKeyManager.THINKING_MINIMAL;
            keyManager.setThinkingLevel(selectedThinking);

            Toast.makeText(context, "Configuración guardada", Toast.LENGTH_SHORT).show();
            if (listener != null) {
                listener.onSettingsSaved();
            }
            dialog.dismiss();
        });

        dialog.show();
    }
}
