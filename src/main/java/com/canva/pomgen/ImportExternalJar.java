// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import java.nio.file.Path;
import java.util.List;

public record ImportExternalJar(
  Path jsonPath,
  List<String> outputs,
  List<String> jars,
  MavenCoordinate coordinate,
  List<String> compileDeps,
  List<String> runtimeDeps
)
  implements AbstractParsedTarget {
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
    return switch (type) {
      case Compile -> compileDeps;
      case Runtime -> runtimeDeps;
    };
  }

  @Override
  public MavenCoordinate getCoordinate(Maps maps) {
    return coordinate;
  }
}
