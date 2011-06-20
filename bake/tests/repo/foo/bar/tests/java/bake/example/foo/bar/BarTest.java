// Copyright 2011 Square, Inc.
package bake.example.foo.bar;

import com.google.inject.Guice;
import junit.framework.TestCase;

public class BarTest extends TestCase {

  private static void testTeeIsVisible() throws Exception {
    Class.forName(tee.Tee.class.getName());
  }

  public void testGuiceIsVisible() {
    Guice.creatInjector();
    Class.forName("javax.inject.Inject"); // Transitive.
  }
}
