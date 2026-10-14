package io.github.hectorvent.floci.services.floci.ui;

import io.github.hectorvent.floci.services.floci.ui.FlociUiManager.UiStatus;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

/**
 * Verifies the browser landing page content-negotiation on {@code /} and the
 * {@code /_floci/ui/status} contract. These do not spawn the sidecar (only
 * {@code GET /_floci/ui} does), so they need no Docker.
 */
@QuarkusTest
class UiLandingIntegrationTest {

    @InjectMock
    FlociUiManager uiManager;

    @BeforeEach
    void sidecarHasNotStarted() {
        when(uiManager.status()).thenReturn(new UiStatus(false, false, 0, null));
    }

    @ParameterizedTest
    @CsvSource({
        "[::1]:4566, http://[::1]:4500/",
        "[::1], http://[::1]:4500/",
        "[2001:db8::1]:4566, http://[2001:db8::1]:4500/",
        "[2001:db8::1], http://[2001:db8::1]:4500/",
        "floci.example:4566, http://floci.example:4500/",
        "floci.example, http://floci.example:4500/",
        "127.0.0.1:4566, http://127.0.0.1:4500/",
        "127.0.0.1, http://127.0.0.1:4500/"
    })
    void readyStatusUsesTheBrowserHostAndPublishedPort(String host, String url) {
        when(uiManager.status()).thenReturn(new UiStatus(true, true, 4500, null));

        for (String prefix : List.of("_floci", "_localstack")) {
            given()
                .header("Host", host)
                .accept("application/json")
            .when()
                .get("/" + prefix + "/ui/status")
            .then()
                .statusCode(200)
                .body("ready", is(true))
                .body("url", is(url))
                .body("error", nullValue());
        }
    }


    @Test
    void browserAcceptHtmlGetsLandingPage() {
        given()
            .accept("text/html")
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .contentType(containsString("text/html"))
            .body(containsString("Floci"))
            .body(containsString("Open Floci UI"));
    }

    @Test
    void sdkClientStillGetsListBucketsXml() {
        // No Accept: text/html — must fall through to S3 ListBuckets, unchanged.
        given()
            .accept("application/xml")
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("ListAllMyBucketsResult"));
    }

    @Test
    void wildcardAcceptIsTreatedAsSdkNotBrowser() {
        // RestAssured default Accept is */* — the strict text/html check must not match.
        given()
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("ListAllMyBucketsResult"));
    }

    @Test
    void statusEndpointReportsNotReadyBeforeStart() {
        given()
            .accept("application/json")
        .when()
            .get("/_floci/ui/status")
        .then()
            .statusCode(200)
            .body("ready", is(false));
    }
}
