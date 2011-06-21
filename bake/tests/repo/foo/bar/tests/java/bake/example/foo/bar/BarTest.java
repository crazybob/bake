// Copyright 2011 Square, Inc.
package bake.example.foo.bar;

import com.google.inject.Guice;
import junit.framework.TestCase;

public class BarTest extends TestCase {

  public void testTeeIsVisible() throws Exception {
    Class.forName(tee.Tee.class.getName());
  }

  public void testGuiceIsVisible() throws ClassNotFoundException {
    Guice.createInjector();
    Class.forName("javax.inject.Inject"); // Transitive.
  }
}
