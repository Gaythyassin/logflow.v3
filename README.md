# LogFlow — Increment 4 (v4)

Configuration-driven assembly and a stage registry.

## Goal

A pipeline is no longer assembled in Java code. A YAML file describes it, and the
**same, unmodified executable** runs any pipeline described this way:

```bash
./logflow --config configs/error-report.yaml
```

Stage type names (`parse`, `filter`, `project`, `aggregate`, ...) are mapped to their
implementations through a `ServiceLoader`-based registry. Every configuration error is
detected **before any data is read** and reported clearly (fail fast, fail loudly).

## Project structure

```text
logflow-project/
├── configs/
│   ├── error-report.yaml          error report (status >= 400)
│   ├── api-traffic.yaml           API traffic extract (CSV)
│   ├── bandwidth-by-client.yaml   bandwidth per client (aggregate, CSV)
│   └── invalid/                   deliberately broken configs (see "Error messages")
├── data/
│   ├── access-small.log           200 lines, every client IP once
│   └── access-clients.log         300 synthetic lines, 12 recurring clients (new)
├── src/main/java/logflow/
│   ├── Stage.java                 (v4: finish(Emitter) hook)
│   ├── Pipeline.java              (v4: calls finish on every stage after the source ends)
│   ├── AggregateStage.java        (new: group, count, sum, top N)
│   ├── Comparison.java            (new: == != > >= < <= startsWith endsWith contains)
│   ├── Filters.java               (v4: compare(field, op, value))
│   ├── OutputFormat.java          (new: text | csv)
│   ├── Reportable.java            (new: one-line run summary per component)
│   ├── Main.java                  (v4: --config, --list-stages; v3 options still work)
│   └── ... v1–v3 classes
├── src/main/java/logflow/config/  (new)
│   ├── YamlParser.java            dependency-free YAML subset reader
│   ├── PipelineSpec.java          config structure (description, no classes)
│   ├── ComponentSpec.java
│   ├── PipelineLoader.java        spec -> validated Pipeline
│   ├── LoadedPipeline.java
│   ├── ComponentFactory.java      type(), description(), params()
│   ├── SourceFactory.java / StageFactory.java / SinkFactory.java
│   ├── Registry.java              type name -> factory (ServiceLoader)
│   ├── StageRegistry.java
│   ├── Plugins.java               source + stage + sink registries
│   ├── ParamSpec.java / ParamType.java / Params.java   declarative parameter validation
│   ├── ConfigException.java       all configuration problems at once
│   └── Suggestions.java           "Did you mean ...?"
├── src/main/java/logflow/plugins/ (new: built-in factories)
│   ├── FileSourceFactory.java
│   ├── ParseStageFactory.java  FilterStageFactory.java  ProjectStageFactory.java  AggregateStageFactory.java
│   └── FileSinkFactory.java    ConsoleSinkFactory.java
├── src/main/resources/META-INF/services/
│   ├── logflow.config.SourceFactory
│   ├── logflow.config.StageFactory
│   └── logflow.config.SinkFactory
├── src/test/java/logflow/config/  PipelineLoaderTest, StageRegistryTest, YamlParserTest (new)
├── src/test/java/logflow/         AggregateStageTest, FiltersCompareTest, OutputFormatTest,
│                                  MainConfigModeTest (new) + v2/v3 tests
├── logflow / logflow.sh / logflow.bat
├── pom.xml                        (JUnit 5 + JaCoCo, runnable jar)
├── ARCHITECTURE.md                (Increment 4: configuration schema, registry, error policy)
└── CHANGELOG.md
```

## Build and run

Requires JDK 17+. No external runtime dependencies.

macOS / Linux (the `logflow` script compiles into `build/classes` and runs):

```bash
./logflow --config configs/error-report.yaml
./logflow --config configs/api-traffic.yaml
./logflow --config configs/bandwidth-by-client.yaml
./logflow --list-stages              # every registered source, stage and sink with its parameters
```

Windows:

```text
logflow.bat --config configs\error-report.yaml
```

With Maven:

```bash
mvn package
java -jar target/logflow-4.0.0.jar --config configs/error-report.yaml
```

Relative paths in a config are resolved against the directory the command is run from,
so run the commands above from the project root.

### Same executable, three configs, three outputs

```text
$ ./logflow --config configs/error-report.yaml
Pipeline 'error-report' (configs/error-report.yaml)
  parse      parsed: 200, malformed (skipped): 0
  filter     status>=400                    in: 200, passed: 80
  sink       written: 80 -> out/errors.txt

$ head -2 out/errors.txt
timestamp=2026-09-29T10:01:18+03:00 clientIp=192.168.0.7 status=400 path=/cart
timestamp=2026-09-29T10:01:31+03:00 clientIp=192.168.0.8 status=401 path=/checkout

$ ./logflow --config configs/api-traffic.yaml
Pipeline 'api-traffic' (configs/api-traffic.yaml)
  parse      parsed: 200, malformed (skipped): 0
  filter     path startsWith /api           in: 200, passed: 25
  sink       written: 25 -> out/api-traffic.csv

$ head -3 out/api-traffic.csv
timestamp,method,path,status,bytes
2026-09-29T10:00:52+03:00,GET,/api/items,204,404
2026-09-29T10:02:36+03:00,GET,/api/items,200,700

$ ./logflow --config configs/bandwidth-by-client.yaml
Pipeline 'bandwidth-by-client' (configs/bandwidth-by-client.yaml)
  parse      parsed: 300, malformed (skipped): 0
  filter     status<400                     in: 300, passed: 256
  aggregate  by clientIp                    in: 256, groups: 12, emitted: 10
  sink       written: 10 -> out/bandwidth-by-client.csv

$ head -4 out/bandwidth-by-client.csv
clientIp,requests,bytes
10.1.0.1,71,1997893
10.1.0.3,42,1201792
10.1.0.2,43,1072821
```

The run summary goes to standard error, so a `console` sink's output on standard output
can be piped.

## Configuration format

```yaml
pipeline:
  name: error-report

  source:
    type: file
    params: { path: data/access-small.log }

  stages:
    - type: parse
      params: { format: clf }

    - type: filter
      params: { field: status, op: ">=", value: 400 }

    - type: project
      params: { fields: [timestamp, clientIp, status, path] }

  sink:
    type: file
    params: { path: out/errors.txt }
```

The full schema, every registered component and its parameters, and the supported YAML
subset are documented in **ARCHITECTURE.md → Increment 4**.

## Error messages

Configuration errors are found before any input is read or any output file is created.
All problems are reported together, each with its location, and the program exits with
code **2**. (Runtime failures exit with code 1.) The files below are in `configs/invalid/`.

**Unknown stage type**

```text
$ ./logflow --config configs/invalid/unknown-stage.yaml
Configuration error: Invalid pipeline configuration 'configs/invalid/unknown-stage.yaml' (1 problem); nothing was processed:
  - pipeline.stages[1].type: unknown stage type 'filtr'. Did you mean 'filter'? Known stage types: [aggregate, filter, parse, project]
```

**Missing parameter**

```text
$ ./logflow --config configs/invalid/missing-param.yaml
Configuration error: Invalid pipeline configuration 'configs/invalid/missing-param.yaml' (2 problems); nothing was processed:
  - pipeline.stages[1] (filter): missing required parameter 'value' (string or number) - number for status/bytes, time for timestamp, text otherwise
  - pipeline.stages[2] (project): missing required parameter 'fields' (list of strings) - fields to keep, from [clientIp, user, timestamp, method, path, protocol, status, bytes, referer, userAgent]
```

**Parameter of the wrong type**

```text
$ ./logflow --config configs/invalid/wrong-type.yaml
Configuration error: Invalid pipeline configuration 'configs/invalid/wrong-type.yaml' (3 problems); nothing was processed:
  - pipeline.stages[1] (filter): field 'status' is numeric, so 'value' must be a number, got "abc"
  - pipeline.stages[2] (project): parameter 'fields' must be a list of strings, got string "clientIp" (write a list: [clientIp])
  - pipeline.sink (file): parameter 'path' must be a string, got integer 42 (quote it: "42")
```

**Empty pipeline**

```text
$ ./logflow --config configs/invalid/empty-pipeline.yaml
Configuration error: Invalid pipeline configuration 'configs/invalid/empty-pipeline.yaml' (1 problem); nothing was processed:
  - pipeline.stages: the pipeline is empty; add at least one stage, e.g. '- type: parse'
```

**Stages in an impossible order** (types are checked at load time)

```text
$ ./logflow --config configs/invalid/wrong-order.yaml
Configuration error: Invalid pipeline configuration 'configs/invalid/wrong-order.yaml' (2 problems); nothing was processed:
  - pipeline.stages[0] (filter): expects LogRecord records, but the source 'file' produces String. Add a 'parse' stage before it.
  - pipeline.stages[1] (parse): expects String records, but pipeline.stages[0] (filter) produces LogRecord.
```

Other examples produced by the loader:

```text
  - pipeline.stages[0]: unknown key 'param'. Did you mean 'params'? Allowed keys: [type, params]
  - pipeline.stages[1] (project): unknown parameter 'field'. Did you mean 'fields'? Known parameters: [fields]
  - pipeline.stages[1] (aggregate): parameter 'top' must be an integer, got string "10" (remove the quotes: 10)
  - pipeline.stages[1] (filter): parameter 'field' must be one of [clientIp, user, ...], got string "stauts". Did you mean 'status'?
  - pipeline.source (file): input file not found: data/acess.log (relative paths are resolved against /home/me/logflow)
  - line 3: expected ',' or ']' in '[ { type: parse }' (multi-line [ ] is not supported)
  - config file not found: /home/me/logflow/nope.yaml
```

## Adding a new stage

1. Write a class implementing `logflow.config.StageFactory` (type name, parameters,
   input/output types, `create`).
2. Add its fully qualified name to
   `src/main/resources/META-INF/services/logflow.config.StageFactory`.
3. Use `- type: <name>` in a config.

No change to `PipelineLoader`, `Main` or any other stage.

## Tests and coverage

```bash
mvn test
```

84 tests. Line coverage (JaCoCo, `target/site/jacoco/index.html`): **83%**.

Loader tests (`PipelineLoaderTest`) cover the cases from the assignment and more:

| Case | Test |
|---|---|
| valid configuration | `validConfigBuildsTheDeclaredPipelineAndRunsIt`, `optionalParametersUseTheirDefaults` |
| unknown stage type | `unknownStageTypeIsRejectedWithSuggestionAndKnownTypes`, `unknownSourceAndSinkTypesAreRejected` |
| missing parameter | `missingRequiredParameterIsRejected`, `stageWithoutParamsBlockReportsEveryMissingParameter`, `missingSourceFileIsAConfigurationError` |
| wrong parameter type | `quotedNumberWhereIntegerIsExpectedIsRejected`, `stringWhereListIsExpectedIsRejected`, `valueThatDoesNotMatchTheFieldTypeIsRejected`, `valueOutsideTheAllowedSetIsRejected` |
| empty pipeline | `emptyStageListIsRejected`, `emptyFileAndEmptyPipelineSectionAreRejected`, `missingSectionsAreAllReported` |
| other | stage order, unknown keys, all problems at once, missing config file, YAML syntax errors |

Every rejection test also asserts that **no output file was created**.
`MainConfigModeTest` runs the three shipped configs through the same `Main` and checks
the three different outputs; `StageRegistryTest` registers a new stage without touching
the loader.

## v3 command-line mode

The v3 options still work when `--config` is not given:

```text
logflow <log-file> [--min-status N] [--path-prefix P] [--from TIME] [--to TIME] [--fields a,b,c] [--out FILE]
```

See the v3 tag for details.
