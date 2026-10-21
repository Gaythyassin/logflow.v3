# Architecture

## Increment 1

The program is split into components instead of putting file I/O and printing logic inside `Main`.

```text
+---------------------------+       +----------------------------+
| FileLineSource            | ----> | Pipeline -> ConsoleSink    |
| implements Source<String> |       | pushes each emitted String|
+---------------------------+       +----------------------------+
```

Data flow:

```text
data/access-small.log
        |
        v
FileLineSource
        |
        | emits one String per line
        v
Pipeline
        |
        v
ConsoleSink
        |
        v
Console
```

## Responsibilities

### FileLineSource

Owns file-reading logic. It reads the input file and emits one `String` for every line.

### Pipeline

Owns component connection and record flow. It can hold an ordered list of `Stage<?, ?>` objects.
For Increment 1 that list may be empty, so the source output reaches the sink directly.

### ConsoleSink

Owns output behavior and prints each consumed item.

### Main

`Main` contains no business processing. It only:

1. reads the command-line argument,
2. assembles the components,
3. calls `pipeline.run()`.

## Increment 2

A real stage was added in the middle of the existing pipeline, and the data model changed
from `String` to `LogRecord`.

```text
FileLineSource --String--> ParserStage --LogRecord--> ConsoleSink
```

### Separation of concerns

- `FileLineSource`: knows files, knows nothing about log formats.
- `ParserStage`: knows the CLF format, knows nothing about files or printing.
- `ConsoleSink`: knows how to display a `LogRecord`, knows nothing about parsing.
- `Pipeline`: unchanged. It already accepted a list of stages.

### LogRecord and `attributes`

`LogRecord` is an immutable record. `attributes` is an immutable `Map<String,String>` that
is the extension point: later stages add data the parser does not know about by creating a
copy with `withAttribute(...)`, so the record type itself never has to change.

### Testability

`Stage.process` receives an `Emitter`, so a test can pass a `CollectingEmitter` instead of
a real downstream stage. No file or console is involved.

## Increment 3

Focus: modularity, composability, one responsibility per stage.

```text
FileLineSource --String--> ParserStage --LogRecord--> PredicateFilterStage* --LogRecord-->
    [ProjectionStage --ProjectedRecord-->] FileSink | ConsoleSink
```

`*` zero or more filters, in the order given on the command line.

### New stages

| Component | Type | Responsibility |
|---|---|---|
| `PredicateFilterStage<T>` | `Stage<T,T>` | Forwards a record unchanged only if a `Predicate<T>` holds. Counts evaluated / passed. |
| `Filters` | factory | Ready-made `Predicate<LogRecord>`s: `minStatus(400)`, `pathPrefix("/api")`, `between(from, to)` (half-open, offset-aware). |
| `ProjectionStage` | `Stage<LogRecord,ProjectedRecord>` | Reduces a record to a chosen, ordered subset of fields. Unknown / duplicate fields are rejected when the stage is built. |
| `FileSink<T>` | `Sink<T>` | Writes one line per record. Opens the writer in `open()`, flushes and closes it in `close()`. |

**Filtering vs. transforming.** A filter that drops records and a projection that rewrites
them are the same architectural element: both implement `Stage<I,O>` and emit zero or more
outputs per input. The pipeline does not know or care which is which; this is why they can be
reordered freely (as long as the types line up).

**Data minimisation.** `ProjectionStage` is the privacy tool of the pipeline. Fields that are
not selected (e.g. `clientIp`, `userAgent`, `referer`, `user`) no longer exist after the stage:
`ProjectedRecord.get("clientIp")` throws. Projecting as early as the downstream needs allow
keeps personal data out of output files and shrinks them.

### PipelineBuilder

```java
Pipeline p = PipelineBuilder.from(new FileLineSource(in))
        .via(new ParserStage())
        .via(new PredicateFilterStage<>(r -> r.status() >= 400))
        .via(new ProjectionStage("timestamp", "clientIp", "status", "path"))
        .to(new FileSink<>(out));
```

- `PipelineBuilder<T>` carries the current output type `T`; `via(Stage<? super T, R>)` returns
  a `PipelineBuilder<R>`. Putting a `LogRecord` filter **before** the parser is therefore a
  *compile-time* error, not a `ClassCastException` at run time.
- The builder is immutable. A shared prefix can be branched into several pipelines.
- It still produces the same `Pipeline` as before, so in Week 4 the builder calls can be
  driven by a configuration file instead of `Main`.

### Lifecycle hooks are no longer decoration

`Pipeline.run()` now:

1. calls `open()` on every stage, then on the sink;
2. streams the source;
3. in a `finally` block calls `close()` on everything that was opened, collecting any
   secondary failure as a suppressed exception.

`FileSink` uses a `BufferedWriter` (8 KB buffer). If `close()` is never called, small outputs
never reach the disk at all: the file exists but is **empty**.
`FileSinkTest.outputIsLostUntilCloseFlushesTheBuffer` writes ten lines, asserts the file is
still 0 bytes, then calls `close()` and finds all ten. `PipelineBuilderTest` checks that
`close()` also runs when the source throws.

### Command line

`CliOptions` turns `--min-status`, `--path-prefix`, `--from`/`--to`, `--fields` and `--out` into
data; `Main` turns that data into stages with the builder. Filters keep the command-line
order, so the ordering experiment can also be repeated by hand:

```bash
./logflow.sh data/access-small.log --min-status 400 --path-prefix /api   # status first
./logflow.sh data/access-small.log --path-prefix /api --min-status 400   # path first
```

### Ordering experiment

`OrderingExperiment` repeats `data/access-small.log` to 1,000,000 lines, keeps them in memory
(so disk I/O does not hide the differences) and runs three orderings of the same stages.
Each variant has 3 warm-up runs; the table shows the median of 15 timed runs (JDK 21, 2 vCPU
Linux container). The experiment was repeated three times; the numbers below are the
third run, and the other runs agreed to within about 10%.

| Variant | Order | Output records | Output hash | Predicate evaluations | End-to-end (ms) | Filters + projection only (ms) |
|---|---|---:|---:|---:|---:|---:|
| A | parse → status≥400 → path^/api → project | 50,000 | 4636657668748302969 | 1,400,000 | 1,567 | 105 |
| B | parse → path^/api → status≥400 → project | 50,000 | 4636657668748302969 | 1,125,000 | 1,598 | 109 |
| C | parse → project → status≥400 → path^/api | 50,000 | 4636657668748302969 | 1,400,000 | 2,495 | 548 |

"End-to-end" starts from raw lines; "filters + projection only" starts from already-parsed
`LogRecord`s to remove the parsing cost. The order-sensitive output hash is identical in all
variants; `PipelineIntegrationTest.filterOrderDoesNotChangeTheResult` checks the same thing
on the fixture by comparing the two output files line by line.

**Same results.** Both filters are pure predicates on one record, so they commute:
`status≥400 ∧ path^/api` = `path^/api ∧ status≥400`. Order changes how much work is done,
never what comes out.

**Different performance, and why:**

1. **Parsing dominates.** About 93% of the end-to-end time is regex parsing, so on this
   data set the A/B difference disappears in the noise end-to-end.
2. **Selectivity is not the whole story (A vs. B).** In this data set 40% of records have
   status ≥ 400, but only 12.5% have a path starting with `/api`. Putting the more selective
   filter first (B) cuts predicate calls by 20% (1.125 M vs 1.4 M). B was still not faster: its
   first filter is a `String.startsWith` on every record, while A's first filter is a single
   `int` comparison. The cost of a filter is about (cost per call) × (records that reach
   it). The rule is to sort filters by `cost / (1 − pass rate)`, not by selectivity alone.
   With an expensive predicate (a regex, a lookup) the more selective filter first would win
   clearly.
3. **Filter before or after projection is a real trade-off (C).** Projecting first is ~5×
   slower in the filter section (~+60% end-to-end). Every one of the 1 M records is copied into
   a `ProjectedRecord`, not just the 50 k survivors, and the predicates must turn text
   back into numbers (`Integer.parseInt(p.get("status"))`). It also *couples* the stages: the
   projection must keep every field a later filter needs. Here `status` and `path` must stay,
   even if the output should not contain them. Projecting early is only worth it when the
   projection drops a lot of data that is expensive to carry (e.g. before a network hop or
   a sort), or when privacy requires that later stages never see the dropped fields.
4. **Filtering before parsing is not possible.** The filters need a `LogRecord`, but the
   source emits `String`. `PipelineBuilder` rejects that order at compile time. A raw-text
   pre-filter (e.g. `line.contains("/api")`) would be a *different* filter with different
   semantics (it would also match a referer containing `/api`), not the same stage moved
   earlier.

**Conclusion.** Composability gives correctness for free (any valid order gives the same
answer). Performance is a separate decision: filter early, put cheap and selective
predicates first, and project late unless size or privacy says otherwise.

## Increment 4 — configuration-driven assembly and stage registry

Focus: extensibility. The *architecture description* of a pipeline (which components, in
which order, with which parameters) now lives in a declarative YAML file; the *architecture
implementation* (Java classes) is found through a registry. A new pipeline needs a new
config file, not a recompile.

```text
configs/x.yaml
     |  YamlParser            text -> Map / List / scalars (syntax errors with line numbers)
     v
PipelineSpec / ComponentSpec  structure only: name, source, stages[], sink (no classes yet)
     |  PipelineLoader
     |    - Registry lookup     type name -> factory   (unknown type -> error + suggestion)
     |    - Params.validate     declared ParamSpecs    (missing / unknown / wrong type / not allowed)
     |    - type-flow check     outputType -> inputType (e.g. filter before parse)
     |    - factory.create      semantic checks        (e.g. "value must be a number for status")
     v
LoadedPipeline (Pipeline)     nothing has been read or written yet
     |  run()
     v
Source -> Stage* -> Sink      same runtime as v3
```

### Configuration schema

```yaml
pipeline:                     # required, the only top-level key
  name: <string>              # required
  description: <string>       # optional
  source:                     # required
    type: <source type>       # required, see "Registered components"
    params: { ... }           # optional, depends on the type
  stages:                     # required, a non-empty list, executed top to bottom
    - type: <stage type>
      params: { ... }
  sink:                       # required
    type: <sink type>
    params: { ... }
```

Rules:

- Unknown keys are errors at every level (`sorce:` → *Did you mean 'source'?*).
- `params` must be a mapping; parameter names and types are declared by each factory.
- Relative paths (`data/...`, `out/...`) are resolved against the working directory.
- The record type must line up from the source through every stage to the sink.

Supported YAML subset (`YamlParser`, no external library so that plain `javac` still builds
the project): block mappings and lists, one-line flow `{ }` and `[ ]`, single/double quoted
strings, integers, decimals, `true/false`, `null/~`, `#` comments. Anchors, aliases, block
scalars (`|`, `>`) and multi-line flow collections are rejected with a clear error.

### Registered components

`./logflow --list-stages` prints this table from the registry itself.

| Kind | Type | In → Out | Parameters |
|---|---|---|---|
| source | `file` | – → `String` | `path` (string, required) |
| stage | `parse` | `String` → `LogRecord` | `format` (string, `clf`\|`combined`, default `clf`) |
| stage | `filter` | `LogRecord` → `LogRecord` | `field` (one of the record fields), `op` (`== != > >= < <= startsWith endsWith contains`), `value` (string or number), all required |
| stage | `project` | `LogRecord` → `ProjectedRecord` | `fields` (list of strings, required) |
| stage | `aggregate` | `LogRecord` → `ProjectedRecord` | `groupBy` (field, required), `sum` (`status`\|`bytes`, optional), `top` (integer, default 0 = all) |
| sink | `file` | any → file | `path` (string, required), `format` (`text`\|`csv`, default `text`) |
| sink | `console` | any → stdout | `format` (`text`\|`csv`, default `text`) |

`filter` value rules: `status`/`bytes` need a number; `timestamp` needs an ISO-8601 or CLF
time (compared as an instant); other fields are text and allow `==`, `!=`, `startsWith`,
`endsWith`, `contains`.

### Registry: data-driven, no `switch`

```java
public interface StageFactory extends ComponentFactory {
    String type();                 // "filter"
    List<ParamSpec> params();      // declared, validated generically
    Class<?> inputType();          // LogRecord
    Class<?> outputType();         // LogRecord
    Stage<?, ?> create(Params p);  // p is already type-checked
}
```

- `Registry<F>` is a `TreeMap<String, F>` filled by `java.util.ServiceLoader` from
  `src/main/resources/META-INF/services/logflow.config.{Source,Stage,Sink}Factory`.
  `StageRegistry` adds `converters(from, to)`, used for the "add a 'parse' stage" hint.
- Two factories with the same name fail at start-up (`Duplicate stage type 'filter'`).
- **Adding a stage**: write a class implementing `StageFactory`, add its name to
  `META-INF/services/logflow.config.StageFactory` (or put a jar with its own services file on
  the class path). `PipelineLoader`, `Main` and the existing stages do not change.
  `StageRegistryTest.aNewStageIsUsableWithoutChangingTheLoader` does exactly this.
- Parameter schemas are data (`ParamSpec`), so validation, error messages and the
  `--list-stages` documentation all come from one place.

### Configuration-time vs. run-time errors

| | Configuration time | Run time |
|---|---|---|
| When | `PipelineLoader.load`, before `run()` | during `Pipeline.run()` |
| Examples | unknown type, missing / unknown / wrongly typed parameter, empty pipeline, wrong stage order, input file missing, YAML syntax | malformed log line, I/O failure while reading or writing |
| Data touched | none: no input read, no output file created | partial |
| Policy | **fail fast, fail loudly**: collect *every* problem, print them all with their location (`pipeline.stages[1] (filter)`), exit code 2 | malformed lines are skipped and counted (v2); hard failures exit with code 1. Detailed handling is the Week 5 topic. |

Collecting all problems instead of stopping at the first one means a user fixes a broken
config in one round trip. Type-flow errors are suppressed after an unknown stage type so
that one typo does not produce a cascade of follow-up errors.

### Stage lifecycle extended: `finish`

`aggregate` cannot emit anything until it has seen every record. `Stage` gained
`default void finish(Emitter<O> out)`, called by `Pipeline.run()` after the source is
exhausted, stage by stage in order, before `close()`. Whatever stage *i* emits in `finish`
flows through stages *i+1..n* and into the sink, so an aggregate can be followed by more
stages. Existing stages inherit the empty default and are unchanged.

### Three configurations, one executable

| Config | Pipeline | Output |
|---|---|---|
| `configs/error-report.yaml` | parse → filter `status >= 400` → project 4 fields | `out/errors.txt` (80 lines, `key=value`) |
| `configs/api-traffic.yaml` | parse → filter `path startsWith /api` → project 5 fields (no client IP) | `out/api-traffic.csv` (25 rows) |
| `configs/bandwidth-by-client.yaml` | parse → filter `status < 400` → aggregate by `clientIp`, sum `bytes`, top 10 | `out/bandwidth-by-client.csv` (10 rows) |

The bandwidth report reads `data/access-clients.log` (300 synthetic lines, 12 clients),
because in `data/access-small.log` every client IP occurs exactly once and grouping would
be trivial.

### v3 command line

`logflow <log-file> --min-status ...` still works unchanged; `Main` chooses the config mode
only when `--config` or `--list-stages` is given.
