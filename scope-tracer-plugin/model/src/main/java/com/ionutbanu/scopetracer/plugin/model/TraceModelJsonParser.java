package com.ionutbanu.scopetracer.plugin.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.lang.reflect.Type;
import java.time.Instant;

/**
 * Parses the JSON produced by {@code scope-tracer-analyzer}'s {@code --format=json} CLI mode
 * (schema documented on {@code TraceModelJson} in the analyzer module) into plugin-local
 * records. Deliberately independent of any scope-tracer class: JSON is the only coupling,
 * since the analyzer is compiled with {@code --enable-preview} and its classes can never load
 * inside the IDE's own JVM (JBR).
 */
public final class TraceModelJsonParser {

  private static final Gson GSON =
      new GsonBuilder()
          .registerTypeAdapter(
              Instant.class, (JsonDeserializer<Instant>) TraceModelJsonParser::parseInstant)
          .registerTypeAdapter(
              PluginTaskOutcome.class,
              (JsonDeserializer<PluginTaskOutcome>) TraceModelJsonParser::parseOutcome)
          .create();

  private TraceModelJsonParser() {}

  public static PluginTraceModel parse(String json) {
    return GSON.fromJson(json, PluginTraceModel.class);
  }

  private static Instant parseInstant(JsonElement json, Type type, JsonDeserializationContext ctx) {
    if (json == null || json.isJsonNull()) {
      return null;
    }
    return Instant.parse(json.getAsString());
  }

  private static PluginTaskOutcome parseOutcome(
      JsonElement json, Type type, JsonDeserializationContext ctx) {
    if (json == null || json.isJsonNull()) {
      return null;
    }
    JsonObject obj = json.getAsJsonObject();
    String outcomeType = obj.get("type").getAsString();
    return switch (outcomeType) {
      case "success" -> new PluginTaskOutcome.Success();
      case "cancelled" -> new PluginTaskOutcome.Cancelled();
      case "failed" ->
          new PluginTaskOutcome.Failed(
              stringOrNull(obj, "exceptionType"),
              stringOrNull(obj, "exceptionMessage"),
              stringOrNull(obj, "stackTrace"));
      default -> throw new JsonParseException("Unknown task outcome type: " + outcomeType);
    };
  }

  private static String stringOrNull(JsonObject obj, String key) {
    var element = obj.get(key);
    return (element == null || element.isJsonNull()) ? null : element.getAsString();
  }
}
