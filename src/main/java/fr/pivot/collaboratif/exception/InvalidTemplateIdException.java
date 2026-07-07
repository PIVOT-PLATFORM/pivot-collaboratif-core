package fr.pivot.collaboratif.exception;

/**
 * Thrown when the {@code templateId} query parameter on
 * {@code POST /whiteboard/boards} is not a syntactically valid UUID.
 *
 * <p>Mapped to HTTP 400 with {@code { "code": "INVALID_TEMPLATE_ID" } } by
 * {@code GlobalExceptionHandler}, distinguishing a malformed identifier from a
 * well-formed but non-existent one ({@link TemplateNotFoundException}, HTTP 404).
 */
public class InvalidTemplateIdException extends RuntimeException {

    /**
     * Creates an invalid-template-id exception for the given raw parameter value.
     *
     * @param rawTemplateId the raw, unparseable {@code templateId} value
     */
    public InvalidTemplateIdException(final String rawTemplateId) {
        super("Malformed templateId: " + rawTemplateId);
    }
}
