package com.classroom.util;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyntaxHighlighter {
    private static final String[] JAVA_KEYWORDS = new String[] {
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
        "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
        "String", "var"
    };

    private static final String[] PYTHON_KEYWORDS = new String[] {
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue",
        "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import",
        "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield"
    };

    private static final String[] C_CPP_KEYWORDS = new String[] {
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum",
        "extern", "float", "for", "goto", "if", "int", "long", "register", "return", "short", "signed",
        "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void", "volatile", "while",
        "class", "public", "private", "protected", "template", "using", "namespace", "bool", "virtual"
    };

    private static final String[] JS_KEYWORDS = new String[] {
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do",
        "else", "export", "extends", "finally", "for", "function", "if", "import", "in", "instanceof",
        "new", "return", "super", "switch", "this", "throw", "try", "typeof", "var", "void", "while",
        "with", "yield", "let", "await", "async"
    };

    private static final String[] HTML_KEYWORDS = new String[] {
        "html", "body", "div", "span", "applet", "object", "iframe", "h1", "h2", "h3", "h4", "h5", "h6", "p",
        "blockquote", "pre", "a", "abbr", "acronym", "address", "big", "cite", "code", "del", "dfn", "em",
        "img", "ins", "kbd", "q", "s", "samp", "small", "strike", "strong", "sub", "sup", "tt", "var", "b", "u",
        "i", "center", "dl", "dt", "dd", "ol", "ul", "li", "fieldset", "form", "label", "legend", "table",
        "caption", "tbody", "tfoot", "thead", "tr", "th", "td", "article", "aside", "canvas", "details", "embed",
        "figure", "figcaption", "footer", "header", "hgroup", "menu", "nav", "output", "ruby", "section", "summary",
        "time", "mark", "audio", "video", "head", "title", "meta", "link", "style", "script"
    };
    
    private static final String[] CSS_KEYWORDS = new String[] {
        "color", "background", "border", "margin", "padding", "display", "position", "top", "bottom", "left", "right",
        "font", "text", "align", "width", "height", "flex", "grid", "float", "clear", "z-index", "opacity"
    };

    private static final String[] SQL_KEYWORDS = new String[] {
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE",
        "DROP", "ALTER", "INDEX", "VIEW", "JOIN", "INNER", "OUTER", "LEFT", "RIGHT", "FULL", "ON", "GROUP",
        "BY", "HAVING", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "AND", "OR", "NOT", "NULL", "AS"
    };

    private static final String[] BASH_KEYWORDS = new String[] {
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "select", "while", "until", "do", "done",
        "in", "function", "time", "echo", "awk", "sed", "grep", "cat", "ls", "cd", "pwd", "exit"
    };

    private static final String STRING_PATTERN = "\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'";
    private static final String NUMBER_PATTERN = "\\b\\d+\\b";
    private static final String COMMENT_PATTERN = "//[^\n]*" + "|" + "/\\*(.|\\R)*?\\*/" + "|" + "#[^\n]*" + "|" + "<!--(.|\\R)*?-->";

    public static StyleSpans<Collection<String>> computeHighlighting(String text, String language) {
        if ("Plain Text".equals(language) || text.isEmpty()) {
            StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
            spansBuilder.add(Collections.emptyList(), text.length());
            return spansBuilder.create();
        }

        String[] keywords = getKeywordsForLang(language);
        String keywordPattern = "";
        if (keywords.length > 0) {
            boolean caseInsensitive = "SQL".equals(language) || "HTML".equals(language);
            String prefix = caseInsensitive ? "(?i)" : "";
            
            // For HTML, keywords are tags, so we might want a slightly different pattern, but word boundaries work OK.
            String kwString = String.join("|", keywords);
            if ("HTML".equals(language)) {
                keywordPattern = prefix + "</?(" + kwString + ")\\b.*?>?";
            } else {
                keywordPattern = prefix + "\\b(" + kwString + ")\\b";
            }
        }

        Pattern pattern;
        if (!keywordPattern.isEmpty()) {
            pattern = Pattern.compile(
                    "(?<KEYWORD>" + keywordPattern + ")"
                    + "|(?<STRING>" + STRING_PATTERN + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN + ")"
                    + "|(?<NUMBER>" + NUMBER_PATTERN + ")"
            );
        } else {
            pattern = Pattern.compile(
                    "(?<STRING>" + STRING_PATTERN + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN + ")"
                    + "|(?<NUMBER>" + NUMBER_PATTERN + ")"
            );
        }

        Matcher matcher = pattern.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        while (matcher.find()) {
            String styleClass =
                    matcher.group("KEYWORD") != null ? "kw" :
                    matcher.group("STRING") != null ? "str" :
                    matcher.group("COMMENT") != null ? "cmt" :
                    matcher.group("NUMBER") != null ? "num" :
                    null;
            if (styleClass != null) {
                spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
                spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
                lastKwEnd = matcher.end();
            }
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        return spansBuilder.create();
    }

    private static String[] getKeywordsForLang(String lang) {
        switch (lang) {
            case "Java": return JAVA_KEYWORDS;
            case "Python": return PYTHON_KEYWORDS;
            case "C/C++": return C_CPP_KEYWORDS;
            case "JavaScript": return JS_KEYWORDS;
            case "HTML": return HTML_KEYWORDS;
            case "CSS": return CSS_KEYWORDS;
            case "SQL": return SQL_KEYWORDS;
            case "Bash/AWK": return BASH_KEYWORDS;
            default: return new String[0];
        }
    }
}
