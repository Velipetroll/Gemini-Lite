package com.google.geminiclone;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownParser {

    public static class MessageBlock {
        public final boolean isCode;
        public final String language;
        public final String code;
        public final CharSequence highlightedCode;
        public final CharSequence formattedText;

        public MessageBlock(String language, String code) {
            this.isCode = true;
            this.language = (language != null && !language.trim().isEmpty()) ? language.trim() : "CODE";
            this.code = code != null ? code : "";
            this.highlightedCode = SyntaxHighlighter.highlight(this.code);
            this.formattedText = null;
        }

        public MessageBlock(CharSequence formattedText) {
            this.isCode = false;
            this.language = null;
            this.code = null;
            this.highlightedCode = null;
            this.formattedText = formattedText;
        }
    }

    // Pattern for code blocks: ```lang\n...``` or streaming unclosed ```...$
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```([a-zA-Z0-9_+#.-]*)\\n?([\\s\\S]*?)(?:```|$)"
    );

    // Headings: 1 to 6 hashes at line start: # Title, ## Title, ### Title, optionally with trailing #
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^\\s*(#{1,6})\\s+(.+?)(?:\\s+#+)?$"
    );

    // Horizontal Rule: ---, ----, ***, ___
    private static final Pattern HR_PATTERN = Pattern.compile(
            "^\\s*([-_*])\\s*\\1\\s*\\1[\\s-_*]*$"
    );

    // Bullet Lists: - item, * item, + item with optional indentation
    private static final Pattern LIST_PATTERN = Pattern.compile(
            "^(\\s*)([-*+])\\s+(.*)$"
    );

    // Numbered Lists: 1. item, 2) item with optional indentation
    private static final Pattern NUMBERED_LIST_PATTERN = Pattern.compile(
            "^(\\s*)(\\d+)[.)]\\s+(.*)$"
    );

    // Blockquote: > Quote or u003e Quote
    private static final Pattern BLOCKQUOTE_PATTERN = Pattern.compile(
            "^\\s*(?:>|u003e|&gt;)\\s*(.*)$"
    );

    // Inline styles: `code`, ***bold-italic***, **bold**, __bold__, *italic*, _italic_, ~~strike~~
    private static final Pattern INLINE_PATTERN = Pattern.compile(
            "(`([^`\\n]+)`)"
            + "|(\\*\\*\\*([^*\\n]+)\\*\\*\\*)"
            + "|(\\*\\*([^*\\n]+)\\*\\*)"
            + "|(__([^_\\n]+)__)"
            + "|(\\*([^*\\n]+)\\*)"
            + "|(\\b_([^_\\n]+)_\\b)"
            + "|(~~([^~\\n]+)~~)"
    );

    private static final int COLOR_INLINE_BG = Color.parseColor("#282A2D");
    private static final int COLOR_INLINE_TEXT = Color.parseColor("#A8C7FA");
    private static final int COLOR_BULLET_PRIMARY = Color.parseColor("#59C0F0");
    private static final int COLOR_WHITE = Color.parseColor("#FFFFFF");
    private static final int COLOR_PRIMARY = Color.parseColor("#E3E3E3");
    private static final int COLOR_SECONDARY = Color.parseColor("#C4C7C5");
    private static final int COLOR_HINT = Color.parseColor("#8E918F");
    private static final int COLOR_ACCENT = Color.parseColor("#4285F4");
    private static final int COLOR_DIVIDER = Color.parseColor("#333538");

    /**
     * Splits raw markdown into structured blocks (Text paragraphs & Code Blocks).
     */
    public static List<MessageBlock> parseBlocks(String rawText) {
        List<MessageBlock> blocks = new ArrayList<>();
        if (rawText == null || rawText.isEmpty()) {
            blocks.add(new MessageBlock(new SpannableStringBuilder("")));
            return blocks;
        }

        Matcher matcher = CODE_BLOCK_PATTERN.matcher(rawText);
        int lastEnd = 0;

        while (matcher.find()) {
            // Text before code block
            if (matcher.start() > lastEnd) {
                String textBefore = rawText.substring(lastEnd, matcher.start());
                if (!textBefore.trim().isEmpty()) {
                    blocks.add(new MessageBlock(parseMarkdownText(textBefore.trim())));
                }
            }

            // Code block
            String language = matcher.group(1);
            String code = matcher.group(2);
            if (code != null && code.endsWith("\n")) {
                code = code.substring(0, code.length() - 1);
            }
            blocks.add(new MessageBlock(language, code));
            lastEnd = matcher.end();
        }

        // Remainder text after last code block
        if (lastEnd < rawText.length()) {
            String textAfter = rawText.substring(lastEnd);
            if (!textAfter.trim().isEmpty()) {
                blocks.add(new MessageBlock(parseMarkdownText(textAfter.trim())));
            }
        }

        if (blocks.isEmpty()) {
            blocks.add(new MessageBlock(parseMarkdownText(rawText)));
        }

        return blocks;
    }

    private static char firstNonSpaceChar(String line) {
        int len = line.length();
        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') return c;
        }
        return '\0';
    }

    /**
     * Parses standard markdown (headings, lists, blockquotes, inline styles, dividers).
     */
    public static CharSequence parseMarkdownText(String text) {
        if (text == null || text.isEmpty()) {
            return new SpannableStringBuilder("");
        }

        String[] lines = text.split("\n", -1);
        SpannableStringBuilder builder = new SpannableStringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            char first = firstNonSpaceChar(line);

            // Fast-path: only test HEADING_PATTERN if line starts with '#'
            Matcher headingMatcher = (first == '#') ? HEADING_PATTERN.matcher(line) : null;
            boolean isHeading = (headingMatcher != null && headingMatcher.matches());

            if (isHeading && builder.length() > 0 && !endsWithDoubleNewline(builder)) {
                builder.append("\n"); // Extra vertical margin before heading
            }

            SpannableStringBuilder formattedLine = parseLine(line, first, isHeading ? headingMatcher : null);
            builder.append(formattedLine);

            if (i < lines.length - 1) {
                builder.append("\n");
            }
        }

        return builder;
    }

    private static boolean endsWithDoubleNewline(SpannableStringBuilder ssb) {
        int len = ssb.length();
        return len >= 2 && ssb.charAt(len - 1) == '\n' && ssb.charAt(len - 2) == '\n';
    }

    private static SpannableStringBuilder parseLine(String line, char first, Matcher matchedHeading) {
        if (first == '\0') {
            return new SpannableStringBuilder(line);
        }

        // 1. Horizontal Rule: ---, ***, ___
        if ((first == '-' || first == '*' || first == '_') && HR_PATTERN.matcher(line).matches()) {
            SpannableStringBuilder hr = new SpannableStringBuilder();
            hr.append("────────────────────────────────────────");
            hr.setSpan(new ForegroundColorSpan(COLOR_DIVIDER), 0, hr.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            hr.setSpan(new RelativeSizeSpan(0.7f), 0, hr.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return hr;
        }

        // 2. Headings: #, ##, ###, ####, #####, ######
        if (matchedHeading != null) {
            String hashes = matchedHeading.group(1);
            String content = matchedHeading.group(2).trim();
            int level = hashes.length();

            SpannableStringBuilder ssb = parseInlineStyles(content);

            switch (level) {
                case 1: // # Heading 1
                    applyLineStyle(ssb, 1.40f, Typeface.BOLD, COLOR_WHITE);
                    break;
                case 2: // ## Heading 2
                    applyLineStyle(ssb, 1.25f, Typeface.BOLD, COLOR_WHITE);
                    break;
                case 3: // ### Heading 3
                    applyLineStyle(ssb, 1.15f, Typeface.BOLD, COLOR_INLINE_TEXT);
                    break;
                case 4: // #### Heading 4
                    applyLineStyle(ssb, 1.08f, Typeface.BOLD, COLOR_PRIMARY);
                    break;
                case 5: // ##### Heading 5
                    applyLineStyle(ssb, 1.02f, Typeface.BOLD, COLOR_SECONDARY);
                    break;
                default: // ###### Heading 6
                    applyLineStyle(ssb, 0.95f, Typeface.BOLD_ITALIC, COLOR_HINT);
                    break;
            }
            return ssb;
        }

        // 3. Blockquote: > Quote
        if (first == '>' || first == 'u' || first == '&') {
            Matcher bqMatcher = BLOCKQUOTE_PATTERN.matcher(line);
            if (bqMatcher.matches()) {
                String content = bqMatcher.group(1).trim();
                SpannableStringBuilder ssb = new SpannableStringBuilder();
                ssb.append("▎ ");
                ssb.setSpan(new ForegroundColorSpan(COLOR_ACCENT), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                int start = ssb.length();
                ssb.append(parseInlineStyles(content));
                ssb.setSpan(new StyleSpan(Typeface.ITALIC), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_SECONDARY), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                return ssb;
            }
        }

        // 4. Bullet lists: - item, * item, + item
        if (first == '-' || first == '*' || first == '+') {
            Matcher listMatcher = LIST_PATTERN.matcher(line);
            if (listMatcher.matches()) {
            int indent = listMatcher.group(1).length();
            String content = listMatcher.group(3).trim();

            SpannableStringBuilder ssb = new SpannableStringBuilder();

            // Check for Task List checkbox: - [ ] or - [x]
            if (content.startsWith("[ ] ")) {
                appendIndent(ssb, indent);
                int start = ssb.length();
                ssb.append("☐  ");
                ssb.setSpan(new ForegroundColorSpan(COLOR_HINT), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.append(parseInlineStyles(content.substring(4)));
                return ssb;
            } else if (content.startsWith("[x] ") || content.startsWith("[X] ")) {
                appendIndent(ssb, indent);
                int start = ssb.length();
                ssb.append("☑  ");
                ssb.setSpan(new ForegroundColorSpan(COLOR_BULLET_PRIMARY), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.append(parseInlineStyles(content.substring(4)));
                return ssb;
            }

            // Standard Bullets by Indentation Level
            if (indent >= 4) {
                // Level 2+ Sub-bullet
                ssb.append("          ▪  ");
                int bulletStart = ssb.length() - 4;
                ssb.setSpan(new ForegroundColorSpan(COLOR_HINT), bulletStart, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (indent >= 2) {
                // Level 1 Sub-bullet
                ssb.append("      ◦  ");
                int bulletStart = ssb.length() - 4;
                ssb.setSpan(new ForegroundColorSpan(COLOR_SECONDARY), bulletStart, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                // Level 0 Primary bullet
                ssb.append("  •  ");
                ssb.setSpan(new ForegroundColorSpan(COLOR_BULLET_PRIMARY), 2, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), 2, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

                ssb.append(parseInlineStyles(content));
                return ssb;
            }
        }

        // 5. Numbered lists: 1. item, 2. item
        if (first >= '0' && first <= '9') {
            Matcher numMatcher = NUMBERED_LIST_PATTERN.matcher(line);
            if (numMatcher.matches()) {
                int indent = numMatcher.group(1).length();
                String num = numMatcher.group(2) + ".";
                String content = numMatcher.group(3).trim();

                SpannableStringBuilder ssb = new SpannableStringBuilder();
                appendIndent(ssb, indent);
                ssb.append("  ").append(num).append("  ");
                int numStart = ssb.length() - num.length() - 2;
                int numEnd = ssb.length() - 2;
                ssb.setSpan(new ForegroundColorSpan(COLOR_INLINE_TEXT), numStart, numEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), numStart, numEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                ssb.append(parseInlineStyles(content));
                return ssb;
            }
        }

        // 6. Regular line with inline formatting
        return parseInlineStyles(line);
    }

    private static void appendIndent(SpannableStringBuilder ssb, int indentSpaces) {
        for (int i = 0; i < indentSpaces; i++) {
            ssb.append(" ");
        }
    }

    private static void applyLineStyle(SpannableStringBuilder ssb, float relativeSize, int typefaceStyle, int color) {
        if (ssb.length() == 0) return;
        ssb.setSpan(new RelativeSizeSpan(relativeSize), 0, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new StyleSpan(typefaceStyle), 0, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new ForegroundColorSpan(color), 0, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static boolean hasInlineDelimiters(String text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '`' || c == '_' || c == '~') {
                return true;
            }
        }
        return false;
    }

    private static SpannableStringBuilder parseInlineStyles(String text) {
        // Fast path: 0 regex Matcher allocation when line has no inline markdown characters
        if (!hasInlineDelimiters(text)) {
            return new SpannableStringBuilder(text);
        }
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        Matcher matcher = INLINE_PATTERN.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                ssb.append(text.substring(lastEnd, matcher.start()));
            }

            if (matcher.group(2) != null) {
                // Inline code pill: `code`
                String code = matcher.group(2);
                int start = ssb.length();
                ssb.append(" ").append(code).append(" ");
                int end = ssb.length();
                ssb.setSpan(new BackgroundColorSpan(COLOR_INLINE_BG), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_INLINE_TEXT), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new RelativeSizeSpan(0.92f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(4) != null) {
                String bi = matcher.group(4);
                int start = ssb.length();
                ssb.append(bi);
                int end = ssb.length();
                ssb.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_WHITE), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(6) != null) {
                String b = matcher.group(6);
                int start = ssb.length();
                ssb.append(b);
                int end = ssb.length();
                ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_WHITE), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(8) != null) { // __bold__
                String b = matcher.group(8);
                int start = ssb.length();
                ssb.append(b);
                int end = ssb.length();
                ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_WHITE), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(10) != null) {
                String it = matcher.group(10);
                int start = ssb.length();
                ssb.append(it);
                int end = ssb.length();
                ssb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(12) != null) { // _italic_
                String it = matcher.group(12);
                int start = ssb.length();
                ssb.append(it);
                int end = ssb.length();
                ssb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(14) != null) {
                String st = matcher.group(14);
                int start = ssb.length();
                ssb.append(st);
                int end = ssb.length();
                ssb.setSpan(new StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            lastEnd = matcher.end();
        }

        if (lastEnd < text.length()) {
            ssb.append(text.substring(lastEnd));
        }

        return ssb;
    }
}
