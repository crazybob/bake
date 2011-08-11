// Copyright 2011 Square, Inc.
package bake.tool;

/**
 * An attribute.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public @interface Attribute {

  /** Attribute name. */
  String name();

  /** Attribute value. */
  String value();
}
