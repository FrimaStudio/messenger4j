package com.github.messenger4j;

import com.github.messenger4j.exception.MessengerApiException;
import com.github.messenger4j.exception.MessengerApiExceptionFactory;
import com.github.messenger4j.exception.MessengerVerificationException;
import com.github.messenger4j.internal.gson.GsonFactory;
import com.github.messenger4j.messengerprofile.*;
import com.github.messenger4j.send.MessageResponse;
import com.github.messenger4j.send.MessageResponseFactory;
import com.github.messenger4j.send.Payload;
import com.github.messenger4j.spi.MessengerHttpClient;
import com.github.messenger4j.spi.MessengerHttpClient.HttpMethod;
import com.github.messenger4j.spi.OkHttpMessengerHttpClient;
import com.github.messenger4j.userprofile.UserProfile;
import com.github.messenger4j.userprofile.UserProfileFactory;
import com.github.messenger4j.webhook.Event;
import com.github.messenger4j.webhook.SignatureUtil;
import com.github.messenger4j.webhook.factory.EventFactory;
import com.google.gson.*;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.github.messenger4j.internal.gson.GsonUtil.Constants.*;
import static com.github.messenger4j.internal.gson.GsonUtil.getPropertyAsJsonArray;
import static com.github.messenger4j.internal.gson.GsonUtil.getPropertyAsString;
import static com.github.messenger4j.spi.MessengerHttpClient.HttpMethod.*;
import static java.util.Optional.empty;

/**
 * @author Max Grabenhorst
 * @since 1.0.0
 */
@Slf4j
public final class Messenger {

    /**
     * Constant for the {@code hub.mode} request parameter name.
     */
    public static final String MODE_REQUEST_PARAM_NAME = "hub.mode";

    /**
     * Constant for the {@code hub.challenge} request parameter name.
     */
    public static final String CHALLENGE_REQUEST_PARAM_NAME = "hub.challenge";

    /**
     * Constant for the {@code hub.verify_token} request parameter name.
     */
    public static final String VERIFY_TOKEN_REQUEST_PARAM_NAME = "hub.verify_token";

    /**
     * Constant for the {@code X-Hub-Signature} header name.
     */
    public static final String SIGNATURE_HEADER_NAME = "X-Hub-Signature";

    private static final String OBJECT_TYPE_PAGE = "page";
    private static final String HUB_MODE_SUBSCRIBE = "subscribe";

    private static final String FB_GRAPH_API_URL_MESSAGES = "https://graph.facebook.com/v2.11/me/messages?access_token=%s";
    private static final String FB_GRAPH_API_URL_MESSENGER_PROFILE = "https://graph.facebook.com/v2.11/me/messenger_profile?access_token=%s";
    private static final String FB_GRAPH_API_URL_USER = "https://graph.facebook.com/v2.11/%s?fields=first_name," +
            "last_name,profile_pic,locale,timezone,gender,is_payment_enabled,last_ad_referral&access_token=%s";

    public static class Builder {
        private String pageAccessToken;
        private String appSecret;
        private String verifyToken;

        private MessengerHttpClient httpClient = null;
        private Gson gson = null;
        private JsonParser jsonParser = null;

        public Builder pageAccessToken(@NonNull String pageAccessToken) {
            this.pageAccessToken = pageAccessToken;
            return this;
        }

        public Builder appSecret(@NonNull String appSecret) {
            this.appSecret = appSecret;
            return this;
        }

        public Builder verifyToken(@NonNull String verifyToken) {
            this.verifyToken = verifyToken;
            return this;
        }

        public Builder httpClient(@NonNull MessengerHttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder gson(@NonNull Gson gson) {
            this.gson = gson;
            return this;
        }

        public Builder jsonParser(@NonNull JsonParser jsonParser) {
            this.jsonParser = jsonParser;
            return this;
        }

        public Messenger build() {
            return new Messenger(
                    pageAccessToken,
                    appSecret,
                    verifyToken,
                    httpClient != null ? httpClient : new OkHttpMessengerHttpClient(new OkHttpClient()),
                    gson != null ? gson : GsonFactory.createGson(),
                    jsonParser != null ? jsonParser : new JsonParser()
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Deprecated
    public static Messenger create(@NonNull String pageAccessToken, @NonNull String appSecret, @NonNull String verifyToken) {
        return new Builder()
                .pageAccessToken(pageAccessToken)
                .appSecret(appSecret)
                .verifyToken(verifyToken)
                .build();
    }

    @Deprecated
    public static Messenger create(@NonNull String pageAccessToken, @NonNull String appSecret,
                                   @NonNull String verifyToken, @NonNull Optional<MessengerHttpClient> customHttpClient) {

        final Builder builder = new Builder()
                .pageAccessToken(pageAccessToken)
                .appSecret(appSecret)
                .verifyToken(verifyToken);

        customHttpClient.ifPresent(builder::httpClient);

        return builder.build();
    }

    private final String pageAccessToken;
    private final String appSecret;
    private final String verifyToken;
    private final String messagesRequestUrl;
    private final String messengerProfileRequestUrl;
    private final MessengerHttpClient httpClient;
    private final Gson gson;
    private final JsonParser jsonParser;

    private Messenger(
            @NonNull String pageAccessToken,
            @NonNull String appSecret,
            @NonNull String verifyToken,
            @NonNull MessengerHttpClient httpClient,
            @NonNull Gson gson,
            @NonNull JsonParser jsonParser
    ) {
        this.pageAccessToken = pageAccessToken;
        this.appSecret = appSecret;
        this.verifyToken = verifyToken;
        this.messagesRequestUrl = String.format(FB_GRAPH_API_URL_MESSAGES, pageAccessToken);
        this.messengerProfileRequestUrl = String.format(FB_GRAPH_API_URL_MESSENGER_PROFILE, pageAccessToken);
        this.httpClient = httpClient;

        this.gson = gson;
        this.jsonParser = jsonParser;
    }

    public CompletionStage<MessageResponse> send(@NonNull Payload payload) {
        return doRequest(POST, messagesRequestUrl, payload, MessageResponseFactory::create);
    }

    public void onReceiveEvents(@NonNull String requestPayload, String signature,
                                @NonNull Consumer<Event> eventHandler)
            throws MessengerVerificationException {

        if (signature != null) {
            if (!SignatureUtil.isSignatureValid(requestPayload, signature, this.appSecret)) {
                throw new MessengerVerificationException("Signature verification failed. " +
                        "Provided signature does not match calculated signature.");
            }
        } else {
            log.warn("No signature provided, hence the signature verification is skipped. THIS IS NOT RECOMMENDED");
        }

        final JsonObject payloadJsonObject = this.jsonParser.parse(requestPayload).getAsJsonObject();

        final Optional<String> objectType = getPropertyAsString(payloadJsonObject, PROP_OBJECT);
        if (!objectType.isPresent() || !objectType.get().equalsIgnoreCase(OBJECT_TYPE_PAGE)) {
            throw new IllegalArgumentException("'object' property must be 'page'. " +
                    "Make sure this is a page subscription");
        }

        final JsonArray entries = getPropertyAsJsonArray(payloadJsonObject, PROP_ENTRY)
                .orElseThrow(IllegalArgumentException::new);
        for (JsonElement entry : entries) {
            final JsonArray messagingEvents = getPropertyAsJsonArray(entry.getAsJsonObject(), PROP_MESSAGING)
                    .orElseThrow(IllegalArgumentException::new);
            for (JsonElement messagingEvent : messagingEvents) {
                final Event event = EventFactory.createEvent(messagingEvent.getAsJsonObject());
                eventHandler.accept(event);
            }
        }
    }

    public void verifyWebhook(@NonNull String mode, @NonNull String verifyToken) throws MessengerVerificationException {
        if (!mode.equals(HUB_MODE_SUBSCRIBE)) {
            throw new MessengerVerificationException("Webhook verification failed. Mode '" + mode + "' is invalid.");
        }
        if (!verifyToken.equals(this.verifyToken)) {
            throw new MessengerVerificationException("Webhook verification failed. Verification token '" +
                    verifyToken + "' is invalid.");
        }
    }

    public CompletionStage<UserProfile> queryUserProfile(@NonNull String userId) {
        final String requestUrl = String.format(FB_GRAPH_API_URL_USER, userId, pageAccessToken);
        return doRequest(GET, requestUrl, null, UserProfileFactory::create);
    }


    public CompletionStage<SetupResponse> updateSettings(@NonNull MessengerSettings messengerSettings) {

        return doRequest(POST, messengerProfileRequestUrl, messengerSettings, SetupResponseFactory::create);
    }

    public CompletionStage<SetupResponse> deleteSettings(@NonNull MessengerSettingProperty property, @NonNull MessengerSettingProperty... properties) {

        final List<MessengerSettingProperty> messengerSettingPropertyList = new ArrayList<>(properties.length + 1);
        messengerSettingPropertyList.add(property);
        messengerSettingPropertyList.addAll(Arrays.asList(properties));
        final DeleteMessengerSettingsPayload payload = DeleteMessengerSettingsPayload.create(messengerSettingPropertyList);
        return doRequest(DELETE, messengerProfileRequestUrl, payload, SetupResponseFactory::create);
    }

    private <R> CompletionStage<R> doRequest(HttpMethod httpMethod, String requestUrl, Object payload,
                                             Function<JsonObject, R> responseTransformer) {
        final String jsonBody = payload != null ? gson.toJson(payload) : null;

        final CompletableFuture<R> future = new CompletableFuture<>();

        httpClient.execute(httpMethod, requestUrl, jsonBody).whenComplete((httpResponse, ex) -> {
            if (ex != null) {
                future.completeExceptionally(ex);
                return;
            }

            final JsonObject responseJsonObject = this.jsonParser.parse(httpResponse.body()).getAsJsonObject();

            if (responseJsonObject.size() == 0) {
                future.completeExceptionally(new MessengerApiException("The response JSON does not contain any key/value pair",
                        empty(), empty(), empty()));
            } else if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300) {
                future.complete(responseTransformer.apply(responseJsonObject));
            } else {
                future.completeExceptionally(MessengerApiExceptionFactory.create(responseJsonObject));
            }
        });

        return future;
    }
}
