# `bazel-to-maven-build`

This tool queries for Java targets in the current Bazel workspace, runs an
aspect and generates `pom.xml` files representing a working Maven build for
usage by IDEs and other tools that can consume `pom.xml` files.

## Installation

There are no published releases, so we recommend choosing the latest commit
hash and adding the following to your `MODULE.bazel`:

```python
archive_override(
    name = "bazel-to-maven-build",
    urls = [
        "https://github.com/canva-public/bazel-to-maven-build/archive/<commit>.zip"
    ],
    integrity = "<integrity>",
    strip_prefix = "bazel-to-maven-build-<commit>",
)

bazel_dep("bazel-to-maven-build")
```

The integrity hash can be generated with `openssl dgst -sha256 -binary
<thefile.zip> | openssl base64 -A | sed 's/^/sha256-/'`.

## Usage

The following will generate a single `pom.xml` in the workspace root that you
can open with the Maven support of IntelliJ, Eclipse, Fleet, VS Code etc.

```
bazel run @bazel-to-maven-build
```

There is experimental support for generating a multi-module project. To use
this, run:

```
CANVA_POMGEN_USE_MULTI_MODULE=true bazel run @bazel-to-maven-build
```

## Contributors

- [Jesse Schalken](https://github.com/jesses-canva)
- [Kelly Huang](https://github.com/kellyhuang11)
- [Jack Zezula](https://github.com/TerrorJacktyl)
