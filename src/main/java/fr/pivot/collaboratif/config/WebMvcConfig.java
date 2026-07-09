package fr.pivot.collaboratif.config;

import fr.pivot.collaboratif.context.RequestPrincipalResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Spring MVC configuration for the collaboratif module.
 *
 * <p>Registers the {@link RequestPrincipalResolver} so that controller methods
 * can declare a {@code RequestPrincipal} parameter that is resolved automatically
 * from the validated {@code Authorization: Bearer} token (EN08.3).
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestPrincipalResolver requestPrincipalResolver;

    /**
     * Constructs the configuration with the shared {@link RequestPrincipalResolver} bean.
     *
     * @param requestPrincipalResolver the argument resolver to register
     */
    public WebMvcConfig(final RequestPrincipalResolver requestPrincipalResolver) {
        this.requestPrincipalResolver = requestPrincipalResolver;
    }

    /**
     * Registers custom argument resolvers.
     *
     * @param resolvers the list of resolvers to add to
     */
    @Override
    public void addArgumentResolvers(final List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(requestPrincipalResolver);
    }
}
