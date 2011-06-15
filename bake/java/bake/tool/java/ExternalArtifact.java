// Copyright 2011 Square, Inc.
package bake.tool.java;

import com.google.common.base.Objects;

import java.io.File;
import java.io.Serializable;
import java.util.LinkedHashMap;

/**
 * An external artifact retrieved by Ivy.
 *
 * @author Bob Lee (bob@squareup.com)
 */
class ExternalArtifact implements Serializable {

  private static final long serialVersionUID = -5331215525972387077L;

  final Id id;
  final File file;

  ExternalArtifact(Id id, File file) {
    this.id = id;
    this.file = file;
  }

  @Override public String toString() {
    return new LinkedHashMap<String, Object>() {{
      put("id", id);
      put("file", file);
    }}.toString();
  }

  /** Returns the ID for this artifact's source. */
  public Id sourceId() {
    return new Id(id.organization, id.name, Type.SOURCE);
  }

  /** Possible artifact types. */
  enum Type {
    JAR, JAVADOC, SOURCE, UNKNOWN;

    String ivyName() {
      return name().toLowerCase();
    }

    static Type fromIvyName(String ivyName) {
      String name = ivyName.toUpperCase();
      for (Type type : values()) {
        if (type.name().equals(name)) return type;
      }
      return UNKNOWN;
    }
  }

  /**
   * Uniquely identifies an external artifact. We only support one revision
   * of an artifact at a time, so this doesn't include a revision.
   */
  static class Id implements Serializable, Comparable<Id> {

    private static final long serialVersionUID = 405664549269608139L;

    final String organization;
    final String name;
    final Type type;

    Id(String organization, String name, Type type) {
      this.organization = organization;
      this.name = name;
      this.type = type;
    }

    @Override public int compareTo(Id other) {
      int result = organization.compareTo(other.organization);
      if (result != 0) return result;
      result = name.compareTo(other.name);
      if (result != 0) return result;
      result = type.compareTo(other.type);
      return result;
    }

    @Override public int hashCode() {
      return Objects.hashCode(organization, name, type);
    }

    @Override public boolean equals(Object o) {
      Id other = (Id) o;
      return organization.equals(other.organization)
          && name.equals(other.name)
          && type.equals(other.type);
    }

    @Override public String toString() {
      return new LinkedHashMap<String, Object>() {{
        put("organization", organization);
        put("name", name);
        put("type", type);
      }}.toString();
    }
  }
}
