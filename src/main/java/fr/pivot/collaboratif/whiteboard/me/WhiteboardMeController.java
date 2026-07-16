package fr.pivot.collaboratif.whiteboard.me;

import fr.pivot.collaboratif.context.RequestPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the authenticated caller's own {@code public.users.id} to the frontend
 * (US08.12.2 enabler).
 *
 * <p>The dot-vote UI needs to know which votes in a {@code VoteSession} belong to the current
 * user (remaining vote budget, own dots, un-casting). That identity is never carried on the
 * realtime channel — it is resolved server-side from the authenticated principal and the
 * room-wide {@code board:state} snapshot is broadcast to every participant, so it cannot deliver
 * a per-client self identity. This tiny REST endpoint fills the gap, server-authoritatively and
 * durably across reconnects.
 *
 * <p>The identity comes exclusively from the {@link RequestPrincipal} resolved from the bearer
 * token (EN08.3) — never from the request. No board scope is involved: the caller's user id is
 * the same regardless of the board being viewed.
 */
@RestController
@RequestMapping("/whiteboard/me")
public class WhiteboardMeController {

    /**
     * Returns the authenticated caller's own user identity.
     *
     * @param principal the resolved caller identity (user + tenant), from the bearer token
     * @return the caller's {@code public.users.id}, rendered as a string to match the
     *         frontend's vote payloads
     */
    @GetMapping
    public MeResponse me(final RequestPrincipal principal) {
        return new MeResponse(String.valueOf(principal.userId()));
    }

    /**
     * Response body for {@code GET /whiteboard/me}.
     *
     * @param userId the caller's {@code public.users.id} as a string
     */
    public record MeResponse(String userId) {
    }
}
