// Copyright 2011 Square, Inc.
package bake.example.foo;

import junit.framework.TestCase;

public class FooTest extends TestCase {

  // Also see bake.example.Foo.

  public void testTestOnlyJarDependency() {
    // Tee should be available to tests at compile and run time.
    assertEquals("tee", tee.Tee.value);
  }

  public void testTransitiveDependencyIsVisible() {
    try {
      // -> foo.bar -> Guice -> javax.inject
      Class.forName("javax.inject.Inject");
      throw new AssertionError();
    } catch (ClassNotFoundException e) { /* expected */ }
  }
}
