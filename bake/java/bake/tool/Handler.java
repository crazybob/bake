// Copyright 2011 Square, Inc.
package bake.tool;

import java.io.IOException;
import java.lang.annotation.Annotation;

/**
 * Handles a {@link BakeAnnotation Bake annotation}.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public interface Handler<A extends Annotation> {

  /** Returns the annotation associated with this handler. */
  A annotation();

  /** Bakes the associated module. */
  void bake(boolean runTests) throws IOException, BakeError;
}
