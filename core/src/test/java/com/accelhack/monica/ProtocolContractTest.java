package com.accelhack.monica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.accelhack.monica.spec.JsonSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Contract test against MONICA's public contract bundle.
 *
 * <p>The contract is not owned by this repository. MONICA publishes it at
 * {@code https://spec.monica.accelhack.net/v1/} and {@code scripts/spec-sync.py} vendors a
 * copy into {@code spec/}. This test runs against that copy, so a change to the shared
 * contract fails here instead of failing in ingest. It never touches the network; a clone
 * or a fork's pull request passes with what is in git.
 *
 * <p>Four layers are checked, because the bundle itself says the schema is not the whole
 * contract:
 *
 * <ol>
 *   <li>the schema still says what this SDK relies on ({@code envelope.json},
 *       {@code limits.json}, {@code error.json})
 *   <li>MONICA's own test vectors get the verdict the bundle expects
 *   <li>every envelope this SDK can emit satisfies the schema <em>and</em> the obligations
 *       {@code payload.md} states in prose
 *   <li>the request the SDK actually makes matches {@code transport.json}
 * </ol>
 *
 * <p>Layer 3 matters most. {@code payload.md} is explicit that a payload can satisfy the
 * published schema and still be rejected by ingest; timestamps are the example the bundle
 * ships vectors for. Passing the schema is not evidence that the SDK is correct.
 */
class ProtocolContractTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern RFC_3339 = Pattern.compile(
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$");
  private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
  /** JavaScript's safe integer range, which the JSON Schema vocabulary cannot express. */
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  private static Path root;
  private static Path specDirectory;
  private static String revision;
  private static JsonSchema schema;
  private static JsonSchema errorSchema;
  private static JsonNode limits;
  private static JsonNode transportSpec;

  // --- the vendored bundle -------------------------------------------------

  /**
   * The vendored bundle, verified against {@code spec.lock.json} before anything reads it.
   * A hand-edited spec would turn this whole class into a test of nothing.
   */
  @BeforeAll
  static void vendoredSpec() throws Exception {
    root = repositoryRoot();
    Path lockFile = root.resolve("spec.lock.json");
    assertTrue(Files.isRegularFile(lockFile),
        "spec.lock.json is missing at " + lockFile + "; see README.md");
    JsonNode lock = MAPPER.readTree(Files.readAllBytes(lockFile));
    for (String key : new String[] {"origin", "version", "revision", "files"}) {
      assertTrue(lock.has(key), "spec.lock.json is missing " + key);
    }
    String version = lock.get("version").asText();
    specDirectory = root.resolve("spec").resolve(version);

    TreeMap<String, String> digests = new TreeMap<>(ProtocolContractTest::byteOrder);
    for (Iterator<Map.Entry<String, JsonNode>> files = lock.get("files").fields(); files.hasNext();) {
      Map.Entry<String, JsonNode> entry = files.next();
      Path file = specDirectory.resolve(entry.getKey());
      if (!Files.isRegularFile(file)) {
        fail("spec/" + version + "/" + entry.getKey() + " is not vendored. The contract lives in"
            + " MONICA and is pulled in by a script:\n  python3 scripts/spec-sync.py\n"
            + "This test must not be skipped: without it a change to the shared contract"
            + " would only be caught in ingest.");
      }
      String expected = entry.getValue().asText();
      assertTrue(SHA_256.matcher(expected).matches(), entry.getKey() + ": lock digest is not sha256");
      assertEquals(expected, sha256(Files.readAllBytes(file)),
          "spec/" + version + "/" + entry.getKey() + " does not match spec.lock.json. Run"
              + " `python3 scripts/spec-sync.py` instead of editing the vendored copy.");
      digests.put(entry.getKey(), expected);
    }
    try (Stream<Path> walk = Files.walk(specDirectory)) {
      List<String> undeclared = walk.filter(Files::isRegularFile)
          .map(path -> specDirectory.relativize(path).toString().replace('\\', '/'))
          .filter(path -> !digests.containsKey(path))
          .collect(Collectors.toList());
      assertTrue(undeclared.isEmpty(), "spec/ holds files spec.lock.json does not declare: " + undeclared);
    }

    // `revision` is MONICA's fingerprint for the whole bundle: the sha256 of
    // "<digest>  <path>" lines in byte order of path, joined by "\n" without a
    // trailing newline. Recomputing it from the digests means a lock whose entries
    // were edited to agree with a doctored spec still fails here.
    String recomputed = sha256(digests.entrySet().stream()
        .map(entry -> entry.getValue() + "  " + entry.getKey())
        .collect(Collectors.joining("\n")).getBytes(StandardCharsets.UTF_8));
    assertEquals(lock.get("revision").asText(), recomputed,
        "spec.lock.json: revision does not match its own files; run `python3 scripts/spec-sync.py`");
    revision = recomputed;

    schema = JsonSchema.fromFile(specDirectory.resolve("envelope.json"));
    errorSchema = JsonSchema.fromFile(specDirectory.resolve("error.json"));
    limits = MAPPER.readTree(Files.readAllBytes(specDirectory.resolve("limits.json")));
    transportSpec = MAPPER.readTree(Files.readAllBytes(specDirectory.resolve("transport.json")));
  }

  // --- 1. the spec itself still says what this SDK relies on ---------------

  @Test
  void theSchemaStillSaysWhatThisSdkReliesOn() throws Exception {
    assertEquals("https://json-schema.org/draft/2020-12/schema", schema.pointer("/$schema").asText(),
        "envelope.json must use JSON Schema draft 2020-12");
    assertEquals("https://spec.monica.accelhack.net/v1/envelope.json", schema.pointer("/$id").asText(),
        "envelope.json must still be the v1 bundle this SDK targets");

    // limits.json is the machine-readable copy of the table in ingest.md. If the two
    // ever disagree, the SDK would be sized against the wrong one.
    assertEquals(limits.get("items_per_envelope").asInt(), schema.pointer("/properties/items/maxItems").asInt(),
        "envelope.json and limits.json must agree on the item limit");
    assertEquals(limits.get("frames_per_stacktrace").asInt(),
        schema.pointer("/$defs/exceptionValue/properties/stacktrace/properties/frames/maxItems").asInt(),
        "envelope.json and limits.json must agree on the frame limit");
    assertEquals(MonicaOptions.MAX_ITEMS_PER_ENVELOPE, limits.get("items_per_envelope").asInt(),
        "this SDK caps a batch at the published item limit");
    assertEquals(ThrowableConverter.MAX_FRAMES, limits.get("frames_per_stacktrace").asInt(),
        "this SDK truncates stack traces to the published frame limit");
    assertEquals(MonicaOptions.MAX_ENVIRONMENT_LENGTH,
        schema.pointer("/$defs/errorItem/properties/environment/maxLength").asInt(),
        "this SDK validates environment against the schema's bound");
    assertTrue(MonicaClient.MAX_SAFE_ENVELOPE_JSON_BYTES <= limits.get("envelope_gzip_bytes").asLong(),
        "the JSON preflight must stay under the gzip limit, which gzip can only shrink");
    assertTrue(MonicaClient.MAX_SAFE_ENVELOPE_JSON_BYTES <= limits.get("envelope_decompressed_bytes").asLong());

    assertTrue(enumValues(schema.pointer("/$defs/errorItem/properties/platform")).contains("java"),
        "envelope.json must accept the java platform");
    assertTrue(enumValues(schema.pointer("/$defs/mechanism/properties/type")).contains("generic"),
        "envelope.json must accept the generic mechanism this SDK emits");
    List<String> levels = enumValues(schema.pointer("/$defs/errorItem/properties/level"));
    assertEquals(Arrays.asList("fatal", "error", "warning", "info", "debug"), levels,
        "this SDK's level vocabulary is a copy of the schema's; both must move together");

    // payload.md says the schema expresses the *shape* of a timestamp, which is what lets
    // `space-separated-timestamp` be a vector the schema rejects. The prose keeps only "the
    // date must exist in the calendar". If the pattern ever goes away, this fails rather
    // than leaving isRfc3339 below as the sole guard.
    for (String pointer : new String[] {
        "/properties/sent_at/pattern",
        "/$defs/errorItem/properties/timestamp/pattern",
        "/$defs/breadcrumb/properties/timestamp/pattern"}) {
      JsonNode pattern = schema.pointer(pointer);
      assertTrue(pattern.isTextual() && !pattern.asText().isEmpty(),
          "envelope.json must constrain the timestamp shape at " + pointer);
    }

    List<String> definitions = schema.definitionNames();
    String serialized = Files.readString(specDirectory.resolve("envelope.json"));
    java.util.regex.Matcher references = Pattern.compile("#/\\$defs/([A-Za-z0-9_-]+)").matcher(serialized);
    while (references.find()) {
      assertTrue(definitions.contains(references.group(1)), "unknown schema reference: #/$defs/" + references.group(1));
    }
  }

  @Test
  void theErrorBodyShapeIsStillUsable() throws Exception {
    // error.json is the shape of a rejection. This SDK branches on the HTTP status only and
    // never reads the body, so what is pinned here is the schema being usable and the two
    // fields a future consumer will need, not conformance the SDK does not yet have.
    assertEquals("https://spec.monica.accelhack.net/v1/error.json", errorSchema.pointer("/$id").asText(),
        "error.json must still be the v1 error schema");
    assertEquals(Arrays.asList("path", "message"), textValues(errorSchema.pointer("/$defs/validationIssue/required")),
        "a 422 issue must keep carrying both a path and a message");
    JsonNode documented = MAPPER.readTree("{\"error\":{\"code\":\"invalid_envelope\","
        + "\"message\":\"The envelope does not match the MONICA schema\",\"issues\":[{"
        + "\"path\":\"$.items[0].exception.values[0].mechanism.type\",\"message\":\"Invalid type\"}]}}");
    assertEquals(List.of(), errorSchema.validate(documented),
        "the rejection shape documented in ingest.md does not satisfy error.json");
  }

  // --- 2. MONICA's own test vectors get the verdict the bundle expects -----

  @Test
  void theVectorsGetTheVerdictTheBundleExpects() throws Exception {
    List<Path> vectorFiles;
    try (Stream<Path> files = Files.list(specDirectory.resolve("vectors").resolve("envelope"))) {
      vectorFiles = files.filter(path -> path.toString().endsWith(".json")).sorted().collect(Collectors.toList());
    }
    assertFalse(vectorFiles.isEmpty(), "the bundle must ship envelope test vectors");

    // A vector says both whether MONICA accepts the envelope (`valid`) and whether the
    // published schema is able to see the problem (`schema_rejects`). Only the second is
    // this validator's business; the first is checked against the SDK's own output below.
    int semanticOnly = 0;
    for (Path vectorFile : vectorFiles) {
      String name = vectorFile.getFileName().toString().replace(".json", "");
      JsonNode vector = MAPPER.readTree(Files.readAllBytes(vectorFile));
      assertTrue(vector.has("valid") && vector.has("envelope"), name + ": a vector needs `valid` and `envelope`");
      boolean valid = vector.get("valid").asBoolean();
      // Absent means "the schema agrees with `valid`", which is how every accepted vector is written.
      boolean schemaRejects = vector.has("schema_rejects") ? vector.get("schema_rejects").asBoolean() : !valid;
      List<String> errors = schema.validate(vector.get("envelope"));
      String description = vector.path("description").asText();
      if (schemaRejects) {
        assertFalse(errors.isEmpty(), "vector " + name + " should be rejected by envelope.json (" + description + ")");
        continue;
      }
      assertTrue(errors.isEmpty(),
          "vector " + name + " should satisfy envelope.json (" + description + "):\n  - " + String.join("\n  - ", errors));
      // Passing the schema is not the same as being accepted. Counting these means the
      // prose checks below cannot become the only thing between us and a 422 unnoticed.
      if (!valid) semanticOnly++;
    }
    assertTrue(semanticOnly > 0, "the bundle should still carry vectors that the schema cannot reject;"
        + " if it no longer does, the prose obligations below may have moved into the schema");
  }

  // --- 3. every envelope this SDK can emit satisfies the schema ------------

  @Test
  void everyEnvelopeThisSdkEmitsSatisfiesTheSchema() throws Exception {
    for (Map.Entry<String, JsonNode> envelope : sdkEnvelopes().entrySet()) {
      assertValid(envelope.getValue(), envelope.getKey());
    }
  }

  @Test
  void aBatchLargerThanThePublishedLimitIsSplit() throws Exception {
    int limit = limits.get("items_per_envelope").asInt();
    // The item limit belongs to MONICA, not to this SDK's defaults. Asking for a larger
    // batch must still split, or ingest answers 422 on an envelope the application had
    // no way to see was too big.
    List<JsonNode> envelopes = capture(builder -> builder.maxQueueSize(limit * 4).batchSize(limit * 4),
        client -> {
          for (int index = 0; index < limit + 50; index++) client.captureMessage("split " + index);
        });
    assertTrue(envelopes.size() >= 2, "an over-sized batch should be split across envelopes");
    long items = 0;
    for (JsonNode envelope : envelopes) {
      assertTrue(envelope.get("items").size() <= limit, "an envelope carries more than the published item limit");
      assertValid(envelope, "a split envelope");
      items += envelope.get("items").size();
    }
    assertEquals(limit + 50, items, "splitting must not lose or duplicate items");
  }

  @Test
  void aFullQueueReportsWhatItDropped() throws Exception {
    int limit = limits.get("items_per_envelope").asInt();
    // A full batch with dropped events: the envelope limit and `discarded` are part of
    // the contract, not an implementation detail. The sender thread may drain while the
    // loop is still adding, so the split between envelopes is not pinned; the sum is.
    List<JsonNode> envelopes = capture(builder -> builder.maxQueueSize(limit).batchSize(limit),
        client -> {
          for (int index = 0; index < limit + 5; index++) client.captureMessage("overflow " + index);
        });
    long items = 0;
    long discarded = 0;
    for (JsonNode envelope : envelopes) {
      assertTrue(envelope.get("items").size() <= limit);
      assertValid(envelope, "a full envelope reporting discarded events");
      items += envelope.get("items").size();
      discarded += envelope.get("discarded").asLong();
    }
    assertEquals(limit + 5, items + discarded, "every captured event is either sent or counted as discarded");
  }

  // --- the payload obligations the schema cannot express (payload.md) ------

  @Test
  void timestampsAndCountersStayInsideWhatIngestAccepts() throws Exception {
    for (Map.Entry<String, JsonNode> entry : sdkEnvelopes().entrySet()) {
      String label = entry.getKey();
      JsonNode envelope = entry.getValue();
      assertRfc3339(envelope.get("sent_at").asText(), label + ": sent_at");
      assertFalse(envelope.at("/sdk/name").asText().isEmpty(), label + ": sdk.name must not be empty");
      assertFalse(envelope.at("/sdk/version").asText().isEmpty(), label + ": sdk.version must not be empty");
      long discarded = envelope.get("discarded").asLong();
      assertTrue(discarded >= 0 && discarded <= MAX_SAFE_INTEGER,
          label + ": discarded must stay inside the safe integer range");
      for (JsonNode item : envelope.get("items")) {
        assertRfc3339(item.get("timestamp").asText(), label + ": timestamp");
        for (JsonNode breadcrumb : item.path("breadcrumbs")) {
          if (breadcrumb.has("timestamp")) assertRfc3339(breadcrumb.get("timestamp").asText(), label + ": breadcrumb");
        }
      }
      for (JsonNode frame : framesIn(envelope)) {
        // An empty filename passes the schema only because the SDK substitutes the class
        // name; payload.md forbids the empty one outright.
        assertFalse(frame.get("filename").asText().isEmpty(), label + ": a stack frame has an empty filename");
      }
    }
  }

  @Test
  void sdkNameIsTheMavenCoordinateAndTheVersionIsTheReactorsVersion() throws Exception {
    // ingest.md: sdk.name is the package name in the distribution registry, so a Maven
    // artifact reports its groupId:artifactId. The version has to be the one the reactor
    // publishes, or the take-up numbers MONICA aggregates name a release that never shipped.
    assertEquals("com.accelhack.monica:monica-core", MonicaOptions.DEFAULT_SDK_NAME);
    String pom = Files.readString(root.resolve("pom.xml"));
    java.util.regex.Matcher version = Pattern.compile("<version>([^<]+)</version>").matcher(pom);
    assertTrue(version.find(), "pom.xml declares a version");
    assertEquals(MonicaOptions.DEFAULT_SDK_VERSION, version.group(1).replace("-SNAPSHOT", ""),
        "MonicaOptions.DEFAULT_SDK_VERSION must follow the reactor version in pom.xml");
  }

  @Test
  void theCauseChainRunsOutermostFirst() throws Exception {
    JsonNode chained = sdkEnvelopes().get("a captured exception with full context");
    JsonNode values = chained.at("/items/0/exception/values");
    assertEquals(2, values.size(), "the cause chain should carry both throwables");
    assertEquals("java.lang.IllegalStateException", values.get(0).get("type").asText(),
        "exception.values should start at the outermost throwable");
    assertEquals("java.io.IOException", values.get(1).get("type").asText(),
        "exception.values should follow getCause() inwards");
  }

  @Test
  void framesRunFromTheOldestCallerToTheThrowSite() {
    // This is the direction every MONICA SDK uses, and the one grouping assumes. Two
    // levels of calls pin it: the JVM's own trace is newest-first, so a converter that
    // forgot to reverse would put the throw site first.
    Throwable throwable = callSite();
    Map<String, Object> converted = ThrowableConverter.convert(throwable, List.of("com.accelhack.monica"), true);
    JsonNode frames = MAPPER.valueToTree(converted).at("/values/0/stacktrace/frames");
    assertTrue(frames.size() >= 3, "the trace should hold the test runner, both calls and the throw site");
    JsonNode last = frames.get(frames.size() - 1);
    JsonNode beforeLast = frames.get(frames.size() - 2);
    assertEquals(ProtocolContractTest.class.getName() + ".throwSite", last.get("function").asText(),
        "the last frame should be where the exception was thrown");
    assertEquals(throwable.getStackTrace()[0].getLineNumber(), last.get("lineno").asInt());
    assertEquals(ProtocolContractTest.class.getName() + ".callSite", beforeLast.get("function").asText(),
        "frames should run from the caller towards the throw site");
    assertTrue(last.get("in_app").asBoolean(), "frames under an inAppPackage must be marked in_app");
    assertEquals("com/accelhack/monica/ProtocolContractTest.java", last.get("filename").asText(),
        "payload.md: Java joins the declaring package and StackTraceElement.getFileName()");
  }

  @Test
  void deepTracesAreTruncatedToThePublishedFrameLimit() {
    int limit = limits.get("frames_per_stacktrace").asInt();
    // Deep recursion must be truncated rather than sent whole: ingest rejects an envelope
    // whose stacktrace exceeds the published frame limit.
    Throwable deep = recurse(limit + 50);
    JsonNode frames = MAPPER.valueToTree(ThrowableConverter.convert(deep, List.of(), true))
        .at("/values/0/stacktrace/frames");
    assertEquals(limit, frames.size(), "a stacktrace deeper than the limit should be truncated to " + limit);
    assertEquals(ProtocolContractTest.class.getName() + ".recurse", frames.get(limit - 1).get("function").asText(),
        "truncation keeps the throw site and drops the oldest callers");
  }

  @Test
  void inAppIsTheSdksJudgementNotACopyOfThePath() {
    // Marking everything false groups every error at the framework; marking everything
    // true groups it at whatever library happened to be on top.
    StackTraceElement application = new StackTraceElement("com.example.app.OrderService", "place", "OrderService.java", 42);
    StackTraceElement library = new StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 427);
    StackTraceElement nativeMethod = new StackTraceElement("java.lang.Thread", "sleep", "Thread.java", -2);
    Throwable throwable = new RuntimeException("in app");
    throwable.setStackTrace(new StackTraceElement[] {application, library, nativeMethod});
    JsonNode frames = MAPPER.valueToTree(ThrowableConverter.convert(throwable, List.of("com.example.app"), true))
        .at("/values/0/stacktrace/frames");
    // Reversed: oldest caller first, so the native method is frames[0].
    assertFalse(frames.get(0).has("lineno"), "payload.md: a native method has no line number, so lineno is omitted");
    assertFalse(frames.get(0).get("in_app").asBoolean(), "JDK frames must not be marked in_app");
    assertFalse(frames.get(1).get("in_app").asBoolean(), "library frames must not be marked in_app");
    assertTrue(frames.get(2).get("in_app").asBoolean(), "frames under inAppPackage must be marked in_app");
    assertEquals("com/example/app/OrderService.java", frames.get(2).get("filename").asText());
  }

  @Test
  void innerClassesAndLambdasStayInFunctionNotFilename() {
    StackTraceElement element = new StackTraceElement("com.example.app.BotService$Worker", "lambda$run$0",
        "BotService.java", 42);
    assertEquals("com/example/app/BotService.java", ThrowableConverter.sourcePath(element),
        "payload.md: `$` and lambda$ stay in function; filename is not rewritten");
  }

  @Test
  void fingerprintReachesTheWireUnchanged() throws Exception {
    // fingerprint is the user's grouping key: it goes out exactly as given, never trimmed,
    // normalised or joined. beforeSend is where an application sets it.
    List<String> fingerprint = Arrays.asList(" things ", "POST|/v1/things", "");
    List<JsonNode> envelopes = capture(
        builder -> builder.beforeSend((event, hint) -> event.put("fingerprint", new ArrayList<>(fingerprint))),
        client -> client.captureMessage("fingerprinted"));
    JsonNode item = envelopes.get(0).at("/items/0");
    assertEquals(MAPPER.valueToTree(fingerprint), item.get("fingerprint"),
        "fingerprint must reach the wire unchanged: no trimming, normalising or joining");
    assertValid(envelopes.get(0), "an envelope with a user fingerprint");
  }

  // --- 4. the request the SDK actually makes (transport.json) --------------

  @Test
  void transportJsonDeclaresOnlyWhatThisSdkHasConsidered() {
    // transport.json is the machine-readable copy of the tables in ingest.md, so everything
    // in this section is driven by the spec rather than by constants copied out of its
    // prose. A change on MONICA's side arrives here as a failure. Pinning the vocabulary
    // turns "MONICA grew an obligation the Java SDK ignores" into a failing test.
    List<String> declared = new ArrayList<>();
    transportSpec.fieldNames().forEachRemaining(declared::add);
    Collections.sort(declared);
    assertEquals(Arrays.asList("auth", "dsn", "endpoint", "retry", "status"), declared,
        "transport.json declares sections this SDK has not considered (see README for what is unimplemented)");

    List<String> statuses = new ArrayList<>();
    transportSpec.get("status").fieldNames().forEachRemaining(statuses::add);
    Collections.sort(statuses);
    assertEquals(Arrays.asList("202", "400", "401", "413", "422", "429", "5xx"), statuses,
        "transport.json changed the status vocabulary");
    assertEquals("accept", transportSpec.at("/status/202").asText());
    assertEquals("drop", transportSpec.at("/status/400").asText());
    assertEquals("drop", transportSpec.at("/status/422").asText());
    assertEquals("wait_retry_after", transportSpec.at("/status/429").asText());
    assertEquals("backoff", transportSpec.at("/status/5xx").asText());
    // Known gaps, kept visible: 401 is dropped but does not stop later sends, and 413 is
    // dropped instead of split (the JSON preflight in MonicaClient keeps envelopes under
    // the limit so ingest should not answer 413 in the first place).
    assertEquals("drop_and_stop", transportSpec.at("/status/401").asText());
    assertEquals("split_and_retry", transportSpec.at("/status/413").asText());

    Map<String, JsonNode> auth = authSchemes();
    assertTrue(auth.containsKey("secret") && auth.containsKey("public"),
        "transport.json should describe both a secret and a public key scheme");
    assertEquals(JdkHttpTransport.SECRET_KEY_PREFIX, auth.get("secret").get("key_prefix").asText());
    assertEquals(JdkHttpTransport.INGEST_PATH, transportSpec.at("/endpoint/path").asText());
  }

  @Test
  void theRetryPolicyMatchesTransportJson() {
    JsonNode retry = transportSpec.get("retry");
    assertEquals(Arrays.asList("429", "5xx"), textValues(retry.get("retryable_statuses")));
    assertTrue(retry.get("retry_on_network_error").asBoolean());
    assertTrue(retry.at("/retry_after/integer_seconds_only").asBoolean(),
        "this SDK parses Retry-After as integer seconds only and falls back to backoff otherwise");
    assertEquals(retry.at("/retry_after/max_seconds").asLong(), JdkHttpTransport.RETRY_AFTER_MAX_SECONDS);
    assertEquals(retry.at("/backoff/base_ms").asLong(), JdkHttpTransport.BACKOFF_BASE_MILLIS);
    assertEquals(retry.at("/backoff/factor").asInt(), JdkHttpTransport.BACKOFF_FACTOR);
    assertEquals(retry.at("/backoff/max_ms").asLong(), JdkHttpTransport.BACKOFF_MAX_MILLIS);
    assertEquals(retry.at("/backoff/jitter_min").asDouble(), JdkHttpTransport.BACKOFF_JITTER_MIN);
    assertEquals(retry.at("/backoff/jitter_max").asDouble(), JdkHttpTransport.BACKOFF_JITTER_MAX);

    // The constants are only half of it: the delay actually drawn has to stay inside the
    // window transport.json describes, for every attempt up to and past the ceiling.
    for (int attempt = 0; attempt < 8; attempt++) {
      long ceiling = Math.min((long) (retry.at("/backoff/base_ms").asLong()
          * Math.pow(retry.at("/backoff/factor").asInt(), attempt)), retry.at("/backoff/max_ms").asLong());
      long floor = (long) (ceiling * retry.at("/backoff/jitter_min").asDouble());
      long cap = (long) (ceiling * retry.at("/backoff/jitter_max").asDouble());
      for (int sample = 0; sample < 50; sample++) {
        long millis = JdkHttpTransport.backoff(attempt).toMillis();
        assertTrue(millis >= floor && millis <= cap,
            "attempt " + attempt + ": backoff " + millis + "ms is outside [" + floor + ", " + cap + "]");
      }
    }
  }

  @Test
  void theDsnIsReducedToTheIngestEndpoint() {
    // The DSN path is not the ingest path. Sending to the DSN's trailing digits would post
    // to a project id that MONICA does not route on. https everywhere, except the hosts
    // transport.json names.
    for (JsonNode host : transportSpec.at("/dsn/insecure_hosts")) {
      new JdkHttpTransport("http://msk_secret@" + host.asText() + "/1", 0, Duration.ofSeconds(1));
    }
    assertThrows(IllegalArgumentException.class,
        () -> new JdkHttpTransport("http://msk_secret@ingest.example.test/1", 0, Duration.ofSeconds(1)),
        "plain http must be rejected for hosts outside dsn.insecure_hosts");
    // A server SDK never issues the public-key header, so it does not accept the key either.
    String publicKey = authSchemes().get("public").get("key_prefix").asText() + "contract";
    assertThrows(IllegalArgumentException.class,
        () -> new JdkHttpTransport("https://" + publicKey + "@ingest.example.test/1", 0, Duration.ofSeconds(1)),
        "a server SDK must insist on the secret key scheme");
    assertThrows(IllegalArgumentException.class,
        () -> new JdkHttpTransport("https://ingest.example.test/1", 0, Duration.ofSeconds(1)),
        "a DSN without a key must be rejected");
  }

  @Test
  void theRequestOnTheWireMatchesTransportJson() throws Exception {
    Map<String, JsonNode> auth = authSchemes();
    JsonNode endpoint = transportSpec.get("endpoint");
    String secretKey = auth.get("secret").get("key_prefix").asText() + "contract";
    try (Ingest ingest = Ingest.start(202)) {
      JdkHttpTransport transport = new JdkHttpTransport(
          "http://" + secretKey + "@127.0.0.1:" + ingest.port() + "/1?q=1#f", 0, Duration.ofSeconds(5));
      assertTrue(transport.send(envelopeFor(client -> client.captureException(new IllegalStateException("over the wire")))),
          "the transport should accept a 202 response");
      Ingest.Request request = ingest.requests().get(0);

      assertEquals(endpoint.get("method").asText(), request.method, "the envelope must be sent with the published method");
      assertEquals(endpoint.get("path").asText(), request.path,
          "the DSN path, query and fragment must be dropped in favour of " + endpoint.get("path").asText());
      assertEquals(endpoint.get("content_type").asText(), request.header("Content-Type"));
      assertEquals(endpoint.get("content_encoding").asText(), request.header("Content-Encoding"));

      // A secret key authenticates with the header transport.json gives for its kind,
      // and must not use the public-key header a server SDK never issues.
      assertEquals(auth.get("secret").get("value").asText().replace("<key>", secretKey),
          request.header(auth.get("secret").get("header").asText()));
      assertEquals(null, request.header(auth.get("public").get("header").asText()),
          "a server SDK must not use the public-key header");

      byte[] body = gunzip(request.body);
      assertTrue(request.body.length <= limits.get("envelope_gzip_bytes").asLong(),
          "the gzipped envelope must stay under the published limit");
      assertTrue(body.length <= limits.get("envelope_decompressed_bytes").asLong(),
          "the decompressed envelope must stay under the published limit");
      // One request carries one envelope: the body is a single JSON value.
      JsonNode decoded = MAPPER.readTree(body);
      assertTrue(decoded.isObject(), "the request body should decode to exactly one envelope");
      assertValid(decoded, "the gzipped request body");
    }
  }

  @Test
  void statusesAreHandledTheWayTransportJsonSays() throws Exception {
    MonicaEnvelope envelope = envelopeFor(client -> client.captureMessage("status"));
    // drop: no retry, so exactly one request reaches the server
    for (int status : new int[] {400, 401, 413, 422}) {
      try (Ingest ingest = Ingest.start(status)) {
        assertFalse(transportFor(ingest, 3).send(envelope), status + " must not be reported as accepted");
        assertEquals(1, ingest.requests().size(), status + " must not be retried");
      }
    }
    // wait_retry_after / backoff: retried, and the retry that meets a 202 succeeds
    try (Ingest ingest = Ingest.start(429, 202)) {
      ingest.retryAfterSeconds = 0;
      assertTrue(transportFor(ingest, 1).send(envelope), "a 429 followed by a 202 is a success");
      assertEquals(2, ingest.requests().size());
    }
    try (Ingest ingest = Ingest.start(503, 202)) {
      assertTrue(transportFor(ingest, 1).send(envelope), "a 5xx followed by a 202 is a success");
      assertEquals(2, ingest.requests().size());
    }
    // exhausted retries drop the envelope instead of queueing it forever
    try (Ingest ingest = Ingest.start(429)) {
      ingest.retryAfterSeconds = 0;
      assertFalse(transportFor(ingest, 2).send(envelope));
      assertEquals(3, ingest.requests().size(), "maxRetries bounds the number of attempts");
    }
  }

  // --- 5. the validator has to be able to say no ---------------------------

  @Test
  void theValidatorRejectsWhatItShould() throws Exception {
    Map<String, JsonNode> envelopes = sdkEnvelopes();
    JsonNode baseline = envelopes.get("messages at every level and an unhandled exception");
    JsonNode chained = envelopes.get("a captured exception with full context");
    assertEquals(List.of(), schema.validate(baseline), "the baseline envelope should be valid");

    assertRejected(mutate(baseline, node -> ((ObjectNode) node.at("/items/0")).remove("platform")),
        "an error item without a platform");
    assertRejected(mutate(baseline, node -> ((ObjectNode) node.at("/items/0")).put("level", "trace")),
        "an unknown level");
    assertRejected(mutate(baseline, node -> ((ObjectNode) node.at("/items/0")).put("event_id", "not-a-uuid")),
        "a malformed event_id");
    assertRejected(mutate(baseline, node -> ((ObjectNode) node.at("/items/0")).put("timestamp", "2026-08-30 00:00:00Z")),
        "a timestamp with a space instead of T");
    assertRejected(mutate(baseline, node -> ((ObjectNode) node).put("discarded", -1)), "a negative discarded count");
    assertRejected(mutate(baseline, node -> {
      ArrayNode items = (ArrayNode) node.get("items");
      JsonNode first = items.get(0);
      while (items.size() <= limits.get("items_per_envelope").asInt()) items.add(first.deepCopy());
    }), "an envelope over the item limit");
    assertRejected(mutate(baseline, node -> ((ObjectNode) node).remove("sdk")), "an envelope without sdk metadata");
    assertRejected(mutate(chained, node -> ((ObjectNode) node.at("/items/0/exception/values/0/stacktrace/frames/0")).remove("in_app")),
        "a stack frame without in_app");
    assertRejected(mutate(chained, node -> ((ObjectNode) node.at("/items/0/exception/values/0/mechanism")).put("type", "servlet")),
        "an unknown mechanism type");
    assertRejected(mutate(chained, node -> ((ObjectNode) node.at("/items/0/tags")).put("count", 3)),
        "a non-string tag value");
    assertRejected(mutate(chained, node -> ((ObjectNode) node.at("/items/0")).putArray("fingerprint")),
        "an empty fingerprint");

    // Forward compatibility is also part of the contract: a newer SDK's item type must
    // not be rejected by an older backend.
    JsonNode unknownItem = mutate(baseline, node -> {
      ArrayNode items = ((ObjectNode) node).putArray("items");
      items.addObject().put("type", "transaction").put("name", "GET /v1/things");
    });
    assertEquals(List.of(), schema.validate(unknownItem), "unknown item types must stay acceptable for forward compatibility");
  }

  // --- helpers -------------------------------------------------------------

  private static Map<String, JsonNode> sdkEnvelopes() throws Exception {
    Map<String, JsonNode> envelopes = new LinkedHashMap<>();

    List<JsonNode> chained = capture(builder -> builder.release("1.2.3").serverName("contract-host"), client -> {
      client.globalScope()
          .setTag("service", "api")
          .setContext("runtime", Map.of("name", "java", "version", System.getProperty("java.version")))
          .setUser(Map.of("id", "u1", "email", "user@example.test", "ip", "203.0.113.1"))
          .addBreadcrumb("http", "POST /v1/things");
      client.captureException(new IllegalStateException("outer", new IOException("inner")),
          CaptureContext.create().tag("route", "/v1/things").context("request", Map.of("method", "POST")));
    });
    assertEquals(1, chained.size());
    envelopes.put("a captured exception with full context", chained.get(0));

    // fatal is sent at once, so this may arrive as more than one envelope; each is checked.
    List<JsonNode> mixed = capture(builder -> { }, client -> {
      for (String level : new String[] {"debug", "info", "warning", "error", "fatal"}) {
        client.captureMessage("message at " + level, level);
      }
      client.captureException(new RuntimeException("unhandled"),
          CaptureContext.create().level("fatal").handled(false));
    });
    int mixedItems = 0;
    for (int index = 0; index < mixed.size(); index++) {
      mixedItems += mixed.get(index).get("items").size();
      envelopes.put(index == 0 ? "messages at every level and an unhandled exception"
          : "messages at every level and an unhandled exception (envelope " + index + ")", mixed.get(index));
    }
    assertEquals(6, mixedItems, "every level and the unhandled exception should be in the envelopes");
    for (JsonNode envelope : mixed) {
      for (JsonNode item : envelope.get("items")) {
        if (item.has("exception")) {
          assertFalse(item.at("/exception/values/0/mechanism/handled").asBoolean(),
              "an unhandled exception must say so in mechanism.handled");
        }
      }
    }

    // Non-ASCII and control characters survive JSON encoding intact.
    List<JsonNode> unicode = capture(builder -> builder.environment("本番"),
        client -> client.captureException(new RuntimeException("結合できません\tid=1")));
    envelopes.put("a non-ASCII envelope", unicode.get(0));
    return envelopes;
  }

  private static List<JsonNode> capture(Consumer<MonicaClient.Builder> configure, Consumer<MonicaClient> use) {
    List<MonicaEnvelope> sent = Collections.synchronizedList(new ArrayList<>());
    MonicaClient.Builder builder = MonicaClient.builder()
        .environment("contract")
        .inAppPackage("com.accelhack.monica")
        .transport(envelope -> { sent.add(envelope); return true; });
    configure.accept(builder);
    try (MonicaClient client = builder.build()) {
      use.accept(client);
      assertTrue(client.flush(Duration.ofSeconds(5)), "the capturing transport should accept every envelope");
    }
    List<JsonNode> wire = new ArrayList<>();
    for (MonicaEnvelope envelope : sent) wire.add(MAPPER.valueToTree(envelope));
    return wire;
  }

  /** One envelope, as the client would hand it to a transport. */
  private static MonicaEnvelope envelopeFor(Consumer<MonicaClient> use) {
    List<MonicaEnvelope> sent = Collections.synchronizedList(new ArrayList<>());
    try (MonicaClient client = MonicaClient.builder()
        .environment("contract")
        .inAppPackage("com.accelhack.monica")
        .transport(envelope -> { sent.add(envelope); return true; })
        .build()) {
      use.accept(client);
      assertTrue(client.flush(Duration.ofSeconds(5)));
    }
    assertEquals(1, sent.size());
    return sent.get(0);
  }

  private static JdkHttpTransport transportFor(Ingest ingest, int maxRetries) {
    return new JdkHttpTransport("http://msk_contract@127.0.0.1:" + ingest.port() + "/1", maxRetries,
        Duration.ofSeconds(5));
  }

  private static Map<String, JsonNode> authSchemes() {
    Map<String, JsonNode> schemes = new HashMap<>();
    for (JsonNode scheme : transportSpec.get("auth")) schemes.put(scheme.get("kind").asText(), scheme);
    return schemes;
  }

  private static void assertValid(JsonNode envelope, String label) {
    List<String> errors = schema.validate(envelope);
    assertTrue(errors.isEmpty(), label + " does not satisfy envelope.json:\n  - " + String.join("\n  - ", errors));
  }

  private static void assertRejected(JsonNode envelope, String label) {
    assertFalse(schema.validate(envelope).isEmpty(), "envelope.json should reject " + label);
  }

  private static JsonNode mutate(JsonNode envelope, Consumer<JsonNode> change) {
    JsonNode copy = envelope.deepCopy();
    change.accept(copy);
    return copy;
  }

  /**
   * RFC 3339 date-time with a timezone, on a date the calendar actually has. The schema's
   * pattern covers the shape; the calendar rule is prose in payload.md and MONICA answers
   * 422 when it is broken, which is why {@code impossible-calendar-date} ships as a vector
   * with {@code schema_rejects: false}.
   */
  private static void assertRfc3339(String value, String label) {
    assertTrue(RFC_3339.matcher(value).matches(), label + " \"" + value + "\" is not an RFC 3339 date-time with a timezone");
    try {
      OffsetDateTime.parse(value);
    } catch (DateTimeParseException impossible) {
      fail(label + " \"" + value + "\" is not a date the calendar has: " + impossible.getMessage());
    }
  }

  private static List<JsonNode> framesIn(JsonNode envelope) {
    List<JsonNode> frames = new ArrayList<>();
    for (JsonNode item : envelope.get("items")) {
      for (JsonNode value : item.path("exception").path("values")) {
        for (JsonNode frame : value.path("stacktrace").path("frames")) frames.add(frame);
      }
    }
    return frames;
  }

  private static List<String> enumValues(JsonNode property) {
    assertTrue(property.has("enum"), property + " should be an enum");
    return textValues(property.get("enum"));
  }

  private static List<String> textValues(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : array) values.add(value.asText());
    return values;
  }

  private static Throwable throwSite() {
    return new IllegalStateException("locate the throw site");
  }

  private static Throwable callSite() {
    return throwSite();
  }

  private static Throwable recurse(int depth) {
    return depth > 0 ? recurse(depth - 1) : new RuntimeException("deep");
  }

  private static String sha256(byte[] bytes) throws Exception {
    StringBuilder hex = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  private static int byteOrder(String a, String b) {
    return Arrays.compare(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] gunzip(byte[] body) throws IOException {
    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
      return in.readAllBytes();
    }
  }

  /** surefire runs with the module directory as user.dir; the lock lives at the reactor root. */
  private static Path repositoryRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("spec.lock.json"))) return current;
      current = current.getParent();
    }
    throw new IllegalStateException("spec.lock.json not found above " + System.getProperty("user.dir"));
  }

  /** A stand-in ingest endpoint that answers a scripted sequence of statuses. */
  private static final class Ingest implements AutoCloseable {
    static final class Request {
      String method;
      String path;
      Map<String, String> headers = new HashMap<>();
      byte[] body;

      String header(String name) {
        return headers.get(name.toLowerCase());
      }
    }

    private final HttpServer server;
    private final Deque<Integer> statuses;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    volatile int retryAfterSeconds = -1;

    private Ingest(HttpServer server, int... statuses) {
      this.server = server;
      this.statuses = new ArrayDeque<>();
      for (int status : statuses) this.statuses.add(status);
    }

    static Ingest start(int... statuses) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      Ingest ingest = new Ingest(server, statuses);
      server.createContext("/", ingest::handle);
      server.start();
      return ingest;
    }

    private void handle(HttpExchange exchange) throws IOException {
      Request request = new Request();
      request.method = exchange.getRequestMethod();
      request.path = exchange.getRequestURI().getPath();
      exchange.getRequestHeaders().forEach((name, values) ->
          request.headers.put(name.toLowerCase(), String.join(",", values)));
      request.body = exchange.getRequestBody().readAllBytes();
      requests.add(request);
      int status;
      synchronized (statuses) {
        // The last scripted status repeats, so a transport that retries past the script
        // keeps meeting the same answer.
        status = statuses.size() > 1 ? statuses.poll() : statuses.peek();
      }
      if (retryAfterSeconds >= 0) exchange.getResponseHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    }

    int port() {
      return server.getAddress().getPort();
    }

    List<Request> requests() {
      return requests;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
