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
    File out = new File("repo/out");
    if (out.exists())
      com.google.common.io.Files.deleteRecursively(out);
  }

  public void testFoo() throws IOException, InterruptedException {
    // Build foo.
    Process p = new ProcessBuilder(
        new File("../../out/bin/bake").getAbsolutePath(), "-v", "foo")
        .redirectErrorStream(true)
        .directory(new File("repo"))
        .start();
    ByteStreams.copy(p.getInputStream(), System.out);
    assertEquals(0, p.waitFor());

    // Run foo.
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    p = new ProcessBuilder("repo/out/bin/foo")
        .redirectErrorStream(true)
        .start();
    ByteStreams.copy(p.getInputStream(), bout);
    assertEquals("OK", new String(bout.toByteArray()));
  }
}
