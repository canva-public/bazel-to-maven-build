// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@SuppressWarnings("Convert2MethodRef")
public record JavaCompile(
  Path jsonPath,
  String label,
  Optional<MavenCoordinate> coords,
  String module,
  List<String> outputs,
  List<SourcePath> srcs,
  List<SourcePath> resources,
  List<String> compileDeps,
  List<String> runtimeDeps,
  List<String> pluginDeps,
  List<String> pluginClasses,
  List<String> copts,
  boolean isTest
)
  implements AbstractParsedTarget {
  @Override
  public Path getJsonPath() {
    return jsonPath;
  }

  public Stream<String> getSplitCopts() {
    return copts.stream().flatMap(x -> Main.bourneShellTokenize(x).stream());
  }

  @Override
  public List<String> getOutputs() {
    return outputs;
  }

  public Stream<SourceRoot> getSourceRoots() {
    return srcs.stream().map(x -> x.root());
  }

  public Stream<SourceRoot> getResourceRoots() {
    return resources.stream().map(x -> x.root());
  }

  public Stream<SourcePath> allSourcePaths() {
    return Stream.concat(srcs.stream(), resources.stream());
  }

  public Stream<String> getDepsWithExtraDeps(Maps maps) {
    return Arrays
      .stream(DepType.values())
      .flatMap(type -> getDepsWithExtraDeps(type, maps))
      .distinct();
  }

  public Stream<JavaCompile> getDepsWithExtraDepsDeepSameModule(
    Maps maps,
    Set<Path> seen
  ) {
    return Stream.concat(
      Stream.of(this),
      getDepsWithExtraDepsJavaCompile(maps)
        .filter(x -> seen.add(x.jsonPath))
        .filter(x -> x.module.equals(module))
        .flatMap(x -> x.getDepsWithExtraDepsDeepSameModule(maps, seen))
    );
  }

  public Stream<JavaCompile> getReverseDepsWithExtraDepsDeepSameModule(
    Maps maps,
    Set<Path> seen
  ) {
    return Stream.concat(
      Stream.of(this),
      getReverseDepsWithExtraDeps(maps)
        .filter(x -> seen.add(x.jsonPath))
        .filter(x -> x.module.equals(module))
        .flatMap(x -> x.getReverseDepsWithExtraDepsDeepSameModule(maps, seen))
    );
  }

  public Stream<JavaCompile> getDepsWithExtraDepsJavaCompile(Maps maps) {
    return getDepsWithExtraDeps(maps)
      .map(x -> maps.mapOutputsToTarget.get(x))
      .flatMap(Main.isInstance(JavaCompile.class));
  }

  public Stream<JavaCompile> getReverseDepsWithExtraDeps(Maps maps) {
    return outputs
      .stream()
      .flatMap(x -> maps.reverseDepsWithExtraDeps.getOrDefault(x, List.of()).stream());
  }

  public Stream<JavaCompile> getReverseDepsWithExtraDepsRecursive(
      Maps maps,
      HashSet<String> seen
  ) {
    return getReverseDepsWithExtraDeps(maps)
        .filter(x -> seen.add(x.label))
        .flatMap(x -> Stream.concat(
            Stream.of(x),
            x.getReverseDepsWithExtraDepsRecursive(maps, seen)
        ));
  }

  public List<String> getDeps(DepType type) {
    return switch (type) {
      case Compile -> compileDeps;
      case Runtime -> runtimeDeps;
    };
  }

  public Stream<String> getDepsWithExtraDeps(DepType type, Maps maps) {
    return getDeps(type)
      .stream()
      .flatMap(x -> maps.flattenExtraDeps(x, type))
      .distinct();
  }

  @Override
  public MavenCoordinate getCoordinate(Maps maps) {
    return maps.modulesByPath.get(module).coordinate;
  }

  public long getNumSources() {
    return allSourcePaths()
      .filter(x -> x.root().prefix().equals(""))
      .distinct()
      .count();
  }

  public boolean hasDirectDependencyOnModule(String module, Maps maps) {
    return getDepsWithExtraDepsJavaCompile(maps)
      .anyMatch(x -> x.module.equals(module));
  }
}


