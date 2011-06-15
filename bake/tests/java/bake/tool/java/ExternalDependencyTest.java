// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.tool.BakeError;
import junit.framework.TestCase;

public class ExternalDependencyTest extends TestCase {

  public void testParse() throws BakeError {
    ExternalDependency ed = ExternalDependency.parse("external:foo/bar@tee");
    assertEquals("foo", ed.organization);
    assertEquals("bar", ed.name);
    assertEquals("tee", ed.version);
    assertEquals("external:foo/bar@tee", ed.toString());
  }

  public void testParseWithoutVersion() throws BakeError {
    ExternalDependency ed = ExternalDependency.parse("external:foo/bar");
    assertEquals("foo", ed.organization);
    assertEquals("bar", ed.name);
    assertNull(ed.version);
    assertEquals("external:foo/bar", ed.toString());
  }

  public void testParseWithReservedCharacters() throws BakeError {
    ExternalDependency ed = ExternalDependency.parse("external:a@b/c/d@e/f@g");
    assertEquals("a@b", ed.organization);
    assertEquals("c/d", ed.name);
    assertEquals("e/f@g", ed.version);
    assertEquals("external:a@b/c/d@e/f@g", ed.toString());
  }
}
