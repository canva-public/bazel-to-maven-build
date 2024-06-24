// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import java.nio.file.Path;
import java.util.EnumSet;

public record MavenPomDependency(
  MavenCoordinate coordinate,
  EnumSet<Scope> scopes,
  Path systemPath,
  boolean optional
) {
  String getMavenScope() {
    if (systemPath != null) {
      return "system";
    } else if (scopes.contains(Scope.MainCompile)) {
      return "compile";
    } else if (scopes.contains(Scope.MainRuntime)) {
      return "runtime";
    } else {
      return "test";
    }
  }
}
