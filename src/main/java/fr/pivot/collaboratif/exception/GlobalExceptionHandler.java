package fr.pivot.collaboratif.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler that maps domain exceptions to RFC 7807 Problem Detail responses.
 *
 * <p>Handles board-specific domain exceptions ({@link BoardNotFoundException},
 * {@link BoardAccessDeniedException}, {@link WhiteboardModuleDisabledException}) as well as
 * Spring MVC validation failures ({@link MethodArgumentNotValidException}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Returns HTTP 404 when a board is not found, belongs to another tenant,
     * or the caller is not a member.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(BoardNotFoundException.class)
    public ProblemDetail handleBoardNotFound(final BoardNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Board not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 403 when the caller is a member but lacks the required role.
     *
     * @param ex the thrown exception
     * @return a 403 problem detail
     */
    @ExceptionHandler(BoardAccessDeniedException.class)
    public ProblemDetail handleBoardAccessDenied(final BoardAccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Access denied");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 403 when the whiteboard module is disabled for the caller's tenant.
     *
     * @param ex the thrown exception
     * @return a 403 problem detail
     */
    @ExceptionHandler(WhiteboardModuleDisabledException.class)
    public ProblemDetail handleModuleDisabled(final WhiteboardModuleDisabledException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Module disabled");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property for board title
     * validation failures.
     *
     * <p>The {@code code} value is extracted from the first field error's default message,
     * which is set to {@code "INVALID_TITLE"} by the validation constraints on
     * {@code CreateBoardRequest} and {@code RenameBoardRequest}.
     *
     * @param ex the validation exception
     * @return a 400 problem detail with a {@code code} property
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(final MethodArgumentNotValidException ex) {
        String firstMessage = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(fe -> fe.getDefaultMessage())
                .orElse("VALIDATION_ERROR");
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setProperties(Map.of("code", firstMessage));
        return problem;
    }

    /**
     * Returns HTTP 400 for parameter constraint violations (e.g. {@code @Min} / {@code @Max}
     * on request parameters).
     *
     * @param ex the constraint violation exception
     * @return a 400 problem detail
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(final ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 400 for invalid argument values (defensive service-layer guard).
     *
     * @param ex the illegal argument exception
     * @return a 400 problem detail
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(final IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Bad request");
        problem.setDetail(ex.getMessage());
        return problem;
    }
}
