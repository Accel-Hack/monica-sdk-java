package com.accelhack.monica.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Checks what the Android integration actually puts on the wire against the published
 * contract bundle's {@code envelope.json}, which is generated from the validator ingest
 * actually runs. Every bound asserted here is read from the schema, so a
 * tightened schema fails this test instead of silently drifting from it.
 *
 * <p>Once this SDK moves to its own repository it reads the vendored copy of the bundle
 * instead of this path.
 */
@Isolated
class EnvelopeContractTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonNode schema = readSchema();

  private JsonNode readSchema() {
    try {
      Path path = Path
          .of(System.getProperty("user.dir"), "../../../apps/docs/spec/v1/envelope.json")
          .normalize();
      return mapper.readTree(Files.readString(path));
    } catch (Exception failure) {
      throw new IllegalStateException(
          "apps/docs/spec/v1/envelope.json must be readable; run bun run build:spec", failure);
    }
  }

  private JsonNode errorItemProperty(String name) {
    return schema.at("/$defs/errorItem/properties/" + name);
  }

  private JsonNode envelope() throws Exception {
    RecordingTransport transport = new RecordingTransport();
    MonicaAndroid monica = MonicaAndroid.install(new FakePlatform(),
        MonicaAndroidOptions.builder()
            .dsn("https://mpk_public@ingest.monica.test/1")
            .environment("production")
            .transport(transport)
            .captureUncaughtExceptions(false)
            .trackScreens(false)
            .build());
    try {
      monica.addBreadcrumb("ui.click", "submitButton");
      monica.setUser("u_123");
      monica.setScreen("CheckoutActivity");
      monica.captureException(new IllegalStateException("boom", new java.io.IOException("cause")));
      assertTrue(monica.flush(Duration.ofSeconds(2)));
    } finally {
      monica.close();
    }
    return mapper.valueToTree(transport.envelopes().get(0));
  }

  @Test
  void carriesEveryFieldTheEnvelopeRequires() throws Exception {
    JsonNode envelope = envelope();
    for (JsonNode required : schema.get("required")) {
      assertTrue(envelope.has(required.asText()), "envelope is missing " + required.asText());
    }
    for (JsonNode required : schema.at("/$defs/sdk/required")) {
      JsonNode value = envelope.at("/sdk/" + required.asText());
      assertTrue(value.isTextual() && !value.asText().isEmpty(), "sdk." + required.asText());
    }
    assertTrue(envelope.get("discarded").isIntegralNumber());
    assertTrue(envelope.get("discarded").asLong() >= schema.at("/properties/discarded/minimum").asLong());
    assertTrue(envelope.get("items").size() <= schema.at("/properties/items/maxItems").asInt());
    Instant.parse(envelope.get("sent_at").asText());
  }

  @Test
  void carriesEveryFieldAnErrorItemRequires() throws Exception {
    JsonNode item = envelope().get("items").get(0);
    for (JsonNode required : schema.at("/$defs/errorItem/required")) {
      assertTrue(item.has(required.asText()), "item is missing " + required.asText());
    }
    assertEquals(errorItemProperty("type").get("const").asText(), item.get("type").asText());
    assertTrue(item.get("event_id").asText()
        .matches(errorItemProperty("event_id").get("pattern").asText()));
    Instant.parse(item.get("timestamp").asText());
    String environment = item.get("environment").asText();
    assertTrue(environment.length() >= errorItemProperty("environment").get("minLength").asInt());
    assertTrue(environment.length() <= errorItemProperty("environment").get("maxLength").asInt());
  }

  @Test
  void staysInsideThePlatformAndLevelConstraints() throws Exception {
    // Android reports platform java. Adding an "android" value would mean teaching
    // ingest and grouping to branch on OS instead of language. The schema may express
    // platform as an enum or as a bounded string; both readings are honoured here.
    JsonNode item = envelope().get("items").get(0);
    assertEquals("java", item.get("platform").asText());
    assertWithin(errorItemProperty("platform"), item.get("platform"));
    assertWithin(errorItemProperty("level"), item.get("level"));
  }

  @Test
  void keepsTagsAsStringsAndFramesAnnotated() throws Exception {
    JsonNode item = envelope().get("items").get(0);
    assertFalse(item.get("tags").isEmpty());
    item.get("tags").fields().forEachRemaining(entry ->
        assertTrue(entry.getValue().isTextual(), entry.getKey() + " must be a string"));

    JsonNode values = item.at("/exception/values");
    assertEquals(2, values.size(), "the cause chain runs outermost first");
    assertEquals("java.lang.IllegalStateException", values.get(0).get("type").asText());
    assertEquals("java.io.IOException", values.get(1).get("type").asText());
    int maxFrames = schema.at("/$defs/exceptionValue/properties/stacktrace/properties/frames/maxItems")
        .asInt();
    for (JsonNode value : values) {
      for (JsonNode required : schema.at("/$defs/exceptionValue/required")) {
        assertTrue(value.has(required.asText()), "exception value is missing " + required.asText());
      }
      assertWithin(schema.at("/$defs/mechanism/properties/type"), value.at("/mechanism/type"));
      assertTrue(value.at("/mechanism/handled").isBoolean());
      JsonNode frames = value.at("/stacktrace/frames");
      assertFalse(frames.isEmpty(), "a Java exception always has frames");
      assertTrue(frames.size() <= maxFrames);
      for (JsonNode frame : frames) {
        for (JsonNode required : schema.at("/$defs/frame/required")) {
          assertTrue(frame.has(required.asText()), "frame is missing " + required.asText());
        }
        assertTrue(frame.get("filename").isTextual() && !frame.get("filename").asText().isEmpty());
        assertTrue(frame.get("in_app").isBoolean());
        if (frame.has("lineno")) {
          assertTrue(frame.get("lineno").asInt()
              >= schema.at("/$defs/frame/properties/lineno/minimum").asInt());
        }
      }
    }
  }

  @Test
  void keepsBreadcrumbsAndUserInTheShapeTheSchemaDescribes() throws Exception {
    JsonNode item = envelope().get("items").get(0);
    JsonNode breadcrumb = item.get("breadcrumbs").get(0);
    Instant.parse(breadcrumb.get("timestamp").asText());
    assertEquals("ui.click", breadcrumb.get("category").asText());
    assertTrue(item.at("/user/id").isTextual());
    assertEquals("u_123", item.at("/user/id").asText());
  }

  /** Enum or bounded string, whichever the schema uses for this property. */
  private static void assertWithin(JsonNode property, JsonNode value) {
    if (property.has("enum")) {
      List<String> allowed = new ArrayList<>();
      for (JsonNode candidate : property.get("enum")) allowed.add(candidate.asText());
      assertTrue(allowed.contains(value.asText()), value + " is not in " + allowed);
      return;
    }
    assertEquals("string", property.get("type").asText());
    assertTrue(value.isTextual());
    if (property.has("minLength")) assertTrue(value.asText().length() >= property.get("minLength").asInt());
    if (property.has("maxLength")) assertTrue(value.asText().length() <= property.get("maxLength").asInt());
  }
}
