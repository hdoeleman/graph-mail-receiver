package org.example.graphmail.rest;

import org.example.graphmail.config.GraphProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizationFailureHandler;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.ClientRegistrationIdProcessor;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * REST mode wiring: registers {@link GraphMailApi} as a Spring HTTP service client and lets Spring Security's OAuth2
 * client fetch, cache and refresh its app-only token (client credentials, refreshed 60 s before expiry).
 * <p>
 * The {@code spring.http.clients.*} settings (timeouts, the JDK client) apply to both: Boot's HTTP service client
 * auto-configuration sets up the Graph group's client with them, and the token request uses a client built from Boot's
 * {@code RestClient.Builder}. When Graph rejects a token (401 or 403), the cached token is dropped, so the next
 * request fetches a new one. App roles are fixed in a token when it is issued, so this makes
 * permission changes such as admin consent take effect right away.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = GraphRestClientConfig.GROUP, types = GraphMailApi.class)
public class GraphRestClientConfig {

    static final String GROUP = "graph";
    static final String REGISTRATION_ID = "graph";

    private static final Logger log = LoggerFactory.getLogger(GraphRestClientConfig.class);

    /** App-only tokens belong to the app, not to whoever clicked Pull, so every request shares this principal. */
    private static final Authentication APP = new AnonymousAuthenticationToken(
            "graph-mail-receiver", "graph-mail-receiver", AuthorityUtils.createAuthorityList("ROLE_APP"));

    /** Holds the Graph registration only when its settings are complete; otherwise REST mode is unavailable. */
    @Bean
    ClientRegistrationRepository clientRegistrationRepository(GraphProperties props) {
        ClientRegistration graph = props.missingGraphSettings().isEmpty()
                ? ClientRegistration.withRegistrationId(REGISTRATION_ID)
                        .clientId(props.clientId())
                        .clientSecret(props.clientSecret())
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .scope(props.scope())
                        .tokenUri(props.tokenUri())
                        .build()
                : null;
        return registrationId -> REGISTRATION_ID.equals(registrationId) ? graph : null;
    }

    // The OAuth2AuthorizedClientService (the token cache) is Boot's: an in-memory one, created because the
    // ClientRegistrationRepository above exists.

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                          OAuth2AuthorizedClientService authorizedClients,
                                                          ObjectProvider<RestClient.Builder> builders) {
        // Spring Security's default token client builds its RestClient with RestClient.builder(), which ignores the
        // spring.http.clients settings (it would run on Reactor Netty with its own timeouts). This one has the same
        // converters and error handling, on top of Boot's builder.
        var tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(builders.getObject()
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .requestInterceptor(HttpExchangeLogging.INSTANCE)
                .build());
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(clientCredentials -> clientCredentials.accessTokenResponseClient(tokenClient))
                .build());
        return manager;
    }

    @Bean
    RestClientHttpServiceGroupConfigurer graphGroupConfigurer(GraphProperties props,
                                                              OAuth2AuthorizedClientManager manager,
                                                              OAuth2AuthorizedClientService authorizedClients) {
        var oauth2 = new OAuth2ClientHttpRequestInterceptor(manager);
        oauth2.setPrincipalResolver(request -> APP);
        oauth2.setAuthorizationFailureHandler(dropTokenOnRejection(authorizedClients));
        return groups -> {
            groups.filterByName(GROUP).forEachClient((group, builder) -> builder
                    .baseUrl(props.graphBaseUrl())
                    .requestInterceptor(oauth2)
                    .requestInterceptor(HttpExchangeLogging.INSTANCE));
            groups.filterByName(GROUP).forEachProxyFactory((group, factory) ->
                    factory.httpRequestValuesProcessor(ClientRegistrationIdProcessor.DEFAULT_INSTANCE));
        };
    }

    /**
     * The interceptor drops nothing by default, and Spring Security's ready-made handler
     * ({@code RemoveAuthorizedClientOAuth2AuthorizationFailureHandler}) covers {@code invalid_token} (401) and
     * {@code invalid_grant}, not {@code insufficient_scope} (403). This one drops the token after both, because a 403
     * is what Graph returns for a token issued before admin consent.
     */
    private static OAuth2AuthorizationFailureHandler dropTokenOnRejection(OAuth2AuthorizedClientService authorizedClients) {
        return (exception, principal, attributes) -> {
            if (exception instanceof ClientAuthorizationException rejected) {
                log.debug("Graph rejected the access token ({}), dropping it", exception.getError().getErrorCode());
                authorizedClients.removeAuthorizedClient(rejected.getClientRegistrationId(), principal.getName());
            }
        };
    }
}
