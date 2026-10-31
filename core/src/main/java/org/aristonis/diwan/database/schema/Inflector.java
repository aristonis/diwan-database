package org.aristonis.diwan.database.schema;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Guesses an English plural for {@link ForeignIdColumnDefinition#constrained()}.
 *
 * <p>Laravel uses a full inflector; these rules cover regular nouns and a short list of irregular
 * ones. A wrong guess is fixed by naming the table: {@code constrained("people")}.
 */
final class Inflector {

    private static final Map<String, String> IRREGULAR = new HashMap<>();
    private static final String[] UNCOUNTABLE = {"equipment", "information", "money", "news", "series", "sheep",
        "species", "fish", "data", "metadata", "feedback"};

    static {
        IRREGULAR.put("person", "people");
        IRREGULAR.put("man", "men");
        IRREGULAR.put("woman", "women");
        IRREGULAR.put("child", "children");
        IRREGULAR.put("tooth", "teeth");
        IRREGULAR.put("foot", "feet");
        IRREGULAR.put("mouse", "mice");
        IRREGULAR.put("goose", "geese");
        IRREGULAR.put("ox", "oxen");
        IRREGULAR.put("leaf", "leaves");
        IRREGULAR.put("life", "lives");
        IRREGULAR.put("knife", "knives");
        IRREGULAR.put("wife", "wives");
        IRREGULAR.put("criterion", "criteria");
        IRREGULAR.put("analysis", "analyses");
        IRREGULAR.put("status", "statuses");
    }

    private Inflector() {
    }

    /**
     * Pluralises the last word of a snake case name: {@code blog_category} gives
     * {@code blog_categories}.
     */
    static String plural(String name) {
        int split = name.lastIndexOf('_') + 1;
        return name.substring(0, split) + pluralWord(name.substring(split));
    }

    private static String pluralWord(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return word;
        }
        for (String uncountable : UNCOUNTABLE) {
            if (lower.equals(uncountable)) {
                return word;
            }
        }
        String irregular = IRREGULAR.get(lower);
        if (irregular != null) {
            return irregular;
        }
        if (lower.endsWith("y") && lower.length() > 1 && !isVowel(lower.charAt(lower.length() - 2))) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z") || lower.endsWith("ch")
                || lower.endsWith("sh")) {
            return word + "es";
        }
        return word + "s";
    }

    private static boolean isVowel(char c) {
        return "aeiou".indexOf(c) >= 0;
    }
}
