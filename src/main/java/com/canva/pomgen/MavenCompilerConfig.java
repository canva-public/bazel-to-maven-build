// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import java.util.List;

public record MavenCompilerConfig(
  List<MavenPomDependency> annotationProcessorPaths,
  List<String> annotationProcessors,
  List<String> compilerArgs
) {}
