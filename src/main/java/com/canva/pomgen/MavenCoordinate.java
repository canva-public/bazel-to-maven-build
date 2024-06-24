// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public record MavenCoordinate(
  String groupId,
  String artifactId,
  String packaging,
  String classifier,
  String version
) {
  private static final Pattern regex = Pattern.compile(
    "^([^:]*):([^:]*)(?::([^:]*))?(?::([^:]*))?:([^:]*)$"
  );

  static MavenCoordinate createFromPath(String path, String classifier) {
    return new MavenCoordinate(
      "bazel.generated",
      path.isEmpty() ? "canva" : path.replace("/", "_"),
      "jar",
      classifier,
      "1.0-SNAPSHOT"
    );
  }

  static MavenCoordinate parse(String coord) {
    var matcher = regex.matcher(coord);
    if (matcher.matches()) {
      return new MavenCoordinate(
        matcher.group(1),
        matcher.group(2),
        matcher.group(3),
        matcher.group(4),
        matcher.group(5)
      );
    }
    throw new IllegalArgumentException(
      "Invalid maven coordinate syntax: " + coord
    );
  }

  public MavenCoordinate {
    if (packaging == null) {
      packaging = "jar";
    }
  }

  public String unparse() {
    return Stream
      .of(groupId, artifactId, packaging, classifier, version)
      .filter(Objects::nonNull)
      .collect(Collectors.joining(":"));
  }

  public String toUrlPath() {
    return "%s/%s/%s/%s-%s%s.jar".formatted(
        groupId.replace('.', '/'),
        artifactId,
        version,
        artifactId,
        version,
        classifier == null ? "" : "-" + classifier
      );
  }
}
