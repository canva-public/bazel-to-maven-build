package com.canva.pomgen;

import static com.canva.pomgen.JsonTargetInfo.warning;
import static java.lang.ProcessBuilder.Redirect.*;
import static java.nio.file.FileVisitResult.CONTINUE;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

@SuppressWarnings({ "Convert2MethodRef", "StatementWithEmptyBody" })
public class Main {

  private static final Pattern bazelConfigPattern = Pattern.compile(
    "^bazel-out/(.*?)/bin/"
  );

  public static String getPathBazelConfigOrNull(String path) {
    var matcher = bazelConfigPattern.matcher(path);
    if (matcher.find()) {
      return matcher.group(1);
    } else {
      return null;
    }
  }

  public static final ObjectMapper mapper = new ObjectMapper();

  public static final Path workspaceDir;
  public static final Path realBazelBinPath;
  public static final Path bazelExecRoot;
  public static final Path bazelOutputBase;
  public static final BazelInfo bazelInfo;

  private static final boolean isDebug = Boolean.parseBoolean(
    System.getenv("CANVA_POMGEN_DEBUG")
  );
  public static final boolean USE_SINGLE_MODULE = !Boolean.parseBoolean(
    System.getenv("CANVA_POMGEN_USE_MULTI_MODULE")
  );

  static {
    bazelInfo = readBazelInfo();
    workspaceDir = Path.of(bazelInfo.workspace());
    realBazelBinPath = Path.of(bazelInfo.bazelBin());
    bazelExecRoot = Path.of(bazelInfo.executionRoot());
    bazelOutputBase = Path.of(bazelInfo.outputBase());
  }

  public static Path toAbsolutePath(String execRootPath) {
    if (execRootPath.startsWith("external/")) {
      // File is in external repo, those are in the output base
      return bazelOutputBase.resolve(execRootPath);
    } else if (execRootPath.startsWith("bazel-out/")) {
      // File is generated, that will be in the exec root
      return bazelExecRoot.resolve(execRootPath);
    } else {
      // Source file in the workspace
      return workspaceDir.resolve(execRootPath);
    }
  }

  private static final List<String> SOURCE_ROOTS = List.of(
    "codegen/src/main/java",
    "codegen/src/test/java",
    "generated/src/main/java",
    "test_container/src/main/java",
    "test_container/src/test/java",
    "src/main/java",
    "src/test/java",
    "src/test-utils/java",
    "src/tools/java",
    "src/tools/test",
    "src/jmh/java",
    "src/test/java_generated",
    "src/main/generated",
    "java",
    "src"
  );

  private static final List<String> RESOURCE_ROOTS = List.of(
    "codegen/src/main/resources",
    "codegen/src/test/resources",
    "test_container/src/main/resources",
    "test_container/src/test/resources",
    "src/main/dynamo",
    "src/test/dynamo",
    "src/dynamo",
    "src/jmh/resources",
    "src/main/resources",
    "src/test/resources",
    "src/test/java",
    "src/tools/resources",
    "src/resources",
    "resources",
    "src"
  );

  public static Optional<String> removeSuffix(String string, String suffix) {
    return Optional.ofNullable(
      string.endsWith(suffix)
        ? string.substring(0, string.length() - suffix.length())
        : null
    );
  }

  public static Optional<String> removePrefix(String string, String prefix) {
    return Optional.ofNullable(
      string.startsWith(prefix) ? string.substring(prefix.length()) : null
    );
  }

  public static <T, U> Function<T, Stream<U>> isInstance(Class<U> clazz) {
    return x -> clazz.isInstance(x) ? Stream.of(clazz.cast(x)) : null;
  }

  public static List<String> runBazel(List<String> args) {
    try {
      // If we are run by "bazel run" then our cwd is our repo in the runfiles tree and Bazel will set
      // BUILD_WORKING_DIRECTORY to the real cwd of the caller, so we need to use that instead if set.
      var bazelRunCwd = System.getenv("BUILD_WORKING_DIRECTORY");

      var allArgs = Stream.concat(Stream.of("bazel"), args.stream()).toList();
      var process = new ProcessBuilder()
        .command(allArgs)
        .directory(bazelRunCwd != null ? new File(bazelRunCwd) : null)
        .redirectInput(PIPE)
        .redirectOutput(PIPE)
        .redirectError(isDebug ? INHERIT : DISCARD)
        .start();

      // close stdin
      process.getOutputStream().close();

      // read stdout
      List<String> output;
      try (var reader = process.inputReader()) {
        output = reader.lines().toList();
      }

      // wait for the process to finish
      process.waitFor();

      if (process.exitValue() != 0) {
        throw new RuntimeException(
          "Bazel command failed (status %d): %s".formatted(
              process.exitValue(),
              allArgs
            )
        );
      }

      // return stdout
      return output;
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public static BazelInfo readBazelInfo() {
    var map = new HashMap<String, String>();
    for (var line : runBazel(List.of("info"))) {
      var split = line.split(": ", 2);
      map.put(split[0], split[1]);
    }
    return new BazelInfo(map);
  }

  final public static class TemporaryFile implements Closeable {
    public final Path path;
    
    public TemporaryFile(String prefix, String suffix) {
      try {
        path = Files.createTempFile(prefix, suffix);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void close() throws IOException {
      Files.delete(path);
    }
  }

  final public static class TemporaryDir implements Closeable {
    public final Path path;

    public TemporaryDir(String prefix) {
      try {
        path = Files.createTempDirectory(prefix);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void close() {
      recursiveDelete(path);
    }
  }

  public static void recursiveDelete(Path path) {
    try {
      Files.walkFileTree(path, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return CONTINUE;
        }
  
        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.delete(dir);
          return CONTINUE;
        }
      });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static TemporaryDir prepareAspectWorkspace() {
    var tempDir = new TemporaryDir("bazel-to-maven-aspect-workspace-");
    try {
      Files.write(tempDir.path.resolve("WORKSPACE"), List.of());
      Files.write(tempDir.path.resolve("BUILD"), List.of());

      // copy aspect definition
      try (var source = Main.class.getResourceAsStream("maven_pom.bzl")) {
        Files.copy(Objects.requireNonNull(source), tempDir.path.resolve("maven_pom.bzl"));
      }
    } catch (IOException e) {
      tempDir.close();
      throw new RuntimeException(e);
    }
    return tempDir;
  }

  public static final String BAZEL_QUERY = """
    let
      targets =
        //...
    in
      kind(java_binary, $targets) +
      kind(java_library, $targets) +
      kind(java_test, $targets) +
      kind(java_plugin, $targets) -
      attr("tags", "[\\[ ]no-ide[,\\]]", $targets)
    """;

  public static List<BazelLabel> runBazelQuery() {
    return runBazel(List.of("query", BAZEL_QUERY))
      .stream()
      .map(BazelLabel::parse)
      .toList();
  }

  public static void runAspect(List<BazelLabel> labels) {
    try (
      var aspectDir = prepareAspectWorkspace();
      var targetList = new TemporaryFile("bazel-to-maven-target-list-", ".txt")) {

      Files.write(targetList.path, labels.stream().map(x -> x.toString()).toList());

      runBazel(List.of(
        "build",
        "--keep_going",
        "--override_repository=bazel_to_maven_build_aspect=" + aspectDir.path,
        "--output_groups=pom_info",
        "--aspects=@@bazel_to_maven_build_aspect//:maven_pom.bzl%maven_pom_aspect",
        "--target_pattern_file=" + targetList.path,
        "--remote_download_outputs=toplevel"
      ));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void main(String[] args) throws Exception {
    System.err.println("Running query...");
    var labels = runBazelQuery();

    System.err.printf("Found %d targets%n", labels.size());

    System.err.println("Running aspect...");
    runAspect(labels);

    System.err.println("Generating pom.xml files...");

    var seen = new HashSet<Path>();
    var targets = labels
      .stream()
      .flatMap(label -> {
        try {
          return JsonTargetInfo
            .readAllRecursive(label.toMavenPomFilePath(), seen)
            .stream();
        } catch (Throwable e) {
          if (e.getCause() instanceof FileNotFoundException) {
            warning("Skipping target %s (%s)".formatted(label, e.getMessage()));
            return Stream.of();
          } else {
            throw e;
          }
        }
      })
      .map(x -> x.json().parse(x.path()))
      .sorted(Comparator.comparing(x -> x.getJsonPath()))
      .toList();

    var maps = new Maps(targets);

    var done = new HashSet<String>();
    var cycles = maps.modulesByPath
      .values()
      .stream()
      .filter(x -> done.add(x.path))
      .flatMap(x -> x.findCycles(maps, done))
      .toList();

    if (!cycles.isEmpty()) {
      System.err.println("Cycles detected:");
      for (var cycle : cycles) {
        System.err.println("  " + String.join(" -> ", cycle));
      }
      cycles
        .stream()
        .flatMap(x -> getTriples(x))
        .collect(Collectors.groupingBy(x -> x))
        .entrySet()
        .stream()
        .sorted(reversed(Comparator.comparing(x -> x.getValue().size())))
        .filter(x -> {
          var k = x.getKey();
          return !maps.modulesByPath
            .get(k.b())
            .hasPathThroughModule(k.a(), k.c(), maps);
        })
        .forEach(pair -> {
          var triple = pair.getKey();
          var list = pair.getValue();
          var module = maps.modulesByPath.get(triple.b());
          var from = triple.a();
          var to = triple.c();
          var path = List.of(triple.a(), triple.b(), triple.c());

          System.err.println();
          System.err.printf(
            "%s (%s cycles)%n",
            String.join(" -> ", path),
            list.size()
          );
          System.err.println();

          System.err.printf(
            "  transitive \"%s ->\" in %s%n",
            from,
            module.path
          );
          for (var target : module
            .getTransitiveForwardDepsOfModule(from, maps)
            .toList()) {
            System.err.printf("    %s%n", target.label());
          }
          System.err.println();

          System.err.printf("  transitive \"-> %s\" in %s%n", to, module.path);
          for (var target : module
            .getTransitiveReverseDepsOnModule(to, maps)
            .toList()) {
            System.err.printf("    %s%n", target.label());
          }
          System.err.println();
        });
    }

    var seenDeps = new HashSet<String>();
    Set<String> cycleImpactedModules = cycles
      .stream()
      .flatMap(list -> list.stream())
      .distinct()
      .flatMap(x -> maps.modulesByPath.get(x).targets.stream())
      .flatMap(x -> x.getReverseDepsWithExtraDepsRecursive(maps, seenDeps))
      .map(x -> x.module())
      .collect(Collectors.toSet());

    // Deleting all existing pom generated pom.xml to ensure no removed folders have dangling poms inside them
    Path pomXmlPath = workspaceDir.resolve("pom.xml");
    deletePomFilesInSubmodules(pomXmlPath);
    for (var module : maps.modulesByPath
      .values()
      .stream()
      .filter(module -> !cycleImpactedModules.contains(module.toString())) // filter out modules that exist in cyclesDirectory
      .sorted(Comparator.comparing(x -> x.path))
      .toList()) {
      generatePomXmlFile(module, maps);
    }

    if (maps.modulesByPath.containsKey("")) {
      // Single module project
      if (maps.modulesByPath.size() > 1) {
        throw new IllegalArgumentException(
          "Cannot create multi module project with sources or resources in root module"
        );
      }
    } else {
      // Multi module project
      writeRootPomXml(
        maps.modulesByPath
          .values()
          .stream()
          .filter(module -> !cycleImpactedModules.contains(module.toString()))
          .collect(Collectors.toList())
      );
    }
    createMvnDir();
  }

  private static void deletePomFilesInSubmodules(Path pomXmlPath) {
    try {
      // Call the method and get the submodule names
      List<String> submodules = getSubmodulesFromPOM(pomXmlPath);

      // For each submodule, resolve its path, check if there is a pom.xml, and delete it if it exists
      for (String submodule : submodules) {
        Path submodulePath = workspaceDir.resolve(submodule);
        Path submodulePomXmlPath = submodulePath.resolve("pom.xml");

        if (Files.exists(submodulePomXmlPath)) {
          System.err.println(
            "Deleting " + workspaceDir.relativize(submodulePomXmlPath)
          );
          Files.delete(submodulePomXmlPath);
        } else if (isDebug) {
          System.out.println(
            "There is no pom.xml in the directory: " + submodulePath
          );
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static List<String> getSubmodulesFromPOM(Path pomXmlPath) {
    List<String> submodules = new ArrayList<>();

    try {
      // Convert the provided path to a real path
      pomXmlPath = pomXmlPath.toRealPath();

      // Read the pom.xml as a document
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document doc = builder.parse(pomXmlPath.toFile());

      // Extract <module> tags
      NodeList modules = doc.getElementsByTagName("module");

      // Go through each module and add its text content i.e. the submodule to the list
      for (int i = 0; i < modules.getLength(); i++) {
        Node moduleNode = modules.item(i);
        if (moduleNode.getNodeType() == Node.ELEMENT_NODE) {
          Element moduleElement = (Element) moduleNode;
          submodules.add(moduleElement.getTextContent());
        }
      }
    } catch (NoSuchFileException e) {
      System.out.println("No pom.xml file found at the provided path.");
    } catch (IOException | SAXException | ParserConfigurationException e) {
      throw new RuntimeException(
        "An error occurred while processing the pom.xml file.",
        e
      );
    }

    return submodules;
  }

  private static void createMvnDir() throws IOException {
    // Maven uses this dir to determine the root of the multimodule project
    var mvnDir = workspaceDir.resolve(".mvn");
    if (!Files.exists(mvnDir)) {
      Files.createDirectory(mvnDir);
    }
  }

  private static void writeRootPomXml(Collection<MavenModule> modules) {
    var document = new XmlGenerator();
    document.createProject(
      new MavenCoordinate(
        "bazel.generated",
        "canva",
        "pom",
        null,
        "1.0-SNAPSHOT"
      ),
      document.element(
        "modules",
        modules
          .stream()
          .sorted(Comparator.comparing(x -> x.path))
          .map(x -> document.element("module", x.path))
      )
    );
    document.write(workspaceDir.resolve("pom.xml"));
  }

  public static <T> Comparator<T> reversed(Comparator<T> c) {
    return c.reversed();
  }

  public static <T> List<T> listAdd(List<T> xs, T x) {
    var xs2 = new ArrayList<>(xs);
    xs2.add(x);
    return xs2;
  }

  public static <T> Stream<Triple<T, T, T>> getTriples(List<T> list) {
    return IntStream
      .range(0, list.size())
      .mapToObj(i ->
        new Triple<>(
          list.get(i),
          list.get((i + 1) % list.size()),
          list.get((i + 2) % list.size())
        )
      );
  }

  /**
   * <a href="https://bazel.build/reference/be/common-definitions#sh-tokenization">link</a>
   */
  public static List<String> bourneShellTokenize(String s) {
    var args = new ArrayList<String>();
    var i = 0;
    var arg = new StringBuilder();
    var valid = false;
    while (i < s.length()) {
      var c = s.charAt(i++);
      if (c == ' ' || c == '\t') {
        if (valid) {
          args.add(arg.toString());
          arg.setLength(0);
          valid = false;
        }
      } else if (c == '\'') {
        valid = true;
        while (i < s.length()) {
          c = s.charAt(i++);
          if (c == '\'') {
            break;
          }
          arg.append(c);
        }
      } else if (c == '\"') {
        valid = true;
        while (i < s.length()) {
          c = s.charAt(i++);
          if (c == '"') {
            break;
          } else if (c == '\\') {
            if (i < s.length()) {
              arg.append(s.charAt(i++));
            }
          } else {
            arg.append(c);
          }
        }
      } else if (c == '\\') {
        if (i < s.length()) {
          arg.append(s.charAt(i++));
          valid = true;
        }
      } else {
        valid = true;
        arg.append(c);
      }
    }
    if (valid) {
      args.add(arg.toString());
    }
    return args;
  }

  public static void generatePomXmlFile(MavenModule module, Maps maps) {
    var generator = new XmlGenerator();
    generator.createProject(module, maps);
    generator.write(workspaceDir.resolve(module.path).resolve("pom.xml"));
  }

  public static final Pattern javaPathRegex = Pattern.compile(
    "^(bazel-out/[^/]*/bin/|)(.*?)/([^/]*)$"
  );
  public static final Pattern resourcePathRegex = Pattern.compile(
    "^(bazel-out/[^/]*/bin/|)(.*?)$"
  );

  static class InvalidPathException extends Exception {

    public InvalidPathException(String message) {
      super(message);
    }
  }

  public static SourcePath parseJavaPath(String path)
    throws InvalidPathException {
    var matcher = javaPathRegex.matcher(path);
    if (!matcher.matches()) {
      throw new InvalidPathException("Invalid java source file path");
    }

    var prefix = matcher.group(1);
    var directory = matcher.group(2);
    var fileName = matcher.group(3);
    var packagePath = getJavaPackage(path).replace(".", "/");

    var sourceRoot = Stream
      .of(directory, directory.replace("/generated/", "/"))
      .flatMap(x -> removeSuffix(x, "/" + packagePath).stream())
      .findFirst()
      .orElseThrow(() ->
        new InvalidPathException(
          "Directory should end in %s".formatted(packagePath)
        )
      );

    var filePath =
      removePrefix(directory, sourceRoot + "/").orElseThrow() + "/" + fileName;

    if (USE_SINGLE_MODULE) {
      return new SourcePath(new SourceRoot(prefix, "", sourceRoot), filePath);
    }

    for (var root : SOURCE_ROOTS) {
      var moduleRoot = removeSuffix(sourceRoot, "/" + root);
      if (moduleRoot.isPresent()) {
        return new SourcePath(
          new SourceRoot(prefix, moduleRoot.get(), root),
          filePath
        );
      }
    }
    throw new InvalidPathException(
      "Java file path is not a recognised pattern"
    );
  }

  public static SourcePath parseResourcePath(String path)
    throws InvalidPathException {
    var matcher = resourcePathRegex.matcher(path);
    if (!matcher.matches()) {
      throw new InvalidPathException("Invalid resource path");
    }

    var prefix = matcher.group(1);
    var path2 = matcher.group(2);
    for (var root : RESOURCE_ROOTS) {
      var index = path2.indexOf("/" + root + "/");
      if (index == -1) {
        continue;
      }
      var modulePath = path2.substring(0, index);
      return new SourcePath(
        USE_SINGLE_MODULE
          ? new SourceRoot(prefix, "", modulePath + "/" + root)
          : new SourceRoot(prefix, modulePath, root),
        path2.substring(index + root.length() + 2)
      );
    }
    throw new InvalidPathException(
      "Resource is not in a recognised resource root"
    );
  }

  public static String getJavaPackage(String path) throws InvalidPathException {
    try (var reader = Files.newBufferedReader(toAbsolutePath(path))) {
      var line = reader.readLine();
      if (line == null) {
        throw new InvalidPathException("File is empty");
      }
      do {
        if (line.startsWith("package ") && line.endsWith(";")) {
          return line.substring(8, line.length() - 1);
        }
        line = reader.readLine();
      } while (line != null);
      throw new InvalidPathException("No package line found");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
