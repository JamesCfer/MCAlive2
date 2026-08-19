package dev.celestia.mcalive2.gadget;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles a single Java source file entirely in memory and loads the resulting class,
 * without ever touching disk. Deliberately Bukkit-decoupled (only touches
 * {@code org.bukkit.Bukkit} reflectively, via the caller-supplied parent classloader) so
 * it can be unit-tested with a plain JDK interface.
 *
 * <p>Every {@code compile} call gets its own fresh in-memory classloader, so recompiling
 * the same class name (e.g. redefining a gadget) never collides with a previous version -
 * the old class + its bytecode are simply dropped once nothing references them anymore.
 */
public class GadgetCompiler {

    /**
     * Compile {@code source} - which may declare ANY package and ANY public class name, as
     * long as exactly one public top-level concrete class implements {@code expectedInterface} -
     * and return a fresh no-arg instance of that class. The author does not need to know or
     * match any particular class name; the implementing class is discovered after compilation.
     *
     * @param source            complete Java source, e.g. {@code "public class Foo implements ... { ... }"}
     * @param expectedInterface the interface/superclass the gadget class must implement/extend
     * @param parent            parent classloader for the compiled class (Bukkit + plugin types
     *                          resolve through it); typically the plugin's own classloader
     * @throws GadgetCompileException on a compiler error, or if not exactly one implementing class is found
     */
    public <T> T compile(String source, Class<T> expectedInterface, ClassLoader parent)
            throws GadgetCompileException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new GadgetCompileException(
                    "No system Java compiler available - the server must run on a JDK, not a JRE, "
                            + "for gadget compilation.");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager stdFileManager =
                compiler.getStandardFileManager(diagnostics, Locale.getDefault(), java.nio.charset.StandardCharsets.UTF_8);
        InMemoryFileManager fileManager = new InMemoryFileManager(stdFileManager);

        String classpath = assembleClasspath();
        List<String> options = Arrays.asList("-classpath", classpath);

        // The source object accepts any public class name (isNameCompatible always true), so
        // the author is free to name the class whatever they like.
        JavaFileObject sourceObject = new StringSourceObject(source);
        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null, List.of(sourceObject));

        boolean success = task.call();

        if (!success) {
            throw new GadgetCompileException(formatDiagnostics(diagnostics));
        }

        Map<String, byte[]> bytecode = fileManager.bytecode();
        if (bytecode.isEmpty()) {
            throw new GadgetCompileException("Compilation produced no class output"
                    + (diagnostics.getDiagnostics().isEmpty() ? "" : ("\n" + formatDiagnostics(diagnostics))));
        }

        InMemoryClassLoader loader = new InMemoryClassLoader(bytecode, parent);

        // Discover the gadget class: load every compiled class (without initializing it) and
        // keep the public, top-level, concrete ones that implement the expected interface.
        List<Class<?>> candidates = new ArrayList<>();
        for (String name : bytecode.keySet()) {
            Class<?> c;
            try {
                c = Class.forName(name, false, loader);
            } catch (Throwable e) {
                continue; // helper/nested class that can't resolve standalone - not our entry point
            }
            if (!expectedInterface.isAssignableFrom(c)) continue;
            int mods = c.getModifiers();
            if (!java.lang.reflect.Modifier.isPublic(mods)) continue;
            if (java.lang.reflect.Modifier.isAbstract(mods) || c.isInterface()) continue;
            if (c.getEnclosingClass() != null) continue; // skip member/anonymous/local classes
            candidates.add(c);
        }

        if (candidates.isEmpty()) {
            throw new GadgetCompileException(
                    "No public class implementing " + expectedInterface.getName() + " was found. "
                            + "Declare your gadget as: public class YourName implements "
                            + expectedInterface.getName() + " { ... }");
        }
        if (candidates.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (Class<?> c : candidates) {
                if (names.length() > 0) names.append(", ");
                names.append(c.getName());
            }
            throw new GadgetCompileException(
                    "Found more than one public class implementing " + expectedInterface.getName()
                            + " (" + names + "). Declare exactly one public gadget class per source.");
        }

        Class<?> loaded = candidates.get(0);
        try {
            @SuppressWarnings("unchecked")
            T instance = (T) loaded.getDeclaredConstructor().newInstance();
            return instance;
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new GadgetCompileException(
                    "Failed to instantiate " + loaded.getName() + " (needs a public no-arg constructor): "
                            + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
        }
    }

    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder sb = new StringBuilder("Gadget compilation failed:\n");
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            sb.append(d.getKind()).append(": ");
            if (d.getSource() != null) {
                sb.append(d.getSource().getName()).append(':').append(d.getLineNumber())
                        .append(':').append(d.getColumnNumber()).append(' ');
            }
            sb.append(d.getMessage(Locale.getDefault())).append('\n');
        }
        return sb.toString();
    }

    /**
     * Classpath for compiling gadgets: the plugin jar, the server jar, and whatever the
     * current JVM was launched with - so gadget source can reference Bukkit API, other
     * plugin classes, and any library already on the classpath.
     */
    private static String assembleClasspath() {
        java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>();

        // This plugin's own jar (GadgetContract/GadgetContext live here).
        addCodeSourceLocation(entries, GadgetCompiler.class);

        // Everything a gadget realistically writes against. These are resolved
        // reflectively and each contributes the jar it actually lives in, which on
        // Paper is NOT necessarily the launched server.jar (paperclip patches and
        // caches the real server jar elsewhere, and libraries like gson and
        // adventure are loaded from their own paths). Missing any of these shows up
        // as a baffling "package does not exist" for gadget authors.
        for (String name : new String[]{
                "org.bukkit.Bukkit",                       // Bukkit/Paper API
                "com.google.gson.JsonObject",              // gadget args/return type
                "net.kyori.adventure.text.Component",      // chat components
                "io.papermc.paper.threadedregions.scheduler.ScheduledTask", // Paper API extras
        }) {
            addCodeSourceLocation(entries, classOrNull(name));
        }

        // Anything else the plugin classloader was given (Paper exposes plugin and
        // library jars this way), so gadgets can use whatever the plugin can.
        ClassLoader cl = GadgetCompiler.class.getClassLoader();
        if (cl instanceof java.net.URLClassLoader ucl) {
            for (java.net.URL u : ucl.getURLs()) addLocation(entries, u);
        }

        // Every jar Paper downloaded into the server's libraries/ tree. Naming
        // individual classes above is not enough: javac also needs the TRANSITIVE
        // deps of the API it is reading (adventure-key behind Component, guava
        // behind Material, bungee-chat behind the chat API...). Those only exist as
        // library jars, so hand javac the whole tree rather than playing
        // whack-a-mole with "class file for X not found".
        addServerLibraryJars(entries);

        String jcp = System.getProperty("java.class.path");
        if (jcp != null && !jcp.isBlank()) entries.add(jcp);
        return String.join(java.io.File.pathSeparator, entries);
    }

    /**
     * Recursively collect every .jar under the server's {@code libraries/} directory
     * (Paper's Maven-layout cache of its own dependencies), relative to the server's
     * working directory. Best-effort and bounded: a missing directory is fine, and a
     * hard cap keeps a pathological tree from building an enormous classpath.
     */
    private static void addServerLibraryJars(java.util.Collection<String> entries) {
        java.io.File root = new java.io.File("libraries");
        if (!root.isDirectory()) return;
        java.util.ArrayDeque<java.io.File> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        int added = 0;
        while (!stack.isEmpty() && added < 2000) {
            java.io.File dir = stack.pop();
            java.io.File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (java.io.File f : kids) {
                if (f.isDirectory()) {
                    stack.push(f);
                } else if (f.getName().endsWith(".jar")) {
                    entries.add(f.getAbsolutePath());
                    added++;
                }
            }
        }
    }

    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name, false, GadgetCompiler.class.getClassLoader());
        } catch (Throwable e) {
            return null;
        }
    }

    private static void addCodeSourceLocation(java.util.Collection<String> entries, Class<?> cls) {
        if (cls == null) return;
        try {
            ProtectionDomain pd = cls.getProtectionDomain();
            if (pd == null) return;
            CodeSource cs = pd.getCodeSource();
            if (cs == null || cs.getLocation() == null) return;
            addLocation(entries, cs.getLocation());
        } catch (Exception ignored) {
            // best-effort; the java.class.path fallback usually covers this anyway
        }
    }

    private static void addLocation(java.util.Collection<String> entries, java.net.URL url) {
        if (url == null) return;
        try {
            entries.add(new java.io.File(url.toURI()).getAbsolutePath());
        } catch (Exception e) {
            // Non-file URLs (jar:, nested paths) can't be handed to javac directly;
            // fall back to the raw path, which is right often enough to be worth trying.
            String p = url.getPath();
            if (p != null && !p.isBlank()) entries.add(p);
        }
    }

    /**
     * Wraps a String as a compilable Java source file object with no disk involvement.
     * {@link #isNameCompatible} always returns true so javac accepts whatever public class
     * name the author declared, regardless of this object's synthetic URI - the caller does
     * not need to know the class name up front.
     */
    private static final class StringSourceObject extends SimpleJavaFileObject {
        private final String code;

        StringSourceObject(String code) {
            super(URI.create("string:///Gadget" + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public boolean isNameCompatible(String simpleName, Kind kind) {
            return kind == Kind.SOURCE;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    /** Captures compiled .class bytes to an in-memory map instead of writing them to disk. */
    private static final class InMemoryClassObject extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final String className;

        InMemoryClassObject(String className, Kind kind) {
            super(URI.create("bytes:///" + className.replace('.', '/') + kind.extension), kind);
            this.className = className;
        }

        @Override
        public ByteArrayOutputStream openOutputStream() {
            return bytes;
        }

        byte[] bytecode() {
            return bytes.toByteArray();
        }

        String className() {
            return className;
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, InMemoryClassObject> classObjects = new HashMap<>();

        InMemoryFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                     javax.tools.FileObject sibling) {
            InMemoryClassObject obj = new InMemoryClassObject(className, kind);
            classObjects.put(className, obj);
            return obj;
        }

        Map<String, byte[]> bytecode() {
            Map<String, byte[]> out = new HashMap<>();
            for (InMemoryClassObject obj : classObjects.values()) {
                out.put(obj.className(), obj.bytecode());
            }
            return out;
        }
    }

    /**
     * Loads classes from an in-memory bytecode map. A fresh instance is created per
     * {@link #compile} call, so recompiling the same class name never collides with a
     * previously loaded version of it.
     */
    private static final class InMemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> bytecode;

        InMemoryClassLoader(Map<String, byte[]> bytecode, ClassLoader parent) {
            super(parent);
            this.bytecode = bytecode;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = bytecode.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Thrown on compile failure; the message carries the complete javac diagnostic text. */
    public static final class GadgetCompileException extends Exception {
        public GadgetCompileException(String message) {
            super(message);
        }
    }
}
