"""
Aspect for gathering information from java_* targets to use for generating pom.xml files.
"""

_MAVEN_COORDINATES_PREFIX = "maven_coordinates="
_MAVEN_URL_PREFIX = "maven_url="

def _get_maven_coordinates(ctx):
    for tag in ctx.rule.attr.tags:
        if tag.startswith(_MAVEN_COORDINATES_PREFIX):
            return tag[len(_MAVEN_COORDINATES_PREFIX):]
    return None

def _get_maven_url(ctx):
    for tag in ctx.rule.attr.tags:
        if tag.startswith(_MAVEN_URL_PREFIX):
            return tag[len(_MAVEN_URL_PREFIX):]
    return None

# Copied from src/main/starlark/builtins_bzl/common/java/java_common.bzl:43
def _collect_deps(attr):
    dep_list = []

    # Native code collected data into a NestedSet, using add for legacy jars and
    # addTransitive for JavaInfo. This resulted in legacy jars being first in the list.
    for dep in attr:
        if not JavaInfo in dep:
            for file in dep[DefaultInfo].files.to_list():
                if file.extension == "jar":
                    dep_list.append(JavaInfo(
                        output_jar = file,
                        compile_jar = file,
                    ))

    for dep in attr:
        if JavaInfo in dep:
            dep_list.append(dep[JavaInfo])

    return dep_list

def _get_java_infos_jars(java_infos):
    return depset([], transitive = [
        set
        for j in java_infos
        for set in [
            j.full_compile_jars,
            j.compile_jars,
            depset(j.runtime_output_jars),
            depset(j.source_jars),
        ]
    ]).to_list()

def _get_java_output_for_maven(java_infos):
    return depset([], transitive = [
        set
        for j in java_infos
        for set in [
            j.full_compile_jars,
            # j.compile_jars,
            depset(j.runtime_output_jars),
            # j.source_jars,
        ]
    ]).to_list()

MavenPomInfo = provider(
    fields = [
        "file",
        "deps",
    ],
    doc = "",
)

def _is_excluded_src(file):
    if not file.is_source and file.basename.endswith("Messages.java"):
        return True
    if not file.is_source and file.basename.endswith("TestSuite.java"):
        return True
    return False

def _is_excluded_resource(file):
    if not file.is_source:
        return True
    return False

def _is_excluded_label(label):
    # Everything external is excluded
    if label.workspace_name != "":
        return True

    return False

def _has_srcjars(ctx):
    for file in ctx.rule.files.srcs:
        if file.extension == "srcjar":
            return True
    return False

def _maven_pom_aspect(target, ctx):
    kind = ctx.rule.kind

    java_infos = _collect_deps([target])
    output_jars = _get_java_infos_jars(java_infos)
    maven_coords = _get_maven_coordinates(ctx)
    maven_url = _get_maven_url(ctx)
    testonly = ctx.rule.attr.testonly

    is_java_binary = kind == "java_binary"
    is_java_test = kind == "java_test"
    is_java_plugin = kind == "java_plugin"
    is_java_library = kind == "java_library"
    is_java_import = kind == "java_import"
    is_jvm_import = kind == "jvm_import"  # From rules_jvm_external

    is_any_import = is_java_import or is_jvm_import
    is_any_compile = is_java_binary or is_java_test or is_java_library or is_java_plugin
    is_any_java = is_any_compile or is_any_import

    deps = ctx.rule.attr.deps if is_any_java else []

    # Disabled until we care about runtime deps in Maven/IntelliJ build
    #    runtime_deps = ctx.rule.attr.runtime_deps if (
    #        is_java_binary or
    #        is_java_test or
    #        is_java_library or
    #        is_java_import
    #    ) else []
    runtime_deps = []

    exports = ctx.rule.attr.exports if is_java_library or is_java_import else []
    exported_jars = _get_java_infos_jars(_collect_deps(exports))

    # Remove output jars that come from exports, this way those jars show up as outputs only in
    # the target that actually produces them, and Maven modules will be forced to depend directly
    # on what we export instead of only on us.
    output_jars = [f for f in output_jars if f not in exported_jars]

    if not output_jars:
        return []

    srcs = []
    resources = []
    resource_strip_prefix = None
    plugins = []
    javacopts = []
    jars = []

    if (
        is_any_compile and
        not _is_excluded_label(target.label) and
        not _has_srcjars(ctx)
    ):
        srcs = [x for x in ctx.rule.files.srcs if not _is_excluded_src(x)]
        resources = [x for x in ctx.rule.files.resources if not _is_excluded_resource(x)]
        resource_strip_prefix = ctx.rule.attr.resource_strip_prefix
        plugins = ctx.rule.attr.plugins
        javacopts = ctx.rule.attr.javacopts

        # Unneeded as Maven has no analog and it is already included in the .full_compile_jars of deps
        # exports = ctx.rule.attr.exports if is_java_library or is_java_import else []
        # Unneeded as Maven has no analog and it is already included in the .plugins of deps
        # exported_plugins = ctx.rule.attr.exported_plugins if is_java_library else []

        # TODO
        # proguard_specs = ctx.rule.files.proguard_specs if is_java_library or is_java_plugin else []

    elif maven_coords != None and maven_url != None:
        # Maven can fetch this remotely, no need to import a .jar
        pass
    else:
        # This could be a java_import or jvm_import or some other java_* rule outside our workspace
        # so to get the jars lets just grab them from the JavaInfo
        jars = _get_java_output_for_maven(java_infos)

        # Remove jars from exports
        jars = [j for j in jars if j not in exported_jars]

    dep_infos = _collect_deps(deps)
    runtime_dep_infos = _collect_deps(runtime_deps)

    plugin_infos = [p[JavaPluginInfo] for p in plugins if JavaPluginInfo in p]
    javacopts_expanded = [ctx.expand_location(opt) for opt in javacopts]

    other_maven_infos = [
        t[MavenPomInfo]
        for t in (
            deps +
            runtime_deps +
            plugins +
            exports
        )
        if MavenPomInfo in t
    ]

    depsets = [
        depset([t.file], transitive = t.deps)
        for t in other_maven_infos
    ] + [
        depset(srcs + resources + jars),
    ]

    source_files = [f for f in srcs if f.extension == "java"]
    source_jars = [f for f in srcs if f.extension == "srcjar"]
    properties = [f for f in srcs if f.extension == "properties"]

    if len(source_jars) > 0:
        fail("srcjars should have been excluded by _has_srcjars above")

    if properties:
        resources = list(resources)
        resources.extend(properties)

    # TODO plugins from toolchain and --plugins?
    all_plugin_data = [p.plugins for p in plugin_infos] + \
                      [d.plugins for d in dep_infos]

    # This has to match the structure on the Java side
    json_data = struct(
        label = str(target.label),
        kind = kind,
        mavenCoords = maven_coords,
        mavenUrl = maven_url,
        jars = [f.path for f in jars],
        outputJars = [f.path for f in output_jars],
        runtimeJars = [
            f.path
            for d in runtime_dep_infos + dep_infos
            for f in d.runtime_output_jars
        ],
        compileJars = [
            f.path
            for d in dep_infos
            for f in d.full_compile_jars.to_list()
        ],
        pluginJars = [
            j.path
            for p in all_plugin_data
            for j in p.processor_jars.to_list()
        ],
        pluginClasses = [
            c
            for p in all_plugin_data
            for c in p.processor_classes.to_list()
        ],
        # TODO copts from deps, toolchain and --javacopts?
        javaCopts = javacopts_expanded,
        srcs = [f.path for f in source_files],
        resources = [f.path for f in resources],
        # TODO copy Bazel's default resource_strip_prefix logic
        resourceStripPrefix = resource_strip_prefix,
        testOnly = testonly,
        otherInfos = [
            f.file.path
            for f in other_maven_infos
        ],
    )

    file = ctx.actions.declare_file(ctx.rule.attr.name + "-maven-info.json")

    ctx.actions.write(file, json.encode_indent(json_data, indent = "  ") + "\n")

    return [
        MavenPomInfo(file = file, deps = depsets),
        OutputGroupInfo(pom_info = depset([file], transitive = depsets)),
    ]

maven_pom_aspect = aspect(
    implementation = _maven_pom_aspect,
    attr_aspects = [
        # Attributes consuming JavaInfo or JavaPluginInfo
        "deps",
        "runtime_deps",
        "plugins",
        "exports",
    ],
    requires = [],
)
