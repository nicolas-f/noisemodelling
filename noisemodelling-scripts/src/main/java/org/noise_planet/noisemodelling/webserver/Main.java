package org.noise_planet.noisemodelling.webserver;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.io.IOException;
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

        Path scriptsDir = Path.of(System.getProperty("user.dir"));
        if (!Files.exists(scriptsDir)) {
            Path devDir = Paths.get("noisemodelling-scripts/src/main/groovy/org/noise_planet/noisemodelling/scripts");
            if (Files.exists(devDir)) {
                scriptsDir = devDir;
            } else {
                System.out.println(scriptsDir);
                throw new RuntimeException("Impossible de trouver le répertoire des scripts !");
            }
        }

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
            config.staticFiles.add("static/wpsbuilder", Location.CLASSPATH);
        }).start(8000);

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
}
