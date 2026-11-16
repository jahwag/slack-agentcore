package io.github.jahwag.slackgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SlackSignatureTest {

	// Example from Slack's docs.
	static final String SECRET = "8f742231b10e8888abcd99yyyzzz85a5";

	static final String TS = "1531420618";

	static final String BODY = "token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text=&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c";

	static final String SIG = "v0=a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503";

	@Test
	void acceptsSlackDocExample() {
		assertTrue(SlackSignature.isValid(SECRET, TS, SIG, BODY, Long.parseLong(TS) + 10));
	}

	@Test
	void rejectsStaleTimestamp() {
		assertFalse(SlackSignature.isValid(SECRET, TS, SIG, BODY, Long.parseLong(TS) + 301));
	}

	@Test
	void rejectsTamperedBody() {
		assertFalse(SlackSignature.isValid(SECRET, TS, SIG, BODY + "x", Long.parseLong(TS)));
	}

	@Test
	void rejectsMissingSecret() {
		assertFalse(SlackSignature.isValid("", TS, SIG, BODY, Long.parseLong(TS)));
	}

	@Test
	void sessionIdIsLongEnoughAndSafe() {
		String id = Handler.sessionId("T1", "C2", "1700000000.123456");
		assertTrue(id.length() >= 33, id);
		assertTrue(id.matches("[A-Za-z0-9-]+"), id);
	}

	@Test
	void unwrapsJsonEncodedString() throws Exception {
		assertEquals("hi \"there\"", Handler.unwrap("\"hi \\\"there\\\"\""));
		assertEquals("plain", Handler.unwrap("plain"));
	}

}
