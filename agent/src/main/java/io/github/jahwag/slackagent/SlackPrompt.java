package io.github.jahwag.slackagent;

/**
 * Payload sent by the Slack gateway Lambda to /invocations.
 */
public record SlackPrompt(String prompt, String teamId, String userId, String channelId, String threadTs) {
}
