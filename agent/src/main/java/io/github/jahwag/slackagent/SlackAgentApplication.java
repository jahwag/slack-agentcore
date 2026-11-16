package io.github.jahwag.slackagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

@SpringBootApplication
public class SlackAgentApplication {

	public static void main(String[] args) {
		loadAnthropicKeyIfNeeded();
		SpringApplication.run(SlackAgentApplication.class, args);
	}

	/**
	 * When the Anthropic API is the active provider, pull the key from Secrets Manager
	 * (secret JSON field "anthropicApiKey") before Spring starts, so it never lives in
	 * env vars or config files.
	 */
	private static void loadAnthropicKeyIfNeeded() {
		String provider = System.getenv().getOrDefault("LLM_PROVIDER", "bedrock-converse");
		String secretArn = System.getenv("SECRET_ARN");
		if (!"anthropic".equals(provider) || secretArn == null || secretArn.isBlank()) {
			return;
		}
		try (SecretsManagerClient sm = SecretsManagerClient.create()) {
			String json = sm.getSecretValue(r -> r.secretId(secretArn)).secretString();
			String key = new ObjectMapper().readTree(json).path("anthropicApiKey").asText("");
			if (key.isBlank() || key.startsWith("CHANGE_ME")) {
				throw new IllegalStateException("LLM_PROVIDER=anthropic but anthropicApiKey is not set in " + secretArn);
			}
			System.setProperty("spring.ai.anthropic.api-key", key);
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to load Anthropic API key from Secrets Manager", ex);
		}
	}

}
