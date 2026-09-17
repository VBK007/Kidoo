package com.example.kido.media.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.example.kido.media.assistant.AssistantTools.Caller;
import com.example.kido.media.dto.AssistantDtos.AssistantAnswerDto;
import com.example.kido.media.dto.AssistantDtos.ToolCallDto;
import com.example.kido.media.search.ai.AiSearchProperties;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers questions about the library by calling the tools in {@link AssistantTools}.
 *
 * <p>Everything it can do already existed. "What have we watched more than twice?" and
 * "show me something nobody has seen" are not new features — they are the catalog, the
 * taste model and the play counts, reached by a model that picks which to ask. Anything
 * these four tools cannot answer is a missing tool, which is a small concrete piece of
 * work rather than a prompt to be tuned.
 *
 * <p>A hand-written loop rather than the SDK's tool runner. The loop is a dozen lines and
 * this way the two things that matter are visible at the call site: the turn ceiling, and
 * the fact that every tool runs against a {@link Caller} taken from the request rather
 * than from anything the model said.
 *
 * <p>Like the search fallback, off by default and never load-bearing: with the feature
 * off there is no bean, and with it on every failure is reported as an answer the person
 * can read rather than a stack trace.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.media.ai", name = "assistant-enabled",
        havingValue = "true")
public class AssistantService {

    /**
     * How many times the model may call tools before it has to answer.
     *
     * <p>A question about a library is one or two lookups. A loop that has gone round six
     * times is not converging, and cutting it off is better than letting one question
     * spend without limit.
     */
    private static final int MAX_TURNS = 6;

    private static final long MAX_TOKENS = 4096;

    private static final String SYSTEM_PROMPT = """
            You answer questions about the media library on a household's own server, \
            using the tools provided. You are talking to somebody standing in their \
            living room deciding what to watch.

            - Answer from tool results only. If a tool did not return something, say so \
            rather than filling the gap from general knowledge — you are describing one \
            household's disk, not films in general.
            - Name titles as the library names them.
            - Be brief. Two or three sentences, or a short list. This is read on a phone.
            - If nothing matches, say that plainly and suggest what to relax.
            - Questions about "I", "me" and "we" refer to the person asking; the tools \
            already know who that is.""";

    private final AiSearchProperties properties;
    private final AssistantTools tools;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private volatile AnthropicClient client;

    public AssistantService(AiSearchProperties properties, AssistantTools tools) {
        this.properties = properties;
        this.tools = tools;
    }

    public boolean isAvailable() {
        return properties.isAssistantUsable();
    }

    /**
     * @param question as typed
     * @param caller   resolved from the request; the model never names a profile
     */
    public AssistantAnswerDto ask(String question, Caller caller) {
        if (!isAvailable()) {
            return AssistantAnswerDto.unavailable(
                    "The assistant is switched off on this server.");
        }
        if (question == null || question.isBlank()) {
            return AssistantAnswerDto.unavailable("Ask a question about your library.");
        }

        List<MessageParam> conversation = new ArrayList<>();
        conversation.add(MessageParam.builder().role(MessageParam.Role.USER)
                .content(question).build());
        List<ToolCallDto> calls = new ArrayList<>();

        try {
            for (int turn = 0; turn < MAX_TURNS; turn++) {
                Message response = client().messages().create(request(conversation));
                conversation.add(response.toParam());

                List<ToolUseBlock> requested = response.content().stream()
                        .flatMap(block -> block.toolUse().stream())
                        .toList();
                if (requested.isEmpty()) {
                    return new AssistantAnswerDto(textOf(response), calls, true);
                }

                // Every result goes back in ONE user message. Splitting them teaches the
                // model to stop asking for tools in parallel.
                List<ContentBlockParam> results = new ArrayList<>(requested.size());
                for (ToolUseBlock call : requested) {
                    results.add(ContentBlockParam.ofToolResult(run(call, caller, calls)));
                }
                conversation.add(MessageParam.builder().role(MessageParam.Role.USER)
                        .contentOfBlockParams(results).build());
            }

            log.warn("Assistant gave up after {} turns on '{}'", MAX_TURNS, question);
            return new AssistantAnswerDto(
                    "I could not work that one out. Try asking for something more specific.",
                    calls, false);
        } catch (RuntimeException e) {
            log.warn("Assistant failed on '{}': {}", question, e.toString());
            return AssistantAnswerDto.unavailable(
                    "I could not reach the assistant just now. Everything else still works.");
        }
    }

    /**
     * Runs one tool call.
     *
     * <p>A tool that throws comes back as a tool result marked in error rather than
     * ending the conversation: the model can then say what went wrong, or try a different
     * tool, which is a better outcome for the person asking than a failed request.
     */
    private ToolResultBlockParam run(ToolUseBlock call, Caller caller, List<ToolCallDto> calls) {
        Map<String, Object> arguments = argumentsOf(call);
        String name = call.name();
        try {
            String result = switch (name) {
                case AssistantToolCatalog.SEARCH_LIBRARY -> tools.searchLibrary(arguments, caller);
                case AssistantToolCatalog.MOST_PLAYED -> tools.mostPlayed(arguments, caller);
                case AssistantToolCatalog.TASTE_PROFILE -> tools.tasteProfile(arguments, caller);
                case AssistantToolCatalog.LIST_COLLECTIONS ->
                        tools.listCollections(arguments, caller);
                // Unreachable unless a tool is added to the catalog and not here, which
                // is worth reporting rather than crashing on.
                default -> throw new IllegalArgumentException("No such tool: " + name);
            };
            calls.add(new ToolCallDto(name, arguments, false));
            return ToolResultBlockParam.builder()
                    .toolUseId(call.id())
                    .content(result)
                    .build();
        } catch (RuntimeException e) {
            log.warn("Tool {} failed: {}", name, e.toString());
            calls.add(new ToolCallDto(name, arguments, true));
            return ToolResultBlockParam.builder()
                    .toolUseId(call.id())
                    .content("That did not work: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private MessageCreateParams request(List<MessageParam> conversation) {
        return MessageCreateParams.builder()
                .model(properties.getModel())
                .maxTokens(MAX_TOKENS)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(effort()).build())
                // The system prompt and the tool list are identical on every turn of
                // every conversation, which is exactly the shape caching pays for.
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(SYSTEM_PROMPT)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .tools(AssistantToolCatalog.tools())
                .messages(conversation)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> argumentsOf(ToolUseBlock call) {
        try {
            // Parsed rather than read as a string: tool input escaping varies, and
            // matching on the serialised form is how that bites.
            Object parsed = mapper.convertValue(call._input(), Map.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (RuntimeException e) {
            log.warn("Unreadable arguments for {}: {}", call.name(), e.toString());
            return Map.of();
        }
    }

    private static String textOf(Message response) {
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(block -> block.text())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b)
                .trim();
        return text.isEmpty() ? "I do not have an answer for that." : text;
    }

    private OutputConfig.Effort effort() {
        try {
            return OutputConfig.Effort.of(properties.getEffort().toLowerCase(
                    java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return OutputConfig.Effort.LOW;
        }
    }

    /** Built on first use, for the reason given in the search translator. */
    private AnthropicClient client() {
        AnthropicClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                client = AnthropicOkHttpClient.builder()
                        .apiKey(properties.getApiKey())
                        .timeout(properties.getAssistantTimeout())
                        .build();
            }
            return client;
        }
    }
}
