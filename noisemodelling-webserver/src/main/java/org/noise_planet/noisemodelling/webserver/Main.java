package org.noise_planet.noisemodelling.webserver;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

public class Main {

    /**
     * A static instance of the OwsController class, used to handle HTTP GET and POST
     * requests for OGC Web Services (OWS) endpoints, including WPS (Web Processing Service),
     * WFS (Web Feature Service), and WCS (Web Coverage Service).
     * <p>
     * This controller is responsible for processing incoming requests, routing them to the
     * appropriate OWS service logic, and generating responses in accordance with the
     * OGC (Open Geospatial Consortium) standards. It is used in the main application
     * to attach route handlers for the /ows endpoint.
     * <p>
     * The instance is statically initialized and shared within the application, enabling
     * consistent handling of OWS-related requests across different HTTP endpoints.
     */
    static OwsController owsController = new OwsController();

    /**
     * The main entry point of the application. Configures and starts the Javalin server,
     * sets up endpoints, and defines request handling behavior.
     *
     * @param args an array of command-line arguments passed to the application
     */
    public static void main(String[] args) {

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("static/wpsbuilder", Location.CLASSPATH);
        }).start(8000);

        app.get("/ows", owsController::handleGet);
        app.post("/ows", owsController::handleWPSPost);



    }
}
