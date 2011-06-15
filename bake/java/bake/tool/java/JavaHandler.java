// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.Java;
import bake.tool.BakeError;
import bake.tool.BakePackage;
import bake.tool.Files;
import bake.tool.Handler;
import bake.tool.Log;
import bake.tool.Repository;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;

import javax.inject.Inject;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bakes Java libraries.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class JavaHandler implements Handler<Java> {

  private static final String TEST_PACKAGE_NAME = "tests";

  final Java java;
  final Repository repository;
  final BakePackage bakePackage;
  final ExternalDependencies external;
  final IncrementalCompiler.Builder compilerBuilder;

  final ExecutableJar executableJar = new FatJar(this);

  /** True if this is a test package. */
  final boolean testPackage;

  @Inject JavaHandler(Java java, Repository repository,
      BakePackage bakePackage, IncrementalCompiler.Builder compilerBuilder) {
    this.java = java;
    this.repository = repository;
    this.bakePackage = bakePackage;
    this.compilerBuilder = compilerBuilder;
    this.external = new ExternalDependencies(this);

    this.testPackage = bakePackage.name().endsWith("." + TEST_PACKAGE_NAME);
  }

  public Java annotation() {
    return java;
  }

  /** Returns the destination directory for classes. */
  private File classesDirectory() throws IOException {
    return bakePackage.outputDirectory("classes");
  }

  private Map<ExternalArtifact.Id, ExternalArtifact> externalArtifacts;

  /** Returns all external artifacts required by this package. */
  Map<ExternalArtifact.Id, ExternalArtifact> externalArtifacts() {
    return externalArtifacts;
  }

  public void bake() throws IOException, BakeError {
    externalArtifacts = external.retrieveAll();
    new Intellij(this).bake();
    compileAll(new CompilationContext(externalArtifacts));
    if (!java.mainClass().equals("")) executableJar.bake();
    if (!testPackage) runAllTests();
  }

  /**
   * Creates a class loader containing the classes from this package and
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

  /** Gathers jar files needed to run this package. */
  private List<File> allJars() throws BakeError, IOException {
    List<File> jarFiles = Lists.newArrayList();
    for (BakePackage bakePackage : allPackages()) {
      jarFiles.add(bakePackage.javaHandler().classesJar());
      jarFiles.addAll(bakePackage.javaHandler().jars());
    }
    addExternalJarsTo(jarFiles);
    return jarFiles;
  }

  /** Adds all external jars to the given list. */
  private void addExternalJarsTo(List<File> jars) {
    for (ExternalArtifact externalArtifact : externalArtifacts.values()) {
      if (externalArtifact.id.type == ExternalArtifact.Type.JAR) {
        jars.add(externalArtifact.file);
      }
    }
  }

  /** Returns this package's first order external dependencies. */
  Iterable<ExternalDependency> externalDependencies()
      throws BakeError {
    List<ExternalDependency> externalDependencies = Lists.newArrayList();
    for (String dependency : java.dependencies()) {
      if (ExternalDependency.isExternal(dependency)) {
        externalDependencies.add(ExternalDependency.parse(dependency));
      }
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
      throw new BakeError("Circular dependency in " + bakePackage.name() + ".");
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

  /** Compiles this package and calls {@link #jarClasses()}. */
  void compileThis(CompilationContext context) throws BakeError,
      IOException {
    if (hasSourceDirectories()) {

      // TODO: We want to support packages that do nothing but aggregate
      // other dependencies. Use a different Bake annotation? Should we go
      // ahead and include transitive dependencies in the compilation
      // classpath? That wouldn't be as clean...

      Log.i("Compiling %s...", bakePackage.name());

      // Add first-order dependencies to classpath.
      for (String dependency : java.dependencies()) {
        if (ExternalDependency.isExternal(dependency)) {
          ExternalDependency parsed = ExternalDependency.parse(dependency);
          ExternalArtifact artifact
              = context.externalArtifacts.get(parsed.jarId());
          compilerBuilder.appendClasspath(artifact.file);
        } else {
          BakePackage otherPackage = repository.packageByName(dependency);
          JavaHandler otherJava = otherPackage.javaHandler();
          compilerBuilder.appendClasspath(otherJava.classesJar());
          compilerBuilder.appendClasspath(otherJava.jars());
        }
      }

      // Pre-compiled jars in this package.
      for (File jar : jars()) compilerBuilder.appendClasspath(jar);

      for (String sourceDirectory : java.source()) {
        compilerBuilder.appendSourceDirectory(
            new File(bakePackage.directory(), sourceDirectory));
      }

      compilerBuilder.destinationDirectory(classesDirectory())
        .database(new File(bakePackage.outputDirectory(), "jmake.db"))
        .build()
        .compile();
    } else {
      Log.i("%s has no source directories.", bakePackage.name());
    }

    jarClasses();
  }

  /** Transitively compiles internal packages that this package depends on. */
  private void compileDependencies(CompilationContext context) throws BakeError,
      IOException {
    for (String dependency : java.dependencies()) {
      if (!ExternalDependency.isExternal(dependency)) {
        BakePackage otherPackage = repository.packageByName(dependency);
        otherPackage.javaHandler().compileAll(context);
      }
    }
  }

  /**
   * Returns true if this Java module has source code directories.
   */
  private boolean hasSourceDirectories() {
    for (String sourceDirectory : java.source()) {
      if (new File(bakePackage.directory(), sourceDirectory).exists()) {
        return true;
      }
    }
    return false;
  }

  /** Maintains context between Bake packages during compilation. */
  static class CompilationContext {

    final Map<ExternalArtifact.Id, ExternalArtifact> externalArtifacts;

    /** Handlers that are currently being compiled. */
    final Set<JavaHandler> compiling = Sets.newLinkedHashSet();

    CompilationContext(
        Map<ExternalArtifact.Id, ExternalArtifact> externalArtifacts) {
      this.externalArtifacts = externalArtifacts;
    }
  }

  // Jar:

  /**
   * Returns all of the packages this package transitively depends on
   * (including this one).
   */
  Set<BakePackage> allPackages() throws BakeError, IOException {
    Set<BakePackage> allPackages = Sets.newHashSet();
    allPackages.add(bakePackage);
    addPackageDependenciesTo(allPackages);
    return allPackages;
  }

  /** Adds internal dependencies to the given set. */
  private void addPackageDependenciesTo(Set<BakePackage> dependencies)
      throws BakeError, IOException {
    // TODO: Order these breadth first.
    for (String dependencyId : java.dependencies()) {
      if (!ExternalDependency.isExternal(dependencyId)) {
        BakePackage otherPackage = repository.packageByName(dependencyId);
        if (dependencies.add(otherPackage)) {
          otherPackage.javaHandler().addPackageDependenciesTo(dependencies);
        }
      }
    }
  }

  /**
   * Creates a classes.jar containing the classes and resources from this
   * package.
   */
  private void jarClasses() throws IOException {
    // Check class and resource modification times against classes.jar.
    long mostRecent = mostRecentModification(classesDirectory());
    for (String path : java.resources()) {
      File resourcesDirectory = new File(bakePackage.directory(), path);
      mostRecent = Math.max(mostRecent,
          mostRecentModification(resourcesDirectory));
    }
    if (mostRecent == -1) {
      Log.v("No classes or resources to jar for %s.", bakePackage.name());
      return;
    }

    File classesJar = classesJar();
    if (mostRecent <= classesJar.lastModified()) {
      Log.i("%s is up to date.", repository.relativePath(classesJar));
      return;
    }

    Log.i("Jarring classes and resources for %s...", bakePackage.name());
    File temp = new File(classesJar.getPath() + ".temp");
    FileOutputStream fout = new FileOutputStream(temp);
    try {
      ZipOutputStream zout = new ZipOutputStream(
          new BufferedOutputStream(fout));
      Set<String> paths = Sets.newHashSet("/");
      zout.putNextEntry(new ZipEntry("/"));
      zip(zout, classesDirectory(), "", paths);
      for (String path : java.resources()) {
        File resourcesDirectory = new File(bakePackage.directory(), path);
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

  /** Jar containing the classes and resources for this package. */
  File classesJar() {
    return new File(bakePackage.outputDirectory(), "classes.jar");
  }

  /** Pre-compiled jars that are part of this package. */
  List<File> jars() throws BakeError {
    if (java.jars().length == 0) return Collections.emptyList();
    List<File> jars = Lists.newArrayList();
    for (String path : java.jars()) {
      File jarFile = new File(bakePackage.directory(), path);
      if (!jarFile.exists()) {
        throw new BakeError("File not found: " + jarFile + " (package "
            + bakePackage.name() + ")");
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
    for (BakePackage otherPackage : allPackages()) {
      // TODO: Run least dependent packges first.
      otherPackage.javaHandler().findAndRunTests();
    }
  }

  boolean ranTests;

  /** Finds the tests for this package and runs them. */
  private void findAndRunTests() throws BakeError, IOException {
    if (ranTests) return;
    ranTests = true;

    if (!new File(bakePackage.directory(), TEST_PACKAGE_NAME).exists()) {
      Log.i("No tests found for %s.", bakePackage.name());
      return;
    }

    Log.i("Building tests for %s...", bakePackage.name());
    BakePackage testPackage = repository.packageByName(
        bakePackage.name() + "." + TEST_PACKAGE_NAME);
    testPackage.bake();

    Log.i("Running tests for %s...", bakePackage.name());
    testPackage.javaHandler().runTests();
  }

  /** Runs tests in this package. */
  private void runTests() throws BakeError, IOException {
    Set<String> testClassNames = Sets.newHashSet();
    for (String sourceDirectory : java.source()) {
      findTestFiles(new File(bakePackage.directory(), sourceDirectory),
          "", testClassNames);
    }

    if (testClassNames.isEmpty()) {
      Log.i("No tests found for %s.", bakePackage.name());
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
        .directory(bakePackage.directory()) // Run from tests directory.
        .start();

    ByteStreams.copy(process.getInputStream(), System.out);
    try {
      int result = process.waitFor();
      if (result == 0) {
        Log.i("%s passed.", bakePackage.name());
      } else {
        throw new BakeError(bakePackage.name() + " failed.");
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

  /** Initializes a Java package. */
  public static void initializePackage(Repository repository,
      String packageName) throws IOException,
      BakeError {
    Repository.validatePackageName(packageName);

    // Get default annotation.
    Package javaPackage = JavaHandler.class.getPackage();
    Java java = javaPackage.getAnnotation(Java.class);

    // Create main package.
    File packageRoot = new File(repository.root(),
        packageName.replace('.', File.separatorChar));
    Files.mkdirs(new File(packageRoot, java.source()[0]));
    Files.mkdirs(new File(packageRoot, java.resources()[0]));
    String unqualifiedName = packageName.substring(
        packageName.lastIndexOf('.') + 1);
    File bakeFile = new File(packageRoot,
        unqualifiedName + Repository.DOT_BAKE);
    if (!bakeFile.exists()) {
      com.google.common.io.Files.write(
          "@bake.Java package " + packageName + ";\n",
          bakeFile, Charsets.UTF_8);
    }

    // Create tests package.
    File testsRoot = new File(repository.root(), packageName.replace(
        '.', File.separatorChar) + File.separatorChar + "tests");
    Files.mkdirs(new File(testsRoot, java.source()[0]));
    Files.mkdirs(new File(testsRoot, java.resources()[0]));
    File testBakeFile = new File(testsRoot, "tests" + Repository.DOT_BAKE);
    if (!testBakeFile.exists()) {
      com.google.common.io.Files.write("@bake.Java(\n"
          + "  dependencies = {\n"
          + "      \"external:junit/junit@4.+\",\n"
          + "      \"" + packageName + "\"\n"
          + "  }\n"
          + ") package " + packageName + ".tests;\n",
          testBakeFile, Charsets.UTF_8);
    }

    Log.i("Created " + repository.relativePath(packageRoot) + ".");
  }
}
