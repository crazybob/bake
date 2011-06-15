// Copyright 2011 Square, Inc.
package bake.example.foo;

import bake.example.foo.bar.Bar;
import com.sun.jersey.core.spi.scanning.PackageNamesScanner;
import com.sun.jersey.core.spi.scanning.ScannerListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class Foo {

  public static void main(String[] args) throws Exception {
    testClasspathScanning();

    Bar bar = com.google.inject.Guice.createInjector().getInstance(Bar.class);
    if (!bar.lower("HELLO, WORLD!").equals("hello, world!")) {
      throw new AssertionError();
    }
    if (!tee.Tee.value.equals("tee")) throw new AssertionError();
    System.out.print("OK");
  }

  private static void testClasspathScanning() {
    final List<String> names = new ArrayList<String>();
    PackageNamesScanner scanner = new PackageNamesScanner(
        Foo.class.getClassLoader(),
        new String[]{ "bake.example.foo" });
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
