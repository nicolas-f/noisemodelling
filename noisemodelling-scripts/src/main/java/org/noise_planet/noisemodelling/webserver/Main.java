package org.noise_planet.noisemodelling.webserver;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.apache.log4j.PropertyConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.concurrent.Executors;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    /**
     * The entry point of the application. This method initializes and starts the server.
     *
     * @param args command-line arguments passed to the application. Not utilized currently.
     * @throws IOException if an I/O error occurs during server initialization.
     */
    public static void main(String[] args) throws IOException {
        startServer(true);
    }

    /**
     * Initializes and starts the NoiseModelling server with the specified configuration.
     * The server serves static files, provides endpoints for OGC-compliant operations,
     * and optionally opens a browser pointing to the server's base URL.
     *
     * @param openBrowser indicates whether the default web browser should be opened
     *                     pointing to the server's base URL.
     * @return the initialized and started Javalin application instance.
     * @throws IOException if an I/O error occurs during server initialization or script directory resolution.
     */
    public static Javalin startServer(boolean openBrowser) throws IOException {
        Path scriptsDir = finScriptsDir();

        PropertyConfigurator.configure(org.noise_planet.noisemodelling.scripts.Main.class.getResource("static/log4j.properties"));

        OwsController owsController = new OwsController();
        String root = System.getProperty("user.dir");
        Path staticRoot = Paths.get(root).getParent().resolve("static");

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("org/noise_planet/noisemodelling/scripts/static/wpsbuilder", Location.CLASSPATH);
            if (!scriptsDir.toString().contains("main/groovy")){
                config.staticFiles.add(staticRoot.toString(), Location.EXTERNAL);
            }
        }).start(8000);

        int port = app.port();
        String url = "http://localhost:" + port + "/";
        logger.info("Start NoiseModelling: " + url);

        if (openBrowser) {
            openBrowser(url);
        }

        app.get("/ows", owsController::handleGet);
        app.post("/ows", owsController::handleWPSPost);

        startWatcher(scriptsDir, owsController);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                app.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));

        return app;
    }

    /**
     * Opens the default web browser and navigates to the specified URL.
     *
     * @param url the URL to be opened in the default web browser. It must be a properly formatted URI.
     */
    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            System.out.println("Unable to open the browser : " + e.getMessage());
        }
    }

    /**
     * Monitors a specified directory and its subdirectories for changes in files.
     * Specifically watches for creation, deletion, and modification events of files with the `.groovy` extension
     * and triggers a script reload using the provided OwsController.
     *
     * @param scriptsDir the root directory to monitor for changes. All subdirectories under this will also be monitored.
     * @param owsController the instance responsible for reloading scripts when a `.groovy` file is changed.
     * @throws IOException if an I/O error occurs during the setup of the WatchService or directory registration.
     */
    private static void startWatcher(Path scriptsDir,OwsController owsController) throws IOException {
        WatchService watchService = FileSystems.getDefault().newWatchService();

        Files.walk(scriptsDir)
                .filter(Files::isDirectory)
                .forEach(dir -> {
                    try {
                        dir.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
        Executors.newSingleThreadExecutor().submit(() -> {
            while (true) {
                try {
                    WatchKey key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path fileName = (Path) event.context();
                        if (fileName.toString().endsWith(".groovy")) {
                            owsController.reloadScripts();
                        }
                    }

                    boolean valid = key.reset();
                    if (!valid) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ClosedWatchServiceException e) {
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Resolves and returns the path to the directory containing the NoiseModelling scripts.
     * The method first searches for the "noisemodelling-scripts" directory by traversing
     * upwards from the user's current working directory. It then determines whether it is
     * in a development environment or a deployed (zipped) environment by checking for the
     * existence of specific paths. If neither path is found, a RuntimeException is thrown.
     *
     * @return the {@code Path} to the directory containing NoiseModelling scripts, either in
     *         the development or deployment structure.
     * @throws RuntimeException if the script directory cannot be located in the expected paths.
     */
    private static Path finScriptsDir() {
        Path scriptsDir = Paths.get(System.getProperty("user.dir"));
        if (!Files.exists(scriptsDir.resolve("noisemodelling-scripts")) && scriptsDir.getParent() != null) {
            scriptsDir = scriptsDir.getParent();
        }
        Path devScripts = scriptsDir.resolve(Paths.get("noisemodelling-scripts/src/main/groovy/org/noise_planet/noisemodelling/scripts")).normalize();
        Path zipScripts = scriptsDir.resolve("noisemodelling/scripts");
        if (Files.exists(devScripts)) {
            return devScripts;
        } else if (Files.exists(zipScripts)) {
            return zipScripts;
        } else {
            throw new RuntimeException("Scripts not found in expected locations: "+ scriptsDir);
        }
    }
}
