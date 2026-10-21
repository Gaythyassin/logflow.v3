# Changelog

## v2 — Increment 2
- `LogRecord` immutable domain model with an `attributes` extension map.
- `ParserStage` parses Common Log Format (Combined referer/user agent optional); malformed lines are skipped and counted.
- `ConsoleSink` is now a `Sink<LogRecord>`.
- JUnit 5 tests (`CollectingEmitter` test double) and `pom.xml` with JaCoCo.

## v3 — Increment 3: composition
- `PredicateFilterStage<T>` with `Filters.minStatus`, `Filters.pathPrefix`, `Filters.between` (time range).
- `ProjectionStage` + `ProjectedRecord`: keep only selected fields (data minimisation).
- `FileSink<T>`: buffered file output with real `open()`/`close()`.
- `Sink` gets `open()`/`close()`; `Pipeline.run()` opens all components and always closes them.
- Fluent, type-safe, immutable `PipelineBuilder`.
- Command line: `--min-status`, `--path-prefix`, `--from`, `--to`, `--fields`, `--out` (`CliOptions`). Filters run in the order given.
- `OrderingExperiment` and the write-up in ARCHITECTURE.md.
- `LogRecord.field("timestamp")` always includes seconds (`10:01:00`, not `10:01`).
- Tests for every new stage, the builder, the CLI, and a 3-stage integration test on `src/test/resources/fixture.log`.
- `logflow.sh` / `logflow.bat` forward all arguments.

## v4 — Increment 4: configuration-driven assembly and stage registry
- `logflow --config <file.yaml>` builds and runs a pipeline described in YAML; no recompile for new pipelines.
- `YamlParser`: dependency-free YAML subset reader with line-numbered syntax errors.
- `PipelineSpec` / `ComponentSpec` (description) separated from `PipelineLoader` (implementation).
- `ComponentFactory` / `SourceFactory` / `StageFactory` / `SinkFactory`, discovered through `ServiceLoader` (`META-INF/services`); `Registry` / `StageRegistry` map type names to factories, duplicates are rejected.
- Declarative parameter schemas (`ParamSpec`, `ParamType`, `Params`): missing, unknown, wrongly typed and disallowed values are reported with location and hints ("Did you mean ...", "remove the quotes").
- Fail fast: every configuration problem (also stage-order type mismatches and missing input files) is collected and reported before any data is read; exit code 2. Runtime failures exit with 1.
- New stages: generic `filter` (`Filters.compare`, `Comparison`) and `aggregate` (`AggregateStage`); new sinks: `console`; `file` sink gets `format: text|csv` (`OutputFormat`).
- `Stage.finish(Emitter)` lifecycle hook for stages that emit after the input ends.
- `logflow --list-stages` prints all registered components and their parameters.
- Configs: `error-report`, `api-traffic`, `bandwidth-by-client`, plus `configs/invalid/*` examples; new sample `data/access-clients.log`.
- `./logflow` script (compiles into `build/classes`, copies resources); `logflow.sh` delegates to it; `logflow.bat` updated. `mvn package` builds a runnable jar.
- Loader tests (valid, unknown type, missing / wrong-type / unknown parameter, empty pipeline, stage order, YAML syntax), registry, YAML, aggregate, filter and end-to-end tests: 84 tests.
