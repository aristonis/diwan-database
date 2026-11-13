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
import java.util.Set;
import java.util.TreeSet;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.factory.Sequence;
import org.junit.jupiter.api.Test;

/** The factory package is exported and its public surface is the planned list and nothing else. */
class FactoryApiShapeTest {

    private static final String FACTORY_PACKAGE = "org.aristonis.diwan.database.eloquent.factory";

    private static final Set<String> FACTORY_METHODS = Set.of("of", "state", "set", "sequence", "forEachSequence",
            "trashed", "count", "afterMaking", "afterCreating", "withoutAfterMaking", "withoutAfterCreating",
            "connection", "make", "makeMany", "create", "createMany", "createQuietly", "createManyQuietly", "raw",
            "rawMany", "insert");

    private static final Set<String> SEQUENCE_METHODS = Set.of("of", "byIndex", "and", "crossJoin", "size");

    private static final Set<String> FORBIDDEN_METHODS = Set.of("when", "unless", "lazy", "has", "for", "recycle",
            "withoutParents");

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
    void noMethodNamedWhenUnlessLazyHasForRecycleOrWithoutParents() {
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
}
