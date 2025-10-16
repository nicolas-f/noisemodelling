package org.noise_planet.noisemodelling.webserver;

import io.javalin.http.Context;
import groovy.lang.*;
import net.opengis.wps10.*;
import org.geotools.wps.WPSConfiguration;
import org.geotools.xsd.Parser;
import org.h2gis.utilities.wrapper.ConnectionWrapper;

import java.io.*;
import java.sql.Connection;
import java.util.*;

/**
 * The OwsController class handles requests for OGC Web Services (OWS), including
 * WPS (Web Processing Service), WFS (Web Feature Service), and WCS (Web Coverage Service).
 * It provides functionalities for GET and POST requests, depending on the OWS service and
 * operation type.
 */
public class OwsController {
    /**
     * A static and final instance of the {@link DataBaseManager} class, responsible for managing database operations
     * for the application. This instance is initialized with "webserver" as the default database name.
     *
     * The {@code databaseManager} provides access to functionalities such as setting up the default database directory,
     * managing the active database name, and establishing database connections. It plays a key role in ensuring the
     * application interacts seamlessly with the underlying H2 database in AUTO_SERVER mode.
     *
     * This field is used within the {@code OwsController} class to manage database access for handling various OWS requests.
     */
    private static final DataBaseManager databaseManager = new DataBaseManager("webserver");

    public OwsController() {}

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
            ctx.status(500).result("Erreur serveur : " + e.getMessage());
        }
    }

    /**
     * Handles a WPS GET request. Processes the "request" query parameter to determine the required
     * WPS operation. Supports "GetCapabilities" for fetching service capabilities and "DescribeProcess"
     * for retrieving descriptions of a specific process. If the request type is unknown, responds
     * with HTTP 400 (Bad Request).
     *
     * @param ctx the context of the current HTTP request, providing access to query parameters,
     *            and enabling the method to write responses to the client and set HTTP status codes
     * @throws Exception if an error occurs during request handling or XML generation
     */
    private void handleWPSGet(Context ctx) throws Exception {
        String request = ctx.queryParam("request");
        if ("GetCapabilities".equalsIgnoreCase(request)) {
            ctx.result(ScriptParser.buildCapabilitiesXml(ScriptParser.groovyScriptExtractor.scripts));
        } else if ("DescribeProcess".equalsIgnoreCase(request)) {
            ctx.result(ScriptParser.buildDescribeProcessXml(ctx.queryParam("identifier"), ScriptParser.groovyScriptExtractor.scripts));
        } else {
            ctx.status(400).result("Unknown WPS request");
        }
    }

    /**
     * Handles a WFS GET request. Processes the "request" query parameter to determine the
     * requested Web Feature Service (WFS) operation. Supports "GetCapabilities" for retrieving
     * service metadata. If the request type is unknown, responds with HTTP 400 (Bad Request).
     *
     * @param ctx the context of the current HTTP request, providing access to query parameters,
     *            and enabling the method to write responses to the client and set HTTP status codes.
     * @throws Exception if an error occurs while processing the request or reading the XML resource.
     */
    private void handleWFSGet(Context ctx) throws Exception {
        String request = ctx.queryParam("request");
        if ("GetCapabilities".equalsIgnoreCase(request)) {
            try (InputStream xmlStream = getClass().getClassLoader().getResourceAsStream("static/xmlFiles/wfs.xml")) {
                ctx.result(xmlStream.readAllBytes());
            }
        } else {
            ctx.status(400).result("Unknown WFS request");
        }
    }

    /**
     * Handles a WCS (Web Coverage Service) GET request. Processes the "request" query parameter
     * to determine the desired WCS operation. Currently supports "GetCapabilities", which serves
     * WCS XML capabilities file. If the request type is unknown, it responds with HTTP 400
     * (Bad Request).
     *
     * @param ctx the context of the current HTTP request, providing access to query parameters,
     *            response output stream, and HTTP status codes
     * @throws Exception if an error occurs while reading the resource file or writing the response
     */
    private void handleWCSGet(Context ctx) throws Exception {
        String request = ctx.queryParam("request");
        if ("GetCapabilities".equalsIgnoreCase(request)) {
            try (InputStream xmlStream = getClass().getClassLoader().getResourceAsStream("static/xmlFiles/wcs.xml")) {
                ctx.result(xmlStream.readAllBytes());
            }
        } else {
            ctx.status(400).result("Unknown WCS request");
        }
    }


    /**
     * Handles a WPS (Web Processing Service) POST request. Parses the incoming request body to extract
     * and execute a WPS process. Validates the request format, extracts the process identifier and inputs,
     * retrieves the corresponding script file, and executes the process using a Groovy scripting engine.
     * Responds with execution results or appropriate HTTP status codes for errors such as invalid WPS requests,
     * process identifier issues, or script not found.
     *
     * @param ctx the context of the current HTTP request, providing access to the request body, response handling,
     *            and methods to set HTTP status codes and results
     */
    public void handleWPSPost(Context ctx) {
        try {
            Parser parser = new Parser(new WPSConfiguration());
            Object parsed = parser.parse(new ByteArrayInputStream(ctx.bodyAsBytes()));

            if (!(parsed instanceof ExecuteType)) {
                ctx.status(400).result("Requête WPS invalide");
                return;
            }

            ExecuteType execute = (ExecuteType) parsed;
            String processId = ScriptParser.extractProcessName(execute);
            Map<String, Object> inputs = ScriptParser.extractInputs(execute);

            String[] parts = processId.split(":");
            if (parts.length != 2) {
                ctx.status(400).result("Identifiant de processus invalide");
                return;
            }

            String group = parts[0];
            String scriptName = parts[1];
            File scriptFile = ScriptParser.groovyScriptExtractor.findScript(ScriptParser.groovyScriptExtractor.scripts, group, scriptName);
            if (scriptFile == null) {
                ctx.status(404).result("Script introuvable");
                return;
            }

            Connection connection = databaseManager.openDatabaseConnection();
            Binding binding = new Binding();
            binding.setVariable("input", inputs);
            binding.setVariable("connection", new ConnectionWrapper(connection));

            GroovyShell shell = new GroovyShell(binding);
            Script script = shell.parse(scriptFile);
            Object result = script.invokeMethod("exec", new Object[]{connection, inputs});

            ctx.json(Map.of("result", result));

        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(500).result("Erreur WPS : " + e.getMessage());
        }
    }
}
