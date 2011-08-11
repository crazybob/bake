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

  @Test
  public void testProvidedDependencies() throws IOException, InterruptedException {
    File dir = null;
    try {
      // Sleep jar is needed for provided
      Process p = new ProcessBuilder(
          new File("../out/bin/bake").getCanonicalPath(), "-v", "sleep")
          .redirectErrorStream(true)
          .directory(new File("tests/repo"))
          .start();
      ByteStreams.copy(p.getInputStream(), System.out);
      assertEquals(0, p.waitFor());

      p = new ProcessBuilder(
          new File("../out/bin/bake").getCanonicalPath(), "-v", "provided")
          .redirectErrorStream(true)
          .directory(new File("tests/repo"))
          .start();
      ByteStreams.copy(p.getInputStream(), System.out);
      assertEquals(0, p.waitFor());

      dir = Files.createTempDir();
      File srcJar = new File("tests/repo/out/jars/provided.jar");
      File destJar = new File(dir, "provided.jar");
      Files.copy(srcJar, destJar);

      p = Runtime.getRuntime().exec("java -jar " + destJar.getCanonicalPath());
      assertTrue(p.waitFor() != 0);

      File jar = new File("tests/repo/out/jars/sleep.jar");
      Files.copy(jar, new File(dir, "sleep.jar"));
      jar = new File("tests/repo/tee/tee.jar");
      Files.copy(jar, new File(dir, "tee.jar"));
      jar = new File("tests/repo/out/ivy/libs/commons-lang/commons-lang/jar/commons-lang-2.6.jar");
      Files.copy(jar, new File(dir, "commons-lang.jar"));

      p = Runtime.getRuntime().exec("java -jar " + destJar.getCanonicalPath());
      assertTrue(p.waitFor() == 0);

      ZipEntry entry;
      ZipFile zipFile = new ZipFile(srcJar);
      Enumeration e = zipFile.entries();
      while (e.hasMoreElements()) {
        entry = (ZipEntry) e.nextElement();
        if (entry.getName().equals("META-INF/MANIFEST.MF")) {
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)));

          // Search for a Class-Path entry
          boolean foundClassPath = false;
          String line;
          while((line = reader.readLine()) != null) {
            if (line.startsWith("Class-Path:")) {
              // Entry must reference each jar
              assertTrue(line.contains("sleep.jar"));
              assertTrue(line.contains("tee.jar"));
              assertTrue(line.contains("commons-lang.jar"));
              foundClassPath = true;
            }
          }

          if (!foundClassPath) {
            fail("Class-Path line missing from manifest.");
          }
        }

        if (entry.getName().endsWith("Sleep.class")) {
          fail("Sleep.class should not be included in jar.");
        }

        if (entry.getName().endsWith("Tee.class")) {
          fail("Tee.class should not be included in jar.");
        }

        if (entry.getName().endsWith("StringUtils.class")) {
          fail("StringUtils.class should not be included in jar.");
        }
      }

    } finally {
      if (dir != null) {
        Files.deleteDirectoryContents(dir);
        dir.deleteOnExit();
      }
    }
  }

  // TODO: Test bake init/init-java.
}
