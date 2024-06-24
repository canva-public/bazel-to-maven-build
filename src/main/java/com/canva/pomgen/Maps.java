// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings({ "Convert2MethodRef", "CodeBlock2Expr" })
class Maps {

  public final Map<String, AbstractParsedTarget> mapOutputsToTarget = new HashMap<>();
  public final Map<String, MavenModule> modulesByPath = new HashMap<>();
  public final Map<MavenCoordinate, List<String>> systemImports;
  public final Map<String, List<JavaCompile>> reverseDepsWithExtraDeps = new HashMap<>();

  Maps(List<AbstractParsedTarget> targets) {
    for (var target : targets) {
      // target.getOutputs() can contain duplicates!
      for (var output : new HashSet<>(target.getOutputs())) {
        var existing = mapOutputsToTarget.get(output);
        if (existing != null) {
          System.err.printf(
            "File %s is provided by both %s and %s%n",
            output,
            target.getJsonPath(),
            existing.getJsonPath()
          );
        }
        mapOutputsToTarget.put(output, target);
      }
    }

    this.systemImports =
      targets
        .stream()
        .flatMap(Main.isInstance(ImportExternalJar.class))
        .collect(
          Collectors.toMap(
            x -> x.coordinate(),
            x -> x.jars(),
            // The same coordinate could appear multiple times if a target is
            // depended on through multiple configurations. Just pick any one.
            (a, b) -> a
          )
        );

    targets
      .stream()
      .flatMap(Main.isInstance(JavaCompile.class))
      .collect(Collectors.groupingBy(x -> x.module()))
      .forEach((k, v) -> {
        modulesByPath.put(k, new MavenModule(k, v));
      });

    modulesByPath
      .values()
      .stream()
      .collect(Collectors.groupingBy(x -> x.coordinate))
      .values()
      .stream()
      .filter(x -> x.size() > 1)
      .flatMap(x -> x.stream())
      .forEach(x -> x.makeCoordinateUnique());

    for (var target : targets) {
      if (target instanceof JavaCompile java) {
        java
          .getDepsWithExtraDeps(this)
          .forEach(dep -> {
            reverseDepsWithExtraDeps
              .computeIfAbsent(dep, i -> new ArrayList<>())
              .add(java);
          });
      }
    }
  }

  public Stream<String> flattenExtraDeps(String dep, DepType type) {
    return Stream.concat(
      Stream.of(dep),
      mapOutputsToTarget
        .get(dep)
        .getExtraDeps(type)
        .stream()
        .flatMap(x -> flattenExtraDeps(x, type))
    );
  }
}
