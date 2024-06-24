// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import static com.canva.pomgen.Main.workspaceDir;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class XmlGenerator {

  public final Document document;

  public static final String namespace = "http://maven.apache.org/POM/4.0.0";
  public static final String schemaLocation =
    "http://maven.apache.org/xsd/maven-4.0.0.xsd";
  private static final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
  private static final TransformerFactory transformerFactory = TransformerFactory.newInstance();

  {
    try {
      document = documentBuilderFactory.newDocumentBuilder().newDocument();
      document.setXmlVersion("1.0");
    } catch (ParserConfigurationException e) {
      throw new RuntimeException(e);
    }
  }

  public Element element(String tag, String text) {
    var element = document.createElementNS(namespace, tag);
    element.setTextContent(text);
    return element;
  }

  public Element element(String tag, Stream<Element> children) {
    var element = document.createElementNS(namespace, tag);
    children.forEach(child -> {
      if (child != null) {
        element.appendChild(child);
      }
    });
    return element;
  }

  public Element element(String tag, Element... children) {
    return element(tag, Arrays.stream(children));
  }

  public void createProject(MavenCoordinate coordinate, Element... children) {
    var element = element("project");
    element.setAttributeNS( //
      "http://www.w3.org/2001/XMLSchema-instance",
      "schemaLocation",
      namespace + " " + schemaLocation
    );
    element.appendChild(element("modelVersion", "4.0.0"));
    element.appendChild(element("groupId", coordinate.groupId()));
    element.appendChild(element("artifactId", coordinate.artifactId()));
    element.appendChild(element("version", coordinate.version()));
    element.appendChild(element("packaging", coordinate.packaging()));
    for (var child : children) {
      element.appendChild(child);
    }
    document.appendChild(element);
  }

  public void createProject(MavenModule data, Maps maps) {
    var sourceDirs = data.sourceDirectories().sorted().toList();
    var testSourceDirs = data.testSourceDirectories().sorted().toList();
    var resourceDirs = data.resourceDirectories().sorted().toList();
    var testResourceDirs = data.testResourceDirectories().sorted().toList();
    createProject(
      data.coordinate,
      element(
        "repositories",
        data
          .getMavenRepositories(maps)
          .stream()
          .map(x ->
            element(
              "repository",
              element("id", x.replaceAll("\\W+", "-")),
              element("url", x),
              element(
                "releases",
                element("enabled", "true"),
                element("updatePolicy", "never")
              ),
              element(
                "snapshots",
                element("enabled", "false"),
                element("updatePolicy", "never")
              )
            )
          )
      ),
      element(
        "properties",
        data
          .properties()
          .entrySet()
          .stream()
          .sorted(Map.Entry.comparingByKey())
          .map(x -> element(x.getKey(), x.getValue()))
      ),
      element(
        "build",
        element(
          "directory",
          workspaceDir + "/maven_build/" + data.pathPrefix + "target"
        ),
        element(
          "plugins",
          element(
            "plugin",
            element("groupId", "org.apache.maven.plugins"),
            element("artifactId", "maven-resources-plugin"),
            // Old version to work around https://issues.apache.org/jira/browse/MRESOURCES-237
            element("version", "2.7")
          ),
          element(
            "plugin",
            element("groupId", "org.apache.maven.plugins"),
            element("artifactId", "maven-compiler-plugin"),
            element("version", "3.10.1"),
            element(
              "executions",
              element(
                "execution",
                element("id", "default-compile"),
                element("phase", "compile"),
                element("goals", element("goal", "compile")),
                element(
                  "configuration",
                  element(
                    "compilerArgs",
                    data
                      .mainCompilerConfig()
                      .compilerArgs()
                      .stream()
                      .map(x -> element("arg", x))
                  )
                )
              ),
              element(
                "execution",
                element("id", "default-testCompile"),
                element("phase", "test-compile"),
                element("goals", element("goal", "testCompile")),
                element(
                  "configuration",
                  element(
                    "compilerArgs",
                    data
                      .testCompilerConfig()
                      .compilerArgs()
                      .stream()
                      .map(x -> element("arg", x))
                  )
                )
              )
            )
          ),
          element(
            "plugin",
            element("groupId", "org.codehaus.mojo"),
            element("artifactId", "build-helper-maven-plugin"),
            element("version", "3.3" + ".0"),
            element(
              "executions",
              element(
                "execution",
                element("id", "add-source"),
                element("phase", "generate-sources"),
                element("goals", element("goal", "add-source")),
                element(
                  "configuration",
                  element(
                    "sources",
                    sourceDirs
                      .stream()
                      .skip(1)
                      .map(x -> element("source", x.toString()))
                  )
                )
              ),
              element(
                "execution",
                element("id", "add-test-source"),
                element("phase", "generate-test-sources"),
                element("goals", element("goal", "add-test-source")),
                element(
                  "configuration",
                  element(
                    "sources",
                    testSourceDirs
                      .stream()
                      .skip(1)
                      .map(x -> element("source", x.toString()))
                  )
                )
              )
            )
          )
        ),
        element(
          "sourceDirectory",
          sourceDirs
            .stream()
            .map(x -> x.toString())
            .findFirst()
            .orElse("no_sources")
        ),
        element(
          "testSourceDirectory",
          testSourceDirs
            .stream()
            .map(x -> x.toString())
            .findFirst()
            .orElse("no_test_sources")
        ),
        element(
          "resources",
          resourceDirs
            .stream()
            .map(dir ->
              element(
                "resource",
                element("directory", dir.toString()),
                element("filtering", "false"),
                element("includes", element("include", "**/*"))
              )
            )
        ),
        element(
          "testResources",
          testResourceDirs
            .stream()
            .map(dir ->
              element(
                "testResource",
                element("directory", dir.toString()),
                element("filtering", "false"),
                element("includes", element("include", "**/*"))
              )
            )
        )
      ),
      element(
        "dependencies",
        data
          .dependencies(maps)
          .stream()
          .sorted(
            Comparator
              .comparing((MavenPomDependency x) -> x.coordinate().groupId())
              .thenComparing(x -> x.coordinate().artifactId())
          )
          .map(x ->
            element(
              "dependency",
              element("groupId", x.coordinate().groupId()),
              element("artifactId", x.coordinate().artifactId()),
              element("version", x.coordinate().version()),
              element("type", x.coordinate().packaging()),
              element("scope", x.getMavenScope()),
              element("optional", Boolean.toString(x.optional())),
              x.coordinate().classifier() != null
                ? element("classifier", x.coordinate().classifier())
                : null,
              x.systemPath() != null
                ? element("systemPath", x.systemPath().toString())
                : null,
              MavenArtifact.USE_BAZEL_DEPENDENCY_RESOLUTION &&
                  !x.coordinate().groupId().equals("bazel.generated")
                ? element(
                  "exclusions",
                  element(
                    "exclusion",
                    element("groupId", "*"),
                    element("artifactId", "*")
                  )
                )
                : null
            )
          )
      )
    );
  }

  public void write(Path path) {
    System.err.println("Writing " + workspaceDir.relativize(path));
    try (var writer = Files.newBufferedWriter(path)) {
      write(writer);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void write(Writer writer) {
    try {
      var transformer = transformerFactory.newTransformer();
      transformer.setOutputProperty(
        OutputKeys.ENCODING,
        StandardCharsets.UTF_8.name()
      );
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.setOutputProperty(
        "{http://xml.apache.org/xslt}indent-amount",
        "2"
      );
      transformer.transform(new DOMSource(document), new StreamResult(writer));
    } catch (TransformerException e) {
      throw new RuntimeException(e);
    }
  }
}
