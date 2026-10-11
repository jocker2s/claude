package dev.claudeingame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public final class ClaudeClient {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	private ClaudeClient() {}

	/** Блокирующий вызов Messages API. Вызывать только вне главного потока. */
	public static String send(Config cfg, String system, List<JsonObject> history) throws Exception {
		if (cfg.apiKey == null || cfg.apiKey.isBlank()) {
			throw new IllegalStateException("API-ключ не задан. Используйте /claude key <ключ> или config/claude-in-game.json");
		}
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.model);
		body.addProperty("max_tokens", cfg.maxTokens);
		body.addProperty("system", system);
		JsonArray messages = new JsonArray();
		for (JsonObject m : history) messages.add(m);
		body.add("messages", messages);

		HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
				.timeout(Duration.ofSeconds(180))
				.header("content-type", "application/json")
				.header("x-api-key", cfg.apiKey)
				.header("anthropic-version", "2023-06-01")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();

		HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200) {
			String msg = resp.body();
			try {
				JsonObject err = JsonParser.parseString(resp.body()).getAsJsonObject().getAsJsonObject("error");
				if (err != null && err.has("message")) msg = err.get("message").getAsString();
			} catch (Exception ignored) {}
			throw new RuntimeException("API " + resp.statusCode() + ": " + msg);
		}
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		StringBuilder out = new StringBuilder();
		for (JsonElement el : json.getAsJsonArray("content")) {
			JsonObject block = el.getAsJsonObject();
			if ("text".equals(block.get("type").getAsString())) out.append(block.get("text").getAsString());
		}
		return out.toString();
	}
}
