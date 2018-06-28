package com.github.messenger4j.spi;

import java.io.IOException;
import java.util.concurrent.CompletionStage;

/**
 * @author Max Grabenhorst
 * @author Andriy Koretskyy
 * @since 1.0.0
 */
public interface MessengerHttpClient {

    CompletionStage<HttpResponse> execute(HttpMethod httpMethod, String url, String jsonBody);

    /**
     * @since 1.0.0
     */
    enum HttpMethod {
        GET,
        POST,
        DELETE
    }

    /**
     * @since 1.0.0
     */
    final class HttpResponse {

        private final int statusCode;
        private final String body;

        public HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int statusCode() {
            return statusCode;
        }

        public String body() {
            return body;
        }
    }
}
