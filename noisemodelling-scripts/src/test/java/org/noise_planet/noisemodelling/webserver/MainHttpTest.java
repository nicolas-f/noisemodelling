package org.noise_planet.noisemodelling.webserver;

import io.javalin.Javalin;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.http.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainHttpTest {

    /**
     * A Javalin instance used to manage the HTTP server lifecycle and handle HTTP routes
     * for the web application during testing.
     *
     * This static variable is initialized and configured in the {@code setUp} method,
     * and is responsible for serving HTTP routes used by the test cases defined in the
     * {@link MainHttpTest} class.
     *
     * It supports the execution of various HTTP-based operations such as handling requests
     * for WPS capabilities, process descriptions, and WPS execution, as verified in the test methods.
     */
    private static Javalin app;

    /**
     * The default port number on which the HTTP server will listen.
     *
     * This constant defines the port number used to establish server connections.
     * It is primarily used during the setup phase of the server and
     * in test cases to ensure proper server communication and resource access.
     *
     * Modifying this value may require corresponding updates in client-side
     * configurations and resource endpoints to maintain compatibility.
     */
    private static final int PORT = 8000;
    /**
     * The base URL for the OWS (OGC Web Services) endpoints used in the test cases.
     * It dynamically constructs the URL using the `localhost` domain and the value
     * of the `PORT` variable defined in the class.
     *
     * This URL serves as the base endpoint for various HTTP requests made during
     * the execution of the test suite and is primarily used for testing capabilities,
     * descriptions, and process execution of the WPS (Web Processing Service).
     */
    private static final String BASE_URL = "http://localhost:" + PORT + "/ows";

    /**
     * Sets up the test environment for the HTTP-based tests.
     * This method is executed once before all tests in the test class.
     *
     * During the setup, a Javalin server instance is initialized by invoking the
     * {@code Main.startServer} method with the browser opening disabled. The server
     * instance is assigned to the static field {@code app}.
     *
     * @throws IOException if an I/O error occurs while starting the server.
     */
    @BeforeAll
    public static void setUp() throws IOException {
        app = Main.startServer(false);
    }

    /**
     * Tears down the testing environment after all tests have been executed.
     *
     * This method is annotated with {@code @AfterAll}, meaning it will be executed
     * once after all test cases in the test class have been run. It is responsible
     * for performing cleanup operations such as stopping the application instance
     * if it has been initialized during the test setup.
     *
     * If the application instance {@code app} is not null, this method will invoke
     * the {@code stop()} method to cease its operations and release any resources
     * associated with it. This ensures a proper shutdown and prevents resource leaks.
     */
    @AfterAll
    public static void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    @Order(1)
    void testGetWPSCapabilities() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String serviceParam = URLEncoder.encode("WPS", StandardCharsets.UTF_8);
        String requestParam = URLEncoder.encode("GetCapabilities", StandardCharsets.UTF_8);
        URI uri = URI.create(BASE_URL + "?service=" + serviceParam + "&VERSION=1.0.0&request=" + requestParam);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertNotNull(body);
        assertTrue(body.contains("<wps:Capabilities "));
        assertTrue(body.contains("NoiseModelling:Road_Emission_from_Traffic"));
        assertTrue(body.contains("Computes the propagation from the sounds sources to the receivers"));
    }

    /**
     * Tests the DescribeProcess operation of the Web Processing Service (WPS).
     *
     * This method performs the following steps:
     * - Creates an HTTP GET request for the WPS DescribeProcess operation by specifying
     *   the service as "WPS", the request type as "DescribeProcess", and an identifier
     *   representing the process "Geometric_Tools:Screen_to_building".
     * - Sends the request using {@link HttpClient} and retrieves the response.
     * - Validates that the HTTP response status code is 200 (OK).
     * - Ensures that the response body is not null.
     * - Checks that the response body contains:
     *   - The XML element `<wps:ProcessDescriptions>`.
     *   - A description for the process, mentioning "Convert screens to building format."
     *   - Detailed information about the process functionality, including conversions and
     *     optional merging with a building table layer.
     *
     * @throws Exception if an error occurs during the HTTP request, response handling, or validation steps.
     */
    @Test
    @Order(2)
    void testGetWPSDescribeProcess() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String serviceParam = URLEncoder.encode("WPS", StandardCharsets.UTF_8);
        String requestParam = URLEncoder.encode("DescribeProcess", StandardCharsets.UTF_8);
        URI uri = URI.create(BASE_URL + "?service=" + serviceParam + "&VERSION=1.0.0&request=" + requestParam + "&identifier=Geometric_Tools:Screen_to_building");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertNotNull(body);
        assertTrue(body.contains("wps:ProcessDescriptions "));
        assertTrue(body.contains("Convert screens to building format."));
        assertTrue(body.contains("Convert the screens to the building format.  A width of 10 cm will be defined. If you also give a building table, this WPS script allows you to merge the two layers together"));
    }

    /**
     * Tests the Execute operation of the Web Processing Service (WPS) using a POST request.
     *
     * This method performs the following actions:
     * - Constructs an HTTP POST request with an XML payload for executing the
     *   "Database_Manager:Clean_Database" process.
     * - Sends the request to the WPS server using {@link HttpClient}.
     * - Validates that the HTTP response status code is 200 (OK).
     * - Ensures that the response body is not null.
     * - Verifies that the response body contains the expected "result" element.
     *
     * The XML payload specifies the WPS service, version, process identifier, input
     * parameters, and raw data output format for the Execute operation.
     *
     * @throws Exception if an error occurs during the HTTP request, response handling, or validation steps.
     */
    @Test
    @Order(3)
    void testPostWPSExecute() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String requestBody ="<p0:Execute xmlns:p0=\"http://www.opengis.net/wps/1.0.0\" " +
                "service=\"WPS\" version=\"1.0.0\"><p1:Identifier xmlns:p1=\"http://www.opengis.net/ows/1.1\">Database_Manager:Clean_Database</p1:Identifier><p0:DataInputs><p0:Input><p1:Identifier xmlns:p1=\"http://www.opengis.net/ows/1.1\">areYouSure</p1:Identifier><p0:Data><p0:LiteralData>true</p0:LiteralData></p0:Data></p0:Input></p0:DataInputs><p0:ResponseForm><p0:RawDataOutput><p1:Identifier xmlns:p1=\"http://www.opengis.net/ows/1.1\">result</p1:Identifier></p0:RawDataOutput></p0:ResponseForm></p0:Execute>";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .header("Content-Type", "text/xml")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertNotNull(response.body());
        assertTrue(response.body().contains("result"));
    }
}
