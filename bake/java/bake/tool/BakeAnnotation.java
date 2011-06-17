// Copyright 2011 Square, Inc.
package bake.tool;

import java.lang.annotation.*;

/**
 * Identifies Bake annotations and specifies how they should be handled.
 *
 * @author Bob Lee (bob@squareup.com)
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface BakeAnnotation {

  /** Identifies the handler class for a definition type. */
  Class<? extends Handler> handler();
}
