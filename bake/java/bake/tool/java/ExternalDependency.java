// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.tool.BakeError;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dependency on an external library. The format for a dependency string is:
 *
 * <pre>
 *   external:{organization}/{name}[@{version}]
 * </pre>
 *
 * <p>Resolves to an {@link ExternalArtifact}.
 */
class ExternalDependency {

  static final String SCHEME = "external:";

  final String organization;
  final String name;
  final String version;

  ExternalDependency(String organization, String name, String version) {
    this.organization = organization;
    this.name = name;
    this.version = version;
  }

  String ivyVersion() {
    return version == null ? "latest.integration" : version;
  }

  @Override public String toString() {
    return SCHEME + organization + "/" + name
        + ((version == null) ? "" : "@" + version);
  }

  static final Pattern pattern = Pattern.compile(SCHEME
      + "([^/]*)"    // organization
      + "/([^@]*)"   // /name
      + "(?:@(.*))?" // @version
  );

  /**
   * Returns the ID for the jar artifact this dependency resolves to.
   */
  ExternalArtifact.Id jarId() {
    return new ExternalArtifact.Id(organization, name,
        ExternalArtifact.Type.JAR);
  }

  /**
   * Returns the ID for the source artifact this dependency resolves to.
   */
  ExternalArtifact.Id sourceId() {
    return new ExternalArtifact.Id(organization, name,
        ExternalArtifact.Type.SOURCE);
  }

  /** Parses a dependency string from a .bake file. */
  static ExternalDependency parse(String dependencyId) throws BakeError {
    Matcher matcher = pattern.matcher(dependencyId);
    if (!matcher.matches()) {
      // TODO: Add source information.
      throw new BakeError("Failed to parse dependency: " + dependencyId);
    }
    String organization = matcher.group(1);
    String name = matcher.group(2);
    String version = matcher.group(3);
    return new ExternalDependency(organization, name, version);
  }

  /** Returns true if 'dependencyId' identifies an external dependency. */
  static boolean isExternal(String dependencyId) {
    return dependencyId.startsWith(SCHEME);
  }
}
