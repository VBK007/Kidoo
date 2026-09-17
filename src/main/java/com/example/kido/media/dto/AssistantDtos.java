package com.example.kido.media.dto;

import java.util.List;
import java.util.Map;

/**
 * The assistant on the wire.
 *
 * <p>The tool calls come back with the answer. An assistant that will not say what it
 * looked at is one nobody can check: a person reading "you have three unwatched Tamil
 * films" should be able to see that it searched rather than remembered, and a wrong
 * answer should point at which lookup went wrong rather than at the whole feature.
 */
public final class AssistantDtos {

    private AssistantDtos() {}

    /**
     * One lookup the assistant made.
     *
     * @param arguments what it asked for — the filters it chose, which is where a
     *                  misunderstanding usually shows
     * @param failed    true when the tool errored; the assistant is told and may recover,
     *                  so this is visible rather than fatal
     */
    public record ToolCallDto(String tool, Map<String, Object> arguments, boolean failed) {}

    /**
     * @param answered false when the assistant gave up, was switched off, or could not be
     *                 reached — the text is still something worth showing, so a client
     *                 renders it either way and uses this only to decide whether to
     *                 offer a retry
     */
    public record AssistantAnswerDto(String answer, List<ToolCallDto> toolCalls,
                                     boolean answered) {

        public static AssistantAnswerDto unavailable(String because) {
            return new AssistantAnswerDto(because, List.of(), false);
        }
    }

    /** @param question as typed, in the person's own words */
    public record AssistantRequest(String question) {}
}
