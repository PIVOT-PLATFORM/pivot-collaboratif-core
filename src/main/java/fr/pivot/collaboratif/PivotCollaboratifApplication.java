package fr.pivot.collaboratif;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entree du module Collaboratif (whiteboard, quiz, session live, formulaire).
 *
 * <p>Porte le noyau whiteboard (F08.x/EN08.x) : creation/partage/roles de tableaux, canvas
 * temps reel via WebSocket STOMP, templates. L'authentification est branchee sur
 * {@code fr.pivot:pivot-core-starter} (EN08.3) : {@code RequestPrincipalResolver} valide un
 * bearer opaque token directement contre {@code public.access_tokens}/{@code public.users}/
 * {@code public.tenants} (algorithme duplique depuis {@code pivot-core}, jamais d'appel
 * reseau — ADR-022), plus aucun mecanisme d'en-tetes client non verifies.
 */
@SpringBootApplication(scanBasePackages = "fr.pivot.collaboratif")
public class PivotCollaboratifApplication {

    /** Demarre l'application Spring Boot. */
    public static void main(String[] args) {
        SpringApplication.run(PivotCollaboratifApplication.class, args);
    }
}
