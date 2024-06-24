// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

interface AbstractParsedTarget {
  default Optional<String> getRepo() {
    return Optional.empty();
  }

  Path getJsonPath();

  List<String> getOutputs();

  /**
   * Get the maven coordinate that provides this target.
   */
  MavenCoordinate getCoordinate(Maps maps);

  /**
   * Get a list of transitive dependencies that will not be added automatically by Maven by
   * depending on the MavenCoordinate returned from getCoordinate(), and so need to also
   * be added to the pom.xml.
   */
  default List<String> getExtraDeps(DepType type) {
    return List.of();
  }
}
