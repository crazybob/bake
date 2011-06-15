// Copyright 2011 Square, Inc.
package bake.tool;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;

/**
 * Keeps track of whether or not the Java compiler has reported an error.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class Diagnostics implements DiagnosticListener<FileObject> {

  private final DiagnosticListener<FileObject> listener;

  private boolean hasErred;

  Diagnostics(DiagnosticListener<FileObject> listener) {
    this.listener = listener;
  }

  public void report(Diagnostic<? extends FileObject> diagnostic) {
    if (diagnostic == null) throw new NullPointerException();
    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) hasErred = true;
    if (listener != null) listener.report(diagnostic);
  }

  /** Returns true if errors have been reported. */
  public boolean hasErred() {
    return hasErred;
  }
}
