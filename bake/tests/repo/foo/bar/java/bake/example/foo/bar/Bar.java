// Copyright 2011 Square, Inc.
package bake.example.foo.bar;

import com.google.inject.Guice;
import java.io.File;
import java.io.IOException;

public class Bar {

  public String lower(String s) {
    return s.toLowerCase();
  }

  public static void main(String[] args) throws Exception {
    testJunitIsntVisible();
    testTeeIsntVisible();
    testGuiceIsVisible();
    testWorkingDirectory();

    System.out.print("OK"); // Read by BakeTest.
  }

  private static void testWorkingDirectory() throws IOException {
    File workingDirectory = new File(".").getCanonicalFile();
    if (!workingDirectory.getPath().endsWith("/bake")) {
      throw new AssertionError("Unexpected working directory: " + workingDirectory);
    }
  }

  private static void testGuiceIsVisible() throws ClassNotFoundException {
    Guice.createInjector();
    Class.forName("javax.inject.Inject"); // Transitive.
  }

  private static void testTeeIsntVisible() {
    try {
      // Tee is a test jar dependency.
      Class.forName("tee.Tee");
      throw new AssertionError();
    } catch (ClassNotFoundException e) { /* expected */ }
  }

  private static void testJunitIsntVisible() {
    try {
      Class.forName("junit.framework.TestCase");
      throw new AssertionError();
    } catch (ClassNotFoundException e) { /* expected */ }
  }
}
