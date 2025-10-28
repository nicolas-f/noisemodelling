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

    /**
     * The main method initializes and starts the application. It sets up file
     * watchers, a Javalin server for serving HTTP requests, and dynamic script
     * reloading for Groovy scripts. A shutdown hook is also added to gracefully
     * stop the application and release resources on termination.
     *
     * @param args Command-line arguments provided during the execution of the program.
     * @throws IOException If an I/O error occurs during file operations or server setup.
     */
    public static void main(String[] args) throws IOException {
        final Logger logger = LoggerFactory.getLogger(Main.class);
        Path scriptsDir = Path.of(System.getProperty("user.dir"));

        Path devScripts = scriptsDir.resolve("noisemodelling-scripts/src/main/groovy/org/noise_planet/noisemodelling/scripts");

        Path zipScripts = scriptsDir.getParent().resolve("noisemodelling/scripts");
        if (Files.exists(devScripts)) {
            scriptsDir = devScripts;
        } else if (Files.exists(zipScripts)) {
            scriptsDir = zipScripts;
        } else {
            throw new RuntimeException("Scripts not found in expected locations");
        }
        PropertyConfigurator.configure(org.noise_planet.noisemodelling.scripts.Main.class.getResource("static/log4j.properties"));

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

        OwsController owsController = new OwsController();

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("org/noise_planet/noisemodelling/scripts/static/wpsbuilder", Location.CLASSPATH);
        }).start(8000);

        int port = app.port();
        String url = "http://localhost:" + port + "/";
        logger.info("Start NoiseModelling: " + url);
        openBrowser(url);

        app.get("/ows", owsController::handleGet);
        app.post("/ows", owsController::handleWPSPost);

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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                watchService.close();
                app.stop();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));
    }

    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            System.out.println("Unable to open the browser : " + e.getMessage());
        }
    }
}
