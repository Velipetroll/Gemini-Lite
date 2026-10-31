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
                    } catch (Exception e) {
                        ivUserAttachment.setImageResource(R.drawable.ic_image);
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
    }

    // --- Gemini ViewHolder ---
    class GeminiViewHolder extends RecyclerView.ViewHolder {
        ImageView ivGeminiSparkle;
        TextView tvGeminiModelBadge;
        LinearLayout layoutThinking;
        TextView tvThinkingLabel;
        LinearLayout containerGeminiContent;
        TextView tvGeminiMessage;
        LinearLayout layoutActions;
        ImageButton btnCopy, btnLike, btnDislike, btnRegenerate, btnShare;
        ObjectAnimator pulseAnimator;

        GeminiViewHolder(@NonNull View itemView) {
            super(itemView);
            ivGeminiSparkle = itemView.findViewById(R.id.ivGeminiSparkle);
            tvGeminiModelBadge = itemView.findViewById(R.id.tvGeminiModelBadge);
            layoutThinking = itemView.findViewById(R.id.layoutThinking);
            tvThinkingLabel = itemView.findViewById(R.id.tvThinkingLabel);
            containerGeminiContent = itemView.findViewById(R.id.containerGeminiContent);
            tvGeminiMessage = itemView.findViewById(R.id.tvGeminiMessage);
            layoutActions = itemView.findViewById(R.id.layoutActions);

            btnCopy = itemView.findViewById(R.id.btnCopy);
            btnLike = itemView.findViewById(R.id.btnLike);
            btnDislike = itemView.findViewById(R.id.btnDislike);
            btnRegenerate = itemView.findViewById(R.id.btnRegenerate);
            btnShare = itemView.findViewById(R.id.btnShare);
        }

        void updateStreamingText(ChatMessage message) {
            // Direct clean update without re-binding or cross-fading
            if (layoutThinking.getVisibility() == View.VISIBLE && message.getText() != null && !message.getText().isEmpty()) {
                layoutThinking.setVisibility(View.GONE);
                stopSparkleAnimation();
            }
            GeminiMessageRenderer.render(context, containerGeminiContent, tvGeminiMessage, message);
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
            tvGeminiModelBadge.setText(message.getModelName() != null ? message.getModelName() : "Gemini");

            if (message.isGenerating()) {
                layoutThinking.setVisibility(View.VISIBLE);
                String thinkingText = "Pensando (" + (message.getThinkingLevel() != null ? message.getThinkingLevel() : "High") + ")...";
                tvThinkingLabel.setText(thinkingText);

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
                layoutThinking.setVisibility(View.GONE);
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

            btnLike.setOnClickListener(v ->
                    Toast.makeText(context, "Respuesta valorada positivamente", Toast.LENGTH_SHORT).show()
            );

            btnDislike.setOnClickListener(v ->
                    Toast.makeText(context, "Respuesta marcada para mejorar", Toast.LENGTH_SHORT).show()
            );

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
