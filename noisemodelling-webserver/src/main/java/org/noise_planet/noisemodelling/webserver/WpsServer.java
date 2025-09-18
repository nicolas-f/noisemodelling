package org.noise_planet.noisemodelling.webserver;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import net.opengis.ows11.*;
import net.opengis.wps10.*;
import org.geotools.wps.WPSConfiguration;
import org.geotools.xsd.Encoder;
import org.geotools.xsd.Parser;
import org.h2.Driver;
import org.h2gis.functions.factory.H2GISFunctions;
import org.h2gis.utilities.wrapper.ConnectionWrapper;

import javax.xml.namespace.QName;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        System.out.println("WPS Server debut "+SCRIPTS_ROOT);

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("static/wpsbuilder", Location.CLASSPATH);
        }).start(7000);
         //Connection connection = null;
        System.out.println("WPS Server debutt "+SCRIPTS_ROOT);

        app.get("/wps", ctx -> {
             String request = ctx.queryParam("request");
             if ("GetCapabilities".equalsIgnoreCase(request)) {
                 ctx.contentType("text/xml");
                 try {
                     ctx.result(buildCapabilitiesXml());
                     System.out.println("WPS here");
                 } catch (Exception e) {
                     e.printStackTrace(); // log sur la console
                     ctx.status(500).result("Erreur GetCapabilities : " + e.getMessage());
                 }
             } else if ("DescribeProcess".equalsIgnoreCase(request)) {
                System.out.println("WPS Server here " + request);
                ctx.contentType("text/xml");
                //ctx.result(buildDescribeProcessXml(ctx.queryParam("identifier")));
                ctx.result(buildDescribeProcessXml(ctx.queryParam("identifier")));
             }else {
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

                Connection connection = openDatabaseConnection();
                Binding binding = new Binding();
                binding.setVariable("input", inputs);
                binding.setVariable("connection", new ConnectionWrapper(connection));

                GroovyShell shell = new GroovyShell( binding);
                Script script = (Script) shell.parse(scriptFile);
                Object result = script.invokeMethod("exec", new Object[]{connection, inputs});

                ctx.json(Map.of("result", result));
            } catch (Exception e) {
                ctx.status(500).result("Erreur WPS : " + e.getMessage());
            }
        });

        app.post("/wps/execute/{package}/{script}", ctx -> {
            try {
                String pkg = ctx.pathParam("package");
                String scriptName = ctx.pathParam("script");
                Map<String,Object> params = ctx.bodyAsClass(Map.class);

                File scriptFile = findScript(pkg, scriptName);
                if (scriptFile == null) {
                    ctx.status(404).result("Script introuvable");
                    return;
                }

                Connection connection = openDatabaseConnection();

                Binding binding = new Binding();
                binding.setVariable("input", params);
                binding.setVariable("connection",new ConnectionWrapper(connection));

                GroovyShell shell = new GroovyShell(binding);
                Script script = shell.parse(scriptFile);

                Object result = script.invokeMethod("exec", new Object[]{connection, params});

                ctx.json(Map.of("result", result));
            } catch (Exception e) {
                ctx.status(500).result("Erreur d'excution : " + e.getMessage());
            }
        });

    }

    private static String buildCapabilitiesXml() throws Exception {
        System.out.println("ici buildCapabilitiesXml appelllé");


        WPSCapabilitiesType capabilities = Wps10Factory.eINSTANCE.createWPSCapabilitiesType();
        capabilities.setService("WPS");
        capabilities.setVersion("1.0.0");

        System.out.println("Création ServiceIdentification");
        ServiceIdentificationType serviceId = Ows11Factory.eINSTANCE.createServiceIdentificationType();
        CodeType serviceType = Ows11Factory.eINSTANCE.createCodeType();
        serviceType.setValue("WPS");
        serviceId.setServiceType(serviceType);
        serviceId.setServiceTypeVersion("1.0.0");


        LanguageStringType title = Ows11Factory.eINSTANCE.createLanguageStringType();
        title.setValue("NoiseModelling WPS");
        serviceId.getTitle().add(title);

        LanguageStringType abs = Ows11Factory.eINSTANCE.createLanguageStringType();
        abs.setValue("Service WPS pour exécuter les scripts NoiseModelling");
        serviceId.getAbstract().add(abs);

        capabilities.setServiceIdentification(serviceId);

        System.out.println("ici ServiceProvider");
        ServiceProviderType provider = Ows11Factory.eINSTANCE.createServiceProviderType();
        provider.setProviderName("NoiseModelling");

        OnlineResourceType site = Ows11Factory.eINSTANCE.createOnlineResourceType();
        site.setHref("http://localhost:7000/");
        provider.setProviderSite(site);

        ResponsiblePartySubsetType contact = Ows11Factory.eINSTANCE.createResponsiblePartySubsetType();
        contact.setIndividualName("Support ");
        ContactType contactType = Ows11Factory.eINSTANCE.createContactType();
        AddressType addr = Ows11Factory.eINSTANCE.createAddressType();
        //addr.getElectronicMailAddress().add("support@noisemodelling.org");
        addr.setElectronicMailAddress("support@noisemodelling.org");
        contactType.setAddress(addr);
        contact.setContactInfo(contactType);
        provider.setServiceContact(contact);

        capabilities.setServiceProvider(provider);

        OperationsMetadataType ops = Ows11Factory.eINSTANCE.createOperationsMetadataType();
        ops.getOperation().add(createOperation("GetCapabilities", "http://localhost:7000/wps"));
        ops.getOperation().add(createOperation("DescribeProcess", "http://localhost:7000/wps"));
        ops.getOperation().add(createOperation("Execute", "http://localhost:7000/wps"));
        capabilities.setOperationsMetadata(ops);

        ProcessOfferingsType offerings = Wps10Factory.eINSTANCE.createProcessOfferingsType();

        Map<String, List<String>> grouped = scanScriptsGrouped();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            String group = entry.getKey();
            for (String process : entry.getValue()) {

                ProcessBriefType brief = Wps10Factory.eINSTANCE.createProcessBriefType();

                CodeType id = Ows11Factory.eINSTANCE.createCodeType();
                id.setValue(group + ":" + process);
                brief.setIdentifier(id);

                LanguageStringType procTitle = Ows11Factory.eINSTANCE.createLanguageStringType();
                procTitle.setValue(process);
                brief.setTitle(procTitle);

                LanguageStringType procAbstract = Ows11Factory.eINSTANCE.createLanguageStringType();
                procAbstract.setValue("Description du process " + process);
                brief.setAbstract(procAbstract);

                offerings.getProcess().add(brief);
            }
        }
        capabilities.setProcessOfferings(offerings);
        System.out.println("etat capabilitie:"+capabilities.toString());


        Encoder encoder = new Encoder(new WPSConfiguration());
        encoder.setIndenting(true);

        encoder.getNamespaces().declarePrefix("wps",   "http://www.opengis.net/wps/1.0.0");
        encoder.getNamespaces().declarePrefix("ows",   "http://www.opengis.net/ows/1.1");
        encoder.getNamespaces().declarePrefix("xlink", "http://www.w3.org/1999/xlink");
        encoder.getNamespaces().declarePrefix("xsi",   "http://www.w3.org/2001/XMLSchema-instance");

        encoder.setSchemaLocation(
                "http://www.opengis.net/wps/1.0.0",
                "http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd"
        );
        System.out.println("encoder "+encoder.toString());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encoder.encode(
                capabilities,
                new QName("http://www.opengis.net/wps/1.0.0", "Capabilities"),
                out
        );

        return out.toString(StandardCharsets.UTF_8);
    }

    private static OperationType createOperation(String name, String baseUrl) {
        OperationType op = Ows11Factory.eINSTANCE.createOperationType();
        op.setName(name);

        DCPType dcp = Ows11Factory.eINSTANCE.createDCPType();
        HTTPType http = Ows11Factory.eINSTANCE.createHTTPType();

        RequestMethodType get = Ows11Factory.eINSTANCE.createRequestMethodType();
        get.setHref(baseUrl + "?");
        http.getGet().add(get);

        RequestMethodType post = Ows11Factory.eINSTANCE.createRequestMethodType();
        post.setHref(baseUrl);
        http.getPost().add(post);

        dcp.setHTTP(http);
        op.getDCP().add(dcp);
        return op;
    }


    private static String buildDescribeProcessXml(String identifier) throws Exception {
        ProcessDescriptionsType processDescriptions = Wps10Factory.eINSTANCE.createProcessDescriptionsType();
        processDescriptions.setLang("en");
        processDescriptions.setService("WPS");
        processDescriptions.setVersion("1.0.0");

        if (identifier == null || identifier.isEmpty() || identifier.equalsIgnoreCase("all")) {
            Map<String, List<String>> grouped = scanScriptsGrouped();
            for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
                String group = entry.getKey();
                for (String process : entry.getValue()) {
                    try {
                        addProcessDescription(processDescriptions, group + ":" + process);
                    } catch (Exception e) {
                        System.err.println("Erreur   processus " + group + ":" + process + ": " + e.getMessage());
                    }
                }
            }
        } else {
            addProcessDescription(processDescriptions, identifier);
        }

        Encoder encoder = new Encoder(new WPSConfiguration());
        encoder.setIndenting(true);
        encoder.setSchemaLocation(
                "http://www.opengis.net/wps/1.0.0",
                "http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd"
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encoder.encode(processDescriptions,
                new QName("http://www.opengis.net/wps/1.0.0", "ProcessDescriptions"),
                out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void addProcessDescription(ProcessDescriptionsType processDescriptions, String identifier) throws Exception {
        String[] parts = identifier.split(":");
        if (parts.length != 2) throw new IllegalArgumentException("Identifiant doit être au format 'Groupe:Nom'");
        String group = parts[0], processName = parts[1];

        File scriptFile = findScript(group, processName);
        if (scriptFile == null) throw new Exception("Script non trouvé: " + identifier);

        Map<String, Object> metadata = parseGroovyScriptMetadata(scriptFile);

        ProcessDescriptionType process = Wps10Factory.eINSTANCE.createProcessDescriptionType();
        process.setStatusSupported(true);
        process.setStoreSupported(true);

        CodeType code = Ows11Factory.eINSTANCE.createCodeType();
        code.setValue(identifier);
        process.setIdentifier(code);

        LanguageStringType title = Ows11Factory.eINSTANCE.createLanguageStringType();
        title.setValue(metadata.getOrDefault("title", processName).toString());
        process.setTitle(title);

        LanguageStringType abstractText = Ows11Factory.eINSTANCE.createLanguageStringType();
        abstractText.setValue(metadata.getOrDefault("description", "Description du process " + processName).toString());
        process.setAbstract(abstractText);

        // Input
        DataInputsType inputs = Wps10Factory.eINSTANCE.createDataInputsType();
        Map<String, Map<String,Object>> inputsMap = (Map<String, Map<String,Object>>) metadata.getOrDefault("inputs", new HashMap<>());
        for (Map.Entry<String, Map<String,Object>> entry : inputsMap.entrySet()) {
            InputDescriptionType input = Wps10Factory.eINSTANCE.createInputDescriptionType();
            input.setMinOccurs(BigInteger.ONE);
            input.setMaxOccurs(BigInteger.ONE);

            CodeType inputId = Ows11Factory.eINSTANCE.createCodeType();
            inputId.setValue(entry.getKey());
            input.setIdentifier(inputId);

            LanguageStringType inputTitle = Ows11Factory.eINSTANCE.createLanguageStringType();
            inputTitle.setValue(entry.getValue().getOrDefault("title", entry.getKey()).toString());
            input.setTitle(inputTitle);

            if (entry.getValue().containsKey("description")) {
                LanguageStringType inputAbstract = Ows11Factory.eINSTANCE.createLanguageStringType();
                inputAbstract.setValue(entry.getValue().get("description").toString());
                input.setAbstract(inputAbstract);
            }

            configureLiteralInput(input, entry.getValue());
            inputs.getInput().add(input);
        }
        if (!inputsMap.isEmpty()) process.setDataInputs(inputs);

        ProcessOutputsType outputs = Wps10Factory.eINSTANCE.createProcessOutputsType();
        Map<String, Map<String,Object>> outputsMap = (Map<String, Map<String,Object>>) metadata.getOrDefault("outputs", new HashMap<>());
        for (Map.Entry<String, Map<String,Object>> entry : outputsMap.entrySet()) {
            OutputDescriptionType output = Wps10Factory.eINSTANCE.createOutputDescriptionType();

            CodeType outputId = Ows11Factory.eINSTANCE.createCodeType();
            outputId.setValue(entry.getKey());
            output.setIdentifier(outputId);

            LanguageStringType outputTitle = Ows11Factory.eINSTANCE.createLanguageStringType();
            outputTitle.setValue(entry.getValue().getOrDefault("title", entry.getKey()).toString());
            output.setTitle(outputTitle);

            if (entry.getValue().containsKey("description")) {
                LanguageStringType outputAbstract = Ows11Factory.eINSTANCE.createLanguageStringType();
                outputAbstract.setValue(entry.getValue().get("description").toString());
                output.setAbstract(outputAbstract);
            }

            configureLiteralOutput(output, entry.getValue());
            outputs.getOutput().add(output);
        }
        if (!outputsMap.isEmpty()) process.setProcessOutputs(outputs);

        processDescriptions.getProcessDescription().add(process);
    }

    private static void configureLiteralInput(InputDescriptionType input, Map<String, Object> inputProps) {
        LiteralInputType literalInput = Wps10Factory.eINSTANCE.createLiteralInputType();


        DomainMetadataType dataType = Ows11Factory.eINSTANCE.createDomainMetadataType();
        String typeName = "string";
        if (inputProps.containsKey("type")) {
            Object typeObj = inputProps.get("type");
            if (typeObj instanceof Class<?>) {
                Class<?> typeClass = (Class<?>) typeObj;
                if (typeClass == String.class) typeName = "string";
                else if (typeClass == Integer.class) typeName = "integer";
                else if (typeClass == Double.class || typeClass == Float.class) typeName = "double";
                else if (typeClass == Boolean.class) typeName = "boolean";
            } else if (typeObj instanceof String) {
                String typeStr = (String) typeObj;
                if (typeStr.contains("String")) typeName = "string";
                else if (typeStr.contains("Integer")) typeName = "integer";
                else if (typeStr.contains("Double") || typeStr.contains("Float")) typeName = "double";
                else if (typeStr.contains("Boolean")) typeName = "boolean";
            }
        }
        dataType.setValue(typeName);
        literalInput.setDataType(dataType);

        AllowedValuesType allowedValues = Ows11Factory.eINSTANCE.createAllowedValuesType();
        if (inputProps.containsKey("allowedValues")) {
            Object allowedObj = inputProps.get("allowedValues");
            if (allowedObj instanceof List) {
                for (Object value : (List<?>) allowedObj) {
                    allowedValues.getValue().add(String.valueOf(value));
                }
            }
        }
        literalInput.setAllowedValues(allowedValues);

        if (inputProps.containsKey("default")) {
            literalInput.setDefaultValue(String.valueOf(inputProps.get("default")));
        } else {
            literalInput.setDefaultValue("");
        }

        input.setLiteralData(literalInput);
    }

    private static void configureLiteralOutput(OutputDescriptionType output, Map<String, Object> outputProps) {
        LiteralOutputType literalOutput = Wps10Factory.eINSTANCE.createLiteralOutputType();


        DomainMetadataType dataType = Ows11Factory.eINSTANCE.createDomainMetadataType();
        String typeName = "string";

        if (outputProps.containsKey("type")) {
            Object typeObj = outputProps.get("type");
            if (typeObj instanceof Class) {
                Class<?> typeClass = (Class<?>) typeObj;
                if (typeClass == String.class) {
                    typeName = "string";
                } else if (typeClass == Integer.class) {
                    typeName = "integer";
                } else if (typeClass == Double.class || typeClass == Float.class) {
                    typeName = "double";
                } else if (typeClass == Boolean.class) {
                    typeName = "boolean";
                }
            } else if (typeObj instanceof String) {
                String typeStr = (String) typeObj;
                if (typeStr.contains("String")) {
                    typeName = "string";
                } else if (typeStr.contains("Integer")) {
                    typeName = "integer";
                } else if (typeStr.contains("Double") || typeStr.contains("Float")) {
                    typeName = "double";
                } else if (typeStr.contains("Boolean")) {
                    typeName = "boolean";
                }
            }
        }

        dataType.setValue(typeName);
        literalOutput.setDataType(dataType);

        output.setLiteralOutput(literalOutput);
    }
    private static Map<String, Object> parseGroovyScriptMetadata(File scriptFile) throws IOException {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", scriptFile.getName().replace(".groovy", ""));
        metadata.put("description", "");
        metadata.put("inputs", new HashMap<String, Map<String,Object>>());
        metadata.put("outputs", new HashMap<String, Map<String,Object>>());

        try {
            List<String> lines = Files.readAllLines(scriptFile.toPath(), StandardCharsets.UTF_8);
            String scriptContent = String.join("\n", lines);

            Binding binding = new Binding();
            binding.setVariable("String", String.class);
            binding.setVariable("Integer", Integer.class);
            binding.setVariable("Double", Double.class);
            binding.setVariable("Boolean", Boolean.class);

            GroovyShell shell = new GroovyShell(binding);
            shell.evaluate(scriptContent);

            if (binding.hasVariable("title")) metadata.put("title", binding.getVariable("title"));
            if (binding.hasVariable("description")) metadata.put("description", binding.getVariable("description"));
            if (binding.hasVariable("inputs")) metadata.put("inputs", binding.getVariable("inputs"));
            if (binding.hasVariable("outputs")) metadata.put("outputs", binding.getVariable("outputs"));

        } catch (Exception e) {
            System.err.println("Erreur evaluation du script " + scriptFile.getName() + ": " + e.getMessage());

        }

        return metadata;
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
        System.out.println("Scan des scripts à partir de : " + SCRIPTS_ROOT);
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
            System.out.println("file name :" +f.getName());
            if (f.isDirectory()) {
                scanRecursive(f, currentPackage + "." + f.getName(), grouped);
            } else if (f.getName().endsWith(".groovy")) {
                String scriptName = f.getName().replace(".groovy", "");
                String groupName = currentPackage.substring(BASE_PACKAGE.length() + 1);
                if (groupName.isEmpty()) groupName = "Root";
                grouped.computeIfAbsent(groupName, k -> new ArrayList<>()).add(scriptName);
            }
        }
    }

    private static File findScript(String group, String scriptName) {
        String path = SCRIPTS_ROOT + "/" + group.replace('.', '/') + "/" + scriptName + ".groovy";
        File f = new File(path);
        return f.exists() ? f : null;
    }

    private static Connection openDatabaseConnection() throws SQLException, ClassNotFoundException {
        File dbFile = new File("/home/maguettte/IdeaProjects/NoiseModelling/noisemodelling-webserver/projetManager");
        String databasePath = "jdbc:h2:" + dbFile.getAbsolutePath() + ";AUTO_SERVER=TRUE";

        Driver.load();
        Connection connection = DriverManager.getConnection(databasePath, "", "");
        H2GISFunctions.load(connection);
        return new ConnectionWrapper(connection);
    }
}
