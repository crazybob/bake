// Copyright 2011 Square, Inc.
package bake.tool;

import com.google.common.io.*;
import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Builds "foo" in the test repo.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class BakeTest extends TestCase {

  public void setUp() throws IOException {
    File out = new File("tests/repo/out");
    if (out.exists())
      com.google.common.io.Files.deleteRecursively(out);
  }

  public void testFoo() throws IOException, InterruptedException {
    // We build foo twice. Once clean and then again.
    for (int i = 0; i < 2; i++) {
      System.out.println("Test Build #" + i);
      Process p = new ProcessBuilder(
          new File("../out/bin/bake").getCanonicalPath(), "-v", "foo")
          .redirectErrorStream(true)
          .directory(new File("tests/repo"))
          .start();
      ByteStreams.copy(p.getInputStream(), System.out);
      assertEquals(0, p.waitFor());
    }

    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    Process p = new ProcessBuilder("tests/repo/out/bin/foo")
        .redirectErrorStream(true)
        .start();
    ByteStreams.copy(p.getInputStream(), bout);
    assertEquals("OK", new String(bout.toByteArray()));
  }

  public void testBar() throws Exception {
    // Build and test foo.bar.
    Process p = new ProcessBuilder(
        new File("../out/bin/bake").getCanonicalPath(), "-v", "foo.bar")
        .redirectErrorStream(true)
        .directory(new File("tests/repo"))
        .start();
    ByteStreams.copy(p.getInputStream(), System.out);
    assertEquals(0, p.waitFor());

    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    p = new ProcessBuilder("tests/repo/out/bin/foo.bar")
        .redirectErrorStream(true)
        .start();
    ByteStreams.copy(p.getInputStream(), bout);
    assertEquals("OK", new String(bout.toByteArray()));
  }

  public void testAll() throws Exception {
    Process p = new ProcessBuilder(
        new File("../out/bin/bake").getCanonicalPath(), "-v", "all")
        .redirectErrorStream(true)
        .directory(new File("tests/repo"))
        .start();
    ByteStreams.copy(p.getInputStream(), System.out);
    assertEquals(0, p.waitFor());
  }

  // TODO: Test bake init/init-java.
}
