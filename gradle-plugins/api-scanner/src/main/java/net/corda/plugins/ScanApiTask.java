package net.corda.plugins;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.FieldInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.MethodInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import java.io.*;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class ScanApiTask extends DefaultTask {

    private final ConfigurableFileCollection sources;
    private final ConfigurableFileCollection classpath;

    public ScanApiTask() {
        sources = getProject().files();
        classpath = getProject().files();
    }

    @Input
    public FileCollection getSources() {
        return sources;
    }

    public void setSources(FileCollection sources) {
        this.sources.setFrom(sources);
    }

    @Input
    public FileCollection getClasspath() {
        return classpath;
    }

    public void setClasspath(FileCollection classpath) {
        this.classpath.setFrom(classpath);
    }

    @TaskAction
    public void scan() {
        File outputDir = new File(getProject().getBuildDir(), "api");
        if (outputDir.isDirectory() || outputDir.mkdirs()) {
            for (File source : sources) {
                scan(source, outputDir);
            }
        } else {
            getLogger().error("Cannot create directory '{}'", outputDir.getAbsolutePath());
        }
    }

    private static URL toURL(File file) throws MalformedURLException {
        return file.toURI().toURL();
    }

    private URL[] toURLs(Iterable<File> files) throws MalformedURLException {
        List<URL> urls = new LinkedList<>();
        for (File file : files) {
            urls.add(toURL(file));
        }
        return urls.toArray(new URL[urls.size()]);
    }

    private void scan(File source, File outputDir) {
        File output = new File(outputDir, source.getName().replaceAll(".jar$", ".txt"));
        try (
            URLClassLoader classpathLoader = new URLClassLoader(toURLs(classpath));
            URLClassLoader appLoader = new URLClassLoader(new URL[]{ toURL(source) }, classpathLoader);
            PrintWriter writer = new PrintWriter(output, "UTF-8")
        ) {
            ScanResult result = new FastClasspathScanner("-dir:")
                .overrideClassLoaders(appLoader)
                .ignoreParentClassLoaders()
                .ignoreMethodVisibility()
                .ignoreFieldVisibility()
                .enableMethodInfo()
                .enableFieldInfo()
                .scan();
            writeApis(writer, result);
        } catch (IOException e) {
            getLogger().error("API scan has failed", e);
        }
    }

    private void writeApis(PrintWriter writer, ScanResult result) {
        Map<String, ClassInfo> allInfo = result.getClassNameToClassInfo();
        result.getNamesOfAllClasses().forEach(className -> {
            if (className.contains(".internal.")) {
                return;
            }
            ClassInfo classInfo = allInfo.get(className);
            if (classInfo.getClassLoaders() == null) {
                return;
            }

            writeClass(writer, classInfo, result.classNameToClassRef(className));
            writeMethods(writer, classInfo.getMethodInfo());
            writeFields(writer, classInfo.getFieldInfo());
        });
    }

    private void writeClass(PrintWriter writer, ClassInfo classInfo, Class<?> javaClass) {
        writer.print("C: ");

        if (classInfo.isAnnotation()) {
            writer.append("@interface ").print(classInfo);
        } else if (classInfo.isStandardClass()) {
            if (Modifier.isAbstract(javaClass.getModifiers())) {
                writer.append("abstract ");
            } else if (Modifier.isFinal(javaClass.getModifiers())) {
                writer.append("final ");
            }
            writer.append("class ").print(classInfo);
            if (classInfo.getDirectSuperclass() != null) {
                writer.append(" extends ").print(classInfo.getDirectSuperclass());
            }
        } else {
            writer.append("interface ").print(classInfo);
            if (!classInfo.getDirectSuperinterfaces().isEmpty()) {
                writer.append(" extends ").print(toString(classInfo.getDirectSuperinterfaces()));
            }
        }

        writer.println();
    }

    private static String toString(Collection<ClassInfo> items) {
        return items.stream().map(ClassInfo::toString).collect(Collectors.joining(", "));
    }

    private void writeMethods(PrintWriter writer, List<MethodInfo> methods) {
        methods.sort(new MethodComparator());
        for (MethodInfo method : methods) {
            if (method.isPublic() || method.isProtected()) {
                writer.append("M:  ").println(method);
            }
        }
    }

    private void writeFields(PrintWriter output, List<FieldInfo> fields) {
        fields.sort(new FieldComparator());
        for (FieldInfo field : fields) {
            if (field.isPublic() || field.isProtected()) {
                output.append("F:  ").println(field);
            }
        }
    }
}
