// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.tool.BakeError;
import bake.tool.Diagnostics;
import bake.tool.Log;
import bake.tool.LogPrefixes;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.sun.tools.jmake.Main;
import com.sun.tools.jmake.PublicExceptions;

import javax.inject.Inject;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Incrementally compiles Java source code. Decouples Bake from jmake.
 *
 * @author Bob Lee (bob@squareup.com)
 */
class IncrementalCompiler {

  private final Diagnostics diagnostics;

  @Inject IncrementalCompiler(Diagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  private final List<File> classpath = Lists.newArrayList();

  /**
   * Appends a jar or class directory to the compiler classpath.
   */
  IncrementalCompiler appendClasspath(File file) {
    classpath.add(file);
    return this;
  }

  /**
   * Appends jar or class directories to the compiler classpath.
   */
  IncrementalCompiler appendClasspath(Collection<? extends File> files) {
    classpath.addAll(files);
    return this;
  }

  private final List<File> sourceDirectories = Lists.newArrayList();
  private final List<String> sourceFiles = Lists.newArrayList();

  /**
   * Adds a directory of Java files that should be compiled.
   */
  IncrementalCompiler appendSourceDirectory(File directory) {
    sourceDirectories.add(directory);

    // Recursively find .java files.
    for (File file : directory.listFiles()) {
      if (file.getPath().endsWith(".java")) {
        sourceFiles.add(file.getPath());
      } else if (file.isDirectory()) {
        appendSourceDirectory(file);
      }
    }
    return this;
  }

  private File destinationDirectory;

  /**
   * Specifies a destination directory for the class files.
   */
  IncrementalCompiler destinationDirectory(File directory) {
    this.destinationDirectory = directory;
    return this;
  }

  private File database;

  /**
   * Database used to store dependency metadata.
   */
  IncrementalCompiler database(File file) {
    this.database = file;
    return this;
  }

  /**
   * Compiles the source 
   */
  void compile() throws IOException, BakeError {
    try {
      String classpathString
          = Joiner.on(File.pathSeparatorChar).join(classpath);
      Main.setClassPath(classpathString);

      Log.v("Classpath: %s", classpathString);

      PrintStream out = DecoratedPrintStream.decorate(System.out);
      PrintStream err = DecoratedPrintStream.decorate(System.err);
      Main.setOutputStreams(out, err, err);

      Main jmake = new Main();

      Method method = getClass().getDeclaredMethod("compile", String[].class);
      method.setAccessible(true);

      method.invoke(this, (Object) new String[0]);

      jmake.mainProgrammaticControlled(
          sourceFiles.toArray(new String[sourceFiles.size()]),
          destinationDirectory.getPath(),
          database.getPath(),
          this,
          method
      );
    } catch (IOException e) {
      throw e;
    } catch (PublicExceptions.CompilerInteractionException e) {
      // Thrown when compile(String[]) returns a non-zero result.
      throw new BakeError("Compilation failed.");
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  // Called by jmake.
  @SuppressWarnings("UnusedDeclaration")
  int compile(String[] javaFiles) throws Exception {
    // TODO: Fork if we aren't running in One-Jar.
    try {
      if (javaFiles.length == 0) {
        Log.v("Classes are up to date.");
        return 0;
      }

      Log.i("[Re]compiling %d files...", javaFiles.length);
      Log.v("Compiling: %s", Arrays.asList(javaFiles));

      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      StandardJavaFileManager fileManager
          = compiler.getStandardFileManager(diagnostics, null, null);
      fileManager.setLocation(StandardLocation.CLASS_OUTPUT,
          Collections.singleton(destinationDirectory));
      fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
      fileManager.setLocation(StandardLocation.SOURCE_PATH,
          sourceDirectories);
      Iterable<? extends JavaFileObject> javaFileObjects
          = fileManager.getJavaFileObjects(javaFiles);
      JavaCompiler.CompilationTask task = compiler.getTask(
          null, fileManager, diagnostics, null, null, javaFileObjects);
      return task.call() ? 0 : 1;
    } catch (Exception e) {
      // The original stacktrace seems to get lost when this goes through
      // jmake. Log it here. This should only occur if there's a bug in Bake.
      e.printStackTrace();
      throw e;
    }
  }

  /**
   * Prepends log messages with "jmake".
   */
  private static class DecoratedPrintStream extends PrintStream {

    private static final String PREFIX = LogPrefixes.JMAKE;

    private DecoratedPrintStream(OutputStream out)
        throws UnsupportedEncodingException {
      super(out, true, "UTF-8");
    }

    static PrintStream decorate(OutputStream out) {
      try {
        return new DecoratedPrintStream(out);
      } catch (UnsupportedEncodingException e) {
        throw new AssertionError(e);
      }
    }

    // Note: This doesn't handle line breaks in the message itself.

    boolean needsPrefix = true;

    private void printPrefix() {
      if (needsPrefix) super.print(PREFIX);
      needsPrefix = false;
    }

    @Override public void println(String s) {
      printPrefix();
      super.println(s);
      needsPrefix = true;
    }

    @Override public void print(String s) {
      printPrefix();
      super.print(s);
    }
  }
}
