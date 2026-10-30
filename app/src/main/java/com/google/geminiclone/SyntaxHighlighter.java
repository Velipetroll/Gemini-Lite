package com.google.geminiclone;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyntaxHighlighter {

    // Palette matching Google Gemini official Code theme
    private static final int COLOR_KEYWORD      = Color.parseColor("#8AB4F8"); // Google Blue 300
    private static final int COLOR_STRING       = Color.parseColor("#81C995"); // Google Green 300
    private static final int COLOR_NUMBER       = Color.parseColor("#FCAD70"); // Google Orange 300
    private static final int COLOR_COMMENT      = Color.parseColor("#9AA0A6"); // Google Gray 400
    private static final int COLOR_TYPE         = Color.parseColor("#78D9EC"); // Google Cyan 300
    private static final int COLOR_PREPROCESSOR = Color.parseColor("#F28B82"); // Google Red/Coral 300
    private static final int COLOR_BUILTIN      = Color.parseColor("#C58AF9"); // Google Purple 300

    private static final String KEYWORDS = "\\b("
            + "abstract|alignas|alignof|and|and_eq|as|asm|assert|async|await|auto|"
            + "bitand|bitor|bool|boolean|box|break|byte|case|catch|char|char16_t|char32_t|char8_t|"
            + "class|compl|concept|const|const_cast|consteval|constexpr|constinit|continue|"
            + "co_await|co_return|co_yield|debugger|decltype|def|default|del|delete|do|double|"
            + "dynamic_cast|elif|else|enum|elif|explicit|export|extends|extern|false|final|"
            + "finally|float|fn|for|friend|from|function|global|goto|if|implements|import|in|"
            + "inline|instanceof|int|interface|is|lambda|let|long|match|module|mutable|mut|"
            + "namespace|native|new|nil|noexcept|none|nonlocal|not|not_eq|null|nullptr|of|"
            + "operator|or|or_eq|package|pass|private|protected|pub|public|raise|register|"
            + "reinterpret_cast|requires|return|self|short|signed|sizeof|static|static_assert|"
            + "static_cast|strictfp|struct|super|switch|synchronized|template|this|thread_local|"
            + "throw|throws|trait|transient|true|try|typedef|typeid|typename|typeof|undef|union|"
            + "unsafe|unsigned|use|using|val|var|virtual|void|volatile|wchar_t|where|while|with|"
            + "xor|xor_eq|yield|"
            // SQL Keywords
            + "SELECT|FROM|WHERE|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|TABLE|ALTER|DROP|"
            + "INDEX|JOIN|INNER|LEFT|RIGHT|FULL|OUTER|CROSS|ON|GROUP|BY|ORDER|HAVING|LIMIT|"
            + "OFFSET|UNION|ALL|DISTINCT|AS|AND|OR|NOT|IN|BETWEEN|LIKE|IS|NULL|COUNT|AVG|SUM|"
            + "MIN|MAX|CASE|WHEN|THEN|ELSE|END|EXISTS|PRIMARY|KEY|FOREIGN|REFERENCES|DATABASE"
            + ")\\b";

    private static final String TYPES = "\\b("
            + "int|float|double|char|long|short|boolean|bool|void|byte|string|String|Integer|"
            + "Double|Float|Boolean|Long|Byte|Short|Character|List|Map|Set|Array|ArrayList|"
            + "HashMap|HashSet|LinkedList|TreeMap|TreeSet|Vector|Object|Promise|Observable|"
            + "size_t|int8_t|int16_t|int32_t|int64_t|uint8_t|uint16_t|uint32_t|uint64_t|"
            + "i8|i16|i32|i64|i128|isize|u8|u16|u32|u64|u128|usize|f32|f64|str|any|number|"
            + "dict|tuple|set|frozenset|bytes|bytearray|memoryview|range"
            + ")\\b";

    private static final String PREPROCESSORS = "(^\\s*#\\s*(include|define|undef|ifdef|ifndef|if|elif|else|endif|line|error|pragma)|@\\w+)";
    private static final String NUMBERS = "\\b(0[xX][0-9a-fA-F]+|[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?[fFdDlL]?)\\b";
    private static final String STRINGS = "(\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)";
    private static final String COMMENTS = "(//.*?$|/\\*[\\s\\S]*?\\*/|#.*?$|--.*?$|<!--[\\s\\S]*?-->)";

    private static final Pattern PATTERN_COMBINED = Pattern.compile(
            "(?<COMMENT>" + COMMENTS + ")"
            + "|(?<STRING>" + STRINGS + ")"
            + "|(?<PREPROCESSOR>" + PREPROCESSORS + ")"
            + "|(?<KEYWORD>" + KEYWORDS + ")"
            + "|(?<TYPE>" + TYPES + ")"
            + "|(?<NUMBER>" + NUMBERS + ")",
            Pattern.MULTILINE
    );

    public static Spannable highlight(String code) {
        if (code == null) return new SpannableString("");
        SpannableString spannable = new SpannableString(code);
        Matcher matcher = PATTERN_COMBINED.matcher(code);

        while (matcher.find()) {
            if (matcher.group("COMMENT") != null) {
                spannable.setSpan(new ForegroundColorSpan(COLOR_COMMENT), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new StyleSpan(Typeface.ITALIC), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group("STRING") != null) {
                spannable.setSpan(new ForegroundColorSpan(COLOR_STRING), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group("PREPROCESSOR") != null) {
                spannable.setSpan(new ForegroundColorSpan(COLOR_PREPROCESSOR), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group("KEYWORD") != null) {
                spannable.setSpan(new ForegroundColorSpan(COLOR_KEYWORD), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new StyleSpan(Typeface.BOLD), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group("TYPE") != null) {
                spannable.setSpan(new ForegroundColorSpan(COLOR_TYPE), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group("NUMBER") != null) {
                spannable.setSpan(new ForegroundColorSpan(COLOR_NUMBER), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        return spannable;
    }
}
