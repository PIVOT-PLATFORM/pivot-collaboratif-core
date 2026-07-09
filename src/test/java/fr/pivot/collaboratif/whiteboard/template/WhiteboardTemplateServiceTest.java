package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.exception.InvalidTemplateIdException;
import fr.pivot.collaboratif.exception.TemplateNotFoundException;
import fr.pivot.collaboratif.exception.WhiteboardModuleDisabledException;
import fr.pivot.collaboratif.whiteboard.board.WhiteboardModuleCheck;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasElementType;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasElementValidator;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEvent;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEventRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEventType;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WhiteboardTemplateService} covering all business branches
 * (US08.4.1): gallery listing, module-disabled guard, templateId resolution (malformed
 * UUID, unknown template), and board initialization ordering/validation.
 */
@ExtendWith(MockitoExtension.class)
class WhiteboardTemplateServiceTest {

    @Mock
    private WhiteboardTemplateRepository templateRepository;

    @Mock
    private WhiteboardTemplateElementRepository templateElementRepository;

    @Mock
    private CanvasEventRepository canvasEventRepository;

    @Mock
    private CanvasElementValidator canvasElementValidator;

    @Mock
    private WhiteboardModuleCheck moduleCheck;

    private WhiteboardTemplateService templateService;

    private static final Long TENANT_A = 100L;
    private static final Long USER_A = 1L;
    private static final UUID BOARD_A = UUID.randomUUID();

    /** Initialises the service under test with mocked dependencies. */
    @BeforeEach
    void setUp() {
        templateService = new WhiteboardTemplateService(
                templateRepository, templateElementRepository, canvasEventRepository,
                canvasElementValidator, moduleCheck);
    }

    // -------------------------------------------------------------------------
    // listGlobalTemplates()
    // -------------------------------------------------------------------------

    /**
     * Given the module is enabled, when listGlobalTemplates() is called,
     * then it returns the ordered global templates as response DTOs.
     */
    @Test
    void listGlobalTemplates_whenModuleEnabled_returnsOrderedTemplates() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        WhiteboardTemplate template = mockTemplate(UUID.randomUUID(), "BRAINSTORM", "Brainstorm");
        when(templateRepository.findAllByTenantIdIsNullOrderByDisplayOrderAsc())
                .thenReturn(List.of(template));

        List<TemplateResponse> responses = templateService.listGlobalTemplates(TENANT_A);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).code()).isEqualTo("BRAINSTORM");
    }

    /**
     * Given the module is disabled for the tenant, when listGlobalTemplates() is called,
     * then it throws {@link WhiteboardModuleDisabledException} (mapped to HTTP 403).
     */
    @Test
    void listGlobalTemplates_whenModuleDisabled_throwsWhiteboardModuleDisabledException() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(false);

        assertThatThrownBy(() -> templateService.listGlobalTemplates(TENANT_A))
                .isInstanceOf(WhiteboardModuleDisabledException.class);
    }

    // -------------------------------------------------------------------------
    // resolveGlobalTemplate()
    // -------------------------------------------------------------------------

    /**
     * Given a malformed UUID string, when resolveGlobalTemplate() is called,
     * then it throws {@link InvalidTemplateIdException} (mapped to HTTP 400
     * INVALID_TEMPLATE_ID) without querying the repository.
     */
    @Test
    void resolveGlobalTemplate_withMalformedUuid_throwsInvalidTemplateIdException() {
        assertThatThrownBy(() -> templateService.resolveGlobalTemplate("not-a-uuid"))
                .isInstanceOf(InvalidTemplateIdException.class);
    }

    /**
     * Given a well-formed UUID that does not match any global template, when
     * resolveGlobalTemplate() is called, then it throws {@link TemplateNotFoundException}
     * (mapped to HTTP 404, no existence leak).
     */
    @Test
    void resolveGlobalTemplate_withUnknownUuid_throwsTemplateNotFoundException() {
        UUID templateId = UUID.randomUUID();
        when(templateRepository.findByIdAndTenantIdIsNull(templateId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.resolveGlobalTemplate(templateId.toString()))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    /**
     * Given a well-formed UUID matching an existing global template, when
     * resolveGlobalTemplate() is called, then it returns that template.
     */
    @Test
    void resolveGlobalTemplate_withValidUuid_returnsTemplate() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = mockTemplate(templateId, "RETROSPECTIVE", "Retrospective");
        when(templateRepository.findByIdAndTenantIdIsNull(templateId))
                .thenReturn(Optional.of(template));

        WhiteboardTemplate resolved = templateService.resolveGlobalTemplate(templateId.toString());

        assertThat(resolved.getId()).isEqualTo(templateId);
    }

    // -------------------------------------------------------------------------
    // initializeBoard()
    // -------------------------------------------------------------------------

    /**
     * Given a template with several ordered elements, when initializeBoard() is called,
     * then each element is validated and persisted as a DRAW canvas event with strictly
     * increasing timestamps preserving the template's display order.
     */
    @Test
    void initializeBoard_persistsCanvasEventsInDisplayOrder() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = mockTemplate(templateId, "BRAINSTORM", "Brainstorm");
        WhiteboardTemplateElement element0 = mockElement(CanvasElementType.TEXT, "{\"content\":\"Title\"}");
        WhiteboardTemplateElement element1 = mockElement(CanvasElementType.SHAPE, "{\"shapeKind\":\"rectangle\"}");
        when(templateElementRepository.findAllByTemplateIdOrderByDisplayOrderAsc(templateId))
                .thenReturn(List.of(element0, element1));

        templateService.initializeBoard(template, BOARD_A, TENANT_A, USER_A);

        verify(canvasElementValidator).validate(CanvasElementType.TEXT, "{\"content\":\"Title\"}");
        verify(canvasElementValidator).validate(CanvasElementType.SHAPE, "{\"shapeKind\":\"rectangle\"}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CanvasEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(canvasEventRepository).saveAll(captor.capture());
        List<CanvasEvent> savedEvents = captor.getValue();

        assertThat(savedEvents).hasSize(2);
        assertThat(savedEvents).allSatisfy(event -> {
            assertThat(event.getBoardId()).isEqualTo(BOARD_A);
            assertThat(event.getTenantId()).isEqualTo(TENANT_A);
            assertThat(event.getUserId()).isEqualTo(USER_A);
            assertThat(event.getEventType()).isEqualTo(CanvasEventType.DRAW);
        });
        assertThat(savedEvents.get(0).getCreatedAt()).isBefore(savedEvents.get(1).getCreatedAt());
        assertThat(savedEvents.get(0).getPayload()).isEqualTo("{\"content\":\"Title\"}");
        assertThat(savedEvents.get(1).getPayload()).isEqualTo("{\"shapeKind\":\"rectangle\"}");
    }

    /**
     * Given a template with no elements, when initializeBoard() is called,
     * then it persists an empty list without invoking the validator.
     */
    @Test
    void initializeBoard_withNoElements_persistsEmptyList() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = mockTemplate(templateId, "EMPTY", "Empty");
        when(templateElementRepository.findAllByTemplateIdOrderByDisplayOrderAsc(templateId))
                .thenReturn(List.of());

        templateService.initializeBoard(template, BOARD_A, TENANT_A, USER_A);

        verify(canvasElementValidator, never()).validate(any(), any());
        verify(canvasEventRepository, times(1)).saveAll(eq(List.of()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private WhiteboardTemplate mockTemplate(final UUID id, final String code, final String name) {
        WhiteboardTemplate template = mock(WhiteboardTemplate.class);
        lenient().when(template.getId()).thenReturn(id);
        lenient().when(template.getCode()).thenReturn(code);
        lenient().when(template.getName()).thenReturn(name);
        return template;
    }

    private WhiteboardTemplateElement mockElement(final CanvasElementType type, final String payload) {
        WhiteboardTemplateElement element = mock(WhiteboardTemplateElement.class);
        lenient().when(element.getElementType()).thenReturn(type);
        lenient().when(element.getPayload()).thenReturn(payload);
        return element;
    }
}
