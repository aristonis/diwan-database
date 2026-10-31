package org.aristonis.diwan.database.driver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a SQLite script into its statements, for drivers whose SQLite API runs only the first
 * statement of a string.
 *
 * <p>A semicolon ends a statement unless it sits in a string, a quoted name, a comment, or the body
 * of a {@code create trigger}, which ends at its {@code end}.
 *
 * @apiNote For drivers in this library; it is not a general SQL parser.
 */
public final class SqliteScripts {

    private SqliteScripts() {
    }

    /**
     * Splits a script.
     *
     * @param script the script, never {@code null}
     * @return the statements in order, trimmed, without empty ones
     */
    public static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        StringBuilder word = new StringBuilder();
        List<String> words = new ArrayList<>();
        int i = 0;
        while (i < script.length()) {
            char c = script.charAt(i);
            int end = skipQuoted(script, i);
            if (end > i) {
                current.append(script, i, end);
                i = end;
                endWord(word, words);
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_') {
                word.append(c);
            } else {
                endWord(word, words);
            }
            if (c == ';' && !isInsideTrigger(words)) {
                add(statements, current);
                current.setLength(0);
                words.clear();
            } else {
                current.append(c);
            }
            i++;
        }
        endWord(word, words);
        add(statements, current);
        return statements;
    }

    // Strings, quoted names and comments are copied whole; returns where the run ends, or start if none.
    private static int skipQuoted(String sql, int start) {
        char c = sql.charAt(start);
        char close;
        if (c == '\'' || c == '"' || c == '`') {
            close = c;
        } else if (c == '[') {
            close = ']';
        } else if (c == '-' && start + 1 < sql.length() && sql.charAt(start + 1) == '-') {
            int newline = sql.indexOf('\n', start);
            return newline < 0 ? sql.length() : newline + 1;
        } else if (c == '/' && start + 1 < sql.length() && sql.charAt(start + 1) == '*') {
            int stop = sql.indexOf("*/", start + 2);
            return stop < 0 ? sql.length() : stop + 2;
        } else {
            return start;
        }
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == close) {
                if (close != ']' && i + 1 < sql.length() && sql.charAt(i + 1) == close) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    private static void endWord(StringBuilder word, List<String> words) {
        if (word.length() > 0) {
            words.add(word.toString().toLowerCase(Locale.ROOT));
            word.setLength(0);
        }
    }

    // Inside "create [temp|temporary] trigger ... begin ... end" until the body's own end; an end
    // that closes a CASE inside the body does not count.
    private static boolean isInsideTrigger(List<String> words) {
        if (words.size() < 2 || !words.get(0).equals("create")) {
            return false;
        }
        int at = words.get(1).equals("temp") || words.get(1).equals("temporary") ? 2 : 1;
        if (at >= words.size() || !words.get(at).equals("trigger")) {
            return false;
        }
        boolean hasBegun = false;
        int caseDepth = 0;
        boolean isBodyClosed = false;
        for (String word : words.subList(at + 1, words.size())) {
            isBodyClosed = false;
            if (!hasBegun) {
                hasBegun = word.equals("begin");
            } else if (word.equals("case")) {
                caseDepth++;
            } else if (word.equals("end")) {
                if (caseDepth > 0) {
                    caseDepth--;
                } else {
                    isBodyClosed = true;
                }
            }
        }
        return !isBodyClosed;
    }

    // A fragment of only comments, such as one after the last semicolon, is not a statement.
    private static void add(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        String rest = statement;
        while (rest.startsWith("--") || rest.startsWith("/*")) {
            rest = rest.substring(skipQuoted(rest, 0)).trim();
        }
        if (!rest.isEmpty()) {
            statements.add(statement);
        }
    }
}
