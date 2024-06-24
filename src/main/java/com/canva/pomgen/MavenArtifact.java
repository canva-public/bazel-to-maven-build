// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record MavenArtifact(
  Path jsonPath,
  List<String> outputs,
  MavenCoordinate coords,
  String repo,
  List<String> compileDeps,
  List<String> runtimeDeps
)
  implements AbstractParsedTarget {
  /**
   * If false, include only top level Maven dependencies in the pom.xml and let Maven/IntelliJ
   * resolve the dependency closure.
   * If true, include the full transitive closure from Bazel in the pom.xml and use
   * &lt;exclusions&gt; to prevent Maven from trying to resolve transitive dependencies itself.
   */
  public static final boolean USE_BAZEL_DEPENDENCY_RESOLUTION = true;

  @Override
  public Path getJsonPath() {
    return jsonPath;
  }

  @Override
  public List<String> getOutputs() {
    return outputs;
  }

  @Override
  public List<String> getExtraDeps(DepType type) {
    if (USE_BAZEL_DEPENDENCY_RESOLUTION) {
      return switch (type) {
        case Compile -> compileDeps;
        case Runtime -> runtimeDeps;
      };
    } else {
      return List.of();
    }
  }

  @Override
  public MavenCoordinate getCoordinate(Maps maps) {
    return coords;
  }

  @Override
  public Optional<String> getRepo() {
    return Optional.of(repo);
  }
}
