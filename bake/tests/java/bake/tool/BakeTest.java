// Copyright 2011 Square, Inc.
package bake.tool;

import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Builds "foo" in the test repo.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class BakeTest {

  @Before
  public void setUp() throws Exception {
    System.err.println("*** STARTING " + this.getClass().getName());
  }

  @After
  public void tearDown() throws Exception {
    System.err.println("*** FINISHED " + this.getClass().getName());
  }

  @Test
  public void testRepo() throws IOException, InterruptedException {
    clean();

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
    Process p = new ProcessBuilder("out/bin/foo")
        .directory(new File("tests/repo"))
        .redirectErrorStream(true)
        .start();
    ByteStreams.copy(p.getInputStream(), bout);
    assertEquals("OK", new String(bout.toByteArray()));
    assertEquals(0, p.waitFor());

    // Build and test foo.bar.
    p = new ProcessBuilder(
        new File("../out/bin/bake").getCanonicalPath(), "-v", "foo.bar")
        .redirectErrorStream(true)
        .directory(new File("tests/repo"))
        .start();
    ByteStreams.copy(p.getInputStream(), System.out);
    assertEquals(0, p.waitFor());

    bout = new ByteArrayOutputStream();
    p = new ProcessBuilder("tests/repo/out/bin/foo.bar")
        .redirectErrorStream(true)
        .start();
    ByteStreams.copy(p.getInputStream(), bout);
    assertEquals("OK", new String(bout.toByteArray()));
    assertEquals(0, p.waitFor());

    // Bake all.
    p = new ProcessBuilder(
        new File("../out/bin/bake").getCanonicalPath(), "-v", "all")
        .redirectErrorStream(true)
        .directory(new File("tests/repo"))
        .start();
    ByteStreams.copy(p.getInputStream(), System.out);
    assertEquals(0, p.waitFor());
  }

  private void clean() throws IOException {
    File out = new File("tests/repo/out");
    if (out.exists())
      com.google.common.io.Files.deleteRecursively(out);
  }

  @Test
  public void testKill() throws IOException, InterruptedException {
    Process p = new ProcessBuilder(
        new File("../out/bin/bake").getCanonicalPath(), "-v", "sleep")
        .redirectErrorStream(true)
        .directory(new File("tests/repo"))
        .start();
    ByteStreams.copy(p.getInputStream(), System.out);
    assertEquals(0, p.waitFor());

    long start = System.currentTimeMillis();
    p = new ProcessBuilder("tests/repo/out/bin/sleep", "10")
        .redirectErrorStream(true)
        .start();
    p.destroy();
    assertTrue(p.waitFor() > 0);
    assertTrue("> 5s elapsed.", System.currentTimeMillis() - start < 5000);
  }

  @Test
  public void testExitCode() throws IOException, InterruptedException {
    Process p = new ProcessBuilder(
        new File("../out/bin/bake").getCanonicalPath(), "-v", "return1")
        .redirectErrorStream(true)
        .directory(new File("tests/repo"))
        .start();
    ByteStreams.copy(p.getInputStream(), System.out);
    assertEquals(0, p.waitFor());

    p = new ProcessBuilder("tests/repo/out/bin/return1")
        .redirectErrorStream(true)
        .start();
    assertEquals(1, p.waitFor()); // != 0
  }

  @Test
  public void testStandardInputRedirection() throws IOException, InterruptedException {
    Process p = new ProcessBuilder(
        new File("../out/bin/bake").getCanonicalPath(), "-v", "cli")
        .redirectErrorStream(true)
        .directory(new File("tests/repo"))
        .start();
    ByteStreams.copy(p.getInputStream(), System.out);
    assertEquals(0, p.waitFor());

    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    p = new ProcessBuilder("tests/repo/out/bin/cli")
        .redirectErrorStream(true)
        .start();
    OutputStream out = p.getOutputStream();
    out.write("OK".getBytes("UTF-8"));
    out.close();
    ByteStreams.copy(p.getInputStream(), bout);
    assertEquals("OK", new String(bout.toByteArray()));
    assertEquals(0, p.waitFor());
  }

  // TODO: Test bake init/init-java.
}
