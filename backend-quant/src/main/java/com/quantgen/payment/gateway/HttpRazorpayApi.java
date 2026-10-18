package com.quantgen.payment.gateway;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link RazorpayApi} over HTTPS with basic auth (key id : key secret). Response bodies are never logged. */
@Component
class HttpRazorpayApi implements RazorpayApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final RazorpayProperties props;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    HttpRazorpayApi(RazorpayProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public String createOrder(long amountPaise, String receipt) {
        String body = json.writeValueAsString(Map.of("amount", amountPaise, "currency", "INR", "receipt", receipt,
                "notes", Map.of("topupId", receipt)));
        JsonNode order = call(HttpRequest.newBuilder(URI.create(props.baseUrl() + "/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        return order.get("id").asString();
    }

    @Override
    public Payment fetchPayment(String paymentId) {
        JsonNode p = call(HttpRequest.newBuilder(URI.create(props.baseUrl() + "/payments/"
                + URLEncoder.encode(paymentId, StandardCharsets.UTF_8))).GET());
        return new Payment(p.get("id").asString(), p.path("order_id").asString(null), p.get("amount").asLong(),
                p.get("currency").asString(), p.get("status").asString());
    }

    private JsonNode call(HttpRequest.Builder builder) {
        String auth = Base64.getEncoder().encodeToString((props.keyId() + ":" + props.keySecret())
                .getBytes(StandardCharsets.UTF_8));
        HttpRequest request = builder.header("Authorization", "Basic " + auth).timeout(TIMEOUT).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new GatewayException("Razorpay answered " + response.statusCode(), null);
            }
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("Interrupted calling Razorpay", e);
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("Could not reach Razorpay", e);
        }
    }
}
