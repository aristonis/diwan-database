import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Prints the API a caller or subclass sees: every public and protected member of each public
 * class (nested ones too), inherited ones included, under the public class's name. Moving a member into a
 * package-private parent therefore leaves the output unchanged.
 *
 * <p>Usage: {@code java -cp <runtime classpath> scripts/ApiList.java <classes dir>}
 */
public final class ApiList {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        TreeSet<String> lines = new TreeSet<>();
        for (String name : classNames(root)) {
            Class<?> type = Class.forName(name, false, ApiList.class.getClassLoader());
            if (!isVisible(type)) {
                continue;
            }
            lines.add(name + " : " + header(type));
            for (Class<?> c : hierarchy(type)) {
                for (Method m : c.getDeclaredMethods()) {
                    add(lines, name, m, m.toGenericString().replace(c.getName() + ".", ""));
                }
                for (Field f : c.getDeclaredFields()) {
                    add(lines, name, f, f.toGenericString().replace(c.getName() + ".", ""));
                }
            }
            for (Constructor<?> k : type.getDeclaredConstructors()) {
                add(lines, name, k, k.toGenericString());
            }
        }
        lines.forEach(System.out::println);
    }

    // Superclasses and every interface reached from them, so inherited default methods count too.
    private static Set<Class<?>> hierarchy(Class<?> type) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>(List.of(type));
        while (!todo.isEmpty()) {
            Class<?> c = todo.pop();
            if (c == Object.class || !seen.add(c)) {
                continue;
            }
            if (c.getSuperclass() != null) {
                todo.add(c.getSuperclass());
            }
            todo.addAll(List.of(c.getInterfaces()));
        }
        return seen;
    }

    // A nested type is API only when it and every type enclosing it are public.
    private static boolean isVisible(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            if (!Modifier.isPublic(c.getModifiers())) {
                return false;
            }
        }
        return !type.isAnonymousClass() && !type.isLocalClass() && !type.isSynthetic();
    }

    private static String header(Class<?> type) {
        List<String> parts = new ArrayList<>();
        parts.add(Modifier.toString(type.getModifiers()));
        for (Class<?> s = type.getSuperclass(); s != null; s = s.getSuperclass()) {
            if (Modifier.isPublic(s.getModifiers())) {
                parts.add("extends " + s.getName());
                break;
            }
        }
        for (Class<?> i : type.getInterfaces()) {
            if (Modifier.isPublic(i.getModifiers())) {
                parts.add("implements " + i.getName());
            }
        }
        return String.join(" ", parts);
    }

    private static void add(TreeSet<String> lines, String owner, Member member, String text) {
        int mods = member.getModifiers();
        if ((Modifier.isPublic(mods) || Modifier.isProtected(mods)) && !member.isSynthetic()) {
            lines.add(owner + " : " + text);
        }
    }

    private static List<String> classNames(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.map(p -> root.relativize(p).toString())
                    .filter(p -> p.endsWith(".class"))
                    .filter(p -> !p.endsWith("package-info.class") && !p.endsWith("module-info.class"))
                    .map(p -> p.substring(0, p.length() - 6).replace('/', '.'))
                    .sorted()
                    .toList();
        }
    }
}
