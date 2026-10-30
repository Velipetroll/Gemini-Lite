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

    public static void render(Context context, LinearLayout container, TextView fallbackTextView, String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            container.removeAllViews();
            fallbackTextView.setText("");
            fallbackTextView.setVisibility(View.VISIBLE);
            container.addView(fallbackTextView);
            return;
        }

        // Fast path: pure text without code blocks
        if (!rawText.contains("```")) {
            if (container.getChildCount() != 1 || container.getChildAt(0) != fallbackTextView) {
                container.removeAllViews();
                container.addView(fallbackTextView);
            }
            fallbackTextView.setVisibility(View.VISIBLE);
            fallbackTextView.setText(MarkdownParser.parseMarkdownText(rawText));
            return;
        }

        // Parse into structured message blocks (text paragraphs and code cards)
        List<MarkdownParser.MessageBlock> blocks = MarkdownParser.parseBlocks(rawText);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);

        for (MarkdownParser.MessageBlock block : blocks) {
            if (!block.isCode) {
                if (block.formattedText != null && block.formattedText.length() > 0) {
                    TextView tv = new TextView(context);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    lp.setMargins(0, 0, 0, dpToPx(context, 4));
                    tv.setLayoutParams(lp);
                    tv.setTextColor(Color.parseColor("#E3E3E3"));
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    tv.setLineSpacing(dpToPx(context, 4), 1.0f);
                    tv.setTextIsSelectable(true);
                    tv.setText(block.formattedText);
                    container.addView(tv);
                }
            } else {
                // Code block card
                View codeView = inflater.inflate(R.layout.item_code_block, container, false);
                TextView tvLang = codeView.findViewById(R.id.tvCodeLanguage);
                TextView tvCode = codeView.findViewById(R.id.tvCodeContent);
                View btnCopy = codeView.findViewById(R.id.btnCopyCode);
                ImageView ivCopy = codeView.findViewById(R.id.ivCopyIcon);
                TextView tvCopyLabel = codeView.findViewById(R.id.tvCopyLabel);

                tvLang.setText(block.language.toUpperCase());
                tvCode.setText(SyntaxHighlighter.highlight(block.code));

                btnCopy.setOnClickListener(v -> {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        ClipData clip = ClipData.newPlainText(block.language, block.code);
                        cm.setPrimaryClip(clip);
                        Toast.makeText(context, "Código copiado", Toast.LENGTH_SHORT).show();

                        // Visual feedback: green checkmark and "¡Copiado!"
                        ivCopy.setImageResource(R.drawable.ic_check);
                        ivCopy.setImageTintList(null);
                        tvCopyLabel.setText("¡Copiado!");
                        tvCopyLabel.setTextColor(Color.parseColor("#34A853"));

                        btnCopy.postDelayed(() -> {
                            ivCopy.setImageResource(R.drawable.ic_copy);
                            ivCopy.setImageTintList(ColorStateList.valueOf(Color.parseColor("#C4C7C5")));
                            tvCopyLabel.setText("Copiar");
                            tvCopyLabel.setTextColor(Color.parseColor("#C4C7C5"));
                        }, 1500);
                    }
                });

                container.addView(codeView);
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
