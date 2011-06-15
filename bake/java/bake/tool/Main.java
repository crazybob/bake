// Copyright 2011 Square, Inc.
package bake.tool;

import bake.tool.java.JavaHandler;
import com.google.common.collect.Lists;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * Bake's CLI.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class Main {

  static long start = System.nanoTime();

  public static void main(String[] args) throws Exception {
    List<String> list = Lists.newArrayList(args);
    for (Iterator<String> iterator = list.iterator(); iterator.hasNext(); ) {
      String arg = iterator.next();
      if (arg.equals("-v")) {
        Log.VERBOSE = true;
        iterator.remove();
      }
    }
    main(list);
  }

  private static void main(List<String> args) throws Exception {
    try {
      if (args.size() == 0) {
        printUsage();
        return;
      } else if (args.get(0).equals("init")) {
        if (args.size() != 2) {
          System.err.println("Usage: bake init [path]");
          exit(1);
        }

        Repository.initialize(args.get(1));
        return;
      }

      Repository repo = new Repository.Builder()
          .diagnosticListener(new CliListener())
          .build();

      if (args.get(0).equals("init-java")) {
        if (args.size() != 2) {
          System.err.println("Usage: bake init-java [package name]");
          exit(1);
        }

        initializeJavaPackage(repo, args.get(1));
      } else if (args.get(0).equals("all")) {
        buildAll(repo);
      } else {
        build(repo, args);
      }
    } catch (Exception e) {
      @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
      BakeError bakeError = BakeError.unwrap(e);
      if (bakeError != null) {
        Log.e(bakeError.getMessage());
        exit(1);
      } else {
        throw e;
      }
    }
    exit(0);
  }

  static void exit(int code) {
    Log.i("Done in %dms.", (System.nanoTime() - start) / 1000000);
    System.exit(code);
  }

  private static void build(Repository repo, Iterable<String> paths)
      throws BakeError, IOException {
    repo.bakePaths(paths);
  }

  private static void buildAll(Repository repo) throws BakeError, IOException {
    repo.bakeAll();
  }

  private static void initializeJavaPackage(Repository repo,
      String packageName) throws BakeError, IOException {
    JavaHandler.initializePackage(repo, packageName);
  }

  private static void printUsage() {
    System.err.println("Usage:\n"
        + "\n"
        + "Print these usage instructions:\n"
        + "\n"
        + "  $ bake\n"
        + "\n"
        + "Turn on verbose logging:\n"
        + "\n"
        + "  $ bake -v {options}\n"
        + "\n"
        + "Initialize a Bake repository:\n"
        + "\n"
        + "  $ bake init {path}\n"
        + "\n"
        + "Initialize a Bake Java package:\n"
        + "\n"
        + "  $ bake init-java {package-name}\n"
        + "\n"
        + "Build specified packages:\n"
        + "\n"
        + "  $ bake {package-path} [{package-path}...]\n"
        + "\n"
        + "Build all packages:\n"
        + "\n"
        + "  $ bake all"
    );
  }

  /** Logs diagnostics to stdout/err. */
  private static class CliListener
      implements DiagnosticListener<FileObject> {
    com.sun.tools.javac.util.Log log
        = com.sun.tools.javac.util.Log.instance(new Context());
    public void report(Diagnostic<? extends FileObject> diagnostic) {
      // Assumes OpenJDK's compiler.
      log.report((JCDiagnostic) diagnostic);
    }
  }
}
