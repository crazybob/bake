// Copyright 2011 Square, Inc.
package bake.tool;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.inject.Injector;
import com.simontuffs.onejar.JarClassLoader;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.parser.Token;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.tools.*;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static javax.tools.JavaFileObject.Kind;

/**
 * Parses .bake files and instantiates {@link BakePackage}s.
 *
 * @author Bob Lee (bob@squareup.com)
 */
class PackageParser {

  private final Injector injector;
  private final File root;
  private final Diagnostics diagnostics;
  private final Elements elements;
  private final Provider<Repository> repositoryProvider;

  @Inject PackageParser(Injector injector, @Root File root,
      Diagnostics diagnostics, Elements elements,
      Provider<Repository> repositoryProvider) {
    this.injector = injector;
    this.root = root;
    this.diagnostics = diagnostics;
    this.elements = elements;
    this.repositoryProvider = repositoryProvider;
  }

  /**
   * Parses the .bake file for the package with the given name and instantiates
   * its handlers.
   */
  BakePackage parse(String name) throws IOException, BakeError {
    File directory = new File(root, name.replace('.', File.separatorChar));

    // .bake file shares the same name as its containing directory.
    File bakeFile = new File(directory, directory.getName()
        + Repository.DOT_BAKE);

    // Parse .bake file.
    Element packageElement = parseBakeFile(bakeFile);
    if (packageElement == null) {
      throw new BakeError("Error parsing " + bakeFile + ".");
    }

    // Create package. We mutate the handlers map after instantiating
    // bakePackage so the handlers can reference bakePackage.
    Map<Class<? extends Annotation>, Handler> handlers
        = new HashMap<Class<? extends Annotation>, Handler>();
    final BakePackage bakePackage = new BakePackage(injector, name,
        repositoryProvider.get(), handlers, directory);

    // Iterate over annotations on the package element.
    for (AnnotationMirror annotationMirror
        : packageElement.getAnnotationMirrors()) {
      TypeElement annotationTypeElement
          = (TypeElement) annotationMirror.getAnnotationType().asElement();

      // This is an annotation type because we got it from an annotation.
      @SuppressWarnings("unchecked")
      Class<? extends Annotation> annotationType
          = (Class<? extends Annotation>) lookUp(annotationTypeElement);

      // Look up a real annotation instance.
      // TODO: Proxy the annotation instance so it can return Class objects.
      Annotation annotation = packageElement.getAnnotation(annotationType);
      Handler handler = bakePackage.newHandlerFor(annotation);
      if (handler != null) handlers.put(annotationType, handler);
    }

    if (handlers.isEmpty()) {
      throw new BakeError("No Bake annotations found in " + bakeFile + ".");
    }

    return bakePackage;
  }

  /** Looks up the given type in the current runtime. */
  private Class<?> lookUp(TypeElement element) {
    String typeName = elements.getBinaryName(element).toString();
    try {
       return Class.forName(typeName);
    } catch (ClassNotFoundException e) {
      throw new AssertionError(e);
    }
  }

  private List<BakeClass> bakeClasses;

  /**
   * Loads Bake's classes from /main/main.jar. Necessary when Bake is packaged
   * using One-Jar.
   */
  private List<BakeClass> loadBakeClasses() throws IOException {
    if (this.bakeClasses != null) return this.bakeClasses;
    InputStream in = getClass().getClassLoader().getResourceAsStream(
        "main/main.jar");
    try {
      List<BakeClass> bakeClasses = Lists.newArrayList();
      ZipInputStream zin = new ZipInputStream(new BufferedInputStream(in));
      ZipEntry entry;
      while ((entry = zin.getNextEntry()) != null) {
        String name = entry.getName();
        if (!name.endsWith(Kind.CLASS.extension)) continue;
        byte[] bytes = ByteStreams.toByteArray(zin);
        // TODO: Use File.separatorChar?
        String className = name.substring(0, name.length()
            - Kind.CLASS.extension.length()).replace('/', '.');
        bakeClasses.add(new BakeClass(className, bytes));
      }
      return this.bakeClasses = bakeClasses;
    } finally {
      in.close();
    }
  }

  /** Lists Bake classes for javac. */
  private Iterable<JavaFileObject> listBakeClasses(
      JavaFileManager.Location location, final String packageName,
      Set<JavaFileObject.Kind> kinds, final boolean recurse) throws IOException {
    List<? extends JavaFileObject> fileObjects = Lists.newArrayList(
        Iterables.filter(loadBakeClasses(), new Predicate<BakeClass>() {
      public boolean apply(BakeClass bakeClass) {
        return bakeClass.inPackage(packageName, recurse);
      }
    }));
    return Collections.unmodifiableList(fileObjects);
  }

  /**
   * Parses a {@code .bake} file. Returns the package element.
   */
  private Element parseBakeFile(File file) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager standardFileManager
        = compiler.getStandardFileManager(diagnostics, null, null);
    JavaFileObject fileObject
        = standardFileManager.getJavaFileObjects(file).iterator().next();
    fileObject = new ForwardingJavaFileObject<JavaFileObject>(fileObject) {
      @Override public Kind getKind() {
        return Kind.SOURCE;
      }
      @Override public String toString() {
        return fileObject.toString();
      }
      @Override public boolean isNameCompatible(String simpleName, Kind kind) {
        // Pretend to be a package-info.java file.
        return simpleName.equals("package-info") && kind == Kind.SOURCE;
      }
    };

    // Handle One-Jar if necessary.
    JavaFileManager fileManager = standardFileManager;
    boolean inOneJar = getClass().getClassLoader() instanceof JarClassLoader;
    if (inOneJar) {
      // Javac can't read nested jars, so we have to do it.
      fileManager = new ForwardingJavaFileManager<JavaFileManager>(
          standardFileManager) {
        @Override
        public Iterable<JavaFileObject> list(Location location,
            String packageName, Set<JavaFileObject.Kind> kinds,
            boolean recurse) throws IOException {
          if (location.equals(StandardLocation.CLASS_PATH) &&
              (packageName.equals("bake") || packageName.startsWith("bake."))) {
            return listBakeClasses(location, packageName, kinds, recurse);
          }
          return super.list(location, packageName, kinds, recurse);
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
          if (file instanceof BakeClass) return file.getName();
          return super.inferBinaryName(location, file);
        }
      };
    }

    try {
        /*
         * HACK! We change Sun's "package" constant to "module" for the duration of this
         * compile. This will cause problems if we try to compile normal Java code
         * concurrently in the same VM. This will break if Sun's internal compiler API changes.
         *
         * Long term, we probably want to generate a custom parser using ANTLR, but that would
         * require us to build a ton of infrastructure (error reporting,
         * parsing external class files, etc.).
         */

        Field tokenName = Token.class.getField("name");
        tokenName.setAccessible(true);
        try {
            tokenName.set(Token.PACKAGE, "module");
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager,
                diagnostics, null, null, Collections.singleton(fileObject));
            // Compile the source but don't actually generate a .class file.
            Iterable<? extends Element> elements = task.analyze();
            Iterator<? extends Element> iterator = elements.iterator();
            return iterator.hasNext() ? iterator.next() : null;
        } finally {
            tokenName.set(Token.PACKAGE, "package");
        }
    } catch (IllegalAccessException e) {
        throw new AssertionError(e);
    } catch (NoSuchFieldException e) {
        throw new AssertionError(e);
    }
  }

  /** Exposes a Bake class to javac so we can compile .bake files. */
  static class BakeClass implements JavaFileObject {

    final String name;
    final byte[] bytes;

    BakeClass(String name, byte[] bytes) {
      this.name = name;
      this.bytes = bytes;
    }

    /**
     * Returns true if this class is in the given package.
     *
     * @param recurse if true, this method returns true for sub-packages, too.
     */
    private boolean inPackage(String packageName, boolean recurse) {
      int lastPeriod = name.lastIndexOf('.');
      if (lastPeriod == -1) return false;
      if (recurse) return name.startsWith(packageName);
      return name.substring(0, lastPeriod).equals(packageName);
    }

    /** Returns the class name minus the package. */
    private String simpleName() {
      int lastPeriod = name.lastIndexOf('.');
      if (lastPeriod == -1) return name;
      return name.substring(lastPeriod + 1);
    }

    public Kind getKind() {
      return Kind.CLASS;
    }

    public boolean isNameCompatible(String simpleName, Kind kind) {
      return kind == Kind.CLASS && simpleName.equals(simpleName());
    }

    public NestingKind getNestingKind() {
      return NestingKind.TOP_LEVEL;
    }

    public Modifier getAccessLevel() {
      return null; // Not known.
    }

    public URI toUri() {
      return null;
    }

    public String getName() {
      return name;
    }

    public InputStream openInputStream() throws IOException {
      return new ByteArrayInputStream(bytes);
    }

    public OutputStream openOutputStream() throws IOException {
      throw new UnsupportedOperationException();
    }

    public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
      throw new UnsupportedOperationException();
    }

    public CharSequence getCharContent(boolean ignoreEncodingErrors)
        throws IOException {
      throw new UnsupportedOperationException();
    }

    public Writer openWriter() throws IOException {
      throw new UnsupportedOperationException();
    }

    public long getLastModified() {
      return 0L;
    }

    public boolean delete() {
      throw new UnsupportedOperationException();
    }
  }
}
