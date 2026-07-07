package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.context.RequestPrincipal;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing the whiteboard template gallery under
 * {@code /whiteboard/templates} (US08.4.1).
 *
 * <p>Board initialization from a template is exposed on
 * {@code POST /whiteboard/boards?templateId=...} — see {@code BoardController}, which
 * reuses the existing US08.1.1 board-creation flow rather than duplicating it.
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/whiteboard/templates}.
 */
@RestController
@RequestMapping("/whiteboard/templates")
public class WhiteboardTemplateController {

    private final WhiteboardTemplateService templateService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param templateService the template business logic service
     */
    public WhiteboardTemplateController(final WhiteboardTemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * Lists all global templates available in the "New board" gallery.
     *
     * @param principal the resolved caller identity (user + tenant)
     * @return the ordered list of available templates
     */
    @GetMapping
    public List<TemplateResponse> list(final RequestPrincipal principal) {
        return templateService.listGlobalTemplates(principal.tenantId());
    }
}
