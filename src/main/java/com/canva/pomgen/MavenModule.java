// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import static com.canva.pomgen.Main.toAbsolutePath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("Convert2MethodRef")
final class MavenModule {

  /**
   * Must be disabled for an actual build to work  because in our Bazel build we have dependencies
   * on code in test source roots in other modules, which is not allowed in Maven. So enabling this
   * will require moving some code around.
   */
  private static final boolean TEST_ROOTS_ENABLED = true;

  /**
   * Just detect test source roots by their suffix instead of according to the type of targets
   * inside it.
   */
  private static final boolean DETECT_TEST_ROOTS_BY_SUFFIX = true;

  private static final List<String> TEST_SOURCE_ROOT_SUFFIXES = List.of(
    "src/test/java",
    "src/jmh/java"
  );

  private static final List<String> TEST_RESOURCE_ROOT_SUFFIXES = List.of(
    "src/test/resources",
    "src/jmh/resources"
  );

  private static boolean hasTestSourceRootSuffix(SourceRoot sourceRoot) {
    return TEST_SOURCE_ROOT_SUFFIXES
      .stream()
      .anyMatch(x -> sourceRoot.sourceRoot().endsWith(x));
  }

  private static boolean hasTestResourceRootSuffix(SourceRoot sourceRoot) {
    return TEST_RESOURCE_ROOT_SUFFIXES
      .stream()
      .anyMatch(x -> sourceRoot.sourceRoot().endsWith(x));
  }

  public final String path;
  public final String pathPrefix;
  public final List<JavaCompile> targets;
  public MavenCoordinate coordinate;
  public final Set<SourceRoot> testSourceRoots = new HashSet<>();
  public final Set<SourceRoot> testResourceRoots = new HashSet<>();
  public final Set<SourceRoot> mainSourceRoots = new HashSet<>();
  public final Set<SourceRoot> mainResourceRoots = new HashSet<>();

  @Override
  public String toString() {
    return path;
  }

  MavenModule(String path, List<JavaCompile> targets) {
    this.path = path;
    this.pathPrefix = path.isEmpty() ? "" : (path + "/");
    this.targets = targets;

    var coordinates = targets
      .stream()
      .flatMap(x -> x.coords().stream())
      .toList();

    var parts = path.split(Pattern.quote("/"));
    this.coordinate =
      coordinates.size() == 1
        ? coordinates.get(0)
        : MavenCoordinate.createFromPath(parts[parts.length - 1], null);

    for (var target : targets) {
      if (!TEST_ROOTS_ENABLED) {
        mainSourceRoots.addAll(target.getSourceRoots().toList());
        mainResourceRoots.addAll(target.getResourceRoots().toList());
      } else if (DETECT_TEST_ROOTS_BY_SUFFIX) {
        for (var sourceRoot : target.getSourceRoots().toList()) {
          if (hasTestSourceRootSuffix(sourceRoot)) {
            testSourceRoots.add(sourceRoot);
          } else {
            mainSourceRoots.add(sourceRoot);
          }
        }
        for (var resourceRoot : target.getResourceRoots().toList()) {
          if (hasTestResourceRootSuffix(resourceRoot)) {
            testResourceRoots.add(resourceRoot);
          } else {
            mainResourceRoots.add(resourceRoot);
          }
        }
      } else if (target.isTest()) {
        testSourceRoots.addAll(target.getSourceRoots().toList());
        testResourceRoots.addAll(target.getResourceRoots().toList());
      } else {
        mainSourceRoots.addAll(target.getSourceRoots().toList());
        mainResourceRoots.addAll(target.getResourceRoots().toList());
      }
    }

    // A source root can't be both test and main at the same time.
    // It is "test" if it contains any java_test sources/resources, otherwise it is "main".
    mainSourceRoots.removeAll(testSourceRoots);
    mainResourceRoots.removeAll(testResourceRoots);
  }

  public void makeCoordinateUnique() {
    coordinate = MavenCoordinate.createFromPath(path, null);
  }

  public static Stream<List<String>> findCycles(
    Maps maps,
    List<String> path,
    Set<String> visited,
    Set<String> exclude
  ) {
    var a = path.get(0);
    var b = path.get(path.size() - 1);

    var deps = maps.modulesByPath
      .get(b)
      .targets.stream()
      .flatMap(x -> x.getDepsWithExtraDepsJavaCompile(maps))
      .map(x -> x.module())
      .filter(x -> !b.equals(x))
      .collect(Collectors.toSet());

    var recurse = deps
      .stream()
      .filter(x -> !exclude.contains(x) && visited.add(x))
      .flatMap(x -> findCycles(maps, Main.listAdd(path, x), visited, exclude));

    if (deps.contains(a)) {
      return Stream.concat(Stream.of(path), recurse);
    } else {
      return recurse;
    }
  }

  public Stream<List<String>> findCycles(Maps maps, Set<String> exclude) {
    return findCycles(maps, List.of(path), new HashSet<>(), exclude);
  }

  public boolean isTargetMain(JavaCompile target) {
    return (
      target.getSourceRoots().anyMatch(x -> mainSourceRoots.contains(x)) ||
      target.getResourceRoots().anyMatch(x -> mainResourceRoots.contains(x))
    );
  }

  public boolean isTargetTest(JavaCompile target) {
    return (
      target.getSourceRoots().anyMatch(x -> testSourceRoots.contains(x)) ||
      target.getResourceRoots().anyMatch(x -> testResourceRoots.contains(x))
    );
  }

  public Map<String, EnumSet<Scope>> getDeps(Maps maps) {
    var result = new HashMap<String, EnumSet<Scope>>();
    for (var target : targets) {
      // A target can have sources spread across main and test source roots
      // simultaneously.
      var compile = EnumSet.noneOf(Scope.class);
      var runtime = EnumSet.noneOf(Scope.class);
      if (isTargetMain(target)) {
        compile.add(Scope.MainCompile);
        runtime.add(Scope.MainRuntime);
      }
      if (isTargetTest(target)) {
        compile.add(Scope.TestCompile);
        runtime.add(Scope.TestRuntime);
      }

      target
        .getDepsWithExtraDeps(DepType.Compile, maps)
        .forEach(dep -> {
          result
            .computeIfAbsent(dep, i -> EnumSet.noneOf(Scope.class))
            .addAll(compile);
        });

      target
        .getDepsWithExtraDeps(DepType.Runtime, maps)
        .forEach(dep -> {
          result
            .computeIfAbsent(dep, i -> EnumSet.noneOf(Scope.class))
            .addAll(runtime);
        });
    }
    return result;
  }

  public Map<MavenCoordinate, EnumSet<Scope>> getDepsOnMavenCoords(Maps maps) {
    var result = new HashMap<MavenCoordinate, EnumSet<Scope>>();
    // Map the keys to maven coordinates
    getDeps(maps)
      .forEach((k, v) -> {
        var coord = maps.mapOutputsToTarget.get(k).getCoordinate(maps);
        result
          .computeIfAbsent(coord, i -> EnumSet.noneOf(Scope.class))
          .addAll(v);
      });
    // Remove a dependency on ourselves
    result.remove(coordinate);
    return result;
  }

  public List<String> getMavenRepositories(Maps maps) {
    return getDeps(maps)
      .keySet()
      .stream()
      .flatMap(x -> maps.mapOutputsToTarget.get(x).getRepo().stream())
      // Put most frequently occurring repos first
      .collect(Collectors.groupingBy(x -> x, Collectors.counting()))
      .entrySet()
      .stream()
      .sorted(Entry.<String, Long>comparingByValue().reversed())
      .map(x -> x.getKey())
      .toList();
  }

  public Stream<JavaCompile> getTestTargets() {
    return targets
      .stream()
      .filter(x ->
        x.srcs().stream().anyMatch(y -> testSourceRoots.contains(y.root()))
      );
  }

  public Stream<JavaCompile> getMainTargets() {
    return targets
      .stream()
      .filter(x ->
        x.srcs().stream().anyMatch(y -> mainSourceRoots.contains(y.root()))
      );
  }

  public Stream<Path> sourceDirectories() {
    return mainSourceRoots
      .stream()
      .map(x -> x.getSourceRootPathFromModuleRoot());
  }

  public Stream<Path> testSourceDirectories() {
    return testSourceRoots
      .stream()
      .map(x -> x.getSourceRootPathFromModuleRoot());
  }

  public Stream<Path> resourceDirectories() {
    return mainResourceRoots
      .stream()
      .map(x -> x.getSourceRootPathFromModuleRoot());
  }

  public Stream<Path> testResourceDirectories() {
    return testResourceRoots
      .stream()
      .map(x -> x.getSourceRootPathFromModuleRoot());
  }

  public List<MavenPomDependency> dependencies(Maps maps) {
    return getDepsOnMavenCoords(maps)
      .entrySet()
      .stream()
      .flatMap(x -> {
        var coord = x.getKey();
        var scopes = x.getValue();

        if (scopes.isEmpty()) {
          return Stream.of();
        }
        var jars = maps.systemImports.get(coord);

        if (jars == null) {
          return Stream.of(new MavenPomDependency(coord, scopes, null, false));
        } else if (jars.size() == 0) {
          return Stream.empty();
        } else if (jars.size() == 1) {
          var jar = toAbsolutePath(jars.get(0));
          return Stream.of(new MavenPomDependency(coord, scopes, jar, false));
        } else {
          var index = 0;
          var result = new ArrayList<MavenPomDependency>();
          for (var jar : jars) {
            result.add(
              new MavenPomDependency(
                new MavenCoordinate(
                  coord.groupId(),
                  coord.artifactId() + "-" + index++,
                  coord.packaging(),
                  coord.classifier(),
                  coord.version()
                ),
                scopes,
                toAbsolutePath(jar),
                false
              )
            );
          }
          return result.stream();
        }
      })
      .toList();
  }

  public MavenCompilerConfig mainCompilerConfig() {
    return new MavenCompilerConfig(
      List.of(),
      List.of(),
      List.of()
      // getMainTargets().flatMap(x -> x.getSplitCopts()).toList()
    );
  }

  public MavenCompilerConfig testCompilerConfig() {
    return new MavenCompilerConfig(
      List.of(),
      List.of(),
      List.of()
      // getTestTargets().flatMap(x -> x.getSplitCopts()).toList()
    );
  }

  public Map<String, String> properties() {
    return Map.ofEntries(
      Map.entry("maven.compiler.source", "17"),
      Map.entry("maven.compiler.target", "17"),
      Map.entry("project.build.sourceEncoding", "UTF-8"),
      Map.entry("project.reporting.outputEncoding", "UTF-8")
    );
  }

  public boolean hasPathThroughModule(
    String moduleFrom,
    String moduleTo,
    Maps maps
  ) {
    return getTransitiveForwardDepsOfModule(moduleFrom, maps)
      .flatMap(x -> x.getDepsWithExtraDepsJavaCompile(maps))
      .anyMatch(x -> x.module().equals(moduleTo));
  }

  public Stream<JavaCompile> getTransitiveForwardDepsOfModule(
    String fromModule,
    Maps maps
  ) {
    var seen = new HashSet<Path>();
    return maps.modulesByPath
      .get(fromModule)
      .targets.stream()
      .flatMap(x -> x.getDepsWithExtraDepsJavaCompile(maps))
      .filter(x -> x.module().equals(path))
      .filter(x -> seen.add(x.jsonPath()))
      .flatMap(x -> x.getDepsWithExtraDepsDeepSameModule(maps, seen));
  }

  public Stream<JavaCompile> getTransitiveReverseDepsOnModule(
    String toModule,
    Maps maps
  ) {
    var seen = new HashSet<Path>();
    return targets
      .stream()
      .filter(x -> seen.add(x.jsonPath()))
      .filter(x -> x.hasDirectDependencyOnModule(toModule, maps))
      .flatMap(x -> x.getReverseDepsWithExtraDepsDeepSameModule(maps, seen));
  }
}
