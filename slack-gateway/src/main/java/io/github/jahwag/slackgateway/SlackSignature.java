package io.github.jahwag.slackgateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies Slack request signatures (v0, HMAC-SHA256 over "v0:{timestamp}:{rawBody}").
 * https://api.slack.com/authentication/verifying-requests-from-slack
 */
final class SlackSignature {

	static final long MAX_SKEW_SECONDS = 300;

	private SlackSignature() {
	}

	static boolean isValid(String signingSecret, String timestamp, String signature, String rawBody, long nowEpochSeconds) {
		if (signingSecret == null || signingSecret.isBlank() || timestamp == null || signature == null || rawBody == null) {
			return false;
		}
		long ts;
		try {
			ts = Long.parseLong(timestamp.trim());
		}
		catch (NumberFormatException ex) {
			return false;
		}
		if (Math.abs(nowEpochSeconds - ts) > MAX_SKEW_SECONDS) {
			return false;
		}
		String expected = "v0=" + hmacSha256Hex(signingSecret, "v0:" + timestamp.trim() + ":" + rawBody);
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				signature.trim().getBytes(StandardCharsets.UTF_8));
	}

	static String hmacSha256Hex(String key, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
