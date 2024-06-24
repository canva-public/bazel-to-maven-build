// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import static com.canva.pomgen.Main.toAbsolutePath;

import java.nio.file.Path;

public record SourceRoot(String prefix, String moduleRoot, String sourceRoot) {
  public SourceRoot {
    // Intern these because there will be a lot of duplicates, and we need fast equality checking.
    prefix = prefix.intern();
    moduleRoot = moduleRoot.intern();
    sourceRoot = sourceRoot.intern();
  }

  public Path getSourceRootPathFromModuleRoot() {
    if (moduleRoot.isEmpty()) {
      return toAbsolutePath(prefix + sourceRoot);
    } else {
      return toAbsolutePath(prefix + moduleRoot + "/" + sourceRoot);
    }
  }
}
