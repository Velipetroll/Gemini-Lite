package com.google.geminiclone;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.speech.RecognizerIntent;
import androidx.core.content.ContextCompat;
import android.Manifest;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import androidx.core.content.FileProvider;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.io.File;

public class MainActivity extends AppCompatActivity implements ChatAdapter.OnChatActionListener {

    private GeminiGLSurfaceView glSurfaceView;
    private ApiKeyManager apiKeyManager;
    private GeminiApiClient apiClient;
    private ChatDatabaseHelper dbHelper;

    // Navigation Drawer
    private DrawerLayout drawerLayout;
    private RecyclerView rvChatHistory;
    private TextView tvEmptyHistory;
    private ChatHistoryAdapter historyAdapter;
    private final List<ChatSession> chatSessions = new ArrayList<>();
    private String currentChatId = null;

    // Main Chat Timeline & Hero
    private View heroContainer;
    private View btnNewChat;
    private RecyclerView rvChatMessages;
    private ChatAdapter chatAdapter;
    private final List<ChatMessage> chatMessages = new ArrayList<>();

    // Top Bar & Input
    private TextView tvModelName;
    private EditText etPromptInput;
    private ImageView ivSendIcon;
    private FrameLayout btnSend;

    // Attachment State
    private View layoutAttachmentPreview;
    private ImageView ivPreviewThumb;
    private TextView tvPreviewName;
    private View btnRemoveAttachment;
    private Uri pendingAttachmentUri = null;
    private String pendingAttachmentMime = null;
    private String pendingAttachmentName = null;
    private String pendingAttachmentBase64 = null;
    private String activeChatAttachmentBase64 = null;
    private String activeChatAttachmentMime = null;
    private ActivityResultLauncher<String> filePickerLauncher;

    // Camera State & Launcher
    private Uri cameraPhotoUri = null;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    // Speech Recognition Launchers
    private ActivityResultLauncher<Intent> speechRecognizerLauncher;
    private ActivityResultLauncher<String> audioPermissionLauncher;

    private boolean isGenerating = false;
    private int activeGeneratingPosition = -1;
    private final Handler typewriterHandler = new Handler(Looper.getMainLooper());
    private Runnable currentTypewriterRunnable;
    private android.view.GestureDetector swipeDrawerDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Edge-to-edge full screen extending under notch cutout and nav bar
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        apiKeyManager = new ApiKeyManager(this);
        if (savedInstanceState == null) {
            apiKeyManager.applyDefaultModel();
        }
        apiClient = new GeminiApiClient();
        dbHelper = new ChatDatabaseHelper(this);
        WebSearchEngine.init(this);

        setupSwipeDrawerDetector();
        setupFilePicker();
        setupCameraLauncher();
        setupSpeechRecognizer();
        initViews();
        setupRecyclerView();
        setupDrawer();
        setupListeners();
        updateStatusBadge();
        handleIncomingIntent(getIntent());
    }

    private void setupSwipeDrawerDetector() {
        final float minSwipePx = 72f * getResources().getDisplayMetrics().density;
        swipeDrawerDetector = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float velocityX, float velocityY) {
                if (e1 != null && e2 != null && drawerLayout != null
                        && !drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    float diffX = e2.getX() - e1.getX();
                    float diffY = e2.getY() - e1.getY();
                    if (diffX > minSwipePx && Math.abs(diffX) > Math.abs(diffY) * 1.6f && velocityX > 300f) {
                        drawerLayout.openDrawer(GravityCompat.START);
                        return true;
                    }
                }
                return false;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (swipeDrawerDetector != null) {
            swipeDrawerDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && intent.hasExtra("open_chat_id")) {
            String targetId = intent.getStringExtra("open_chat_id");
            if (targetId != null) {
                currentChatId = targetId;
                activeChatAttachmentBase64 = null;
                activeChatAttachmentMime = null;
                chatMessages.clear();
                chatMessages.addAll(dbHelper.getMessagesForChat(currentChatId));
                chatAdapter.notifyDataSetChanged();
                loadChatSessions();
                transitionToChat();
                if (!chatMessages.isEmpty()) {
                    rvChatMessages.scrollToPosition(chatMessages.size() - 1);
                }
            }
        }
    }

    private void setupFilePicker() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                this::handleSelectedFile
        );
    }

    private void setupCameraLauncher() {
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

    private void showAttachmentMenu() {
        View anchor = findViewById(R.id.btnAddAttachment);
        if (anchor == null) return;

        View popupView = View.inflate(this, R.layout.popup_attach_menu, null);
        PopupWindow popupWindow = new PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popupWindow.setElevation(14f);

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
        // Show directly above the + button
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

    private void setupSpeechRecognizer() {
        speechRecognizerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> matches = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        if (matches != null && !matches.isEmpty()) {
                            String spokenText = matches.get(0);
                            String current = etPromptInput.getText().toString();
                            if (current.isEmpty()) {
                                etPromptInput.setText(spokenText);
                            } else {
                                etPromptInput.setText(current + " " + spokenText);
                            }
                            etPromptInput.setSelection(etPromptInput.getText().length());
                            etPromptInput.requestFocus();
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
                        Toast.makeText(this, "Permiso de micrófono requerido para entrada de voz", Toast.LENGTH_SHORT).show();
                    }
                }
        );
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
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora con Gemini...");
        try {
            speechRecognizerLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(this, "El reconocimiento de voz no está disponible en este dispositivo", Toast.LENGTH_SHORT).show();
        }
    }

    private void initViews() {
        glSurfaceView = findViewById(R.id.glSurfaceView);
        heroContainer = findViewById(R.id.heroContainer);
        btnNewChat = findViewById(R.id.btnNewChat);
        rvChatMessages = findViewById(R.id.rvChatMessages);

        tvModelName = findViewById(R.id.tvModelName);
        etPromptInput = findViewById(R.id.etPromptInput);
        ivSendIcon = findViewById(R.id.ivSendIcon);
        btnSend = findViewById(R.id.btnSend);

        layoutAttachmentPreview = findViewById(R.id.layoutAttachmentPreview);
        ivPreviewThumb = findViewById(R.id.ivPreviewThumb);
        tvPreviewName = findViewById(R.id.tvPreviewName);
        btnRemoveAttachment = findViewById(R.id.btnRemoveAttachment);

        updateModelNameDisplay();

        // Apply window insets (status bar / notch at top, navigation bar / keyboard at bottom)
        // while preserving original XML paddings so top/bottom elements stay at their exact design positions
        View uiContainer = findViewById(R.id.uiContainer);
        View topBar = findViewById(R.id.topBar);
        View navDrawer = findViewById(R.id.navigationDrawer);
        final int topBarInitialTop = (topBar != null) ? topBar.getPaddingTop() : 0;

        // Keep VRI/HWUI dirty bounds anchored at y=0 from the first frame so sliding overlays
        // (navigationDrawer and DrawerLayout scrim) are not clipped at topBar's first visible child
        if (uiContainer instanceof ViewGroup) {
            View topEdgeAnchor = new View(this);
            topEdgeAnchor.setBackgroundColor(0x01000000);
            ((ViewGroup) uiContainer).addView(topEdgeAnchor,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        if (uiContainer != null) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(uiContainer, (v, insets) -> {
                androidx.core.graphics.Insets topInsets = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.statusBars() |
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                );
                androidx.core.graphics.Insets navInsets = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.navigationBars()
                );
                androidx.core.graphics.Insets imeInsets = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.ime()
                );

                if (topBar != null) {
                    topBar.setPadding(
                            topBar.getPaddingLeft(),
                            topBarInitialTop + topInsets.top,
                            topBar.getPaddingRight(),
                            topBar.getPaddingBottom()
                    );
                }

                int bottomInset = Math.max(navInsets.bottom, imeInsets.bottom);
                v.setPadding(v.getPaddingLeft(), 0, v.getPaddingRight(), bottomInset);

                if (navDrawer != null) {
                    navDrawer.setPadding(topInsets.left, topInsets.top, 0, navInsets.bottom);
                }

                return insets;
            });
        }
    }

    private void setupRecyclerView() {
        chatAdapter = new ChatAdapter(this, chatMessages, this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(false);
        rvChatMessages.setLayoutManager(layoutManager);
        rvChatMessages.setHasFixedSize(true);
        rvChatMessages.setItemViewCacheSize(12);
        rvChatMessages.setItemAnimator(null); // Eliminates cross-fade flicker and text superimposition
        rvChatMessages.setAdapter(chatAdapter);
    }

    private void setupDrawer() {
        drawerLayout = findViewById(R.id.drawerLayout);
        drawerLayout.setStatusBarBackground(null);
        rvChatHistory = findViewById(R.id.rvChatHistory);
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory);

        historyAdapter = new ChatHistoryAdapter(chatSessions, new ChatHistoryAdapter.OnHistoryActionListener() {
            @Override
            public void onChatSelected(ChatSession session) {
                openChatSession(session);
            }

            @Override
            public void onChatDeleted(ChatSession session) {
                deleteChatSession(session);
            }
        });

        rvChatHistory.setLayoutManager(new LinearLayoutManager(this));
        rvChatHistory.setHasFixedSize(true);
        rvChatHistory.setAdapter(historyAdapter);

        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                if (glSurfaceView != null) {
                    glSurfaceView.setRenderMode(android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                }
            }

            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                if (glSurfaceView != null && slideOffset < 0.98f &&
                        glSurfaceView.getRenderMode() != android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY) {
                    glSurfaceView.setRenderMode(android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY);
                }
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                if (glSurfaceView != null) {
                    glSurfaceView.setRenderMode(android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY);
                }
            }
        });

        findViewById(R.id.btnOpenDrawer).setOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START)
        );

        findViewById(R.id.btnDrawerNewChat).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            resetToHeroScreen();
        });

        findViewById(R.id.btnDrawerSettings).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            ApiKeyDialog.show(this, apiKeyManager, () -> { updateStatusBadge(); updateModelNameDisplay(); });
        });

        loadChatSessions();
    }

    private void loadChatSessions() {
        chatSessions.clear();
        chatSessions.addAll(dbHelper.getAllChats());
        historyAdapter.setSelectedChatId(currentChatId);
        historyAdapter.notifyDataSetChanged();
        if (tvEmptyHistory != null) {
            tvEmptyHistory.setVisibility(chatSessions.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void openChatSession(ChatSession session) {
        if (isGenerating) {
            cancelCurrentGeneration();
        }

        currentChatId = session.getId();
        activeChatAttachmentBase64 = null;
        activeChatAttachmentMime = null;
        historyAdapter.setSelectedChatId(currentChatId);

        chatMessages.clear();
        chatMessages.addAll(dbHelper.getMessagesForChat(currentChatId));
        chatAdapter.notifyDataSetChanged();

        transitionToChat();
        if (!chatMessages.isEmpty()) {
            rvChatMessages.scrollToPosition(chatMessages.size() - 1);
        }

        drawerLayout.closeDrawer(GravityCompat.START);
    }

    private void deleteChatSession(ChatSession session) {
        boolean wasCurrent = session.getId().equals(currentChatId);
        dbHelper.deleteChat(session.getId());
        if (wasCurrent) {
            resetToHeroScreen();
        }
        loadChatSessions();
        Toast.makeText(this, "Chat eliminado", Toast.LENGTH_SHORT).show();
    }

    private void setupListeners() {
        // Open Settings Dialog via Avatar or Model selector
        findViewById(R.id.btnModelSelector).setOnClickListener(v -> openSettingsDialog());

        // New Chat Button in top bar
        btnNewChat.setOnClickListener(v -> resetToHeroScreen());

        // Plus / Attachment button
        findViewById(R.id.btnAddAttachment).setOnClickListener(v -> showAttachmentMenu());

        // Remove Attachment button
        btnRemoveAttachment.setOnClickListener(v -> clearAttachment());

        // Mic button
        findViewById(R.id.btnMic).setOnClickListener(v -> startVoiceInput());

        // Tapping anywhere in the visible input pill opens the keyboard
        View inputPill = findViewById(R.id.inputPillContainer);
        if (inputPill != null) {
            inputPill.setOnClickListener(v -> {
                etPromptInput.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(etPromptInput, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }

        // Suggestion Cards
        setupSuggestionCard(findViewById(R.id.cardSuggestion1), getString(R.string.suggestion_1_prompt));
        setupSuggestionCard(findViewById(R.id.cardSuggestion2), getString(R.string.suggestion_2_prompt));
        setupSuggestionCard(findViewById(R.id.cardSuggestion3), getString(R.string.suggestion_3_prompt));
        setupSuggestionCard(findViewById(R.id.cardSuggestion4), getString(R.string.suggestion_4_prompt));

        // Input text change listener
        etPromptInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isGenerating) return; // Keep stop button while generating

                boolean hasText = (s != null && s.toString().trim().length() > 0);
                boolean hasFile = (pendingAttachmentBase64 != null);

                if (hasText || hasFile) {
                    ivSendIcon.setImageResource(R.drawable.ic_send);
                    ivSendIcon.setImageTintList(ColorStateList.valueOf(Color.parseColor("#4285F4")));
                } else {
                    ivSendIcon.setImageResource(R.drawable.ic_gemini_sparkle);
                    ivSendIcon.setImageTintList(null);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Send / Stop Button Click
        btnSend.setOnClickListener(v -> {
            if (isGenerating) {
                cancelCurrentGeneration();
            } else {
                handleSendPrompt();
            }
        });
    }

    private void openSettingsDialog() {
        View anchor = findViewById(R.id.btnModelSelector);
        if (anchor == null) return;

        View popupView = View.inflate(this, R.layout.popup_models_menu, null);
        PopupWindow popupWindow = new PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popupWindow.setElevation(16f);

        ImageView ivCheck35Lite = popupView.findViewById(R.id.ivCheck35Lite);
        ImageView ivCheck38Flash = popupView.findViewById(R.id.ivCheck38Flash);

        String currentModel = apiKeyManager.getModel();
        boolean is35Lite = ApiKeyManager.MODEL_35_FLASH_LITE.equals(currentModel);
        ivCheck35Lite.setVisibility(is35Lite ? View.VISIBLE : View.INVISIBLE);
        ivCheck38Flash.setVisibility(!is35Lite ? View.VISIBLE : View.INVISIBLE);

        // Clicking 3.5 Flash-Lite opens thinking submenu without changing the top pill yet
        popupView.findViewById(R.id.btnPopup35Lite).setOnClickListener(v -> {
            ivCheck35Lite.setVisibility(View.VISIBLE);
            ivCheck38Flash.setVisibility(View.INVISIBLE);
            showThinkingSubmenu(v, popupWindow, ApiKeyManager.MODEL_35_FLASH_LITE, "3.5 Flash-Lite");
        });

        // Clicking 3.8 Flash opens thinking submenu without changing the top pill yet
        popupView.findViewById(R.id.btnPopup38Flash).setOnClickListener(v -> {
            ivCheck35Lite.setVisibility(View.INVISIBLE);
            ivCheck38Flash.setVisibility(View.VISIBLE);
            showThinkingSubmenu(v, popupWindow, ApiKeyManager.MODEL_38_FLASH, "3.8 Flash");
        });

        // Google Search Grounding Toggle in Models Menu
        ImageView ivCheckWebSearch = popupView.findViewById(R.id.ivCheckWebSearch);
        View btnPopupWebSearch = popupView.findViewById(R.id.btnPopupWebSearch);
        if (ivCheckWebSearch != null && btnPopupWebSearch != null) {
            ivCheckWebSearch.setVisibility(apiKeyManager.isWebSearchEnabled() ? View.VISIBLE : View.INVISIBLE);
            btnPopupWebSearch.setOnClickListener(v -> {
                boolean next = !apiKeyManager.isWebSearchEnabled();
                apiKeyManager.setWebSearchEnabled(next);
                ivCheckWebSearch.setVisibility(next ? View.VISIBLE : View.INVISIBLE);
                Toast.makeText(this, next ? "Búsqueda web activada" : "Búsqueda web desactivada", Toast.LENGTH_SHORT).show();
            });
        }

        // Show directly below topBar anchored fixed to the right margin (14dp), so changes to btnModelSelector width don't move it
        View topBar = findViewById(R.id.topBar);
        View anchorView = (topBar != null) ? topBar : anchor;
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupWidth = popupView.getMeasuredWidth();
        int marginEndPx = (int) (14 * getResources().getDisplayMetrics().density);
        int xOffset = anchorView.getWidth() - popupWidth - marginEndPx;
        popupWindow.showAsDropDown(anchorView, xOffset, 8);
    }

    private void showThinkingSubmenu(View anchor, PopupWindow parentPopup, String targetModelId, String modelTitle) {
        View submenuView = View.inflate(this, R.layout.popup_thinking_submenu, null);
        PopupWindow submenuPopup = new PopupWindow(
                submenuView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        submenuPopup.setElevation(20f);

        TextView tvHeader = submenuView.findViewById(R.id.tvThinkingHeader);
        tvHeader.setText("Pensamiento • " + modelTitle);

        ImageView ivCheckHigh = submenuView.findViewById(R.id.ivCheckHigh);
        ImageView ivCheckMedium = submenuView.findViewById(R.id.ivCheckMedium);
        ImageView ivCheckLow = submenuView.findViewById(R.id.ivCheckLow);
        ImageView ivCheckMinimal = submenuView.findViewById(R.id.ivCheckMinimal);
        View btnThinkingMinimal = submenuView.findViewById(R.id.btnThinkingMinimal);

        // 3.8 Flash does not support minimal thinking, only 3.5 Flash-Lite supports it
        boolean supportsMinimal = ApiKeyManager.MODEL_35_FLASH_LITE.equals(targetModelId);
        btnThinkingMinimal.setVisibility(supportsMinimal ? View.VISIBLE : View.GONE);

        String currentLevel = apiKeyManager.getThinkingLevel();
        // If currentLevel was minimal and targetModel doesn't support it, default visual check to Medium/Low
        if (!supportsMinimal && ApiKeyManager.THINKING_MINIMAL.equals(currentLevel)) {
            currentLevel = ApiKeyManager.THINKING_LOW;
        }

        ivCheckHigh.setVisibility(ApiKeyManager.THINKING_HIGH.equals(currentLevel) ? View.VISIBLE : View.INVISIBLE);
        ivCheckMedium.setVisibility(ApiKeyManager.THINKING_MEDIUM.equals(currentLevel) ? View.VISIBLE : View.INVISIBLE);
        ivCheckLow.setVisibility(ApiKeyManager.THINKING_LOW.equals(currentLevel) ? View.VISIBLE : View.INVISIBLE);
        ivCheckMinimal.setVisibility(ApiKeyManager.THINKING_MINIMAL.equals(currentLevel) && supportsMinimal ? View.VISIBLE : View.INVISIBLE);

        View.OnClickListener clickListener = v -> {
            int id = v.getId();
            if (id == R.id.btnThinkingHigh) {
                apiKeyManager.setThinkingLevel(ApiKeyManager.THINKING_HIGH);
            } else if (id == R.id.btnThinkingMedium) {
                apiKeyManager.setThinkingLevel(ApiKeyManager.THINKING_MEDIUM);
            } else if (id == R.id.btnThinkingLow) {
                apiKeyManager.setThinkingLevel(ApiKeyManager.THINKING_LOW);
            } else if (id == R.id.btnThinkingMinimal) {
                apiKeyManager.setThinkingLevel(ApiKeyManager.THINKING_MINIMAL);
            }
            apiKeyManager.setModel(targetModelId);
            updateModelNameDisplay();
            submenuPopup.dismiss();
            if (parentPopup != null) {
                parentPopup.dismiss();
            }
        };

        submenuView.findViewById(R.id.btnThinkingHigh).setOnClickListener(clickListener);
        submenuView.findViewById(R.id.btnThinkingMedium).setOnClickListener(clickListener);
        submenuView.findViewById(R.id.btnThinkingLow).setOnClickListener(clickListener);
        submenuView.findViewById(R.id.btnThinkingMinimal).setOnClickListener(clickListener);

        // Position submenu to the LEFT of the models popup window
        submenuView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int subWidth = submenuView.getMeasuredWidth();
        
        // anchor is the row (btnPopup35Lite or btnPopup38Flash), which is 260dp wide
        // Negative xOffset places the submenu to the left of the entire row
        int xOffset = -(subWidth + 8);
        int yOffset = -anchor.getHeight();
        submenuPopup.showAsDropDown(anchor, xOffset, yOffset);
    }

    private void setupSuggestionCard(View card, String promptText) {
        card.setOnClickListener(v -> {
            animateClick(card);
            etPromptInput.setText(promptText);
            etPromptInput.setSelection(promptText.length());
            etPromptInput.requestFocus();

            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etPromptInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void animateClick(View view) {
        ObjectAnimator scaleXDown = ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.95f);
        ObjectAnimator scaleYDown = ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.95f);
        ObjectAnimator scaleXUp = ObjectAnimator.ofFloat(view, "scaleX", 0.95f, 1f);
        ObjectAnimator scaleYUp = ObjectAnimator.ofFloat(view, "scaleY", 0.95f, 1f);

        scaleXDown.setDuration(100);
        scaleYDown.setDuration(100);
        scaleXUp.setDuration(150);
        scaleYUp.setDuration(150);

        AnimatorSet setDown = new AnimatorSet();
        setDown.playTogether(scaleXDown, scaleYDown);
        AnimatorSet setUp = new AnimatorSet();
        setUp.playTogether(scaleXUp, scaleYUp);

        AnimatorSet full = new AnimatorSet();
        full.playSequentially(setDown, setUp);
        full.start();
    }

    private static String resolveMimeType(String rawMime, String displayName, byte[] data) {
        if (data != null && data.length >= 12) {
            if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
                return "image/jpeg";
            }
            if ((data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
                return "image/png";
            }
            if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' &&
                    data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
                return "image/webp";
            }
            if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') {
                return "image/gif";
            }
            if (data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F') {
                return "application/pdf";
            }
        }
        if (rawMime == null || rawMime.isEmpty() ||
                "application/octet-stream".equals(rawMime) ||
                "image/*".equals(rawMime) || "*/*".equals(rawMime)) {
            if (displayName != null) {
                String lower = displayName.toLowerCase();
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
                if (lower.endsWith(".png")) return "image/png";
                if (lower.endsWith(".webp")) return "image/webp";
                if (lower.endsWith(".gif")) return "image/gif";
                if (lower.endsWith(".pdf")) return "application/pdf";
            }
            return "image/jpeg";
        }
        return rawMime;
    }

    private void handleSelectedFile(Uri uri) {
        if (uri == null) return;
        try {
            pendingAttachmentUri = uri;
            String rawMime = getContentResolver().getType(uri);

            // Extract file display name
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

            // Read file into Base64 (limit to 10MB to avoid OOM)
            byte[] rawBytes;
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
                rawBytes = baos.toByteArray();
                pendingAttachmentBase64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP);
            }

            pendingAttachmentMime = resolveMimeType(rawMime, displayName, rawBytes);

            // Update UI preview chip
            layoutAttachmentPreview.setVisibility(View.VISIBLE);
            tvPreviewName.setText(pendingAttachmentName);
            if (pendingAttachmentMime.startsWith("image/")) {
                ImageThumbnailCache.loadInto(this, uri, ivPreviewThumb, 28, 28);
            } else {
                ivPreviewThumb.setImageResource(R.drawable.ic_file);
            }

            ivSendIcon.setImageResource(R.drawable.ic_send);
            ivSendIcon.setImageTintList(ColorStateList.valueOf(Color.parseColor("#4285F4")));

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
        if (layoutAttachmentPreview != null) {
            layoutAttachmentPreview.setVisibility(View.GONE);
        }
        if (etPromptInput != null && etPromptInput.getText().toString().trim().isEmpty() && !isGenerating) {
            ivSendIcon.setImageResource(R.drawable.ic_gemini_sparkle);
            ivSendIcon.setImageTintList(null);
        }
    }

    private String[] readAttachmentPayload(Uri uri, String rawMimeHint, String displayName) {
        if (uri == null) return null;
        try (InputStream is = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (is == null) return null;
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            byte[] bytes = baos.toByteArray();
            if (bytes.length > 0) {
                String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                String mime = resolveMimeType(rawMimeHint, displayName, bytes);
                return new String[]{base64, mime};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void ensureActiveChatAttachmentLoaded(int upToIndex) {
        if (activeChatAttachmentBase64 != null) return;
        for (int i = upToIndex - 1; i >= 0; i--) {
            ChatMessage m = chatMessages.get(i);
            if (m.hasAttachment() && m.getAttachmentUri() != null) {
                String[] payload = readAttachmentPayload(
                        Uri.parse(m.getAttachmentUri()),
                        m.getAttachmentMimeType(),
                        m.getAttachmentName()
                );
                if (payload != null) {
                    activeChatAttachmentBase64 = payload[0];
                    activeChatAttachmentMime = payload[1];
                    return;
                }
            }
        }
    }

    private String buildContextualPrompt(String currentPrompt, int historyEndExclusive) {
        if (historyEndExclusive <= 0) {
            return currentPrompt;
        }
        int start = Math.max(0, historyEndExclusive - 6);
        StringBuilder sb = new StringBuilder();
        sb.append("Contexto reciente de la conversación:\n");
        for (int i = start; i < historyEndExclusive; i++) {
            ChatMessage m = chatMessages.get(i);
            String txt = m.getText();
            if ((txt == null || txt.isEmpty()) && m.hasAttachment()) {
                txt = "[Imagen/archivo adjunto]";
            }
            if (txt != null && !txt.isEmpty()) {
                sb.append(m.isUser() ? "Usuario: " : "Gemini: ").append(txt).append("\n");
            }
        }
        sb.append("\nMensaje actual del usuario: ").append(currentPrompt.isEmpty() ? "Analiza la imagen adjunta." : currentPrompt);
        return sb.toString();
    }

    private void handleSendPrompt() {
        String prompt = etPromptInput.getText().toString().trim();
        boolean hasAttachment = (pendingAttachmentBase64 != null);

        if (prompt.isEmpty() && !hasAttachment) {
            Toast.makeText(this, "Escribe un mensaje o adjunta un archivo", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!apiKeyManager.hasApiKey()) {
            Toast.makeText(this, "Por favor configura tu API Key primero", Toast.LENGTH_LONG).show();
            ApiKeyDialog.show(this, apiKeyManager, () -> { updateStatusBadge(); updateModelNameDisplay(); });
            return;
        }

        // Hide keyboard
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etPromptInput.getWindowToken(), 0);
        }

        // Preserve attachment data before clearing state
        String attachedUriStr = (pendingAttachmentUri != null) ? pendingAttachmentUri.toString() : null;
        String attachedMime = pendingAttachmentMime;
        String attachedName = pendingAttachmentName;
        String attachedBase64 = pendingAttachmentBase64;

        // Clear input text and preview
        etPromptInput.setText("");
        clearAttachment();

        int priorCount = chatMessages.size();
        if (attachedBase64 != null) {
            activeChatAttachmentBase64 = attachedBase64;
            activeChatAttachmentMime = attachedMime;
        } else {
            ensureActiveChatAttachmentLoaded(priorCount);
        }

        String contextualPrompt = (attachedBase64 != null && priorCount == 0)
                ? prompt
                : buildContextualPrompt(prompt, priorCount);

        // Add user message with attachment
        ChatMessage userMessage = new ChatMessage(prompt, true);
        if (attachedUriStr != null) {
            userMessage.setAttachmentUri(attachedUriStr);
            userMessage.setAttachmentMimeType(attachedMime);
            userMessage.setAttachmentName(attachedName);
        }
        chatMessages.add(userMessage);
        chatAdapter.notifyItemInserted(chatMessages.size() - 1);

        // Add Gemini generating placeholder message
        String model = apiKeyManager.getModel();
        String thinkingLevel = apiKeyManager.getThinkingLevel();
        String modelLabel = ApiKeyManager.getModelDisplayName(model);

        ChatMessage geminiPlaceholder = new ChatMessage("", false, true, thinkingLevel, modelLabel);
        chatMessages.add(geminiPlaceholder);
        int geminiPos = chatMessages.size() - 1;
        chatAdapter.notifyItemInserted(geminiPos);

        // Smooth transition from Hero screen to active chat timeline
        transitionToChat();
        rvChatMessages.scrollToPosition(geminiPos);

        final String effectiveBase64 = (attachedBase64 != null) ? attachedBase64 : activeChatAttachmentBase64;
        final String effectiveMime = (attachedBase64 != null) ? attachedMime : activeChatAttachmentMime;

        // Defer disk I/O so the transition animation starts immediately without frame drops
        rvChatMessages.post(() -> {
            if (currentChatId == null) {
                String title = prompt.isEmpty() ? ("Archivo: " + attachedName) : (prompt.length() > 36 ? prompt.substring(0, 34) + "..." : prompt);
                currentChatId = dbHelper.createChat(title);
                loadChatSessions();
            }
            dbHelper.saveMessage(currentChatId, userMessage);
            dbHelper.saveMessage(currentChatId, geminiPlaceholder);
            sendGeminiRequestForMessage(geminiPos, contextualPrompt, effectiveBase64, effectiveMime, model, thinkingLevel, modelLabel);
        });
    }

    private void sendGeminiRequestForMessage(
            int geminiPos,
            String contextualPrompt,
            String base64Data,
            String mimeType,
            String model,
            String thinkingLevel,
            String modelLabel) {
        activeGeneratingPosition = geminiPos;
        setGeneratingState(true);
        if (geminiPos >= 0 && geminiPos < chatMessages.size()) {
            chatMessages.get(geminiPos).setSourcesJson(null);
        }
        if (currentChatId != null) {
            dbHelper.updateMessageSourcesAtPosition(currentChatId, geminiPos, null);
        }
        final long requestStartMs = System.currentTimeMillis();

        String apiKey = apiKeyManager.getApiKey();
        String systemInstruction = DeviceActionExecutor.buildFullSystemInstruction(this, apiKeyManager.getSystemPrompt());
        boolean enableWebSearch = apiKeyManager.isWebSearchEnabled();

        GeminiApiClient.ApiCallback callback = new GeminiApiClient.ApiCallback() {
            @Override
            public void onSourcesReady(String sourcesJson) {
                if (geminiPos >= 0 && geminiPos < chatMessages.size()) {
                    chatMessages.get(geminiPos).setSourcesJson(sourcesJson);
                }
                if (currentChatId != null) {
                    dbHelper.updateMessageSourcesAtPosition(currentChatId, geminiPos, sourcesJson);
                }
            }

            @Override
            public void onStatusUpdate(String statusText) {
                if (geminiPos >= 0 && geminiPos < chatMessages.size()) {
                    String localized = statusText;
                    if ("Buscando en la web...".equals(statusText)) {
                        localized = getString(R.string.status_searching_web);
                    } else if ("Pensando...".equals(statusText)) {
                        localized = getString(R.string.status_thinking);
                    }
                    chatMessages.get(geminiPos).setStatusLabel(localized);
                    chatAdapter.notifyItemChanged(geminiPos, ChatAdapter.PAYLOAD_TEXT_STREAM);
                }
            }

            @Override
            public void onSuccess(String responseText) {
                if (geminiPos < 0 || geminiPos >= chatMessages.size()) {
                    setGeneratingState(false);
                    return;
                }
                chatMessages.get(geminiPos).setStatusLabel(
                        ChatMessage.formatThinkingDuration(MainActivity.this, System.currentTimeMillis() - requestStartMs));
                String cleanText = DeviceActionExecutor.processAndExecuteActions(MainActivity.this, responseText);
                streamTypewriterResponse(geminiPos, cleanText, thinkingLevel, modelLabel);
            }

            @Override
            public void onError(String errorMessage) {
                setGeneratingState(false);
                activeGeneratingPosition = -1;
                if (geminiPos >= 0 && geminiPos < chatMessages.size()) {
                    ChatMessage placeholder = chatMessages.get(geminiPos);
                    placeholder.setGenerating(false);
                    placeholder.setStatusLabel(null);
                    placeholder.setText(errorMessage);
                    chatAdapter.notifyItemChanged(geminiPos);
                    if (currentChatId != null) {
                        dbHelper.updateMessageAtPosition(currentChatId, geminiPos, errorMessage, thinkingLevel, modelLabel);
                    }
                }
            }
        };

        if (base64Data != null && mimeType != null) {
            apiClient.sendPromptWithFile(apiKey, model, thinkingLevel, contextualPrompt, mimeType, base64Data, systemInstruction, enableWebSearch, callback);
        } else {
            apiClient.sendPrompt(apiKey, model, thinkingLevel, contextualPrompt, systemInstruction, enableWebSearch, callback);
        }
    }

    private void streamTypewriterResponse(int position, String fullText, String thinkingLevel, String modelLabel) {
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
                    // Partial payload update: keep current viewport position completely still while streaming
                    chatAdapter.notifyItemChanged(position, ChatAdapter.PAYLOAD_TEXT_STREAM);
                    typewriterHandler.postDelayed(this, 20);
                } else {
                    msg.setText(fullText);
                    msg.setGenerating(false);
                    // Full update when complete to render markdown and reveal action buttons without jumping scroll
                    chatAdapter.notifyItemChanged(position);
                    activeGeneratingPosition = -1;
                    setGeneratingState(false);

                    // Persist completed Gemini response in local SQLite database
                    if (currentChatId != null) {
                        dbHelper.updateMessageAtPosition(currentChatId, position, fullText, thinkingLevel, modelLabel);
                        loadChatSessions(); // Update recent timestamp and ordering
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
        int targetPos = (activeGeneratingPosition >= 0 && activeGeneratingPosition < chatMessages.size())
                ? activeGeneratingPosition
                : chatMessages.size() - 1;
        activeGeneratingPosition = -1;
        setGeneratingState(false);
        if (targetPos >= 0 && targetPos < chatMessages.size()) {
            ChatMessage target = chatMessages.get(targetPos);
            if (target.isGenerating()) {
                target.setGenerating(false);
                String stoppedText = target.getText() + " " + getString(R.string.generation_stopped_suffix);
                target.setText(stoppedText);
                chatAdapter.notifyItemChanged(targetPos);
                if (currentChatId != null) {
                    dbHelper.updateMessageAtPosition(currentChatId, targetPos, stoppedText, target.getThinkingLevel(), target.getModelName());
                }
            }
        }
        Toast.makeText(this, getString(R.string.generation_cancelled), Toast.LENGTH_SHORT).show();
    }

    private void setGeneratingState(boolean generating) {
        this.isGenerating = generating;
        if (generating) {
            ivSendIcon.setImageResource(R.drawable.ic_stop);
            ivSendIcon.setImageTintList(ColorStateList.valueOf(Color.parseColor("#E3E3E3")));
        } else {
            String text = etPromptInput.getText().toString().trim();
            if (text.isEmpty()) {
                ivSendIcon.setImageResource(R.drawable.ic_gemini_sparkle);
                ivSendIcon.setImageTintList(null);
            } else {
                ivSendIcon.setImageResource(R.drawable.ic_send);
                ivSendIcon.setImageTintList(ColorStateList.valueOf(Color.parseColor("#4285F4")));
            }
        }
    }

    private android.animation.ValueAnimator starTransitionAnimator;

    private void animateShaderStar(float fromScale, float toScale, float fromGlow, float toGlow, long durationMs) {
        if (starTransitionAnimator != null) {
            starTransitionAnimator.cancel();
        }
        starTransitionAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        starTransitionAnimator.setDuration(durationMs);
        starTransitionAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        starTransitionAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            GeminiNative.setStarScale(fromScale + (toScale - fromScale) * t);
            GeminiNative.setGlowIntensity(fromGlow + (toGlow - fromGlow) * t);
            if (glSurfaceView != null) {
                glSurfaceView.requestRender();
            }
        });
        starTransitionAnimator.start();
    }

    private void transitionToChat() {
        if (heroContainer.getVisibility() == View.VISIBLE) {
            float density = getResources().getDisplayMetrics().density;
            android.view.animation.Interpolator easeOut = new android.view.animation.DecelerateInterpolator(1.6f);

            heroContainer.animate().cancel();
            rvChatMessages.animate().cancel();
            btnNewChat.animate().cancel();

            // Prepare chat view underneath so individual items animate from bottom to top
            rvChatMessages.setAlpha(1f);
            rvChatMessages.setTranslationY(0f);
            rvChatMessages.setVisibility(View.VISIBLE);

            // Fade & scale in the New Chat button
            btnNewChat.setAlpha(0f);
            btnNewChat.setScaleX(0.85f);
            btnNewChat.setScaleY(0.85f);
            btnNewChat.setVisibility(View.VISIBLE);
            btnNewChat.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(easeOut)
                    .start();

            // Fade & glide out the Hero greeting
            heroContainer.animate()
                    .alpha(0f)
                    .translationY(-20f * density)
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(200)
                    .setInterpolator(easeOut)
                    .withEndAction(() -> {
                        heroContainer.setVisibility(View.GONE);
                        heroContainer.setAlpha(1f);
                        heroContainer.setTranslationY(0f);
                        heroContainer.setScaleX(1f);
                        heroContainer.setScaleY(1f);
                    })
                    .start();

            // Smoothly shrink star shader during transition
            animateShaderStar(1.0f, 0.0f, 1.0f, 0.35f, 260);
        }
    }

    private void resetToHeroScreen() {
        if (isGenerating) {
            cancelCurrentGeneration();
        }
        currentChatId = null;
        activeChatAttachmentBase64 = null;
        activeChatAttachmentMime = null;
        if (historyAdapter != null) {
            historyAdapter.setSelectedChatId(null);
        }
        chatMessages.clear();
        chatAdapter.notifyDataSetChanged();

        float density = getResources().getDisplayMetrics().density;
        android.view.animation.Interpolator easeOut = new android.view.animation.DecelerateInterpolator(1.6f);

        heroContainer.animate().cancel();
        rvChatMessages.animate().cancel();
        btnNewChat.animate().cancel();

        rvChatMessages.setVisibility(View.GONE);
        rvChatMessages.setAlpha(1f);
        rvChatMessages.setTranslationY(0f);
        btnNewChat.setVisibility(View.GONE);

        heroContainer.setAlpha(0f);
        heroContainer.setTranslationY(14f * density);
        heroContainer.setScaleX(0.97f);
        heroContainer.setScaleY(0.97f);
        heroContainer.setVisibility(View.VISIBLE);
        heroContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(230)
                .setInterpolator(easeOut)
                .start();

        // Smoothly restore star in C++ shader
        animateShaderStar(0.0f, 1.0f, 0.35f, 1.0f, 260);
    }

    @Override
    public void onRegenerate(int position) {
        if (isGenerating) return;
        if (position <= 0 || position >= chatMessages.size()) return;

        // Locate preceding user prompt and replace the target Gemini response in-place
        int userIndex = position - 1;
        while (userIndex >= 0 && !chatMessages.get(userIndex).isUser()) {
            userIndex--;
        }
        if (userIndex < 0) return;

        if (!apiKeyManager.hasApiKey()) {
            Toast.makeText(this, "Por favor configura tu API Key primero", Toast.LENGTH_LONG).show();
            ApiKeyDialog.show(this, apiKeyManager, () -> { updateStatusBadge(); updateModelNameDisplay(); });
            return;
        }

        ChatMessage userMsg = chatMessages.get(userIndex);
        String prompt = (userMsg.getText() != null) ? userMsg.getText() : "";

        String attachedBase64 = null;
        String attachedMime = null;
        if (userMsg.hasAttachment() && userMsg.getAttachmentUri() != null) {
            String[] payload = readAttachmentPayload(
                    Uri.parse(userMsg.getAttachmentUri()),
                    userMsg.getAttachmentMimeType(),
                    userMsg.getAttachmentName()
            );
            if (payload != null) {
                attachedBase64 = payload[0];
                attachedMime = payload[1];
            }
        } else {
            ensureActiveChatAttachmentLoaded(userIndex);
            attachedBase64 = activeChatAttachmentBase64;
            attachedMime = activeChatAttachmentMime;
        }

        String contextualPrompt = (attachedBase64 != null && userIndex == 0)
                ? prompt
                : buildContextualPrompt(prompt, userIndex);

        String model = apiKeyManager.getModel();
        String thinkingLevel = apiKeyManager.getThinkingLevel();
        String modelLabel = ApiKeyManager.getModelDisplayName(model);

        ChatMessage targetResponse = chatMessages.get(position);
        targetResponse.setText("");
        targetResponse.setGenerating(true);
        targetResponse.setStatusLabel(null);
        targetResponse.setThinkingLevel(thinkingLevel);
        targetResponse.setModelName(modelLabel);
        chatAdapter.notifyItemChanged(position);

        if (currentChatId != null) {
            dbHelper.updateMessageAtPosition(currentChatId, position, "", thinkingLevel, modelLabel);
        }

        sendGeminiRequestForMessage(position, contextualPrompt, attachedBase64, attachedMime, model, thinkingLevel, modelLabel);
    }

    private void updateModelNameDisplay() {
        tvModelName.setText(apiKeyManager.getFormattedModelLabel());
    }

    private void updateStatusBadge() {
        // Status indicator is reflected through model display
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (heroContainer != null && heroContainer.getVisibility() != View.VISIBLE) {
            resetToHeroScreen();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glSurfaceView != null) {
            glSurfaceView.onResume();
        }
        // Ensure star scale stays 0.0f if chat is open, or 1.0f if on hero screen
        if (rvChatMessages != null && rvChatMessages.getVisibility() == View.VISIBLE) {
            GeminiNative.setStarScale(0.0f);
            GeminiNative.setGlowIntensity(0.35f);
        } else {
            GeminiNative.setStarScale(1.0f);
            GeminiNative.setGlowIntensity(1.0f);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (glSurfaceView != null) {
            glSurfaceView.onPause();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            WebSearchEngine.trim();
        }
    }
}
