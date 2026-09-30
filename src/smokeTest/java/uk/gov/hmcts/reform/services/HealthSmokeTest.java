package uk.gov.hmcts.reform.services;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

/**
 * Runs against the deployed service at {@code TEST_URL}, set by the CNP pipeline after each AKS deployment.
 */
class HealthSmokeTest {

    @BeforeAll
    static void setUp() {
        RestAssured.baseURI = System.getenv().getOrDefault("TEST_URL", "http://localhost:4550");
        RestAssured.useRelaxedHTTPSValidation();
    }

    @Test
    void serviceIsReady() {
        RestAssured.get("/health/readiness").then().statusCode(200).body("status", equalTo("UP"));
    }
}
