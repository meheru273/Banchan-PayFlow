package com.payflow.payment;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The brief's REST Assured suite: happy path, idempotent replay, and the bad
 * webhook signature — expressed as given/when/then API contracts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class PaymentApiRestAssuredTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
    }

    private String firstWalletId() {
        return given().get("/api/v1/wallets").then().statusCode(200).extract().path("[0].id");
    }

    @Test
    void happyPathCreatesAndCompletesAPayment() {
        String walletId = firstWalletId();
        String key = "ra-" + UUID.randomUUID();

        String paymentId = given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", key)
                .body("{\"walletId\":\"" + walletId + "\",\"amount\":7.70,\"currency\":\"GBP\",\"cardRef\":\"tok_visa_4242\"}")
                .when()
                .post("/api/v1/payments")
                .then()
                .statusCode(201)
                .header("Idempotency-Replayed", equalTo("false"))
                .header("Location", notNullValue())
                .body("status", equalTo("PENDING"))
                .extract().path("id");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                given().get("/api/v1/payments/" + paymentId)
                        .then().statusCode(200).body("status", equalTo("COMPLETED")));
    }

    @Test
    void idempotentReplayReturnsTheStoredResponse() {
        String walletId = firstWalletId();
        String key = "ra-" + UUID.randomUUID();
        String body = "{\"walletId\":\"" + walletId + "\",\"amount\":3.30,\"currency\":\"GBP\"}";

        String original = given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(body)
                .post("/api/v1/payments").then().statusCode(201).extract().asString();

        String replayed = given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(body)
                .when().post("/api/v1/payments")
                .then().statusCode(201)
                .header("Idempotency-Replayed", equalTo("true"))
                .extract().asString();

        assertThat(replayed).isEqualTo(original);

        // Same key, different body → 409 problem detail
        given().contentType(ContentType.JSON).header("Idempotency-Key", key)
                .body("{\"walletId\":\"" + walletId + "\",\"amount\":99.00,\"currency\":\"GBP\"}")
                .when().post("/api/v1/payments")
                .then().statusCode(409)
                .body("title", equalTo("Idempotency Conflict"));
    }

    @Test
    void badWebhookSignatureIsRejected() {
        String walletId = firstWalletId();
        String paymentId = given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "ra-" + UUID.randomUUID())
                .body("{\"walletId\":\"" + walletId + "\",\"amount\":5.00,\"currency\":\"GBP\"}")
                .post("/api/v1/payments").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .header("X-Webhook-Timestamp", String.valueOf(System.currentTimeMillis() / 1000))
                .header("X-Webhook-Signature", "0".repeat(64))
                .body("{\"paymentId\":\"" + paymentId + "\",\"providerRef\":\"SIM-x\",\"status\":\"succeeded\"}")
                .when().post("/api/v1/webhooks/provider")
                .then().statusCode(401)
                .body("title", equalTo("Webhook Verification Failed"));
    }

    @Test
    void validationFailureListsFieldErrors() {
        given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "ra-" + UUID.randomUUID())
                .body("{\"amount\":-1,\"currency\":\"x\"}")
                .when().post("/api/v1/payments")
                .then().statusCode(400)
                .body("errors", hasKey("walletId"))
                .body("errors", hasKey("amount"));
    }
}
