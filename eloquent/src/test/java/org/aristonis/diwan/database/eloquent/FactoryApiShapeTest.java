package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.factory.RecycleMode;
import org.aristonis.diwan.database.eloquent.factory.Sequence;
import org.junit.jupiter.api.Test;

/** The factory package is exported and its public surface is the planned list and nothing else. */
class FactoryApiShapeTest {

    private static final String FACTORY_PACKAGE = "org.aristonis.diwan.database.eloquent.factory";

    private static final Set<String> FACTORY_METHODS = Set.of("of", "state", "set", "sequence", "forEachSequence",
            "trashed", "count", "afterMaking", "afterCreating", "withoutAfterMaking", "withoutAfterCreating",
            "connection", "make", "makeMany", "create", "createMany", "createQuietly", "createManyQuietly", "raw",
            "rawMany", "insert", "has", "hasAttached", "forParent", "recycle", "recycleMode", "recycleRandom",
            "withoutParents");

    private static final Set<String> SEQUENCE_METHODS = Set.of("of", "byIndex", "and", "crossJoin", "size");

    private static final Set<String> FORBIDDEN_METHODS = Set.of("when", "unless", "lazy");

    /** Reads the module-info.class that sits beside the factory package, not the first one on the class path. */
    private static ModuleDescriptor descriptorOfTheFactoryModule() throws IOException {
        URL own = Factory.class.getResource("Factory.class");
        assertNotNull(own);
        String text = own.toString();
        String suffix = FACTORY_PACKAGE.replace('.', '/') + "/Factory.class";
        assertTrue(text.endsWith(suffix), text);
        URL descriptor = URI.create(text.substring(0, text.length() - suffix.length()) + "module-info.class").toURL();
        try (InputStream in = descriptor.openStream()) {
            return ModuleDescriptor.read(in);
        }
    }

    private static final Set<String> NEW_FACTORY_SIGNATURES = new TreeSet<>(Arrays.asList(
            "has(Factory,String)", "hasAttached(Factory,String)", "hasAttached(Factory,Map,String)",
            "hasAttached(Factory,Function,String)", "hasAttached(Factory,List,String)",
            "hasAttached(List,Map,String)", "forParent(Factory,String)", "forParent(Model,String)",
            "state(BiFunction)", "afterMaking(BiConsumer)", "afterCreating(BiConsumer)", "recycle(Model[])",
            "recycle(List)", "recycleMode(RecycleMode)", "recycleRandom()", "recycleRandom(long)",
            "withoutParents()", "withoutParents(Class[])", "withoutParents(String[])"));

    private static String signature(Method method) {
        StringBuilder text = new StringBuilder(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            text.append(i == 0 ? "" : ",").append(types[i].getSimpleName());
        }
        return text.append(')').toString();
    }

    private static Class<?> elementOf(Class<?> type) {
        return type.isArray() ? elementOf(type.getComponentType()) : type;
    }

    private static Set<String> publicMethodNames(Class<?> type) {
        Set<String> names = new TreeSet<>();
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == type) {
                names.add(method.getName());
            }
        }
        return names;
    }

    @Test
    void theFactoryPackageIsExportedByModuleInfo() throws IOException {
        ModuleDescriptor descriptor = descriptorOfTheFactoryModule();

        boolean exportedToAll = descriptor.exports().stream()
                .anyMatch(exports -> exports.source().equals(FACTORY_PACKAGE) && !exports.isQualified());

        assertTrue(exportedToAll, descriptor.exports().toString());
    }

    @Test
    void factoryAndSequenceAreReachable() {
        assertTrue(Modifier.isPublic(Factory.class.getModifiers()));
        assertTrue(Modifier.isAbstract(Factory.class.getModifiers()));
        assertTrue(Modifier.isPublic(Sequence.class.getModifiers()));
        assertTrue(Modifier.isFinal(Sequence.class.getModifiers()));
        assertEquals(FACTORY_PACKAGE, Factory.class.getPackageName());
        assertEquals(FACTORY_PACKAGE, Sequence.class.getPackageName());
    }

    @Test
    void noMethodNamedWhenUnlessOrLazy() {
        Set<String> names = new TreeSet<>(publicMethodNames(Factory.class));
        names.addAll(publicMethodNames(Sequence.class));

        for (String forbidden : FORBIDDEN_METHODS) {
            assertFalse(names.contains(forbidden), forbidden);
        }
    }

    @Test
    void publicMethodNamesAreTheExpectedList() {
        assertEquals(new TreeSet<>(FACTORY_METHODS), publicMethodNames(Factory.class));
        assertEquals(new TreeSet<>(SEQUENCE_METHODS), publicMethodNames(Sequence.class));
    }

    @Test
    void recycleModeIsAPublicEnumWithTheThreeModes() {
        assertTrue(RecycleMode.class.isEnum());
        assertTrue(Modifier.isPublic(RecycleMode.class.getModifiers()));
        assertEquals(FACTORY_PACKAGE, RecycleMode.class.getPackageName());
        Set<String> names = new TreeSet<>();
        for (RecycleMode mode : RecycleMode.values()) {
            names.add(mode.name());
        }
        assertEquals(new TreeSet<>(Arrays.asList("IN_TURN", "RANDOM", "FIRST")), names);
    }

    @Test
    void everyNewFactoryMethodIsDeclaredWithItsOverloads() {
        Set<String> declared = new TreeSet<>();
        for (Method method : Factory.class.getMethods()) {
            if (method.getDeclaringClass() == Factory.class) {
                declared.add(signature(method));
            }
        }

        assertTrue(declared.containsAll(NEW_FACTORY_SIGNATURES), NEW_FACTORY_SIGNATURES + " missing from " + declared);
    }

    @Test
    void noHasXOrForXMagicMethodExists() {
        Set<String> allowed = new TreeSet<>(Arrays.asList("has", "hasAttached", "forParent", "forEachSequence"));

        for (String name : publicMethodNames(Factory.class)) {
            if (name.startsWith("has") || name.startsWith("for")) {
                assertTrue(allowed.contains(name), name);
            }
        }
    }

    @Test
    void noMethodReturnsOrTakesAFactoryRunOrFactorySpec() {
        for (Method method : Factory.class.getMethods()) {
            List<Class<?>> types = new ArrayList<>(Arrays.asList(method.getParameterTypes()));
            types.add(method.getReturnType());
            for (Class<?> type : types) {
                Class<?> element = elementOf(type);
                boolean isHidden = element.getPackageName().equals(FACTORY_PACKAGE)
                        && !Modifier.isPublic(element.getModifiers());
                assertFalse(isHidden, method + " names " + element.getName());
                String name = element.getSimpleName();
                assertFalse(name.equals("FactoryRun") || name.equals("FactorySpec"), method.toString());
            }
        }
    }
}
