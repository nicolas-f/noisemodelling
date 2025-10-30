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
     * The base URL for the server connection used during HTTP-based tests.
     *
     * This variable is initialized to point to the local host, and the port can be appended
     * dynamically when required. It serves as the foundational URL for constructing full
     * endpoints for testing various HTTP requests within the test suite.
     *
     * Example usage includes forming URLs for GET, POST, or other HTTP methods
     * targeting the application under test.
     *
     * This variable is private and static, ensuring it is consistent across all instances
     * of the test class and cannot be directly modified outside its scope.
     */
    private static  String BASE_URL = "http://localhost:";

    /**
     * Sets up the testing environment before all test methods are executed.
     *
     * This method is annotated with {@code @BeforeAll}, indicating that it is executed once
     * before any other test method in the test class. It is responsible for initializing
     * and starting the application server in a test-specific configuration, disabling
     * the browser auto-launch to facilitate backend testing.
     *
     * The method performs the following setup operations:
     * 1. Starts the server by invoking {@code Main.startServer(false)}, which initializes
     *    a Javalin server instance without opening a browser.
     * 2. Assigns the base URL for the tests by obtaining the server's port and appending
     *    the path "/ows" to it, enabling HTTP requests targeting the server.
     *
     * @throws IOException if an error occurs while starting the server or during related I/O operations.
     */
    @BeforeAll
    public static void setUp() throws IOException {
        app = Main.startServer(false);
        BASE_URL = app.port() + "/ows";
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

    /**
     * Tests the GetCapabilities response of the Web Processing Service (WPS).
     *
     * This method performs the following steps:
     * - Constructs an HTTP GET request for the WPS GetCapabilities operation.
     * - Sends the request using the {@link HttpClient} and obtains the response.
     * - Validates that the HTTP status code is 200 (OK).
     * - Verifies that the response body is not null.
     * - Checks that the response body contains specific XML elements and expected
     *   content related to the WPS capabilities, such as:
     *   - The presence of `<wps:Capabilities>` indicating a valid capabilities XML.
     *   - Process information, including `NoiseModelling:Road_Emission_from_Traffic`.
     *   - Descriptions for specific process functionalities, e.g., propagation
     *     computations for sound sources to receivers.
     *
     * @throws Exception if an error occurs during the HTTP request or the response validation.
     */
    @Test
    @Order(1)
    void testGetWPSCapabilities() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String serviceParam = URLEncoder.encode("WPS", StandardCharsets.UTF_8);
        String requestParam = URLEncoder.encode("GetCapabilities", StandardCharsets.UTF_8);
        URI uri = URI.create(BASE_URL + "?service=" + serviceParam + "&VERSION=1.0.0&request=" + requestParam);
        System.out.println("uri: "+ uri);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        System.out.println("body: "+ body);
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
        System.out.println("body: "+ body);
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
