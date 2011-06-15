# Bake: A new way to build

Bake's approach to builds was initially inspired by how Google builds code
internally. Compared to Maven, Bake elides versions for internal dependencies
and automatically rebuilds dependencies. Compared to Gradle, Bake uses
Java annotations for its configuration instead of Groovy.

With Bake, you always build the transitive closure of your dependencies.
You don't need to mess with intermediate jars, and you don't need to explicitly
rebuild dependencies. Bake packages reference each other directly, and Bake
rebuilds or retrieves transitive dependencies automatically. Bake keeps
everything up-to-date automatically.

If you change an API that other code depends on, it's your responsibility to
update and test all of the code that depends on that API; Bake facilitates this.
In other words, Bake discourages procrastination, simplifies reuse and fends
off bit rot. That said, Bake plays nicely with external dependencies, too.

Bake uses the Java Programming Language for its configuration file format.
This enables Java programmers to reuse their existing knowledge and tools.
If you've ever written a [`package-info.java` file][1], you already know
how to write a Bake configuration file.

Bake is declarative. Bake can potentially generate a fat binary,
documentation (TODO), a dependency graph (TODO) and an IDE configuration (TODO),
all from the same package configuration.

Bake implements incremental builds correctly. Recompiling classes based on file
timestamps doesn't work when constants, interfaces and superclasses change. Bake
uses [jmake](http://kenai.com/projects/jmake) to ensure incremental builds
never break. You perform clean builds far less often (hopefully never) because
your deliverables don't get out of sync.

Bake is fast and reliable. If you kill Bake during a build, it leaves your
filesystem in a good state. Bake avoids repeating unnecessary work.

Bake is simple and convenient. Bake generates executables that embed your jar
and all of its dependencies. For example:

    $ out/bin/hello_world
    Hello, World!

Finally, Bake is easy to extend, too. Simply create a package annotation and
implement its handler. `@bake.BakeAnnotation` ties an annotation to its handler.
It's plain, typesafe Java. There's no need to deal with XML.

## Source control

Google and Square each host all of their Java code in a single repository.
Adding a dependency is a one line change, and changing code
your application depends on, no matter how deep the dependency, is friction
free. Eliminating the barriers to reuse and having the ability to fix
problems at their source rather than implementing workarounds at higher levels
is critical to a company's agility and the long term health of its code base.

## Installation

Put `bin/bake` somewhere in your `PATH`.

## Usage (by example)

Print usage instructions:

    $ bake

Initialize a Bake repository in the current directory:

    $ bake init .

<b>Note:</b> This creates a directory named `.bake` which Bake can
later use to identify the repository's root directory.

You can run subsequent commands from anywhere within a Bake repository.

Generate a directory hierarchy and Bake file for a Java package named
`foo.bar` (non destructive):

    $ bake init-java foo.bar

Build the package[s] in a given directory or directories:

    $ bake .

    $ bake foo/bar
    $ bake foo/bar/bar.bake # Same as above.

    $ bake tee ../foo/bar

Build everything (starting from the repository root and recursively searching
for `.bake` files):

    $ bake all

## Bake packages

Bake packages have a lot in common with Java packages. They're hierarchical, and
their name and directory structures reflect this hierarchy. For example,
the Bake package `foo.bar` is configured in a file named `foo/bar/bar.bake`.

Like Java packages, Bake's child and parent packages have
no special relationship. Bake packages are internal, so they needn't follow
Java's standard package naming conventions--they don't incorporate a reversed
domain name.

### The Bake file

A Bake file ends with `.bake` and resides in its package's top-level
directory. Bake files follow the same format as [`package-info.java` files][1]
insofar as they contain an annotated `package` declaration but no classes.
Annotations on the `package` declaration define a Bake package.

For example, if a Bake package named `foo.bar` contains Java code and follows
Bake's default conventions, its bake file named `foo/bar/bar.bake` would
contain:

    /** The {@code foo.bar} Java library. */
    @bake.Java package foo.bar;

`@bake.Java` is a Bake annotation that identifies Java libraries. Bake
annotation types like `@bake.Java` are annotated themselves with
`@bake.BakeAnnotation`, and the `BakeAnnotation` points to the Bake handler for
that annotation type.

<b>Note:</b> Most build systems use the same exact name for each build file.
Naming configuration files after their containing package makes using IDEs to
jump to files by name much easier.

### Java packages

`bake init-java foo` creates the following directory structure:

    foo
     |
     +- foo.bake  - Bake file
     +- java      - Java source
     +- resources - Resources
     +- tests     - Tests for the foo package.
         |
         +- tests.bake - Test Bake file
         +- java       - Test Java source
         +- resources  - Test resources

### Internal dependencies

To make package `foo` depend on internal Bake packages `bar` and `tee`:

    @bake.Java(
      dependencies = {
        "bar", "tee"
      }
    }
    package foo;

When you `bake foo`, Bake will automatically bake `bar` and `tee`, too.

### External dependencies

Bake automatically downloads external dependencies from the Maven central
repository. External dependencies start with `external:` and conform to the
following URI spec:

    external:{groupId}/{artifactId}[@{version}]

For example, a Java package that depends on Guice might look like:

    @bake.Java(
      dependencies = {
        "external:com.google.inject/guice@3.0"
      }
    }
    package myapp;

The first time you build `my-app`, Bake will download Guice 3.0 if
it hasn't been downloaded already. Bake will generate an error if your
application transitively depends on two different versions of the same external
library.

### Executable jars

If you set the `mainClass` attribute on the `@Java` annotation, Bake will
generate an executable containing all of the necessary dependencies in
`out/bin/{package-name}`.

[1]: http://java.sun.com/docs/books/jls/third_edition/html/packages.html#7.4.1.1

## IntelliJ

Bake supports IntelliJ's directory-based configuration (as opposed to it's
`ipr` file-based configuration).

To get started, create an empty directory-based IntelliJ project in your
Bake repository's root directory; do not create an IntelliJ module. Run Bake.
Bake will automatically add/update IntelliJ modules for anything you build.

Do not check your module (`iml`) files into source control. Bake generates
them automatically.

## Building Bake itself

    $ git clone git@github.com:square/bake.git
    $ cd bake
    $ bin/bake bake

The new bake executable is in `out/bin/bake`.
