package com.github.messenger4j.spi;

import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class OkHttpMessengerHttpClient implements MessengerHttpClient {
    private static final MediaType APPLICATION_JSON_CHARSET_UTF_8 = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient okHttp;

    public OkHttpMessengerHttpClient(OkHttpClient okHttp) {
        this.okHttp = okHttp;
    }

    @Override
    public CompletionStage<HttpResponse> execute(HttpMethod httpMethod, String url, String jsonBody) {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();

        final Request.Builder requestBuilder = new Request.Builder().url(url);

        if (httpMethod != HttpMethod.GET) {
            final RequestBody requestBody = RequestBody.create(APPLICATION_JSON_CHARSET_UTF_8, jsonBody);
            requestBuilder.method(httpMethod.name(), requestBody);
        }

        final Request request = requestBuilder.build();

        okHttp.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                future.complete(new HttpResponse(response.code(), response.body().string()));
            }
        });

        return future;
    }
}
