package com.google.geminiclone;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class GeminiMessageRenderer {

    private static final String TAG_TEXT_BLOCK = "TAG_TEXT_BLOCK";
    private static final String TAG_CODE_BLOCK = "TAG_CODE_BLOCK";
    private static final int COLOR_TEXT_PRIMARY = Color.parseColor("#E3E3E3");
    private static final int COLOR_COPIED_GREEN = Color.parseColor("#34A853");
    private static final ColorStateList TINT_COPY_DEFAULT = ColorStateList.valueOf(Color.parseColor("#C4C7C5"));

    public static void render(Context context, LinearLayout container, TextView fallbackTextView, ChatMessage message) {
        String rawText = (message != null) ? message.getText() : null;
        if (rawText == null || rawText.isEmpty()) {
            if (container.getChildCount() != 1 || container.getChildAt(0) != fallbackTextView) {
                container.removeAllViews();
                container.addView(fallbackTextView);
            }
            fallbackTextView.setText("");
            fallbackTextView.setVisibility(View.VISIBLE);
            return;
        }

        // Fast path: pure text without code blocks (uses cached Spannable on ChatMessage)
        if (!rawText.contains("```")) {
            if (container.getChildCount() != 1 || container.getChildAt(0) != fallbackTextView) {
                container.removeAllViews();
                container.addView(fallbackTextView);
            }
            fallbackTextView.setVisibility(View.VISIBLE);
            fallbackTextView.setText(message.getParsedMarkdownText());
            return;
        }

        renderBlocks(context, container, message.getParsedBlocks());
    }

    public static void render(Context context, LinearLayout container, TextView fallbackTextView, String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            if (container.getChildCount() != 1 || container.getChildAt(0) != fallbackTextView) {
                container.removeAllViews();
                container.addView(fallbackTextView);
            }
            fallbackTextView.setText("");
            fallbackTextView.setVisibility(View.VISIBLE);
            return;
        }

        if (!rawText.contains("```")) {
            if (container.getChildCount() != 1 || container.getChildAt(0) != fallbackTextView) {
                container.removeAllViews();
                container.addView(fallbackTextView);
            }
            fallbackTextView.setVisibility(View.VISIBLE);
            fallbackTextView.setText(MarkdownParser.parseMarkdownText(rawText));
            return;
        }

        renderBlocks(context, container, MarkdownParser.parseBlocks(rawText));
    }

    private static void renderBlocks(Context context, LinearLayout container, List<MarkdownParser.MessageBlock> blocks) {
        // Count non-empty blocks
        int validCount = 0;
        for (int i = 0, n = blocks.size(); i < n; i++) {
            MarkdownParser.MessageBlock b = blocks.get(i);
            if (b.isCode || (b.formattedText != null && b.formattedText.length() > 0)) {
                validCount++;
            }
        }

        // Check if existing child views in container match the exact block structure (zero view re-inflation during streaming!)
        boolean canReuseInPlace = (container.getChildCount() == validCount);
        if (canReuseInPlace) {
            int childIdx = 0;
            for (int i = 0, n = blocks.size(); i < n; i++) {
                MarkdownParser.MessageBlock b = blocks.get(i);
                if (!b.isCode && (b.formattedText == null || b.formattedText.length() == 0)) continue;
                View child = container.getChildAt(childIdx++);
                Object tag = child.getTag();
                if (b.isCode && !TAG_CODE_BLOCK.equals(tag)) {
                    canReuseInPlace = false;
                    break;
                } else if (!b.isCode && !TAG_TEXT_BLOCK.equals(tag)) {
                    canReuseInPlace = false;
                    break;
                }
            }
        }

        if (!canReuseInPlace) {
            container.removeAllViews();
        }

        LayoutInflater inflater = null;
        int margin4dp = dpToPx(context, 4);
        int childIdx = 0;

        for (int i = 0, n = blocks.size(); i < n; i++) {
            MarkdownParser.MessageBlock block = blocks.get(i);
            if (!block.isCode) {
                if (block.formattedText != null && block.formattedText.length() > 0) {
                    TextView tv;
                    if (canReuseInPlace) {
                        tv = (TextView) container.getChildAt(childIdx++);
                    } else {
                        tv = new TextView(context);
                        tv.setTag(TAG_TEXT_BLOCK);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                        );
                        lp.setMargins(0, 0, 0, margin4dp);
                        tv.setLayoutParams(lp);
                        tv.setTextColor(COLOR_TEXT_PRIMARY);
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                        tv.setLineSpacing(margin4dp, 1.0f);
                        tv.setTextIsSelectable(true);
                        container.addView(tv);
                    }
                    tv.setText(block.formattedText);
                }
            } else {
                View codeView;
                if (canReuseInPlace) {
                    codeView = container.getChildAt(childIdx++);
                } else {
                    if (inflater == null) {
                        inflater = LayoutInflater.from(context);
                    }
                    codeView = inflater.inflate(R.layout.item_code_block, container, false);
                    codeView.setTag(TAG_CODE_BLOCK);
                    container.addView(codeView);
                }

                TextView tvLang = codeView.findViewById(R.id.tvCodeLanguage);
                TextView tvCode = codeView.findViewById(R.id.tvCodeContent);
                View btnCopy = codeView.findViewById(R.id.btnCopyCode);
                ImageView ivCopy = codeView.findViewById(R.id.ivCopyIcon);
                TextView tvCopyLabel = codeView.findViewById(R.id.tvCopyLabel);

                tvLang.setText(block.language.toUpperCase());
                tvCode.setText(block.highlightedCode != null ? block.highlightedCode : SyntaxHighlighter.highlight(block.code));

                btnCopy.setOnClickListener(v -> {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        ClipData clip = ClipData.newPlainText(block.language, block.code);
                        cm.setPrimaryClip(clip);
                        Toast.makeText(context, "Código copiado", Toast.LENGTH_SHORT).show();

                        ivCopy.setImageResource(R.drawable.ic_check);
                        ivCopy.setImageTintList(null);
                        tvCopyLabel.setText("¡Copiado!");
                        tvCopyLabel.setTextColor(COLOR_COPIED_GREEN);

                        btnCopy.postDelayed(() -> {
                            ivCopy.setImageResource(R.drawable.ic_copy);
                            ivCopy.setImageTintList(TINT_COPY_DEFAULT);
                            tvCopyLabel.setText("Copiar");
                            tvCopyLabel.setTextColor(Color.parseColor("#C4C7C5"));
                        }, 1500);
                    }
                });
            }
        }
    }

    private static int dpToPx(Context context, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()
        );
    }
}
