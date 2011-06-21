// Copyright 2011 Square, Inc.
package bake.example.foo.bar;

import com.google.inject.Guice;

public class Bar {

  public String lower(String s) {
    return s.toLowerCase();
  }

  public static void main(String[] args) throws ClassNotFoundException {
    testJunitIsntVisible();
    testTeeIsntVisible();
    testGuiceIsVisible();

    System.out.print("OK"); // Read by BakeTest.
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
