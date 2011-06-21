// Copyright 2011 Square, Inc.
package bake.example.foo.bar;

import com.google.inject.Guice;
import java.io.File;
import java.io.IOException;
import junit.framework.TestCase;

public class BarTest extends TestCase {

  public void testTeeIsVisible() throws Exception {
    Class.forName(tee.Tee.class.getName());
  }

  public void testGuiceIsVisible() throws ClassNotFoundException {
    Guice.createInjector();
    Class.forName("javax.inject.Inject"); // Transitive.
  }

  public void testWorkingDirectory() throws IOException {
    File workingDirectory = new File(".").getCanonicalFile();
    if (!workingDirectory.getPath().endsWith("/bar")) {
      throw new AssertionError("Unexpected working directory: " + workingDirectory);
    }
  }

  public void testExternalTransitiveTestDependencyIsntVisible() {
    try {
      // log4j is included by foo's tests and shouldn't be visible here.
      Class.forName("org.apache.log4j.Logger");
      fail();
    } catch (ClassNotFoundException e) { /* expected */ }
  }

  public void testInternalTransitiveTestDependencyIsntVisible() {
    try {
      // Included by foo's tests and shouldn't be visible here.
      Class.forName("bake.example.foo.test_support.NotInBar");
      fail();
    } catch (ClassNotFoundException e) { /* expected */ }
  }
}
