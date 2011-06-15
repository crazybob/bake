// Copyright 2011 Square, Inc.
package bake.tool;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.matcher.Matchers;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.util.Context;

import javax.inject.Singleton;
import javax.lang.model.util.Elements;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import java.io.File;
import java.io.IOException;

/**
 * Configures Bake's dependencies.
 * 
 * @author Bob Lee (bob@squareup.com)
 */
class BakeModule extends AbstractModule {

  private final File root;
  private final DiagnosticListener<FileObject> diagnosticListener;

  BakeModule(File root, DiagnosticListener<FileObject> diagnosticListener)
      throws BakeError, IOException {
    this.root = root;
    this.diagnosticListener = diagnosticListener;
  }

  @Override protected void configure() {
    binder().disableCircularProxies();

    bindInterceptor(Matchers.any(), Matchers.annotatedWith(Profile.class),
        new ProfileInterceptor());
  }

  @Provides @Root File provideRoot() {
    return root;
  }

  @Provides @Singleton Diagnostics provideDiagnostics() {
    return new Diagnostics(diagnosticListener);
  }

  @Provides @Singleton Elements provideElements() {
    // TODO: Can we retrieve Elements is an impl independent manner?
    return JavacElements.instance(new Context());
  }

}
