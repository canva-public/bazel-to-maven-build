// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import static com.canva.pomgen.Main.realBazelBinPath;

import java.nio.file.Path;
import java.util.regex.Pattern;

public record BazelLabel(
  String workspaceName,
  String packageName,
  String targetName
) {
  public static final Pattern regex = Pattern.compile(
    "^(?:@(.*?))?//(.*?):(.*?)$"
  );

  public static BazelLabel parse(String label) {
    var matcher = regex.matcher(label);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid Bazel label: " + label);
    }
    return new BazelLabel(
      matcher.group(1) == null ? "" : matcher.group(1),
      matcher.group(2),
      matcher.group(3)
    );
  }

  @Override
  public String toString() {
    return "@" + workspaceName + "//" + packageName + ":" + targetName;
  }

  public Path toMavenPomFilePath() {
    return realBazelBinPath.resolve(toPath() + "-maven-info.json");
  }

  public String toPath() {
    var result = targetName;
    if (!packageName.equals("")) {
      result = packageName + "/" + result;
    }
    if (!workspaceName.equals("")) {
      result = "external/" + workspaceName + "/" + result;
    }
    return result;
  }
}
