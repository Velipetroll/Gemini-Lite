package com.google.geminiclone;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class AssistantOverlayActivity extends AppCompatActivity implements ChatAdapter.OnChatActionListener {

    private RecyclerView rvAssistantMessages;
    private ChatAdapter chatAdapter;
    private final List<ChatMessage> chatMessages = new ArrayList<>();

    private EditText etAssistantPromptInput;
    private FrameLayout btnAssistantSend;
    private ImageView ivAssistantSendIcon;
    private FrameLayout btnAssistantMic;
    private FrameLayout btnAssistantAddAttachment;

    private LinearLayout layoutAssistantAttachmentPreview;
    private ImageView ivAssistantPreviewThumb;
    private TextView tvAssistantPreviewName;
    private FrameLayout btnAssistantRemoveAttachment;
    private View scrollSuggestions;

    private ApiKeyManager apiKeyManager;
    private GeminiApiClient apiClient;
    private ChatDatabaseHelper dbHelper;
    private String currentChatId = null;

    private boolean isGenerating = false;
    private final Handler typewriterHandler = new Handler(Looper.getMainLooper());
    private Runnable currentTypewriterRunnable = null;

    private Uri pendingAttachmentUri = null;
    private String pendingAttachmentMime = null;
    private String pendingAttachmentName = null;
    private String pendingAttachmentBase64 = null;
    private Uri cameraPhotoUri = null;

    private ActivityResultLauncher<String> filePickerLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<Intent> speechRecognizerLauncher;
    private ActivityResultLauncher<String> audioPermissionLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_assistant_overlay);

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.BOTTOM);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                window.getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }

        apiKeyManager = new ApiKeyManager(this);
        apiClient = new GeminiApiClient();
        dbHelper = new ChatDatabaseHelper(this);

        initViews();
        setupActivityLaunchers();
        setupSpeechRecognizer();
        setupRecyclerView();
        setupListeners();
        setupKeyboardInsets();
    }

    private void setupKeyboardInsets() {
        View bubbleContainer = findViewById(R.id.assistantSheetContainer);
        if (bubbleContainer == null) return;

        final int baseMarginBottomPx = (int) (18 * getResources().getDisplayMetrics().density);

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.assistantRoot), (v, insets) -> {
            androidx.core.graphics.Insets imeInsets = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.ime()
            );
            androidx.core.graphics.Insets navBarInsets = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            );

            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bubbleContainer.getLayoutParams();
            if (lp != null) {
                // If keyboard is visible, raise above keyboard + margin, otherwise stay above navigation bar + margin
                if (imeInsets.bottom > 0) {
                    lp.bottomMargin = imeInsets.bottom + baseMarginBottomPx;
                } else {
                    lp.bottomMargin = navBarInsets.bottom + baseMarginBottomPx;
                }
                bubbleContainer.setLayoutParams(lp);
            }
            return insets;
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void initViews() {
        rvAssistantMessages = findViewById(R.id.rvAssistantMessages);
        etAssistantPromptInput = findViewById(R.id.etAssistantPromptInput);
        btnAssistantSend = findViewById(R.id.btnAssistantSend);
        ivAssistantSendIcon = findViewById(R.id.ivAssistantSendIcon);
        btnAssistantMic = findViewById(R.id.btnAssistantMic);
        btnAssistantAddAttachment = findViewById(R.id.btnAssistantAddAttachment);

        layoutAssistantAttachmentPreview = findViewById(R.id.layoutAssistantAttachmentPreview);
        ivAssistantPreviewThumb = findViewById(R.id.ivAssistantPreviewThumb);
        tvAssistantPreviewName = findViewById(R.id.tvAssistantPreviewName);
        btnAssistantRemoveAttachment = findViewById(R.id.btnAssistantRemoveAttachment);
        scrollSuggestions = findViewById(R.id.scrollSuggestions);
    }

    private void setupActivityLaunchers() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        handleSelectedFile(uri);
                    }
                }
        );

        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && cameraPhotoUri != null) {
                        handleSelectedFile(cameraPhotoUri);
                    }
                }
        );

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        launchCameraCapture();
                    } else {
                        Toast.makeText(this, "Permiso de cámara necesario para tomar fotos", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void setupSpeechRecognizer() {
        speechRecognizerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> matches = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        if (matches != null && !matches.isEmpty()) {
                            String spokenText = matches.get(0);
                            String current = etAssistantPromptInput.getText().toString();
                            if (current.isEmpty()) {
                                etAssistantPromptInput.setText(spokenText);
                            } else {
                                etAssistantPromptInput.setText(current + " " + spokenText);
                            }
                            etAssistantPromptInput.setSelection(etAssistantPromptInput.getText().length());
                            etAssistantPromptInput.requestFocus();
                        }
                    }
                }
        );

        audioPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        launchSpeechRecognizerIntent();
                    } else {
                        Toast.makeText(this, "Permiso de micrófono requerido para voz", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void setupRecyclerView() {
        chatAdapter = new ChatAdapter(this, chatMessages, this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvAssistantMessages.setLayoutManager(layoutManager);
        rvAssistantMessages.setItemAnimator(null);
        rvAssistantMessages.setAdapter(chatAdapter);
    }

    private void setupListeners() {
        // Scrim outside click dismisses the sheet
        findViewById(R.id.viewScrim).setOnClickListener(v -> finishWithSlideAnimation());

        // Close button dismisses sheet
        findViewById(R.id.btnCloseAssistant).setOnClickListener(v -> finishWithSlideAnimation());

        // Open full main app button
        findViewById(R.id.btnOpenFullApp).setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            if (currentChatId != null) {
                intent.putExtra("open_chat_id", currentChatId);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });

        // Add attachment popup
        btnAssistantAddAttachment.setOnClickListener(v -> showAttachmentMenu());

        // Remove Attachment
        btnAssistantRemoveAttachment.setOnClickListener(v -> clearAttachment());

        // Mic voice input
        btnAssistantMic.setOnClickListener(v -> startVoiceInput());

        // Suggestion Chips
        setupSuggestionChip(findViewById(R.id.chipSuggest1), "¿Qué hay de nuevo hoy?");
        setupSuggestionChip(findViewById(R.id.chipSuggest2), "Explica un concepto rápido");
        setupSuggestionChip(findViewById(R.id.chipSuggest3), "Escribe un mensaje breve");

        // Text input listener
        etAssistantPromptInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isGenerating) return;
                boolean hasText = (s != null && s.toString().trim().length() > 0);
                boolean hasFile = (pendingAttachmentBase64 != null);

                if (hasText || hasFile) {
                    ivAssistantSendIcon.setImageResource(R.drawable.ic_send);
                    ivAssistantSendIcon.setImageTintList(ColorStateList.valueOf(Color.parseColor("#4285F4")));
                } else {
                    ivAssistantSendIcon.setImageResource(R.drawable.ic_gemini_sparkle);
                    ivAssistantSendIcon.setImageTintList(null);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Send button
        btnAssistantSend.setOnClickListener(v -> {
            if (isGenerating) {
                cancelCurrentGeneration();
            } else {
                handleSendPrompt();
            }
        });
    }

    private void setupSuggestionChip(View chip, String text) {
        if (chip == null) return;
        chip.setOnClickListener(v -> {
            etAssistantPromptInput.setText(text);
            etAssistantPromptInput.setSelection(text.length());
            etAssistantPromptInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etAssistantPromptInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void showAttachmentMenu() {
        View anchor = btnAssistantAddAttachment;
        if (anchor == null) return;

        View popupView = View.inflate(this, R.layout.popup_attach_menu, null);
        PopupWindow popupWindow = new PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popupWindow.setElevation(16f);

        popupView.findViewById(R.id.btnAttachCamera).setOnClickListener(v -> {
            popupWindow.dismiss();
            checkAndLaunchCamera();
        });

        popupView.findViewById(R.id.btnAttachFiles).setOnClickListener(v -> {
            popupWindow.dismiss();
            if (filePickerLauncher != null) {
                filePickerLauncher.launch("*/*");
            }
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();
        popupWindow.showAsDropDown(anchor, 0, -(anchor.getHeight() + popupHeight + 16));
    }

    private void checkAndLaunchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCameraCapture();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCameraCapture() {
        try {
            File photoDir = new File(getCacheDir(), "photos");
            if (!photoDir.exists()) {
                photoDir.mkdirs();
            }
            File photoFile = new File(photoDir, "IMG_" + System.currentTimeMillis() + ".jpg");
            cameraPhotoUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    photoFile
            );
            cameraLauncher.launch(cameraPhotoUri);
        } catch (Exception e) {
            Toast.makeText(this, "Error al iniciar cámara: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchSpeechRecognizerIntent();
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void launchSpeechRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla con Gemini...");
        try {
            speechRecognizerLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSelectedFile(Uri uri) {
        if (uri == null) return;
        try {
            pendingAttachmentUri = uri;
            pendingAttachmentMime = getContentResolver().getType(uri);
            if (pendingAttachmentMime == null) {
                pendingAttachmentMime = "application/octet-stream";
            }

            String displayName = "Archivo";
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex);
                    }
                }
            }
            pendingAttachmentName = displayName;

            try (InputStream is = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                long total = 0;
                while ((len = is.read(buffer)) != -1) {
                    total += len;
                    if (total > 10 * 1024 * 1024) {
                        Toast.makeText(this, "El archivo supera el límite de 10MB", Toast.LENGTH_LONG).show();
                        clearAttachment();
                        return;
                    }
                    baos.write(buffer, 0, len);
                }
                pendingAttachmentBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            }

            layoutAssistantAttachmentPreview.setVisibility(View.VISIBLE);
            tvAssistantPreviewName.setText(pendingAttachmentName);
            if (pendingAttachmentMime.startsWith("image/")) {
                ivAssistantPreviewThumb.setImageURI(uri);
            } else {
                ivAssistantPreviewThumb.setImageResource(R.drawable.ic_file);
            }

            ivAssistantSendIcon.setImageResource(R.drawable.ic_send);
            ivAssistantSendIcon.setImageTintList(ColorStateList.valueOf(Color.parseColor("#4285F4")));

        } catch (Exception e) {
            Toast.makeText(this, "Error al leer archivo: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            clearAttachment();
        }
    }

    private void clearAttachment() {
        pendingAttachmentUri = null;
        pendingAttachmentMime = null;
        pendingAttachmentName = null;
        pendingAttachmentBase64 = null;
        if (layoutAssistantAttachmentPreview != null) {
            layoutAssistantAttachmentPreview.setVisibility(View.GONE);
        }
        if (etAssistantPromptInput != null && etAssistantPromptInput.getText().toString().trim().isEmpty() && !isGenerating) {
            ivAssistantSendIcon.setImageResource(R.drawable.ic_gemini_sparkle);
            ivAssistantSendIcon.setImageTintList(null);
        }
    }

    private void handleSendPrompt() {
        String prompt = etAssistantPromptInput.getText().toString().trim();
        boolean hasAttachment = (pendingAttachmentBase64 != null);

        if (prompt.isEmpty() && !hasAttachment) {
            Toast.makeText(this, "Escribe un mensaje o adjunta un archivo", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!apiKeyManager.hasApiKey()) {
            Toast.makeText(this, "Configura tu API Key en la app principal primero", Toast.LENGTH_LONG).show();
            return;
        }

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etAssistantPromptInput.getWindowToken(), 0);
        }

        String attachedUriStr = (pendingAttachmentUri != null) ? pendingAttachmentUri.toString() : null;
        String attachedMime = pendingAttachmentMime;
        String attachedName = pendingAttachmentName;
        String attachedBase64 = pendingAttachmentBase64;

        etAssistantPromptInput.setText("");
        clearAttachment();

        // Create new chat in SQLite if not yet created in this assistant session
        if (currentChatId == null) {
            String title = prompt.isEmpty() ? ("Archivo: " + attachedName) : (prompt.length() > 36 ? prompt.substring(0, 34) + "..." : prompt);
            currentChatId = dbHelper.createChat(title);
        }

        // Show recyclerview and adjust height
        scrollSuggestions.setVisibility(View.GONE);
        rvAssistantMessages.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams lp = rvAssistantMessages.getLayoutParams();
        int targetMaxHeight = (int) (360 * getResources().getDisplayMetrics().density);
        lp.height = targetMaxHeight;
        rvAssistantMessages.setLayoutParams(lp);

        ChatMessage userMessage = new ChatMessage(prompt, true);
        if (attachedUriStr != null) {
            userMessage.setAttachmentUri(attachedUriStr);
            userMessage.setAttachmentMimeType(attachedMime);
            userMessage.setAttachmentName(attachedName);
        }
        chatMessages.add(userMessage);
        chatAdapter.notifyItemInserted(chatMessages.size() - 1);
        rvAssistantMessages.smoothScrollToPosition(chatMessages.size() - 1);
        dbHelper.saveMessage(currentChatId, userMessage);

        String model = apiKeyManager.getModel();
        String thinkingLevel = apiKeyManager.getThinkingLevel();
        String modelLabel = ApiKeyManager.MODEL_35_FLASH_LITE.equals(model) ? "Gemini 3.5 Flash Lite" : "Gemini 3.8 Flash";

        ChatMessage geminiPlaceholder = new ChatMessage("", false, true, thinkingLevel, modelLabel);
        chatMessages.add(geminiPlaceholder);
        int geminiPos = chatMessages.size() - 1;
        chatAdapter.notifyItemInserted(geminiPos);
        rvAssistantMessages.smoothScrollToPosition(geminiPos);
        dbHelper.saveMessage(currentChatId, geminiPlaceholder);

        setGeneratingState(true);

        String apiKey = apiKeyManager.getApiKey();
        GeminiApiClient.ApiCallback callback = new GeminiApiClient.ApiCallback() {
            @Override
            public void onSuccess(String responseText) {
                streamTypewriterResponse(geminiPos, responseText);
            }

            @Override
            public void onError(String errorMessage) {
                setGeneratingState(false);
                geminiPlaceholder.setGenerating(false);
                geminiPlaceholder.setText(errorMessage);
                chatAdapter.notifyItemChanged(geminiPos);
                if (currentChatId != null) {
                    dbHelper.updateLastGeminiMessage(currentChatId, errorMessage);
                }
            }
        };

        if (attachedBase64 != null) {
            apiClient.sendPromptWithFile(apiKey, model, thinkingLevel, prompt, attachedMime, attachedBase64, callback);
        } else {
            apiClient.sendPrompt(apiKey, model, thinkingLevel, prompt, callback);
        }
    }

    private void streamTypewriterResponse(int position, String fullText) {
        ChatMessage msg = chatMessages.get(position);

        final int length = fullText.length();
        final int[] index = {0};
        final int step = Math.max(3, length / 90);

        currentTypewriterRunnable = new Runnable() {
            @Override
            public void run() {
                if (index[0] < length) {
                    index[0] = Math.min(length, index[0] + step);
                    msg.setText(fullText.substring(0, index[0]));
                    chatAdapter.notifyItemChanged(position, ChatAdapter.PAYLOAD_TEXT_STREAM);
                    rvAssistantMessages.scrollToPosition(position);
                    typewriterHandler.postDelayed(this, 20);
                } else {
                    msg.setText(fullText);
                    msg.setGenerating(false);
                    chatAdapter.notifyItemChanged(position);
                    rvAssistantMessages.scrollToPosition(position);
                    setGeneratingState(false);

                    if (currentChatId != null) {
                        dbHelper.updateLastGeminiMessage(currentChatId, fullText);
                    }
                }
            }
        };
        typewriterHandler.post(currentTypewriterRunnable);
    }

    private void cancelCurrentGeneration() {
        if (currentTypewriterRunnable != null) {
            typewriterHandler.removeCallbacks(currentTypewriterRunnable);
            currentTypewriterRunnable = null;
        }

        if (!chatMessages.isEmpty()) {
            int lastPos = chatMessages.size() - 1;
            ChatMessage lastMsg = chatMessages.get(lastPos);
            if (lastMsg.isGenerating()) {
                lastMsg.setGenerating(false);
                if (lastMsg.getText() == null || lastMsg.getText().isEmpty()) {
                    lastMsg.setText("(Respuesta detenida)");
                }
                chatAdapter.notifyItemChanged(lastPos);
                if (currentChatId != null) {
                    dbHelper.updateLastGeminiMessage(currentChatId, lastMsg.getText());
                }
            }
        }
        setGeneratingState(false);
    }

    private void setGeneratingState(boolean generating) {
        this.isGenerating = generating;
        if (generating) {
            ivAssistantSendIcon.setImageResource(R.drawable.ic_stop);
            ivAssistantSendIcon.setImageTintList(ColorStateList.valueOf(Color.parseColor("#EA4335")));
        } else {
            String text = etAssistantPromptInput.getText().toString().trim();
            if (text.isEmpty() && pendingAttachmentBase64 == null) {
                ivAssistantSendIcon.setImageResource(R.drawable.ic_gemini_sparkle);
                ivAssistantSendIcon.setImageTintList(null);
            } else {
                ivAssistantSendIcon.setImageResource(R.drawable.ic_send);
                ivAssistantSendIcon.setImageTintList(ColorStateList.valueOf(Color.parseColor("#4285F4")));
            }
        }
    }

    private void finishWithSlideAnimation() {
        finish();
        overridePendingTransition(R.anim.fade_in_fast, R.anim.slide_out_bottom);
    }

    @Override
    public void onBackPressed() {
        finishWithSlideAnimation();
    }

    @Override
    public void onRegenerate(int position) {
        if (isGenerating) return;
        if (position > 0 && position < chatMessages.size()) {
            ChatMessage userMsg = chatMessages.get(position - 1);
            if (userMsg.isUser()) {
                etAssistantPromptInput.setText(userMsg.getText());
                handleSendPrompt();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentTypewriterRunnable != null) {
            typewriterHandler.removeCallbacks(currentTypewriterRunnable);
        }
    }
}
