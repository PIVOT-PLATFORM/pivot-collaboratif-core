package fr.pivot.collaboratif;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entree du module Collaboratif (whiteboard, quiz, session live, formulaire).
 *
 * <p>Bootstrap uniquement : ce squelette ne porte encore aucune logique metier. La securite
 * (validation des opaque tokens emis par pivot-core, {@code TenantContext}) sera branchee une
 * fois {@code fr.pivot:pivot-core-starter} reellement publie (voir CLAUDE.md, section
 * "Dependance pivot-core-starter — etat reel").
 */
@SpringBootApplication(scanBasePackages = "fr.pivot.collaboratif")
public class PivotCollaboratifApplication {

    /** Demarre l'application Spring Boot. */
    public static void main(String[] args) {
        SpringApplication.run(PivotCollaboratifApplication.class, args);
    }
}
