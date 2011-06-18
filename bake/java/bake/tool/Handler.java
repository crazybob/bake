// Copyright 2011 Square, Inc.
package bake.tool;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Collection;

/**
 * Handles a {@link BakeAnnotation Bake annotation}.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public interface Handler<A extends Annotation> {

  /** Returns the annotation associated with this handler. */
  A annotation();

  /** Bakes the associated module. */
  void bake() throws IOException, BakeError;

  /** This handler's direct dependencies. */
  Collection<Module> directDependencies() throws BakeError, IOException;
}
