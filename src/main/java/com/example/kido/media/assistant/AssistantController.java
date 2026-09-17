package com.example.kido.media.assistant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.assistant.AssistantTools.Caller;
import com.example.kido.media.dto.AssistantDtos.AssistantAnswerDto;
import com.example.kido.media.dto.AssistantDtos.AssistantRequest;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;
import com.example.kido.user.AppUser;

/**
 * Ask a question about the library in your own words.
 *
 * <p>Who is asking is taken from the session here and passed to the tools as a
 * {@link Caller}. It is never a parameter and never something the model supplies, so
 * "films I haven't seen" resolves against whoever is holding the phone and there is no
 * wording that could make it resolve against somebody else.
 */
@RestController
@RequestMapping("/api/media/assistant")
public class AssistantController {

    /** Absent unless {@code app.media.ai.assistant-enabled} is on. */
    private final ObjectProvider<AssistantService> service;

    public AssistantController(ObjectProvider<AssistantService> service) {
        this.service = service;
    }

    /**
     * Whether there is an assistant to ask, so a client can hide the box rather than
     * offer something that will only apologise.
     */
    @GetMapping
    public AvailabilityDto availability() {
        AssistantService available = service.getIfAvailable();
        return new AvailabilityDto(available != null && available.isAvailable());
    }

    public record AvailabilityDto(boolean available) {}

    /**
     * Answers with the tool calls it made, so the answer can be checked rather than
     * taken on faith.
     *
     * <p>Always 200. An assistant that is off, unreachable or stuck returns something a
     * person can read — the rest of the app is unaffected either way, and a failed
     * request would tell a client to retry something that is not going to start working.
     */
    @PostMapping
    public AssistantAnswerDto ask(@AuthenticationPrincipal AppUser user,
                                  @ActiveProfile Profile profile,
                                  @RequestBody AssistantRequest request) {
        AssistantService available = service.getIfAvailable();
        if (available == null) {
            return AssistantAnswerDto.unavailable(
                    "The assistant is switched off on this server.");
        }
        return available.ask(request.question(), new Caller(user, profile));
    }
}
