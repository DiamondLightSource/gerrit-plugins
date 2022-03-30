# Gerrit plugins

Contains source for Gerrit plugins written in Java.

## Development

Clone repository and import as Maven project into IDE of choice.

## Versioning

The project is versioned independently of the Gerrit API version it targets. It is intended to use semantic versioning.
This may be reviewed at a later date to use the versioning scheme that Gerrit uses for its own plugins (matching the
Gerrit API version). The initial version of these plugins is 1.2, releases prior to this are internal (before plugins
converted to Java).

## Making a release

This project is handled by the `maven-release-plugin`. To create a release use:

```
mvn release:prepare
```
Note that this will create some files in the working tree, this can be cleaned with `mvn release:clean`. The project
does not currently "perform" the release (publishing to Maven repository).

This will ask for some input:

* *What is the release for ...?* - Choose the version, using same for all modules. The default should be appropriate
  (`SNAPSHOT` removed from current version) e.g. `1.1.0`
* *What is the SCM release tag for ...?* - The default is sufficient, match version from previous e.g.
  `gerrit-plugins-1.1.0`
* *What is the new development version for ...?* - The default is that the patch version is increased by one, this may
  be fine or if larger changes are planned the major or minor version could be updated. This should be a `SNAPSHOT`
  version. e.g. `1.1.1-SNAPSHOT`

This will prompt for release versions as will as setting the `SNAPSHOT` version for the next release. This process also
creates a tag and pushes it.

GitHub actions have been configured to build the project and also to create a GitHub release when tags are pushed
(matching the specific prefix). This allows the individual plugin jars to be downloaded from the release page.
