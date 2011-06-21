// Copyright 2011 Square, Inc.
package bake.example.foo;

import java.io.File;
import java.io.IOException;
import junit.framework.TestCase;

public class FooTest extends TestCase {

  // Also see bake.example.Foo.

  public void testTestOnlyJarDependency() {
    // Tee should be available to tests at compile and run time.
    assertEquals("tee", tee.Tee.value);
  }

  public void testTransitiveDependencyIsVisible() throws ClassNotFoundException {
    // foo -> foo.bar -> Guice -> javax.inject
    Class.forName("javax.inject.Inject");
  }

  public void testWorkingDirectory() throws IOException {
    File workingDirectory = new File(".").getCanonicalFile();
    if (!workingDirectory.getPath().endsWith("foo/tests")) {
      throw new AssertionError("Unexpected working directory: " + workingDirectory);
    }
  }
}
