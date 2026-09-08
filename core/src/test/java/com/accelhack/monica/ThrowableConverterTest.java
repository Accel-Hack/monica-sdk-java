package com.accelhack.monica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThrowableConverterTest {
  @Test
  void preservesOuterToInnerCauseOrder() {
    IllegalStateException inner = new IllegalStateException("inner");
    RuntimeException outer = new RuntimeException("outer", inner);
    Map<String, Object> exception = ThrowableConverter.convert(outer,
        List.of("com.accelhack.monica"), false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> values = (List<Map<String, Object>>) exception.get("values");
    assertEquals("java.lang.RuntimeException", values.get(0).get("type"));
    assertEquals("java.lang.IllegalStateException", values.get(1).get("type"));
    @SuppressWarnings("unchecked")
    Map<String, Object> mechanism = (Map<String, Object>) values.get(0).get("mechanism");
    assertFalse((Boolean) mechanism.get("handled"));
  }

  @Test
  void derivesTheFileNameFromTheClassWhenR8RewroteTheSourceFile() {
    // R8 replaces SourceFile with a per-build map id, or with the constant an app
    // names in -renamesourcefileattribute. Both would split one crash across builds.
    for (String rewritten : new String[] {
        "r8-map-id-4bd033a7c1f955afadeb5df0afef7a5fef1ccd4df361a8e17c96941e580f1526",
        "SourceFile", "Unknown Source", "Hidden", "", null}) {
      StackTraceElement element = new StackTraceElement(
          "com.accelhack.monica.sample.MainActivity$$ExternalSyntheticLambda0", "run", rewritten, 14);
      assertEquals("com/accelhack/monica/sample/MainActivity.java",
          ThrowableConverter.sourcePath(element), String.valueOf(rewritten));
    }
    // Anything an app names in -renamesourcefileattribute is a word, not a file: real
    // source files always carry an extension.
    assertTrue(ThrowableConverter.hasRealFileName("MainActivity.kt"));
    assertTrue(ThrowableConverter.hasRealFileName("SourceFile.kt"));
    assertFalse(ThrowableConverter.hasRealFileName("r8-map-id-abc"));
    assertFalse(ThrowableConverter.hasRealFileName("r8-map-id-abc.def"));
    assertFalse(ThrowableConverter.hasRealFileName("Hidden"));
    assertFalse(ThrowableConverter.hasRealFileName(".hidden"));
  }

  @Test
  void normalizesSourcePathAndKeepsInnerClassInFunction() {
    StackTraceElement element = new StackTraceElement(
        "com.example.app.BotService$Worker", "lambda$run$0", "BotService.java", 42);
    assertEquals("com/example/app/BotService.java", ThrowableConverter.sourcePath(element));
    Map<String, Object> exception = ThrowableConverter.convert(
        throwableWith(element), List.of("com.example.app"), true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> values = (List<Map<String, Object>>) exception.get("values");
    @SuppressWarnings("unchecked")
    Map<String, Object> stacktrace = (Map<String, Object>) values.get(0).get("stacktrace");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> frames = (List<Map<String, Object>>) stacktrace.get("frames");
    assertTrue((Boolean) frames.get(0).get("in_app"));
    assertEquals("com.example.app.BotService$Worker.lambda$run$0",
        frames.get(0).get("function"));
  }

  private Throwable throwableWith(StackTraceElement element) {
    Throwable throwable = new RuntimeException("boom");
    throwable.setStackTrace(new StackTraceElement[] {element});
    return throwable;
  }
}
