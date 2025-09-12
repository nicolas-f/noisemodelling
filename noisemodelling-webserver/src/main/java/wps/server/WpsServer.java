package wps.server;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.geotools.wps.WPSConfiguration;
import org.geotools.xsd.Parser;
import net.opengis.wps10.ExecuteType;
import net.opengis.wps10.DataInputsType1;
import net.opengis.wps10.InputType;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.h2gis.functions.factory.H2GISFunctions;
import org.h2gis.utilities.wrapper.ConnectionWrapper;

import java.io.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

public class WpsServer {


    private static final String BASE_PACKAGE = "org.noise_planet.noisemodelling.wps";


    private static final String SCRIPTS_ROOT =
            "/home/maguettte/IdeaProjects/NoiseModelling/wps_scripts/src/main/groovy/"
                    + BASE_PACKAGE.replace('.', '/');

    public static void main(String[] args) {

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("wpsbuilder", Location.CLASSPATH);
        }).start(7000);

        app.get("/wps", ctx -> {
            String request = ctx.queryParam("request");
            if ("GetCapabilities".equalsIgnoreCase(request)) {
                ctx.contentType("text/xml");
                Map<String, List<String>> grouped = scanScriptsGrouped();

                StringBuilder xml = new StringBuilder();
                xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                        .append("<wps:Capabilities version=\"1.0.0\"\n")
                        .append(" xmlns:wps=\"http://www.opengis.net/wps/1.0.0\"\n")
                        .append(" xmlns:ows=\"http://www.opengis.net/ows/1.1\">\n")
                        .append(" <ows:ServiceIdentification>\n")
                        .append("   <ows:Title>NoiseModelling WPS</ows:Title>\n")
                        .append("   <ows:ServiceType>WPS</ows:ServiceType>\n")
                        .append("   <ows:ServiceTypeVersion>1.0.0</ows:ServiceTypeVersion>\n")
                        .append(" </ows:ServiceIdentification>\n")
                        .append(" <wps:ProcessOfferings>\n");

                for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
                    String group = entry.getKey();
                    for (String process : entry.getValue()) {
                        xml.append("  <wps:Process>\n")
                                .append("    <ows:Identifier>")
                                .append(group).append(":").append(process)
                                .append("</ows:Identifier>\n")
                                .append("    <ows:Title>").append(process).append("</ows:Title>\n")
                                .append("  </wps:Process>\n");
                    }
                }

                xml.append(" </wps:ProcessOfferings>\n")
                        .append("</wps:Capabilities>");

                ctx.result(xml.toString());
            } else {
                ctx.status(400).result("Unknown request");
            }
        });

        app.post("/wps", ctx -> {
            try {
                Parser parser = new Parser(new WPSConfiguration());
                Object parsed = parser.parse(new ByteArrayInputStream(ctx.bodyAsBytes()));

                if (!(parsed instanceof ExecuteType)) {
                    ctx.status(400).result("WPS non valide");
                    return;
                }

                ExecuteType execute = (ExecuteType) parsed;
                String processId = extractProcessName(execute);
                Map<String, Object> inputs = extractInputs(execute);

                String[] parts = processId.split(":");
                if (parts.length != 2) {
                    ctx.status(400).result("Identifiant de process invalide");
                    return;
                }
                String packageName = parts[0];
                String scriptName = parts[1];

                File scriptFile = findScript(packageName, scriptName);
                if (scriptFile == null) {
                    ctx.status(404).result("Script introuvable");
                    return;
                }

                GroovyShell shell = new GroovyShell();
                Script script = (Script) shell.parse(scriptFile);

                Connection connection = openDatabaseConnection();
                Object result = script.invokeMethod("exec", new Object[]{connection, inputs});

                ctx.json(Map.of("result", result));
            } catch (Exception e) {
                ctx.status(500).result("Erreur WPS : " + e.getMessage());
            }
        });

        app.get("/", ctx -> ctx.redirect("/index.html"));
    }



    private static String extractProcessName(ExecuteType execute) {
        return execute.getIdentifier() != null ? execute.getIdentifier().getValue() : null;
    }

    private static Map<String, Object> extractInputs(ExecuteType execute) {
        Map<String, Object> inputsMap = new HashMap<>();
        DataInputsType1 dataInputs = execute.getDataInputs();
        if (dataInputs != null) {
            for (Object obj : dataInputs.getInput()) {
                if (obj instanceof InputType) {
                    InputType input = (InputType) obj;
                    String name = input.getIdentifier().getValue();
                    Object value = (input.getData() != null && input.getData().getLiteralData() != null)
                            ? input.getData().getLiteralData().getValue() : null;
                    inputsMap.put(name, value);
                }
            }
        }
        return inputsMap;
    }

    private static Map<String, List<String>> scanScriptsGrouped() {
        Map<String, List<String>> grouped = new TreeMap<>();
        File baseDir = new File(SCRIPTS_ROOT);
        if (!baseDir.exists()) return grouped;

        scanRecursive(baseDir, BASE_PACKAGE, grouped);
        return grouped;
    }

    private static void scanRecursive(File dir, String currentPackage, Map<String, List<String>> grouped) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanRecursive(f, currentPackage + "." + f.getName(), grouped);
            } else if (f.getName().endsWith(".groovy")) {
                String scriptName = f.getName().replace(".groovy", "");
                String groupName = currentPackage.substring(BASE_PACKAGE.length() + 1); // sous-package
                if (groupName.isEmpty()) groupName = "Root";
                grouped.computeIfAbsent(groupName, k -> new ArrayList<>()).add(scriptName);
            }
        }
    }

    private static File findScript(String group, String scriptName) {
        // group est par ex: "Dynamic"
        String path = SCRIPTS_ROOT + "/" + group.replace('.', '/') + "/" + scriptName + ".groovy";
        File f = new File(path);
        return f.exists() ? f : null;
    }

    private static Connection openDatabaseConnection() throws SQLException, ClassNotFoundException {
        File dbFile = new File(System.getProperty("user.home"), "projetManager");
        String databasePath = "jdbc:h2:" + dbFile.getAbsolutePath() + ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";

        Class.forName("org.h2.Driver");
        Connection connection = DriverManager.getConnection(databasePath, "sa", "");
        H2GISFunctions.load(connection);
        return new ConnectionWrapper(connection);
    }
}


