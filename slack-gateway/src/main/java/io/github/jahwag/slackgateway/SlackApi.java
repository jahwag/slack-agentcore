package io.github.jahwag.slackgateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal Slack Web API client (chat.postMessage, reactions.add/remove). */
final class SlackApi {

	private final HttpClient http;

	private final ObjectMapper json;

	SlackApi(HttpClient http, ObjectMapper json) {
		this.http = http;
		this.json = json;
	}

	JsonNode call(String botToken, String method, Map<String, Object> body) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://slack.com/api/" + method))
			.timeout(Duration.ofSeconds(10))
			.header("Authorization", "Bearer " + botToken)
			.header("Content-Type", "application/json; charset=utf-8")
			.POST(HttpRequest.BodyPublishers.ofString(this.json.writeValueAsString(body)))
			.build();
		HttpResponse<String> res = this.http.send(req, HttpResponse.BodyHandlers.ofString());
		JsonNode node = this.json.readTree(res.body());
		if (!node.path("ok").asBoolean(false)) {
			System.out.println("Slack " + method + " failed: " + node.path("error").asText(res.body()));
		}
		return node;
	}

	void postInThread(String botToken, String channel, String threadTs, String text) throws Exception {
		call(botToken, "chat.postMessage", Map.of("channel", channel, "thread_ts", threadTs, "text", text,
				"unfurl_links", false, "unfurl_media", false));
	}

	void react(String botToken, String channel, String ts, String name, boolean add) {
		try {
			call(botToken, add ? "reactions.add" : "reactions.remove",
					Map.of("channel", channel, "timestamp", ts, "name", name));
		}
		catch (Exception ex) {
			System.out.println("Slack reaction failed: " + ex);
		}
	}

}
