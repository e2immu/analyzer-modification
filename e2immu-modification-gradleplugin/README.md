Gradle plugin
=============

The primary goal of the gradle plugin is to provide the inspector and analyzer with a proper classpath,
and a set of source directories.
These cannot be set as plugin options.

Annotated APIs
--------------

- `analyzedAnnotatedApiDirs`: when absent, annotated APIs are ignored. This is generally a bad idea.
- `annotatedApiSourcePackages` when included, process annotated API sources, instead of reading from the analyzed
  versions.
- `annotatedApiWriteMode`: `no` or absent: do not write, `yes`,`true` to write the analyzed versions.

Input sources
-------------

- `sourcePackages`: which source packages to analyze. If absent, all sources are analyzed.
- `testSourcePackages`: which test source packages to analyze. if absent, all test sources are analyzed.
- `analyzedSourcesDirs`: where to write the results of analysis of sources
- `analyzedTestSourcesDirs`: where to write the results of analysis of test sources

Analysis
--------

- `incremental`: when `true`, build on all information in `analyzed(Test)SourcesDirs`
- `actions`: a CSV describing exactly which analysis steps to carry out. If absent, all steps are executed.

General options
---------------

- `jmods`: which Java modules to add to the classpath
- `jre`: alternative JRE location
