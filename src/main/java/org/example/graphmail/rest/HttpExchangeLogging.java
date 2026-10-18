package org.example.graphmail.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/** Logs each outgoing request and its status and latency at DEBUG. Headers and bodies, which hold secrets, are not logged. */
final class HttpExchangeLogging implements ClientHttpRequestInterceptor {

    static final HttpExchangeLogging INSTANCE = new HttpExchangeLogging();

    private static final Logger log = LoggerFactory.getLogger(HttpExchangeLogging.class);

    private HttpExchangeLogging() {
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        long start = System.nanoTime();
        log.debug("--> {} {}", request.getMethod(), request.getURI());
        try {
            ClientHttpResponse response = execution.execute(request, body);
            log.debug("<-- {} {} in {} ms", response.getStatusCode(), request.getURI(), millisSince(start));
            return response;
        } catch (IOException e) {
            log.debug("<-- failed {} after {} ms: {}", request.getURI(), millisSince(start), e.toString());
            throw e;
        }
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
