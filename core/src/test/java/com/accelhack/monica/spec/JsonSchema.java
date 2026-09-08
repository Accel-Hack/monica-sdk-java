package com.accelhack.monica.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dependency-free validator for the JSON Schema draft 2020-12 subset that
 * {@code spec/v1/envelope.json} actually uses.
 *
 * <p>The SDK ships with no schema library and its tests run on Java 11, so the keywords
 * below are implemented by hand on top of Jackson's tree model. An unsupported keyword is
 * a hard error rather than a silent pass: a contract test that quietly stops checking is
 * worse than no contract test.
 */
public final class JsonSchema {
  private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(
      "$schema", "$id", "$defs", "$ref", "title",
      "type", "enum", "const", "not", "anyOf",
      "required", "properties", "additionalProperties", "propertyNames",
      "items", "minItems", "maxItems",
      "minLength", "maxLength", "pattern", "format",
      "minimum"));

  private static final Pattern DATE_TIME = Pattern.compile(
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$");
  private static final Pattern UUID = Pattern.compile(
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private final JsonNode root;

  public JsonSchema(JsonNode schema) {
    if (schema == null || !schema.isObject()) {
      throw new IllegalArgumentException("schema is not a JSON object");
    }
    this.root = schema;
  }

  public static JsonSchema fromFile(Path path) throws IOException {
    return new JsonSchema(new ObjectMapper().readTree(Files.readAllBytes(path)));
  }

  /** @return an empty list when {@code value} satisfies the schema */
  public List<String> validate(JsonNode value) {
    return check(value, root, "$");
  }

  /** The names under {@code $defs}, so a test can confirm every {@code $ref} resolves. */
  public List<String> definitionNames() {
    List<String> names = new ArrayList<>();
    JsonNode defs = root.get("$defs");
    if (defs != null && defs.isObject()) defs.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** A JSON pointer into the schema document itself; missing when nothing is there. */
  public JsonNode pointer(String pointer) {
    return root.at(pointer);
  }

  private List<String> check(JsonNode value, JsonNode schema, String path) {
    for (Iterator<String> keywords = schema.fieldNames(); keywords.hasNext();) {
      String keyword = keywords.next();
      if (!SUPPORTED.contains(keyword)) {
        throw new IllegalArgumentException("unsupported schema keyword \"" + keyword + "\" at "
            + path + "; extend " + JsonSchema.class.getName() + " before relying on this run");
      }
    }

    if (schema.has("$ref")) {
      return check(value, resolve(schema.get("$ref").asText()), path);
    }

    List<String> errors = new ArrayList<>();
    if (schema.has("type") && !matchesType(value, schema.get("type").asText())) {
      return List.of(path + ": expected " + schema.get("type").asText() + ", got " + describe(value));
    }
    if (schema.has("const") && !same(value, schema.get("const"))) {
      errors.add(path + ": expected const " + schema.get("const"));
    }
    if (schema.has("enum") && !inList(value, schema.get("enum"))) {
      errors.add(path + ": " + value + " is not one of " + schema.get("enum"));
    }
    if (schema.has("not") && check(value, schema.get("not"), path).isEmpty()) {
      errors.add(path + ": must not match the \"not\" schema");
    }
    if (schema.has("anyOf")) {
      boolean matched = false;
      List<String> branchErrors = new ArrayList<>();
      int index = 0;
      for (JsonNode branch : schema.get("anyOf")) {
        List<String> branchResult = check(value, branch, path);
        if (branchResult.isEmpty()) {
          matched = true;
          break;
        }
        branchErrors.add("#" + index + " " + String.join("; ", branchResult));
        index++;
      }
      if (!matched) {
        errors.add(path + ": matches none of the " + schema.get("anyOf").size()
            + " anyOf branches (" + String.join(" | ", branchErrors) + ")");
      }
    }

    if (value.isObject()) {
      errors.addAll(checkObject(value, schema, path));
    } else if (value.isArray()) {
      errors.addAll(checkArray(value, schema, path));
    } else if (value.isTextual()) {
      errors.addAll(checkString(value.asText(), schema, path));
    } else if (value.isNumber()) {
      if (schema.has("minimum")
          && value.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0) {
        errors.add(path + ": " + value + " is below minimum " + schema.get("minimum"));
      }
    }
    return errors;
  }

  private List<String> checkObject(JsonNode value, JsonNode schema, String path) {
    List<String> errors = new ArrayList<>();
    JsonNode properties = schema.has("properties") ? schema.get("properties") : null;
    if (schema.has("required")) {
      for (JsonNode required : schema.get("required")) {
        if (!value.has(required.asText())) {
          errors.add(path + ": missing required property \"" + required.asText() + "\"");
        }
      }
    }
    for (Iterator<Map.Entry<String, JsonNode>> fields = value.fields(); fields.hasNext();) {
      Map.Entry<String, JsonNode> field = fields.next();
      String key = field.getKey();
      if (schema.has("propertyNames")) {
        // The subschema applies to the name, not to the value under it.
        JsonNode name = new ObjectMapper().getNodeFactory().textNode(key);
        for (String problem : check(name, schema.get("propertyNames"), path + "." + key)) {
          errors.add("property name " + problem);
        }
      }
      if (properties != null && properties.has(key)) {
        errors.addAll(check(field.getValue(), properties.get(key), path + "." + key));
        continue;
      }
      JsonNode additional = schema.get("additionalProperties");
      if (additional == null) continue;
      if (additional.isBoolean() && !additional.asBoolean()) {
        errors.add(path + ": additional property \"" + key + "\" is not allowed");
      } else if (additional.isObject()) {
        errors.addAll(check(field.getValue(), additional, path + "." + key));
      }
    }
    return errors;
  }

  private List<String> checkArray(JsonNode value, JsonNode schema, String path) {
    List<String> errors = new ArrayList<>();
    if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
      errors.add(path + ": has " + value.size() + " items, minimum is " + schema.get("minItems"));
    }
    if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
      errors.add(path + ": has " + value.size() + " items, maximum is " + schema.get("maxItems"));
    }
    if (schema.has("items")) {
      int index = 0;
      for (JsonNode item : value) {
        errors.addAll(check(item, schema.get("items"), path + "[" + index + "]"));
        index++;
      }
    }
    return errors;
  }

  private static List<String> checkString(String value, JsonNode schema, String path) {
    List<String> errors = new ArrayList<>();
    int length = value.codePointCount(0, value.length());
    if (schema.has("minLength") && length < schema.get("minLength").asInt()) {
      errors.add(path + ": length " + length + " is below minLength " + schema.get("minLength"));
    }
    if (schema.has("maxLength") && length > schema.get("maxLength").asInt()) {
      errors.add(path + ": length " + length + " exceeds maxLength " + schema.get("maxLength"));
    }
    // ECMA-262 patterns in the schema are unanchored, which is what find() gives.
    if (schema.has("pattern")
        && !Pattern.compile(schema.get("pattern").asText()).matcher(value).find()) {
      errors.add(path + ": \"" + value + "\" does not match " + schema.get("pattern").asText());
    }
    if (schema.has("format") && !matchesFormat(value, schema.get("format").asText())) {
      errors.add(path + ": \"" + value + "\" is not a valid " + schema.get("format").asText());
    }
    return errors;
  }

  private JsonNode resolve(String reference) {
    if (!reference.startsWith("#/$defs/")) {
      throw new IllegalArgumentException("unsupported $ref: " + reference);
    }
    String name = reference.substring("#/$defs/".length());
    JsonNode defs = root.get("$defs");
    if (defs == null || !defs.isObject() || !defs.has(name) || !defs.get(name).isObject()) {
      throw new IllegalArgumentException("unknown $ref: " + reference);
    }
    return defs.get(name);
  }

  private static boolean matchesType(JsonNode value, String type) {
    switch (type) {
      case "object": return value.isObject();
      case "array": return value.isArray();
      case "string": return value.isTextual();
      case "integer":
        // 2.0 is an integer in JSON Schema even when the parser kept it a double.
        return value.isIntegralNumber()
            || (value.isFloatingPointNumber() && value.doubleValue() == Math.floor(value.doubleValue()));
      case "number": return value.isNumber();
      case "boolean": return value.isBoolean();
      case "null": return value.isNull();
      default: throw new IllegalArgumentException("unsupported type: " + type);
    }
  }

  private static boolean matchesFormat(String value, String format) {
    switch (format) {
      case "date-time": return DATE_TIME.matcher(value).matches();
      case "uuid": return UUID.matcher(value).matches();
      default: throw new IllegalArgumentException("unsupported format: " + format);
    }
  }

  private static boolean same(JsonNode a, JsonNode b) {
    if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
    return a.equals(b);
  }

  private static boolean inList(JsonNode value, JsonNode list) {
    for (JsonNode candidate : list) {
      if (same(value, candidate)) return true;
    }
    return false;
  }

  private static String describe(JsonNode value) {
    if (value == null || value.isMissingNode()) return "nothing";
    if (value.isObject()) return "object";
    if (value.isArray()) return "array";
    if (value.isNull()) return "null";
    return value.getNodeType().name().toLowerCase() + " " + value;
  }
}
