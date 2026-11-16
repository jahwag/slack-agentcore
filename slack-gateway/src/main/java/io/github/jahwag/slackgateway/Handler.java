package io.github.jahwag.slackgateway;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.crac.Core;
import org.crac.Resource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Two modes in one function:
 * <ul>
 * <li><b>Ingress</b> (Function URL event): verify the Slack signature, answer url_verification, ack
 * app_mention within Slack's 3s budget and async-invoke itself with the event.</li>
 * <li><b>Worker</b> (async self-invocation, payload {"worker": {...event...}}): call the AgentCore runtime
 * and post the answer in the Slack thread.</li>
 * </ul>
 */
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>>, Resource {

	private static final Pattern MENTION = Pattern.compile("<@[A-Z0-9]+>");

	private static final ObjectMapper JSON = new ObjectMapper();

	private final String secretArn = env("SECRET_ARN");

	private final String agentRuntimeArn = env("AGENT_RUNTIME_ARN");

	private final String selfFunction = env("AWS_LAMBDA_FUNCTION_NAME");

	private final String selfQualifier = System.getenv().getOrDefault("SELF_QUALIFIER", "live");

	private final SecretsManagerClient secrets;

	private final BedrockAgentCoreClient agentCore;

	private final LambdaClient lambda;

	private final SlackApi slack;

	/** Loaded lazily after SnapStart restore so secrets never end up in the snapshot. */
	private volatile JsonNode secretCache;

	private volatile long secretLoadedAt;

	public Handler() {
		var httpClient = UrlConnectionHttpClient.builder()
			.connectionTimeout(Duration.ofSeconds(5))
			.socketTimeout(Duration.ofSeconds(110))
			.build();
		var override = ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(110)).build();
		this.secrets = SecretsManagerClient.builder().httpClient(httpClient).build();
		this.agentCore = BedrockAgentCoreClient.builder().httpClient(httpClient).overrideConfiguration(override).build();
		this.lambda = LambdaClient.builder().httpClient(httpClient).build();
		this.slack = new SlackApi(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), JSON);
		Core.getGlobalContext().register(this);
	}

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
		try {
			if (event.containsKey("worker")) {
				work(JSON.valueToTree(event.get("worker")));
				return Map.of("ok", true);
			}
			return ingress(event);
		}
		catch (Exception ex) {
			System.out.println("Unhandled error: " + ex);
			ex.printStackTrace(System.out);
			return http(500, "error");
		}
	}

	// ---------------------------------------------------------------- ingress

	@SuppressWarnings("unchecked")
	Map<String, Object> ingress(Map<String, Object> event) throws Exception {
		Map<String, String> headers = lowerCase((Map<String, String>) event.getOrDefault("headers", Map.of()));
		String body = (String) event.getOrDefault("body", "");
		if (Boolean.TRUE.equals(event.get("isBase64Encoded")) && body != null) {
			body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
		}

		if (!SlackSignature.isValid(secret("signingSecret"), headers.get("x-slack-request-timestamp"),
				headers.get("x-slack-signature"), body, System.currentTimeMillis() / 1000)) {
			return http(401, "invalid signature");
		}

		JsonNode payload = JSON.readTree(body);
		String type = payload.path("type").asText();

		if ("url_verification".equals(type)) {
			return http(200, payload.path("challenge").asText());
		}
		// Slack retries if we were slow; the first delivery is already being processed.
		if (headers.containsKey("x-slack-retry-num")) {
			return http(200, "retry ignored");
		}
		if ("event_callback".equals(type)) {
			JsonNode ev = payload.path("event");
			boolean isMention = "app_mention".equals(ev.path("type").asText());
			boolean fromBot = ev.hasNonNull("bot_id") || ev.hasNonNull("subtype");
			if (isMention && !fromBot) {
				ObjectNode work = JSON.createObjectNode();
				work.put("teamId", payload.path("team_id").asText());
				work.put("eventId", payload.path("event_id").asText());
				work.set("event", ev);
				this.lambda.invoke(r -> r.functionName(this.selfFunction)
					.qualifier(this.selfQualifier)
					.invocationType(InvocationType.EVENT)
					.payload(SdkBytes.fromUtf8String(JSON.createObjectNode().set("worker", work).toString())));
			}
		}
		return http(200, "ok");
	}

	// ---------------------------------------------------------------- worker

	void work(JsonNode work) throws Exception {
		JsonNode ev = work.path("event");
		String teamId = work.path("teamId").asText();
		String channel = ev.path("channel").asText();
		String ts = ev.path("ts").asText();
		String threadTs = ev.hasNonNull("thread_ts") ? ev.path("thread_ts").asText() : ts;
		String user = ev.path("user").asText();
		String text = MENTION.matcher(ev.path("text").asText()).replaceAll("").trim();
		String token = secret("botToken");

		this.slack.react(token, channel, ts, "eyes", true);
		try {
			if (text.isEmpty()) {
				text = "Hi!";
			}
			Map<String, Object> prompt = new LinkedHashMap<>();
			prompt.put("prompt", text);
			prompt.put("teamId", teamId);
			prompt.put("userId", user);
			prompt.put("channelId", channel);
			prompt.put("threadTs", threadTs);

			String answer = invokeAgent(sessionId(teamId, channel, threadTs), JSON.writeValueAsString(prompt));
			this.slack.postInThread(token, channel, threadTs, answer.isBlank() ? "_(no answer)_" : answer);
			this.slack.react(token, channel, ts, "white_check_mark", true);
		}
		catch (Exception ex) {
			System.out.println("Agent invocation failed: " + ex);
			ex.printStackTrace(System.out);
			this.slack.postInThread(token, channel, threadTs,
					":warning: Sorry, something went wrong talking to the agent. Check the gateway logs.");
			this.slack.react(token, channel, ts, "x", true);
		}
		finally {
			this.slack.react(token, channel, ts, "eyes", false);
		}
	}

	String invokeAgent(String sessionId, String payloadJson) throws Exception {
		var res = this.agentCore.invokeAgentRuntimeAsBytes(r -> r.agentRuntimeArn(this.agentRuntimeArn)
			.runtimeSessionId(sessionId)
			.contentType("application/json")
			.accept("application/json")
			.payload(SdkBytes.fromUtf8String(payloadJson)));
		return unwrap(res.asUtf8String());
	}

	/** A String handler result may arrive raw or JSON-encoded; normalize to plain text. */
	static String unwrap(String raw) throws Exception {
		String s = raw == null ? "" : raw.trim();
		if (s.startsWith("\"") && s.endsWith("\"")) {
			return JSON.readTree(s).asText();
		}
		return s;
	}

	/** AgentCore requires runtimeSessionId of 33..256 chars; one session per Slack thread. */
	static String sessionId(String teamId, String channel, String threadTs) {
		String id = ("slack-" + teamId + "-" + channel + "-" + threadTs).replaceAll("[^A-Za-z0-9-]", "-");
		while (id.length() < 33) {
			id = id + "-0";
		}
		return id.length() > 256 ? id.substring(0, 256) : id;
	}

	// ---------------------------------------------------------------- helpers

	private String secret(String field) throws Exception {
		long now = System.currentTimeMillis();
		if (this.secretCache == null || now - this.secretLoadedAt > Duration.ofMinutes(5).toMillis()) {
			String s = this.secrets.getSecretValue(r -> r.secretId(this.secretArn)).secretString();
			this.secretCache = JSON.readTree(s);
			this.secretLoadedAt = now;
		}
		return this.secretCache.path(field).asText("");
	}

	private static Map<String, Object> http(int status, String body) {
		Map<String, Object> res = new HashMap<>();
		res.put("statusCode", status);
		res.put("headers", Map.of("Content-Type", "text/plain"));
		res.put("body", body);
		return res;
	}

	private static Map<String, String> lowerCase(Map<String, String> headers) {
		Map<String, String> out = new HashMap<>();
		headers.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
		return out;
	}

	private static String env(String name) {
		return System.getenv().getOrDefault(name, "");
	}

	// ---------------------------------------------------------------- SnapStart (CRaC)

	@Override
	public void beforeCheckpoint(org.crac.Context<? extends Resource> context) {
		// Prime class loading / JIT for the hot paths without touching secrets or the network.
		try {
			JSON.readTree("{\"type\":\"event_callback\",\"event\":{\"type\":\"app_mention\"}}");
			SlackSignature.isValid("prime", "0", "v0=00", "{}", 0);
			sessionId("T0", "C0", "0.0");
			// Warm up SDK clients (marshalling, signing, TLS) with calls that return no secret material.
			this.secrets.describeSecret(r -> r.secretId(this.secretArn));
			this.lambda.invoke(r -> r.functionName(this.selfFunction)
				.qualifier(this.selfQualifier)
				.invocationType(InvocationType.DRY_RUN)
				.payload(SdkBytes.fromUtf8String("{}")));
		}
		catch (Exception ignored) {
			// priming is best effort
		}
		this.secretCache = null;
	}

	@Override
	public void afterRestore(org.crac.Context<? extends Resource> context) {
		this.secretCache = null;
	}

}
