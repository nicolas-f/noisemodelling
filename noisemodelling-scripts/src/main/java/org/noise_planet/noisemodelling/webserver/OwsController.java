package org.noise_planet.noisemodelling.webserver;

import io.javalin.http.Context;
import net.opengis.wps10.ExecuteType;
import org.geotools.wps.WPSConfiguration;
import org.geotools.xsd.Parser;
import org.locationtech.jts.geom.Geometry;
import org.noise_planet.noisemodelling.scripts.Main;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The OwsController class handles requests for OGC Web Services (OWS), including
 * WPS (Web Processing Service), WFS (Web Feature Service), and WCS (Web Coverage Service).
 * It provides functionalities for GET and POST requests, depending on the OWS service and
 * operation type.
 */
public class OwsController {

    /**
     * Manages the database operations and configurations required for the web server.
     *
     * This instance is responsible for handling interactions with the database, such as
     * connecting to the database, initializing GIS-specific functions, and maintaining
     * the current database name and directory path.
     *
     * It ensures the proper setup and creation of directories for database storage during
     * its initialization. The {@code DataBaseManager} also facilitates the retrieval of
     * active database information, including the name and directory path, and supports
     * concurrent database access using H2's auto-server mode.
     */
    DataBaseManager dataBaseManager = new DataBaseManager();
    /**
     * A static collection of {@link ScriptWrapper} objects representing the
     * scripts available for the Web Processing Service (WPS). Each script is wrapped
     * in a {@link ScriptWrapper}, which encapsulates its metadata, inputs, outputs,
     * and logic to facilitate execution.
     *
     * This list serves as a central repository of scripts that can be dynamically
     * reloaded and used to process WPS requests. It plays a crucial role in
     * handling WPS operations by mapping process identifiers to their corresponding
     * Groovy script implementations.
     */
    static List<ScriptWrapper> wpsScripts;
    /**
     * An instance of WpsScriptWrapper used to manage the execution of WPS (Web Processing Service) scripts.
     * This wrapper facilitates the interaction between the application and the underlying scripting engine
     * to execute processes defined in scripts. It is responsible for script execution and handling inputs
     * and outputs for WPS processes.
     */
    WpsScriptWrapper wpsScriptWrapper = new WpsScriptWrapper();


    /**
     * Constructs an instance of the OwsController class. This constructor initializes the
     * WPS scripts by loading and grouping them using the WpsScriptWrapper utility. The scripts
     * are classified based on their directory structure and wrapped into appropriate script
     * wrappers for further processing.
     *
     * @throws IOException if an error occurs while loading or processing the script files.
     */
    public OwsController() throws IOException {
        Map<String, List<File>> groupedScripts = wpsScriptWrapper.loadScripts();
        wpsScripts = WpsScriptWrapper.buildScriptWrappers(groupedScripts);
    }
    /**
     * Reloads the WPS (Web Processing Service) scripts by reloading them from the file system
     * and rebuilding the corresponding script wrappers.
     *
     * This method uses {@link WpsScriptWrapper#loadScripts()} to scan and organize script files into
     * groups. The results are then processed by {@link WpsScriptWrapper#buildScriptWrappers(Map)} to
     * create a new set of script wrappers, which replace the existing ones.
     *
     * @throws IOException if an error occurs while loading or rebuilding the scripts.
     */
    public void reloadScripts() throws IOException {
        Map<String, List<File>> groupedScripts = wpsScriptWrapper.loadScripts();
        wpsScripts = WpsScriptWrapper.buildScriptWrappers(groupedScripts);
    }

    /**
     * Handles GET requests for the OWS (Web Services) endpoint. Based on the "service" query parameter,
     * it routes the request to the appropriate WPS, WFS, or WCS service handler. If the service type is unknown,
     * responds with HTTP 400 (Bad Request). Handles exceptions and responds with HTTP 500 (Internal Server Error)
     * in case of server-side errors.
     *
     * @param ctx the context of the current HTTP request, providing access to request parameters,
     *            response handling, and the ability to set content type and status codes
     */
    public void handleGet(Context ctx) {
        ctx.contentType("text/xml; charset=UTF-8");
        String service = ctx.queryParam("service");

        try {
            if ("WPS".equalsIgnoreCase(service)) {
                handleWPSGet(ctx);
            } else if ("WFS".equalsIgnoreCase(service)) {
                handleWFSGet(ctx);
            } else if ("WCS".equalsIgnoreCase(service)) {
                handleWCSGet(ctx);
            } else {
                ctx.status(400).result("Unknown service");
            }
        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(500).result("Server Error: " + e.getMessage());
        }
    }

    /**
     * Handles HTTP GET requests for the WPS (Web Processing Service) by managing
     * the "GetCapabilities" and "DescribeProcess" operations. Based on the "request"
     * query parameter, this method retrieves WPS capabilities or details of a specific
     * process. Responds with appropriate XML content or error messages in case of invalid
     * requests or missing parameters.
     *
     * @param ctx the context of the current HTTP request, providing access to
     *            query parameters, response handling, and the ability to set
     *            content type and status codes
     */
    private void handleWPSGet(Context ctx) {
        String request = ctx.queryParam("request");
        ctx.contentType("text/xml; charset=UTF-8");

        if ("GetCapabilities".equalsIgnoreCase(request)) {
            String xml = WpsScriptWrapper.generateCapabilitiesXML(wpsScripts);
            ctx.result(xml);

        } else if ("DescribeProcess".equalsIgnoreCase(request)) {
            String identifier = ctx.queryParam("identifier");
            if (identifier == null || identifier.isEmpty()) {
                ctx.status(400).result("<ows:Exception>Missing identifier parameter</ows:Exception>");
                return;
            }

            Optional<ScriptWrapper> target = wpsScripts.stream()
                    .filter(w -> w.id.equals(identifier))
                    .findFirst();

            if (target.isPresent()) {
                ctx.result(WpsScriptWrapper.generateDescribeProcessXML(target.get()));
            } else {
                ctx.status(404).result("<ows:Exception>Process not found: " + identifier + "</ows:Exception>");
            }

        } else {
            ctx.status(400).result("<ows:Exception>Unknown WPS request: " + request + "</ows:Exception>");
        }
    }

    /**
     * Handles WFS (Web Feature Service) GET requests for the OWS (Web Services) endpoint.
     * Depending on the value of the "request" query parameter, this method determines the desired operation.
     * If the request is "GetCapabilities", the corresponding XML file is read and returned in the response.
     * For unknown or unsupported requests, it returns an HTTP 400 (Bad Request) status.
     *
     * @param ctx the context of the current HTTP request, providing access to query parameters,
     *            request and response handling, and allowing for status and body configuration
     * @throws Exception if an error occurs while reading or responding with the requested resource
     */
    private void handleWFSGet(Context ctx) throws Exception {
        String request = ctx.queryParam("request");
        if ("GetCapabilities".equalsIgnoreCase(request)) {
            try (InputStream xmlStream = Main.class.getResourceAsStream("static/xmlFiles/wfs.xml")){
                ctx.result(xmlStream.readAllBytes());
            }
        } else {
            ctx.status(400).result("Unknown WFS request");
        }
    }

    /**
     * Handles a Get request for the Web Coverage Service (WCS). The method processes
     * the incoming HTTP request by examining the "request" query parameter. If the
     * query parameter is "GetCapabilities", it responds with the contents of a WCS
     * capabilities XML file. If the request is not recognized, it responds with a
     * 400 HTTP status and an error message.
     *
     * @param ctx the context of the HTTP request, providing access to query parameters,
     *            response handling, status codes, and other request-related information
     * @throws Exception if an I/O error occurs while attempting to read the WCS capabilities
     *                   XML file or while processing the request
     */
    private void handleWCSGet(Context ctx) throws Exception {
        String request = ctx.queryParam("request");
        if ("GetCapabilities".equalsIgnoreCase(request)) {
            try (InputStream xmlStream = Main.class.getResourceAsStream("static/xmlFiles/wcs.xml")) {
                ctx.result(xmlStream.readAllBytes());
            }
        } else {
            ctx.status(400).result("Unknown WCS request");
        }
    }


    /**
     * Handles an HTTP POST request for a Web Processing Service (WPS) operation.
     * This method parses the request body, validates the WPS Execute Request, identifies
     * the target script to execute based on its process identifier, and executes the script.
     * The result of the script execution is returned as a JSON response.
     * Responds with appropriate HTTP status codes for invalid requests, missing scripts,
     * and internal server errors.
     *
     * @param ctx the context of the HTTP request, providing access to the request body,
     *            response handling, and the ability to set status codes and send JSON responses
     */
    public void handleWPSPost(Context ctx) {
        try {

            Parser parser = new Parser(new WPSConfiguration());
            Object parsed = parser.parse(new ByteArrayInputStream(ctx.bodyAsBytes()));

            if (!(parsed instanceof ExecuteType)) {
                ctx.status(400).result("WPS request not valid");
                return;
            }

            ExecuteType execute = (ExecuteType) parsed;
            String processId = execute.getIdentifier().getValue();

            String[] parts = processId.split(":");
            if (parts.length != 2) {
                ctx.status(400).result("Invalid process ID");
                return;
            }

            String group = parts[0];
            String scriptName = parts[1];

            Optional<ScriptWrapper> wrapperOpt = wpsScripts.stream()
                    .filter(sw -> sw.id.equals(group + ":" + scriptName))
                    .findFirst();

            if (wrapperOpt.isEmpty()) {
                ctx.status(404).result("Script not found");
                return;
            }
            ScriptWrapper wrapper = wrapperOpt.get();
            Map<String, Object> inputs = ScriptWrapper.extractInputs(execute);
            Connection connection = dataBaseManager.openDatabaseConnection();
            Object result = wrapper.execute(connection, inputs);

            if (result instanceof Geometry) {
                ctx.contentType("application/wkt");
                ctx.result(result.toString());
            } else {
                ctx.json(Map.of("result", result));
            }

        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(500).result("Error WPS : " + e.getMessage());
        }
    }
}