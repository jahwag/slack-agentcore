package io.github.jahwag.slackagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryConversationIdParser;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

@Service
public class SlackAgent {

	private static final Logger log = LoggerFactory.getLogger(SlackAgent.class);

	private static final String SYSTEM_PROMPT = """
			You are a helpful teammate living in a Slack workspace. People talk to you by @mentioning you.
			Each user message is prefixed with the Slack user id of the speaker, like <@U123>.

			Answer concisely and format replies with Slack mrkdwn (*bold*, _italic_, `code`, ```blocks```, bullet lists
			with •). Do not use Markdown headings or tables.

			You learn over time: facts, decisions, conventions and corrections people share with you are remembered and
			shared across all channels. When relevant long-term memories are provided, use them and say so briefly.
			When someone corrects you, acknowledge the correction clearly so it is remembered.

			Be honest about what you knew and when. New learnings take about a minute to become available, so if
			information appears in your memory that you did not have earlier in this conversation, say you have
			just learned it. Never claim you "had it all along" or apologize for withholding something you did not
			know.
			""";

	private final ChatClient chatClient;

	private final AgentCoreMemory memory;

	public SlackAgent(ChatClient.Builder builder, AgentCoreMemory memory) {
		this.chatClient = builder.defaultSystem(SYSTEM_PROMPT).build();
		this.memory = memory;
	}

	@AgentCoreInvocation
	public String handle(SlackPrompt request, AgentCoreContext context) {
		String sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID);
		if (sessionId == null || sessionId.isBlank()) {
			sessionId = "slack-" + request.channelId() + "-" + request.threadTs();
		}
		// actor = Slack workspace, session = Slack thread. Short-term history is shared by everyone in the
		// thread; long-term learnings come from the global namespace (see infra/app.yaml).
		String actor = (request.teamId() == null || request.teamId().isBlank()) ? "slack" : request.teamId();
		String conversationId = AgentCoreMemoryConversationIdParser.of(actor, sessionId.replace(':', '-'));
		log.info("Invocation conversationId={} channel={}", conversationId, request.channelId());

		return this.chatClient.prompt()
			.user("<@" + request.userId() + ">: " + request.prompt())
			.advisors(this.memory.advisors)
			.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
			.call()
			.content();
	}

}
