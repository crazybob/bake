// Copyright 2011 Square, Inc.
package bake.example.foo;

import bake.example.foo.bar.Bar;
import com.sun.jersey.core.spi.scanning.PackageNamesScanner;
import com.sun.jersey.core.spi.scanning.ScannerListener;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class Foo {

  public static void main(String[] args) throws Exception {
    testTransitiveExport();
    testClasspathScanning();
    testDirectInternalDependency();
    testTransitiveDependencyIsVisible();
    testJunitIsntVisible();
    testTeeIsVisible();

    System.out.print("OK"); // Read by BakeTest.
  }

  private static void testTransitiveExport() throws ClassNotFoundException {
    // This comes from foo.bar.baz.
    Class.forName(org.apache.commons.lang.BitField.class.getName());
  }

  private static void testJunitIsntVisible() {
    try {
      Class.forName("junit.framework.TestCase");
      throw new AssertionError();
    } catch (ClassNotFoundException e) { /* expected */ }
  }

  private static void testTransitiveDependencyIsVisible() throws ClassNotFoundException {
    // -> foo.bar -> Guice -> javax.inject
    Class.forName("javax.inject.Inject");
  }

  private static void testTeeIsVisible() throws Exception {
    Class.forName(tee.Tee.class.getName());
  }

  private static void testDirectInternalDependency() throws AssertionError {
    if (!new Bar().lower("HELLO, WORLD!").equals("hello, world!")) throw new AssertionError();
  }

  private static void testClasspathScanning() throws IOException {
    // Note: We break down what PackageNameScanner does so we can identify where exactly it fails.

    // First check that we can look up directory resources.
    String packageName = "bake.example.foo";
    Enumeration<URL> resources = Foo.class.getClassLoader().getResources(
        packageName.replace('.', '/'));
    if (!resources.hasMoreElements()) {
      throw new AssertionError("Expected directory resources.");
    }

    // Now test the real thing.
    final List<String> names = new ArrayList<String>();
    PackageNamesScanner scanner = new PackageNamesScanner(
        Foo.class.getClassLoader(),
        new String[] { packageName });
    scanner.scan(new ScannerListener() {
      public boolean onAccept(String name) {
        names.add(name);
        return false;
      }
      public void onProcess(String name, InputStream in) throws IOException {
        throw new UnsupportedOperationException();
      }
    });
    if (names.isEmpty()) {
      throw new AssertionError("Jersey-style classpath scanning doesn't work.");
    }
  }
}
