// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import static com.canva.pomgen.Main.toAbsolutePath;

import com.canva.pomgen.Main.InvalidPathException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@SuppressWarnings("Convert2MethodRef")
public record JsonTargetInfo(
  List<String> compileJars,
  List<String> jars,
  List<String> javaCopts,
  String kind,
  String label,
  String mavenCoords,
  String mavenUrl,
  List<String> otherInfos,
  List<String> outputJars,
  List<String> pluginClasses,
  List<String> pluginJars,
  String resourceStripPrefix,
  List<String> resources,
  List<String> runtimeJars,
  List<String> srcs,
  boolean testOnly
) {
  public record AndPath(JsonTargetInfo json, Path path) {}

  public static void warning(String message) {
    System.err.println("\033[1;93mWARNING\033[0m " + message);
  }

  public static List<AndPath> readAllRecursive(
    Path path,
    Set<Path> seen
  ) {
    if (seen.add(path)) {
      try {
        var target = JsonTargetInfo.read(path);
        // toList() to eagerly catch any errors from our dependencies.
        // Even if our .json file exists we want to fail if any of our dependencies
        // .json files can't be read.
        return Stream.concat(
          Stream.of(target),
          target.json.otherInfos
            .stream()
            .flatMap(x -> readAllRecursive(toAbsolutePath(x), seen).stream())
        ).toList();
      } catch (Throwable e) {
        // Undo the seen.add() so nobody else thinks this file has already been read successfully
        seen.remove(path);
        throw e;
      }
    } else {
      return List.of();
    }
  }

  public static AndPath read(Path path) {
    try {
      var object = Main.mapper.readValue(path.toFile(), JsonTargetInfo.class);
      return new AndPath(object, path);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public AbstractParsedTarget parse(Path jsonPath) {
    var maybeCoords = Optional
      .ofNullable(mavenCoords)
      .map(MavenCoordinate::parse);

    var srcsPaths = srcs
      .stream()
      .flatMap(x -> {
        try {
          return Stream.of(Main.parseJavaPath(x));
        } catch (InvalidPathException e) {
          warning("Skipping source file %s (%s)".formatted(x, e.getMessage()));
          return Stream.empty();
        }
      })
      .toList();

    var resourcesPaths = resources
      .stream()
      .flatMap(x -> {
        try {
          return Stream.of(Main.parseResourcePath(x));
        } catch (InvalidPathException e) {
          warning("Skipping resource file %s (%s)".formatted(x, e.getMessage()));
          return Stream.empty();
        }
      })
      .toList();

    if (!srcsPaths.isEmpty() || !resourcesPaths.isEmpty()) {
      if (resourceStripPrefix != null && !resourceStripPrefix.equals("")) {
        throw new IllegalArgumentException(
          "TODO support resource_strip_prefix"
        );
      }

      var isTest = kind.equals("java_test");

      var modules = Stream
        .concat(srcsPaths.stream(), resourcesPaths.stream())
        .map(x -> x.root().moduleRoot())
        .distinct()
        .toList();

      if (modules.size() != 1) {
        throw new IllegalArgumentException(
          "Target %s should be in one module, but it is in %s".formatted(
              label,
              modules
            )
        );
      }

      return new JavaCompile(
        jsonPath,
        label,
        maybeCoords,
        modules.get(0),
        outputJars,
        srcsPaths,
        resourcesPaths,
        compileJars,
        runtimeJars,
        pluginJars,
        pluginClasses,
        javaCopts,
        isTest
      );
    } else if (mavenUrl != null) {
      Objects.requireNonNull(mavenCoords, "mavenCoords");
      Objects.requireNonNull(mavenUrl, "mavenUrl");
      var coords = maybeCoords.orElseThrow();
      var repo = Main
        .removeSuffix(mavenUrl, "/" + coords.toUrlPath())
        .orElseThrow(() ->
          new IllegalArgumentException(
            ("Maven URL %s doesn't match coordinates " + "%s").formatted(
                mavenUrl,
                mavenCoords
              )
          )
        );
      return new MavenArtifact(
        jsonPath,
        outputJars,
        coords,
        repo,
        compileJars,
        runtimeJars
      );
    } else {
      // jars could be empty if this is a java_library that aggregates other java_libraries.
      // With no sources or resources there is no way to map this to Maven, so we merge its
      // dependencies into the thing that depends on it the same with java_import.
      return new ImportExternalJar(
        jsonPath,
        outputJars,
        jars,
        maybeCoords.orElseGet(() ->
          MavenCoordinate.createFromPath(
            BazelLabel.parse(label).toPath(),
            outputJars.isEmpty()
              ? null
              : Main.getPathBazelConfigOrNull(outputJars.get(0))
          )
        ),
        compileJars,
        runtimeJars
      );
    }
  }
}
