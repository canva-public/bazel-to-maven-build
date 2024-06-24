package com.canva.pomgen;

import java.util.Map;

public record BazelInfo(
  String bazelBin,
  String bazelGenFiles,
  String bazelTestLogs,
  String characterEncoding,
  String commandLog,
  String committedHeapSize,
  String executionRoot,
  String gcCount,
  String gcTime,
  String installBase,
  String javaHome,
  String javaRuntime,
  String javaVm,
  String localResources,
  String maxHeapSize,
  String outputBase,
  String outputPath,
  String packagePath,
  String release,
  String repositoryCache,
  String serverLog,
  String serverPid,
  String usedHeapSize,
  String workspace
) {
  public BazelInfo(Map<String, String> info) {
    this(
      info.get("bazel-bin"),
      info.get("bazel-gen-files"),
      info.get("bazel-test-logs"),
      info.get("character-encoding"),
      info.get("command_log"),
      info.get("committed-heap-size"),
      info.get("execution_root"),
      info.get("gc-count"),
      info.get("gc-time"),
      info.get("install_base"),
      info.get("java-home"),
      info.get("java-runtime"),
      info.get("java-vm"),
      info.get("local_resources"),
      info.get("max-heap-size"),
      info.get("output_base"),
      info.get("output_path"),
      info.get("package-path"),
      info.get("release"),
      info.get("repository_cache"),
      info.get("server_log"),
      info.get("server_pid"),
      info.get("used-heap-size"),
      info.get("workspace")
    );
  }
}
