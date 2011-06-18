// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.Java;
import bake.tool.BakeError;
import bake.tool.Files;
import bake.tool.Handler;
import bake.tool.Log;
import bake.tool.Module;
import bake.tool.Repository;
import bake.tool.Task;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;

import javax.inject.Inject;
import javax.inject.Provider;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static bake.tool.java.ExternalDependency.isExternal;

/**
 * Bakes Java libraries.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class JavaHandler implements Handler<Java> {

  private static final String TEST_MODULE_NAME = "tests";

  final Java java;
  final Repository repository;
  final Module module;
  final ExternalDependencies externalDependencies;
  final Intellij intellij;
  final Provider<IncrementalCompiler> compilerProvider;
  final ExecutableJar executableJar = new FatJar(this);

  // TODO: Remove.
  final Map<ExternalArtifact.Id, ExternalArtifact> externalArtifacts = null;
  public Map<ExternalArtifact.Id, ExternalArtifact> externalArtifacts() {
    return externalArtifacts;
  }

  @Inject JavaHandler(Java java, Repository repository, Module module,
      Provider<IncrementalCompiler> compilerProvider, ExternalDependencies externalDependencies,
      Intellij intellij) {
    this.java = java;
    this.repository = repository;
    this.module = module;
    this.intellij = intellij;
    this.compilerProvider = compilerProvider;
    this.externalDependencies = externalDependencies;

    externalDependencies.setHandler(this);
    intellij.setHandler(this);
  }

  public Java annotation() {
    return java;
  }

  /** Returns the destination directory for classes. */
  private File classesDirectory() throws IOException {
    return module.outputDirectory("classes");
  }

  /** Returns the destination directory for test classes. */
  private File testClassesDirectory() throws IOException {
    return module.outputDirectory("test-classes");
  }

  public void bake() throws IOException, BakeError {
    // Resolve external dependencies.
    walk(new JavaTask() {
      @Override public void execute(JavaHandler handler) throws BakeError, IOException {
        handler.externalDependencies.resolve();
      }
    });

    intellij.updateAll();

//    // TODO: Export to other IDEs and build systems (like POM).
//    new Intellij(this).bake();
//    compileAll(new CompilationContext(externalDependencies));
//    if (!java.mainClass().equals("")) executableJar.bake();
//    runAllTests();
//
//    state = State.BAKED;
  }

  /**
   * Walks the module tree from bottom to top. Executes the given task against each module this
   * module depends on and then against this module.
   */
  public void walk(final JavaTask javaTask) throws BakeError, IOException {
    module.walk(Java.class, new Task() {
      @Override public void execute(Module module) throws BakeError, IOException {
        javaTask.execute(module.javaHandler());
      }
    });
  }

  /** Returns the set of direct dependencies for this module. */
  public Collection<Module> directDependencies() throws BakeError, IOException {
    List<Module> directDependencies = Lists.newArrayList();
    for (String dependency : meld(java.dependencies(), java.testDependencies())) {
      if (!isExternal(dependency)) directDependencies.add(repository.moduleByName(dependency));
    }
    return directDependencies;
  }

  /**
   * Creates a class loader containing the classes from this module and
   * all of its dependencies.
   */
  private ClassLoader classLoader() throws BakeError, IOException {
    List<URL> jarUrls = Lists.transform(allJars(), new Function<File, URL>() {
      public URL apply(File input) {
        try {
          return input.toURI().toURL();
        } catch (MalformedURLException e) {
          throw new AssertionError(e);
        }
      }
    });

    // The system class loader contains no Bake classes when run via One-Jar.
    return new URLClassLoader(jarUrls.toArray(new URL[jarUrls.size()]),
        ClassLoader.getSystemClassLoader());
  }

  /** Gathers jar files needed to run this module. */
  private List<File> allJars() throws BakeError, IOException {
    final List<File> jarFiles = Lists.newArrayList();
    walk(new JavaTask() {
      @Override public void execute(JavaHandler handler) throws BakeError, IOException {
        jarFiles.add(handler.classesJar());
        jarFiles.addAll(handler.jars());
      }
    });
    addExternalJarsTo(jarFiles);
    return jarFiles;
  }

  /** Adds all external jars to the given list. */
  private void addExternalJarsTo(List<File> jars) {
    throw new UnsupportedOperationException();
//    for (ExternalArtifact externalArtifact : externalArtifacts.values()) {
//      if (externalArtifact.id.type == ExternalArtifact.Type.JAR) {
//        jars.add(externalArtifact.file);
//      }
//    }
  }

  /** Returns this module's first order external dependencies. */
  Iterable<ExternalDependency> externalDependencies()
      throws BakeError {
    return externalDependencies(java.dependencies());
  }

  /** Returns this module's first order external test dependencies. */
  Iterable<ExternalDependency> externalTestDependencies()
      throws BakeError {
    return externalDependencies(java.testDependencies());
  }

  private Iterable<ExternalDependency> externalDependencies(String[] dependencies)
      throws BakeError {
    List<ExternalDependency> externalDependencies = Lists.newArrayList();
    for (String dependency : dependencies) {
      if (isExternal(dependency)) externalDependencies.add(ExternalDependency.parse(dependency));
    }
    return externalDependencies;
  }

  // Compile:

  private boolean compiled;

  /** Transitively compiles Java source code. */
  private void compileAll(CompilationContext context) throws BakeError,
      IOException {
    if (!context.compiling.add(this)) {
      // TODO: Output path.
      throw new BakeError("Circular dependency in " + module.name() + ".");
    }
    try {
      // It's important that we check for circular dependencies before this
      // check.
      if (compiled) return;
      compiled = true;

      // Note: We compile but don't bake() dependencies.
      compileDependencies(context);
      compileThis(context);
    } finally {
      context.compiling.remove(this);
    }
  }

  /**
   * Compiles this module and calls {@link #jarClasses()}. Only called once.
   */
  void compileThis(CompilationContext context) throws BakeError,
      IOException {
    if (hasSourceDirectories()) {
      Log.i("Compiling %s...", module.name());

      // Compile main classes.
      IncrementalCompiler mainCompiler = compilerProvider.get();
      appendCompilationDependencies(mainCompiler, context, java.dependencies());
      for (File jar : jars()) mainCompiler.appendClasspath(jar);
      for (String sourceDirectory : java.source()) {
        mainCompiler.appendSourceDirectory(new File(module.directory(), sourceDirectory));
      }
      mainCompiler.destinationDirectory(classesDirectory())
        .database(new File(module.outputDirectory(), "jmake.db"))
        .compile();

      // Compile test classes.
      IncrementalCompiler testCompiler = compilerProvider.get();
      testCompiler.appendClasspath(classesDirectory());
      for (File jar : jars()) testCompiler.appendClasspath(jar);
      appendCompilationDependencies(testCompiler, context, java.dependencies());
      appendCompilationDependencies(testCompiler, context, java.testDependencies());
      for (String sourceDirectory : java.testSource()) {
        testCompiler.appendSourceDirectory(new File(module.directory(), sourceDirectory));
      }
      testCompiler.destinationDirectory(testClassesDirectory())
        .database(new File(module.outputDirectory(), "jmake-tests.db"))
        .compile();
    } else {
      Log.v("%s has no source directories.", module.name());
    }

    jarClasses();
  }

  private void appendCompilationDependencies(IncrementalCompiler compiler,
      CompilationContext context, String[] dependencies) throws BakeError, IOException {
    for (String dependency : dependencies) {
      if (isExternal(dependency)) {
        ExternalDependency parsed = ExternalDependency.parse(dependency);
        ExternalArtifact artifact
            = context.externalDependencies.get(parsed.jarId());
        compiler.appendClasspath(artifact.file);
      } else {
        Module otherModule = repository.moduleByName(dependency);
        JavaHandler otherJava = otherModule.javaHandler();
        compiler.appendClasspath(otherJava.classesJar());
        compiler.appendClasspath(otherJava.jars());
      }
    }
  }

  /** Transitively compiles internal modules that this module depends on. */
  private void compileDependencies(CompilationContext context) throws BakeError,
      IOException {
    throw new UnsupportedOperationException();

//    for (String dependency : directDependencies()) {
//      if (!isExternal(dependency)) {
//        Module otherModule = repository.moduleByName(dependency);
//        otherModule.javaHandler().compileAll(context);
//      }
//    }
  }

  /**
   * Returns true if this Java module has source code directories.
   */
  private boolean hasSourceDirectories() {
    for (String sourceDirectory : meld(java.source(), java.testSource())) {
      if (new File(module.directory(), sourceDirectory).exists()) return true;
    }
    return false;
  }

  /** Combines elements from each array into a single set. */
  static <T> Set<T> meld(T[]... arrays) {
    Set<T> set = Sets.newLinkedHashSet();
    for (T[] array : arrays) set.addAll(Arrays.asList(array));
    return set;
  }

  /** Maintains context between Bake modules during compilation. */
  static class CompilationContext {

    final ExternalDependencies externalDependencies;

    /** Handlers that are currently being compiled. */
    final Set<JavaHandler> compiling = Sets.newLinkedHashSet();

    CompilationContext(ExternalDependencies externalDependencies) {
      this.externalDependencies = externalDependencies;
    }
  }

  // Jar:

  /** Adds internal dependencies to the given set. */
  private void addModuleDependenciesTo(Set<Module> dependencies)
      throws BakeError, IOException {
    // TODO: Order these breadth first.
    for (String dependencyId : java.dependencies()) {
      if (!isExternal(dependencyId)) {
        Module otherModule = repository.moduleByName(dependencyId);
        if (dependencies.add(otherModule)) {
          otherModule.javaHandler().addModuleDependenciesTo(dependencies);
        }
      }
    }
  }

  /**
   * Creates a classes.jar containing the classes and resources from this
   * module.
   */
  private void jarClasses() throws IOException {
    // Check class and resource modification times against classes.jar.
    long mostRecent = mostRecentModification(classesDirectory());
    for (String path : java.resources()) {
      File resourcesDirectory = new File(module.directory(), path);
      mostRecent = Math.max(mostRecent,
          mostRecentModification(resourcesDirectory));
    }
    if (mostRecent == -1) {
      Log.v("No classes or resources to jar for %s.", module.name());
      return;
    }

    File classesJar = classesJar();
    if (mostRecent <= classesJar.lastModified()) {
      Log.i("%s is up to date.", repository.relativePath(classesJar));
      return;
    }

    Log.i("Jarring classes and resources for %s...", module.name());
    File temp = new File(classesJar.getPath() + ".temp");
    FileOutputStream fout = new FileOutputStream(temp);
    try {
      ZipOutputStream zout = new ZipOutputStream(
          new BufferedOutputStream(fout));
      Set<String> paths = Sets.newHashSet("/");
      zout.putNextEntry(new ZipEntry("/"));
      zip(zout, classesDirectory(), "", paths);
      for (String path : java.resources()) {
        File resourcesDirectory = new File(module.directory(), path);
        if (resourcesDirectory.exists()) {
          zip(zout, resourcesDirectory, "", paths);
        }
      }
      zout.finish();
      zout.close();
    } finally {
      fout.close();
    }
    Files.rename(temp, classesJar);
  }

  /**
   * Returns the most recent modification time for a file in the given
   * directory.
   */
  private long mostRecentModification(File directory) {
    long mostRecent = -1;
    if (!directory.exists()) return -1;
    for (File file : directory.listFiles()) {
      if (file.isDirectory()) {
        mostRecent = Math.max(mostRecent, mostRecentModification(file));
      } else {
        mostRecent = Math.max(mostRecent, file.lastModified());
      }
    }
    return mostRecent;
  }

  /** Jar containing the classes and resources for this module. */
  File classesJar() {
    return new File(module.outputDirectory(), "classes.jar");
  }

  /** Pre-compiled jars that are part of this module. */
  List<File> jars() throws BakeError {
    if (java.jars().length == 0) return Collections.emptyList();
    List<File> jars = Lists.newArrayList();
    for (String path : java.jars()) {
      File jarFile = new File(module.directory(), path);
      if (!jarFile.exists()) {
        throw new BakeError("File not found: " + jarFile + " (module "
            + module.name() + ")");
      }
      jars.add(jarFile);
    }
    return jars;
  }

  /**
   * Recursively zips all files in directory. Prepends file names with path.
   */
  private void zip(ZipOutputStream zout, File directory, String path,
      Set<String> paths) throws IOException {
    // Add directory entries.
    if (path.length() > 0 && paths.add(path)) {
      zout.putNextEntry(new ZipEntry(path));
    }

    byte[] buffer = new byte[8192];
    for (File file : directory.listFiles()) {
      if (file.isDirectory()) {
        zip(zout, file, path + file.getName() + "/", paths);
      } else {
        zout.putNextEntry(new ZipEntry(path + file.getName()));
        com.google.common.io.Files.copy(file, zout);
        zout.closeEntry();
      }
    }
  }

  // Tests:

  /** Runs all tests, including transitive depdnencies. */
  private void runAllTests() throws BakeError, IOException {
    Log.i("Running all tests...");
//    for (Module otherModule : allModules()) {
//      // TODO: Run least dependent packges first.
//      otherModule.javaHandler().findAndRunTests();
//    }
  }

  boolean ranTests;

  /** Finds the tests for this module and runs them. */
  private void findAndRunTests() throws BakeError, IOException {
    if (ranTests) return;
    ranTests = true;

    if (!new File(module.directory(), TEST_MODULE_NAME).exists()) {
      Log.i("No tests found for %s.", module.name());
      return;
    }

    Log.i("Building tests for %s...", module.name());
    Module testModule = repository.moduleByName(
        module.name() + "." + TEST_MODULE_NAME);
    testModule.bake();

    Log.i("Running tests for %s...", module.name());
    testModule.javaHandler().runTests();
  }

  /** Runs tests in this module. */
  private void runTests() throws BakeError, IOException {
    Set<String> testClassNames = Sets.newHashSet();
    for (String sourceDirectory : java.source()) {
      findTestFiles(new File(module.directory(), sourceDirectory),
          "", testClassNames);
    }

    if (testClassNames.isEmpty()) {
      Log.i("No tests found for %s.", module.name());
    }

    /*
     * Note: We fork a new VM to run the tests. We don't have JUnit in the
     * current classloader, and even if we did, it would be different from
     * the copy in the test classloader. We'd have to use reflection to access
     * JUnit in the test classloader.
     */
    List<File> files = allJars();
    String classpath = Joiner.on(File.pathSeparatorChar).join(files);

    List<String> command = Lists.newArrayList();
    command.addAll(Arrays.asList("java", "-classpath", classpath,
        "org.junit.runner.JUnitCore"));
    command.addAll(testClassNames);

    Log.v(command.toString());

    Process process = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .directory(module.directory()) // Run from tests directory.
        .start();

    ByteStreams.copy(process.getInputStream(), System.out);
    try {
      int result = process.waitFor();
      if (result == 0) {
        Log.i("%s passed.", module.name());
      } else {
        throw new BakeError(module.name() + " failed.");
      }
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  /** Recursively searches for classes with names ending in Test. */
  private void findTestFiles(File directory, String packageName,
      Set<String> testClassNames) {
    if (!directory.exists()) {
      Log.v("Skipping missing directory: %s",
          repository.relativePath(directory));
      return;
    }
    for (File file : directory.listFiles()) {
      String name = file.getName();
      if (file.isDirectory()) {
        findTestFiles(file, packageName + name + ".", testClassNames);
      } else {
        if (name.endsWith("Test.java")) {
          testClassNames.add(packageName
              + name.substring(0, name.length() - 5));
        }
      }
    }
  }

  // Create directories:

  /** Initializes a Java module. */
  public static void initializeModule(Repository repository,
      String moduleName) throws IOException,
      BakeError {
    Repository.validateModuleName(moduleName);

    // Get default annotation.
    Package javaPackage = JavaHandler.class.getPackage();
    Java java = javaPackage.getAnnotation(Java.class);

    // Create main module.
    File moduleRoot = new File(repository.root(),
        moduleName.replace('.', File.separatorChar));
    Files.mkdirs(new File(moduleRoot, java.source()[0]));
    Files.mkdirs(new File(moduleRoot, java.resources()[0]));
    String unqualifiedName = moduleName.substring(
        moduleName.lastIndexOf('.') + 1);
    File bakeFile = new File(moduleRoot,
        unqualifiedName + Repository.DOT_BAKE);
    if (!bakeFile.exists()) {
      com.google.common.io.Files.write(
          "@bake.Java module " + moduleName + ";\n",
          bakeFile, Charsets.UTF_8);
    }

    // Create tests module.
    File testsRoot = new File(repository.root(), moduleName.replace(
        '.', File.separatorChar) + File.separatorChar + "tests");
    Files.mkdirs(new File(testsRoot, java.source()[0]));
    Files.mkdirs(new File(testsRoot, java.resources()[0]));
    File testBakeFile = new File(testsRoot, "tests" + Repository.DOT_BAKE);
    if (!testBakeFile.exists()) {
      com.google.common.io.Files.write("@bake.Java(\n"
          + "  dependencies = {\n"
          + "      \"external:junit/junit@4.+\",\n"
          + "      \"" + moduleName + "\"\n"
          + "  }\n"
          + ") module " + moduleName + ".tests;\n",
          testBakeFile, Charsets.UTF_8);
    }

    Log.i("Created " + repository.relativePath(moduleRoot) + ".");
  }
}
