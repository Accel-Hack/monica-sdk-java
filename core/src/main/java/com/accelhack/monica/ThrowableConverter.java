package com.accelhack.monica;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ThrowableConverter {
  /** The frame limit ingest puts on one stacktrace; the oldest callers are dropped first. */
  static final int MAX_FRAMES = 200;

  private ThrowableConverter() {}

  public static Map<String, Object> convert(Throwable throwable, Iterable<String> inAppPackages,
      boolean handled) {
    List<Map<String, Object>> values = new ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = throwable;
    while (current != null && seen.add(current)) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("type", current.getClass().getName());
      value.put("value", current.getMessage() == null ? "" : current.getMessage());
      List<Map<String, Object>> frames = frames(current.getStackTrace(), inAppPackages);
      if (!frames.isEmpty()) value.put("stacktrace", Collections.singletonMap("frames", frames));
      Map<String, Object> mechanism = new LinkedHashMap<>();
      mechanism.put("type", "generic");
      mechanism.put("handled", handled);
      value.put("mechanism", mechanism);
      values.add(value);
      current = current.getCause();
    }
    return Collections.singletonMap("values", values);
  }

  private static List<Map<String, Object>> frames(StackTraceElement[] stack,
      Iterable<String> inAppPackages) {
    List<Map<String, Object>> frames = new ArrayList<>();
    for (int index = stack.length - 1; index >= 0; index--) {
      StackTraceElement element = stack[index];
      Map<String, Object> frame = new LinkedHashMap<>();
      frame.put("filename", sourcePath(element));
      frame.put("function", element.getClassName() + "." + element.getMethodName());
      if (!element.isNativeMethod() && element.getLineNumber() > 0) {
        frame.put("lineno", element.getLineNumber());
      }
      frame.put("in_app", isInApp(element.getClassName(), inAppPackages));
      frames.add(frame);
    }
    return frames.size() <= MAX_FRAMES ? frames
        : new ArrayList<>(frames.subList(frames.size() - MAX_FRAMES, frames.size()));
  }

  static String sourcePath(StackTraceElement element) {
    String className = element.getClassName();
    int packageEnd = className.lastIndexOf('.');
    String packagePath = packageEnd < 0 ? "" : className.substring(0, packageEnd).replace('.', '/');
    String fileName = element.getFileName();
    if (!hasRealFileName(fileName)) {
      String simpleName = packageEnd < 0 ? className : className.substring(packageEnd + 1);
      int inner = simpleName.indexOf('$');
      fileName = (inner < 0 ? simpleName : simpleName.substring(0, inner)) + ".java";
    }
    return packagePath.isEmpty() ? fileName : packagePath + "/" + fileName;
  }

  /**
   * R8 rewrites the SourceFile attribute of every minified class: to
   * {@code r8-map-id-<hash>} by default, or to whatever
   * {@code -renamesourcefileattribute} names (conventionally {@code SourceFile}, but
   * any word an application chooses). None of those identify a file, and the hash
   * changes with every build, so a frame carrying one would put the same crash into a
   * new group per build. A real source file always has an extension, so anything
   * without a dot is treated as no file name at all; the class name is a better source
   * when the application kept it with {@code -keepnames}.
   */
  static boolean hasRealFileName(String fileName) {
    if (fileName == null || fileName.isEmpty()) return false;
    if (fileName.startsWith("r8-map-id-")) return false;
    return fileName.indexOf('.') > 0;
  }

  private static boolean isInApp(String className, Iterable<String> prefixes) {
    for (String prefix : prefixes) {
      if (className.equals(prefix) || className.startsWith(prefix + ".")) return true;
    }
    return false;
  }
}
