// Copyright 2011 Square, Inc.
package bake.tool;

import bake.Java;
import bake.tool.java.JavaHandler;
import com.google.common.collect.Maps;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Map;

/**
 * A Bake module.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class Module {

  private final Injector injector;
  private final String name;
  private final Repository repository;
  private final Map<Class<? extends Annotation>, Handler<?>> handlers;
  private final File directory;
  private final File output;

  Module(Injector injector, String name, Repository repository,
         Map<Class<? extends Annotation>, Handler<?>> handlers,
         File directory) throws IOException {
    this.injector = injector;
    this.name = name;
    this.repository = repository;
    this.handlers = handlers;
    this.directory = directory;

    // The output directory is named "foo.bar" instead of "foo/bar". This way
    // output directories don't conflict with nested modules.
    this.output = repository.outputDirectory("modules/" + name);
  }

  /** Bakes this module. Delegates to each handler. */
  public void bake(boolean runTests) throws IOException, BakeError {
    Log.i("Baking %s...", name);
    for (Handler handler : handlers.values()) {
      Log.i("Handling @%s...",
          handler.annotation().annotationType().getSimpleName());
      handler.bake(runTests);
    }
  }

  /** Convenience method. */
  public JavaHandler javaHandler() throws BakeError {
    JavaHandler javaHandler = (JavaHandler) handlers.get(Java.class);
    if (javaHandler == null) {
      throw new BakeError("Not a Java module: " + name);
    }
    return javaHandler;
  }

  /** Returns the path from this module to the root. */
  private String pathToRoot() {
    StringBuilder path = new StringBuilder("../");
    int index = -1;
    while ((index = name.indexOf('.', index + 1)) != -1) path.append("../");
    return path.toString();
  }

  /** Returns a path relative to this module directory. */
  public String relativePath(File absolute) {
    String absolutePath = absolute.getPath();
    if (absolutePath.startsWith(directory.getPath())) {
      return absolutePath.substring(directory.getPath().length() + 1);
    } else {
      // Try relative to the repository root.
      String relativeToRoot = repository.relativePath(absolute);
      return pathToRoot() + relativeToRoot;
    }
  }

  /** Returns the module name. */
  public String name() {
    return name;
  }

  /** Returns this module's directory. */
  public File directory() {
    return directory;
  }

  /** Returns a map from annotation type to the handler for that type. */
  public Map<Class<? extends Annotation>, Handler<?>> handlers() {
    return Collections.unmodifiableMap(handlers);
  }

  /**
   * Returns an output directory relative to this module's output directory.
   */
  public File outputDirectory(String path) throws IOException {
    return Files.mkdirs(new File(output, path));
  }

  /**
   * Returns the top-level output directory.
   */
  public File outputDirectory() {
    return this.output;
  }

  /**
   * Creates a new handler for the given annotation. Makes the annotation
   * and this Module instance available for injection into the handler.
   * Uses {@link BakeAnnotation} on the given annotation's type to determine
   * the handler type.
   */
  public Handler newHandlerFor(final Annotation annotation) {
    BakeAnnotation bakeAnnotation = annotation.annotationType()
        .getAnnotation(BakeAnnotation.class);
    if (bakeAnnotation == null) return null;

    Class<? extends Handler> handlerType = bakeAnnotation.handler();
    Injector handlerInjector = injector.createChildInjector(
        new AbstractModule() {
          @Override protected void configure() {
            castAndBind(annotation.annotationType(), annotation);
            bind(Module.class).toInstance(Module.this);
          }

          /** Casts instance to type at run time. */
          <T> void castAndBind(Class<T> type, Object instance) {
            bind(type).toInstance(type.cast(instance));
          }
        });

    return handlerInjector.getInstance(handlerType);
  }
}
