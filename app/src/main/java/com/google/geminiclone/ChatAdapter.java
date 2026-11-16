package com.google.geminiclone;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_USER = 1;
    public static final int TYPE_GEMINI = 2;
    public static final String PAYLOAD_TEXT_STREAM = "PAYLOAD_TEXT_STREAM";

    public interface OnChatActionListener {
        void onRegenerate(int position);
    }

    private final Context context;
    private final List<ChatMessage> messages;
    private final OnChatActionListener actionListener;

    public ChatAdapter(Context context, List<ChatMessage> messages, OnChatActionListener actionListener) {
        this.context = context;
        this.messages = messages;
        this.actionListener = actionListener;
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).isUser() ? TYPE_USER : TYPE_GEMINI;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_USER) {
            View view = inflater.inflate(R.layout.item_chat_user, parent, false);
            return new UserViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_chat_gemini, parent, false);
            return new GeminiViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && holder instanceof GeminiViewHolder) {
            // Partial in-place update: zero cross-fade, zero flicker, zero overlapping
            ChatMessage message = messages.get(position);
            ((GeminiViewHolder) holder).updateStreamingText(message);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = messages.get(position);
        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).bind(message);
        } else if (holder instanceof GeminiViewHolder) {
            ((GeminiViewHolder) holder).bind(message, position);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof GeminiViewHolder) {
            ((GeminiViewHolder) holder).stopSparkleAnimation();
        }
    }

    // --- User ViewHolder ---
    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView tvUserMessage;
        ImageView ivUserAttachment;
        LinearLayout layoutUserFile;
        TextView tvUserFileName;

        UserViewHolder(@NonNull View itemView) {
            super(itemView);
            tvUserMessage = itemView.findViewById(R.id.tvUserMessage);
            ivUserAttachment = itemView.findViewById(R.id.ivUserAttachment);
            layoutUserFile = itemView.findViewById(R.id.layoutUserFile);
            tvUserFileName = itemView.findViewById(R.id.tvUserFileName);
        }

        void bind(ChatMessage message) {
            if (message.hasAttachment()) {
                String mime = message.getAttachmentMimeType();
                if (mime != null && mime.startsWith("image/")) {
                    ivUserAttachment.setVisibility(View.VISIBLE);
                    layoutUserFile.setVisibility(View.GONE);
                    try {
                        android.net.Uri uri = android.net.Uri.parse(message.getAttachmentUri());
                        ImageThumbnailCache.loadInto(itemView.getContext(), uri, ivUserAttachment, 220, 160);
                        ivUserAttachment.setOnClickListener(v -> showImageViewer(v.getContext(), uri));
                    } catch (Exception e) {
                        ivUserAttachment.setImageResource(R.drawable.ic_image);
                        ivUserAttachment.setOnClickListener(null);
                    }
                } else {
                    ivUserAttachment.setVisibility(View.GONE);
                    layoutUserFile.setVisibility(View.VISIBLE);
                    String name = message.getAttachmentName();
                    tvUserFileName.setText(name != null ? name : "Archivo adjunto");
                }
            } else {
                ivUserAttachment.setVisibility(View.GONE);
                layoutUserFile.setVisibility(View.GONE);
            }

            if (message.getText() != null && !message.getText().trim().isEmpty()) {
                tvUserMessage.setVisibility(View.VISIBLE);
                tvUserMessage.setText(message.getParsedMarkdownText());
            } else {
                tvUserMessage.setVisibility(View.GONE);
            }
        }

        private static void showImageViewer(Context context, android.net.Uri uri) {
            android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
            float density = context.getResources().getDisplayMetrics().density;
            int screenH = context.getResources().getDisplayMetrics().heightPixels;
            int screenW = context.getResources().getDisplayMetrics().widthPixels;

            android.widget.FrameLayout scrim = new android.widget.FrameLayout(context);
            scrim.setBackgroundColor(android.graphics.Color.parseColor("#88000000"));
            scrim.setOnClickListener(v -> dialog.dismiss());

            android.widget.FrameLayout card = new android.widget.FrameLayout(context);
            card.setBackgroundResource(R.drawable.bg_assistant_floating_card);
            card.setClipChildren(true);
            card.setClipToOutline(true);
            int pad = (int) (10 * density);
            card.setPadding(pad, pad, pad, pad);
            android.widget.FrameLayout.LayoutParams cardLp = new android.widget.FrameLayout.LayoutParams(
                    (int) (screenW * 0.88f),
                    (int) (screenH * 0.62f),
                    android.view.Gravity.CENTER);
            card.setLayoutParams(cardLp);

            ImageView fullImage = new ImageView(context);
            fullImage.setScaleType(ImageView.ScaleType.MATRIX);
            fullImage.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            ImageThumbnailCache.loadInto(context, uri, fullImage, 1080, 1440);

            final android.graphics.Matrix matrix = new android.graphics.Matrix();
            final float[] mVals = new float[9];
            final float[] baseScale = {1.0f};
            final boolean[] initialized = {false};
            final float[] lastTouch = {0f, 0f};
            final int[] activePointerId = {-1};

            Runnable initFitMatrix = () -> {
                android.graphics.drawable.Drawable d = fullImage.getDrawable();
                int vw = fullImage.getWidth();
                int vh = fullImage.getHeight();
                if (d == null || vw <= 0 || vh <= 0) return;
                int dw = d.getIntrinsicWidth();
                int dh = d.getIntrinsicHeight();
                if (dw <= 0 || dh <= 0) return;
                float s = Math.min((float) vw / dw, (float) vh / dh);
                baseScale[0] = s;
                initialized[0] = true;
                matrix.reset();
                matrix.postScale(s, s);
                matrix.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f);
                fullImage.setImageMatrix(matrix);
            };
            fullImage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (!initialized[0]) {
                    initFitMatrix.run();
                }
            });
            fullImage.post(initFitMatrix);

            Runnable clampMatrix = () -> {
                android.graphics.drawable.Drawable d = fullImage.getDrawable();
                if (d == null) return;
                matrix.getValues(mVals);
                float curScale = mVals[android.graphics.Matrix.MSCALE_X];
                float tx = mVals[android.graphics.Matrix.MTRANS_X];
                float ty = mVals[android.graphics.Matrix.MTRANS_Y];
                float contentW = d.getIntrinsicWidth() * curScale;
                float contentH = d.getIntrinsicHeight() * curScale;
                int vw = fullImage.getWidth();
                int vh = fullImage.getHeight();

                float dx = 0f;
                float dy = 0f;
                if (contentW <= vw) {
                    dx = (vw - contentW) / 2f - tx;
                } else if (tx > 0f) {
                    dx = -tx;
                } else if (tx + contentW < vw) {
                    dx = vw - (tx + contentW);
                }

                if (contentH <= vh) {
                    dy = (vh - contentH) / 2f - ty;
                } else if (ty > 0f) {
                    dy = -ty;
                } else if (ty + contentH < vh) {
                    dy = vh - (ty + contentH);
                }

                if (dx != 0f || dy != 0f) {
                    matrix.postTranslate(dx, dy);
                }
                fullImage.setImageMatrix(matrix);
            };

            android.view.ScaleGestureDetector scaleDetector = new android.view.ScaleGestureDetector(
                    context,
                    new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(android.view.ScaleGestureDetector detector) {
                            matrix.getValues(mVals);
                            float curScale = mVals[android.graphics.Matrix.MSCALE_X];
                            float minScale = baseScale[0];
                            float maxScale = baseScale[0] * 4.0f;
                            float targetScale = Math.max(minScale, Math.min(maxScale, curScale * detector.getScaleFactor()));
                            float factor = targetScale / curScale;
                            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                            clampMatrix.run();
                            return true;
                        }
                    });

            android.view.GestureDetector tapDetector = new android.view.GestureDetector(
                    context,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(android.view.MotionEvent e) {
                            matrix.getValues(mVals);
                            float curScale = mVals[android.graphics.Matrix.MSCALE_X];
                            if (curScale > baseScale[0] * 1.4f) {
                                initFitMatrix.run();
                            } else {
                                float factor = (baseScale[0] * 2.5f) / curScale;
                                matrix.postScale(factor, factor, e.getX(), e.getY());
                                clampMatrix.run();
                            }
                            return true;
                        }

                        @Override
                        public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                            matrix.getValues(mVals);
                            if (mVals[android.graphics.Matrix.MSCALE_X] <= baseScale[0] * 1.05f) {
                                dialog.dismiss();
                            }
                            return true;
                        }
                    });

            fullImage.setOnTouchListener((v, event) -> {
                if (!initialized[0]) {
                    initFitMatrix.run();
                }
                scaleDetector.onTouchEvent(event);
                tapDetector.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        activePointerId[0] = event.getPointerId(0);
                        lastTouch[0] = event.getX(0);
                        lastTouch[1] = event.getY(0);
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (!scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                            int idx = event.findPointerIndex(activePointerId[0]);
                            if (idx >= 0) {
                                float x = event.getX(idx);
                                float y = event.getY(idx);
                                matrix.postTranslate(x - lastTouch[0], y - lastTouch[1]);
                                clampMatrix.run();
                                lastTouch[0] = x;
                                lastTouch[1] = y;
                            }
                        }
                        break;
                    case android.view.MotionEvent.ACTION_POINTER_UP:
                        int pointerIndex = event.getActionIndex();
                        int pointerId = event.getPointerId(pointerIndex);
                        if (pointerId == activePointerId[0]) {
                            int newIndex = (pointerIndex == 0) ? 1 : 0;
                            activePointerId[0] = event.getPointerId(newIndex);
                            lastTouch[0] = event.getX(newIndex);
                            lastTouch[1] = event.getY(newIndex);
                        }
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        activePointerId[0] = -1;
                        break;
                }
                return true;
            });

            card.addView(fullImage);

            scrim.addView(card);
            dialog.setContentView(scrim);
            dialog.show();
        }
    }

    // --- Gemini ViewHolder ---
    class GeminiViewHolder extends RecyclerView.ViewHolder {
        ImageView ivGeminiSparkle;
        TextView tvGeminiModelBadge;
        LinearLayout layoutThinking;
        View pbThinking;
        TextView tvThinkingLabel;
        LinearLayout containerGeminiContent;
        TextView tvGeminiMessage;
        LinearLayout layoutActions;
        ImageButton btnCopy, btnRegenerate, btnShare, btnSources;
        ObjectAnimator pulseAnimator;

        GeminiViewHolder(@NonNull View itemView) {
            super(itemView);
            ivGeminiSparkle = itemView.findViewById(R.id.ivGeminiSparkle);
            tvGeminiModelBadge = itemView.findViewById(R.id.tvGeminiModelBadge);
            layoutThinking = itemView.findViewById(R.id.layoutThinking);
            pbThinking = itemView.findViewById(R.id.pbThinking);
            tvThinkingLabel = itemView.findViewById(R.id.tvThinkingLabel);
            containerGeminiContent = itemView.findViewById(R.id.containerGeminiContent);
            tvGeminiMessage = itemView.findViewById(R.id.tvGeminiMessage);
            layoutActions = itemView.findViewById(R.id.layoutActions);

            btnCopy = itemView.findViewById(R.id.btnCopy);
            btnSources = itemView.findViewById(R.id.btnSources);
            btnRegenerate = itemView.findViewById(R.id.btnRegenerate);
            btnShare = itemView.findViewById(R.id.btnShare);
        }

        private void showSourcesDialog(String sourcesJson) {
            if (sourcesJson == null) return;
            try {
                org.json.JSONArray arr = new org.json.JSONArray(sourcesJson);
                if (arr.length() == 0) return;

                android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
                float density = context.getResources().getDisplayMetrics().density;
                int screenW = context.getResources().getDisplayMetrics().widthPixels;
                int screenH = context.getResources().getDisplayMetrics().heightPixels;

                android.widget.FrameLayout scrim = new android.widget.FrameLayout(context);
                scrim.setBackgroundColor(android.graphics.Color.parseColor("#88000000"));
                scrim.setOnClickListener(v -> dialog.dismiss());

                LinearLayout card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackgroundResource(R.drawable.bg_assistant_floating_card);
                card.setClipChildren(true);
                card.setClipToOutline(true);
                card.setClickable(true);
                int pad = (int) (18 * density);
                card.setPadding(pad, (int) (16 * density), pad, (int) (16 * density));
                android.widget.FrameLayout.LayoutParams cardLp = new android.widget.FrameLayout.LayoutParams(
                        (int) (screenW * 0.90f),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER);
                card.setLayoutParams(cardLp);

                // Header
                LinearLayout header = new LinearLayout(context);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(android.view.Gravity.CENTER_VERTICAL);
                header.setPadding(0, 0, 0, (int) (12 * density));

                ImageView ivIcon = new ImageView(context);
                ivIcon.setImageResource(R.drawable.ic_gemini_sparkle);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams((int) (20 * density), (int) (20 * density));
                header.addView(ivIcon, iconLp);

                TextView tvTitle = new TextView(context);
                tvTitle.setText("Fuentes consultadas");
                tvTitle.setTextColor(android.graphics.Color.parseColor("#E3E3E3"));
                tvTitle.setTextSize(16f);
                tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                titleLp.setMarginStart((int) (10 * density));
                header.addView(tvTitle, titleLp);

                ImageButton btnClose = new ImageButton(context);
                btnClose.setImageResource(R.drawable.ic_close);
                btnClose.setColorFilter(android.graphics.Color.parseColor("#9AA0A6"));
                android.util.TypedValue outValue = new android.util.TypedValue();
                context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true);
                btnClose.setBackgroundResource(outValue.resourceId);
                btnClose.setOnClickListener(v -> dialog.dismiss());
                header.addView(btnClose, new LinearLayout.LayoutParams((int) (32 * density), (int) (32 * density)));

                card.addView(header);

                View divider = new View(context);
                divider.setBackgroundColor(android.graphics.Color.parseColor("#2E3035"));
                LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density));
                divLp.bottomMargin = (int) (12 * density);
                card.addView(divider, divLp);

                android.widget.ScrollView scroll = new android.widget.ScrollView(context);
                scroll.setVerticalScrollBarEnabled(false);
                LinearLayout list = new LinearLayout(context);
                list.setOrientation(LinearLayout.VERTICAL);

                android.util.TypedValue itemRipple = new android.util.TypedValue();
                context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, itemRipple, true);

                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject obj = arr.getJSONObject(i);
                    String title = obj.optString("title", "Fuente");
                    String url = obj.optString("url", "");
                    String host = "";
                    try {
                        host = android.net.Uri.parse(url).getHost();
                        if ("www.google.com".equalsIgnoreCase(host)) host = "";
                    } catch (Exception ignored) {}

                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    int rPad = (int) (12 * density);
                    row.setPadding(rPad, (int) (10 * density), rPad, (int) (10 * density));

                    android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
                    rowBg.setColor(android.graphics.Color.parseColor("#282A2E"));
                    rowBg.setCornerRadius(14 * density);
                    rowBg.setStroke((int) Math.max(1, density), android.graphics.Color.parseColor("#383A40"));
                    row.setBackground(rowBg);
                    row.setForeground(androidx.core.content.ContextCompat.getDrawable(context, itemRipple.resourceId));
                    row.setClipToOutline(true);

                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (i < arr.length() - 1) {
                        rowLp.bottomMargin = (int) (8 * density);
                    }

                    ImageView ivDoc = new ImageView(context);
                    ivDoc.setImageResource(R.drawable.ic_sources);
                    ivDoc.setColorFilter(android.graphics.Color.parseColor("#8AB4F8"));
                    LinearLayout.LayoutParams docLp = new LinearLayout.LayoutParams((int) (20 * density), (int) (20 * density));
                    docLp.setMarginEnd((int) (12 * density));
                    row.addView(ivDoc, docLp);

                    LinearLayout textCol = new LinearLayout(context);
                    textCol.setOrientation(LinearLayout.VERTICAL);

                    TextView tvItemTitle = new TextView(context);
                    tvItemTitle.setText(title);
                    tvItemTitle.setTextColor(android.graphics.Color.parseColor("#E3E3E3"));
                    tvItemTitle.setTextSize(14f);
                    tvItemTitle.setMaxLines(2);
                    tvItemTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    textCol.addView(tvItemTitle);

                    TextView tvSub = new TextView(context);
                    tvSub.setText((host != null && !host.isEmpty()) ? host : "Abrir enlace web");
                    tvSub.setTextColor(android.graphics.Color.parseColor("#9AA0A6"));
                    tvSub.setTextSize(12f);
                    tvSub.setMaxLines(1);
                    tvSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    tvSub.setPadding(0, (int) (2 * density), 0, 0);
                    textCol.addView(tvSub);

                    row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    ImageView ivArrow = new ImageView(context);
                    ivArrow.setImageResource(R.drawable.ic_chevron_right);
                    ivArrow.setColorFilter(android.graphics.Color.parseColor("#9AA0A6"));
                    LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams((int) (18 * density), (int) (18 * density));
                    arrowLp.setMarginStart((int) (8 * density));
                    row.addView(ivArrow, arrowLp);

                    row.setOnClickListener(v -> {
                        if (url != null && !url.isEmpty()) {
                            dialog.dismiss();
                            context.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                        }
                    });
                    list.addView(row, rowLp);
                }

                scroll.addView(list);
                LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                card.addView(scroll, scrollLp);
                card.post(() -> {
                    int maxH = (int) (screenH * 0.56f);
                    if (scroll.getHeight() > maxH) {
                        scroll.getLayoutParams().height = maxH;
                        scroll.requestLayout();
                    }
                });

                scrim.addView(card);
                dialog.setContentView(scrim);
                dialog.show();
            } catch (Exception ignored) {}
        }

        void updateStreamingText(ChatMessage message) {
            updateThinkingIndicator(message);
            updateSourcesButton(message);
            if (message.getText() != null && !message.getText().isEmpty()) {
                stopSparkleAnimation();
            }
            GeminiMessageRenderer.render(context, containerGeminiContent, tvGeminiMessage, message);
        }

        private void updateSourcesButton(ChatMessage message) {
            if (btnSources == null) return;
            String sourcesJson = message.getSourcesJson();
            boolean hasSources = false;
            if (sourcesJson != null && sourcesJson.length() > 2) {
                try {
                    hasSources = new org.json.JSONArray(sourcesJson).length() > 0;
                } catch (Exception ignored) {}
            }
            btnSources.setVisibility(hasSources ? View.VISIBLE : View.GONE);
            btnSources.setOnClickListener(v -> showSourcesDialog(sourcesJson));
        }

        private void updateThinkingIndicator(ChatMessage message) {
            String status = message.getStatusLabel();
            boolean hasText = message.getText() != null && !message.getText().isEmpty();

            if (message.isGenerating() && !hasText) {
                layoutThinking.setVisibility(View.VISIBLE);
                layoutThinking.setBackgroundResource(R.drawable.bg_gemini_thinking_pill);
                if (pbThinking != null) pbThinking.setVisibility(View.VISIBLE);
                tvThinkingLabel.setText(status != null && !status.isEmpty() ? status : "Pensando...");
                tvThinkingLabel.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.gemini_cyan));
                tvThinkingLabel.setAlpha(1.0f);
            } else if (status != null && status.startsWith("Pensó por ")) {
                layoutThinking.setVisibility(View.VISIBLE);
                layoutThinking.setBackground(null);
                layoutThinking.setPadding(0, 0, 0, 0);
                if (pbThinking != null) pbThinking.setVisibility(View.GONE);
                tvThinkingLabel.setText(status);
                tvThinkingLabel.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_hint));
                tvThinkingLabel.setAlpha(0.75f);
            } else {
                layoutThinking.setVisibility(View.GONE);
            }
        }

        void stopSparkleAnimation() {
            if (pulseAnimator != null) {
                pulseAnimator.cancel();
                pulseAnimator = null;
            }
            ivGeminiSparkle.setScaleX(1.0f);
            ivGeminiSparkle.setScaleY(1.0f);
        }

        void bind(ChatMessage message, int position) {
            tvGeminiModelBadge.setVisibility(View.GONE);
            updateThinkingIndicator(message);

            if (message.isGenerating()) {
                if (pulseAnimator == null) {
                    pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                            ivGeminiSparkle,
                            PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.25f, 1.0f),
                            PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.25f, 1.0f)
                    );
                    pulseAnimator.setDuration(1200);
                    pulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
                    pulseAnimator.start();
                }

                layoutActions.setVisibility(View.GONE);
                GeminiMessageRenderer.render(context, containerGeminiContent, tvGeminiMessage, message);
            } else {
                stopSparkleAnimation();
                layoutActions.setVisibility(View.VISIBLE);

                GeminiMessageRenderer.render(context, containerGeminiContent, tvGeminiMessage, message);
            }

            // Action Listeners
            btnCopy.setOnClickListener(v -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    android.content.ClipData clip = android.content.ClipData.newPlainText("Gemini", message.getText());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context, "Copiado al portapapeles", Toast.LENGTH_SHORT).show();
                }
            });

            updateSourcesButton(message);

            btnRegenerate.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onRegenerate(position);
                }
            });

            btnShare.setOnClickListener(v -> {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, message.getText());
                context.startActivity(Intent.createChooser(shareIntent, "Compartir respuesta de Gemini"));
            });
        }
    }
}
