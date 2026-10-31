package org.aristonis.diwan.database.sqlite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a SQLite {@code create table} statement into tokens and its column and constraint parts,
 * so the table rebuild can read it without being fooled by text inside string literals or quoted
 * names.
 *
 * <p>Only what the rebuild needs is recognised: string literals with doubled quotes, names quoted
 * with double quotes, backticks or brackets, words, numbers and single punctuation marks.
 */
final class SQLiteSqlTokens {

    enum Kind { WORD, NAME, STRING, PUNCT }

    static final class Token {
        final Kind kind;
        final String text;
        final int start;
        final int end;

        Token(Kind kind, String text, int start, int end) {
            this.kind = kind;
            this.text = text;
            this.start = start;
            this.end = end;
        }

        boolean isWord(String word) {
            return kind == Kind.WORD && text.equalsIgnoreCase(word);
        }

        boolean isPunct(char mark) {
            return kind == Kind.PUNCT && text.charAt(0) == mark;
        }
    }

    private SQLiteSqlTokens() {
    }

    static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'') {
                i = quoted(sql, i, '\'', '\'', Kind.STRING, tokens);
            } else if (c == '"') {
                i = quoted(sql, i, '"', '"', Kind.NAME, tokens);
            } else if (c == '`') {
                i = quoted(sql, i, '`', '`', Kind.NAME, tokens);
            } else if (c == '[') {
                i = quoted(sql, i, '[', ']', Kind.NAME, tokens);
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int start = i;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_'
                        || sql.charAt(i) == '$' || sql.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(Kind.WORD, sql.substring(start, i), start, i));
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int newline = sql.indexOf('\n', i);
                i = newline < 0 ? sql.length() : newline + 1;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? sql.length() : close + 2;
            } else {
                tokens.add(new Token(Kind.PUNCT, String.valueOf(c), i, i + 1));
                i++;
            }
        }
        return tokens;
    }

    // A doubled closing mark inside the quotes stands for one mark; the token text is the unquoted value.
    private static int quoted(String sql, int start, char open, char close, Kind kind, List<Token> tokens) {
        StringBuilder value = new StringBuilder();
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == close) {
                if (close != ']' && i + 1 < sql.length() && sql.charAt(i + 1) == close) {
                    value.append(close);
                    i += 2;
                    continue;
                }
                tokens.add(new Token(kind, value.toString(), start, i + 1));
                return i + 1;
            }
            value.append(c);
            i++;
        }
        throw new IllegalArgumentException("The table definition has an unclosed " + open);
    }

    /**
     * Splits the part between the outermost parentheses into its comma-separated parts, each a list
     * of tokens; also returns the tokens after the closing parenthesis as the last element.
     */
    static List<List<Token>> parts(List<Token> tokens) {
        int open = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).isPunct('(')) {
                open = i;
                break;
            }
        }
        if (open < 0) {
            throw new IllegalArgumentException("The table definition has no column list");
        }
        List<List<Token>> parts = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        int i = open + 1;
        for (; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.isPunct('(')) {
                depth++;
            } else if (token.isPunct(')')) {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (token.isPunct(',') && depth == 0) {
                parts.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(token);
        }
        parts.add(current);
        parts.add(new ArrayList<>(tokens.subList(Math.min(i + 1, tokens.size()), tokens.size())));
        return parts;
    }

    static boolean isTableConstraint(List<Token> part) {
        if (part.isEmpty()) {
            return true;
        }
        Token first = part.get(0);
        if (first.kind != Kind.WORD) {
            return false;
        }
        String word = first.text.toLowerCase(Locale.ROOT);
        return word.equals("constraint") || word.equals("primary") || word.equals("unique")
                || word.equals("check") || word.equals("foreign");
    }

    static boolean hasWord(List<Token> tokens, String word) {
        for (Token token : tokens) {
            if (token.isWord(word)) {
                return true;
            }
        }
        return false;
    }
}
