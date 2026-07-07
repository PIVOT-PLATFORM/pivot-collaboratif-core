package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.exception.InvalidTemplateIdException;
import fr.pivot.collaboratif.exception.TemplateNotFoundException;
import fr.pivot.collaboratif.exception.WhiteboardModuleDisabledException;
import fr.pivot.collaboratif.whiteboard.board.WhiteboardModuleCheck;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasElementValidator;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEvent;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEventRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEventType;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for whiteboard templates (US08.4.1): listing the gallery, resolving a
 * {@code templateId} to a global template, and initializing a newly created board from
 * one.
 *
 * <p>In the Socle, only global public templates exist ({@code tenant_id IS NULL}) — see
 * the US's Gate 1 resolution. There is no tenant-scoped template creation channel, so
 * every lookup here is scoped to global templates only.
 */
@Service
@Transactional(readOnly = true)
public class WhiteboardTemplateService {

    /** Nanosecond spacing applied between synthesized element timestamps, to guarantee a
     * strictly increasing {@code created_at} ordering even after PostgreSQL's microsecond
     * truncation (1 microsecond = 1000 nanoseconds). */
    private static final long ELEMENT_TIMESTAMP_SPACING_NANOS = 1_000L;

    private final WhiteboardTemplateRepository templateRepository;
    private final WhiteboardTemplateElementRepository templateElementRepository;
    private final CanvasEventRepository canvasEventRepository;
    private final CanvasElementValidator canvasElementValidator;
    private final WhiteboardModuleCheck moduleCheck;

    /**
     * Creates the service with all required dependencies.
     *
     * @param templateRepository        repository for template header persistence
     * @param templateElementRepository repository for template element persistence
     * @param canvasEventRepository     repository used to persist canvas events derived
     *                                   from template elements
     * @param canvasElementValidator    strict shape/text/image schema validator
     * @param moduleCheck                check for whiteboard module activation
     */
    public WhiteboardTemplateService(
            final WhiteboardTemplateRepository templateRepository,
            final WhiteboardTemplateElementRepository templateElementRepository,
            final CanvasEventRepository canvasEventRepository,
            final CanvasElementValidator canvasElementValidator,
            final WhiteboardModuleCheck moduleCheck) {
        this.templateRepository = templateRepository;
        this.templateElementRepository = templateElementRepository;
        this.canvasEventRepository = canvasEventRepository;
        this.canvasElementValidator = canvasElementValidator;
        this.moduleCheck = moduleCheck;
    }

    /**
     * Lists all global templates available in the gallery, ordered for display.
     *
     * @param tenantId the calling tenant's UUID (used only for the module-activation check)
     * @return the ordered list of template responses
     * @throws WhiteboardModuleDisabledException if the whiteboard module is inactive for the tenant
     */
    public List<TemplateResponse> listGlobalTemplates(final UUID tenantId) {
        if (!moduleCheck.isEnabled(tenantId)) {
            throw new WhiteboardModuleDisabledException(tenantId);
        }
        return templateRepository.findAllByTenantIdIsNullOrderByDisplayOrderAsc().stream()
                .map(TemplateResponse::from)
                .toList();
    }

    /**
     * Resolves a raw {@code templateId} request parameter to an existing global template.
     *
     * @param rawTemplateId the raw, caller-supplied {@code templateId} string
     * @return the resolved global template
     * @throws InvalidTemplateIdException if the value is not a syntactically valid UUID
     * @throws TemplateNotFoundException  if no global template exists for that UUID
     */
    public WhiteboardTemplate resolveGlobalTemplate(final String rawTemplateId) {
        UUID templateId;
        try {
            templateId = UUID.fromString(rawTemplateId);
        } catch (IllegalArgumentException e) {
            throw new InvalidTemplateIdException(rawTemplateId);
        }
        return templateRepository.findByIdAndTenantIdIsNull(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
    }

    /**
     * Initializes a newly created board's canvas from a template's elements.
     *
     * <p>Each element is re-validated against the strict shape/text/image whitelist
     * ({@link CanvasElementValidator}) before being converted into a persisted
     * {@code DRAW} {@link CanvasEvent} on the new board. Element ordering is preserved by
     * synthesizing strictly increasing timestamps ({@link #ELEMENT_TIMESTAMP_SPACING_NANOS}
     * apart), since {@code CanvasEventRepository} history queries order by
     * {@code created_at ASC}.
     *
     * @param template the already-resolved global template (see {@link #resolveGlobalTemplate})
     * @param boardId  the newly created board's UUID
     * @param tenantId the owning tenant's UUID
     * @param userId   the board creator's UUID, recorded as the emitting user
     */
    @Transactional
    public void initializeBoard(
            final WhiteboardTemplate template,
            final UUID boardId,
            final UUID tenantId,
            final UUID userId) {
        List<WhiteboardTemplateElement> elements =
                templateElementRepository.findAllByTemplateIdOrderByDisplayOrderAsc(template.getId());
        OffsetDateTime base = OffsetDateTime.now();
        List<CanvasEvent> events = new ArrayList<>(elements.size());
        for (int i = 0; i < elements.size(); i++) {
            WhiteboardTemplateElement element = elements.get(i);
            canvasElementValidator.validate(element.getElementType(), element.getPayload());
            events.add(new CanvasEvent(
                    UUID.randomUUID(),
                    boardId,
                    tenantId,
                    userId,
                    CanvasEventType.DRAW,
                    element.getPayload(),
                    base.plusNanos((long) i * ELEMENT_TIMESTAMP_SPACING_NANOS)));
        }
        canvasEventRepository.saveAll(events);
    }
}
