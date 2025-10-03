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
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class WpsServer {
//    private static final String BASE_PACKAGE = "org.noise_planet.noisemodelling.scripts";
//
//    private static final String SCRIPTS_ROOT =
//            "./noisemodelling-scripts/src/main/groovy/"
//                    + BASE_PACKAGE.replace('.', '/');
    static ScriptWrapper scriptWrapper = new ScriptWrapper("org.noise_planet.noisemodelling.scripts","./noisemodelling-scripts/src/main/groovy/");
    static Map<String, List<String>> scripts = scriptWrapper.scanScriptsGrouped();
    public static void main(String[] args) {
        //System.out.println("WPS Server debut "+SCRIPTS_ROOT);

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("static/wpsbuilder", Location.CLASSPATH);
        }).start(8000);
        //System.out.println("WPS Server debutt "+SCRIPTS_ROOT);

        app.get("/wps", ctx -> {
            String request = ctx.queryParam("request");
            if ("GetCapabilities".equalsIgnoreCase(request)) {
                ctx.contentType("text/xml; charset=UTF-8");
                try {
                    ctx.result(buildCapabilitiesXml(scripts));
                    System.out.println("WPS here");
                } catch (Exception e) {
                    e.printStackTrace();
                    ctx.status(500).result("Erreur GetCapabilities : " + e.getMessage());
                }
            } else if ("DescribeProcess".equalsIgnoreCase(request)) {
                System.out.println("WPS Server here " + request);
                ctx.contentType("text/xml; charset=UTF-8");
                //ctx.result(buildDescribeProcessXml(ctx.queryParam("identifier")));
                ctx.result(buildDescribeProcessXml(ctx.queryParam("identifier"), scripts));
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

                File scriptFile = findScript(scripts,packageName, scriptName);
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

                File scriptFile = findScript(scripts,pkg, scriptName);
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

    private static String buildCapabilitiesXml(Map<String, List<String>> grouped) throws Exception {
        System.out.println("ici buildCapabilitiesXml appelllé");


        WPSCapabilitiesType capabilities = Wps10Factory.eINSTANCE.createWPSCapabilitiesType();
        capabilities.setLang("en");
        capabilities.setService("WPS");
        capabilities.setVersion("1.0.0");

        System.out.println("Création ServiceIdentification");
        ServiceIdentificationType serviceId = Ows11Factory.eINSTANCE.createServiceIdentificationType();
        LanguageStringType title = Ows11Factory.eINSTANCE.createLanguageStringType();
        title.setValue("Prototype GeoServer WPS");
        serviceId.getTitle().add(title);
        CodeType serviceType = Ows11Factory.eINSTANCE.createCodeType();
        serviceType.setValue("WPS");
        serviceId.setServiceType(serviceType);
        serviceId.setServiceTypeVersion("1.0.0");

        //serviceId.getAbstract().add();

        LanguageStringType abs = Ows11Factory.eINSTANCE.createLanguageStringType();
        serviceId.getAbstract().add(abs);

        capabilities.setServiceIdentification(serviceId);

        System.out.println("ici ServiceProvider");
        ServiceProviderType provider = Ows11Factory.eINSTANCE.createServiceProviderType();
        provider.setProviderName("The Ancient Geographers");

        OnlineResourceType site = Ows11Factory.eINSTANCE.createOnlineResourceType();
        site.setHref("http://localhost:8000/");
        provider.setProviderSite(site);

        ResponsiblePartySubsetType contact = Ows11Factory.eINSTANCE.createResponsiblePartySubsetType();
        provider.setServiceContact(contact);

        capabilities.setServiceProvider(provider);

        OperationsMetadataType ops = Ows11Factory.eINSTANCE.createOperationsMetadataType();
        ops.getOperation().add(createOperation("GetCapabilities", "http://localhost:8000/wps"));
        ops.getOperation().add(createOperation("DescribeProcess", "http://localhost:8000/wps"));
        ops.getOperation().add(createOperation("Execute", "http://localhost:8000/wps"));
        capabilities.setOperationsMetadata(ops);

        ProcessOfferingsType offerings = Wps10Factory.eINSTANCE.createProcessOfferingsType();

        //Map<String, List<String>> grouped = scanScriptsGrouped();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            String group = entry.getKey();
            for (String process : entry.getValue()) {
                File scriptFile = findScript(grouped,group, process);
                if (scriptFile == null) throw new Exception("Script non trouvee: " + group+":"+process);

                Map<String, Object> metadata = parseGroovyScriptMetadata(scriptFile);

                ProcessBriefType processes = Wps10Factory.eINSTANCE.createProcessBriefType();
                processes.setProcessVersion("1.0.0");

                CodeType code = Ows11Factory.eINSTANCE.createCodeType();
                code.setValue(group + ":" + process);
                processes.setIdentifier(code);

                LanguageStringType procTitle = Ows11Factory.eINSTANCE.createLanguageStringType();
                procTitle.setValue(metadata.getOrDefault("title", process).toString());
                processes.setTitle(procTitle);

                LanguageStringType abstractText = Ows11Factory.eINSTANCE.createLanguageStringType();
                abstractText.setValue(metadata.getOrDefault("description", process).toString());
                processes.setAbstract(abstractText);

                offerings.getProcess().add(processes);
            }
        }
        capabilities.setProcessOfferings(offerings);

        LanguagesType1 languages = Wps10Factory.eINSTANCE.createLanguagesType1();
        DefaultType2 defaultLang = Wps10Factory.eINSTANCE.createDefaultType2();
        defaultLang.setLanguage("en-US");
        languages.setDefault(defaultLang);
        LanguagesType supported = Wps10Factory.eINSTANCE.createLanguagesType();
        supported.getLanguage().add("en-US");
        languages.setSupported(supported);

        capabilities.setLanguages(languages);

       // System.out.println("etat capabilitie:"+capabilities.toString());


        Encoder encoder = new Encoder(new WPSConfiguration());
        encoder.setIndenting(false);

        encoder.getNamespaces().declarePrefix("wps",   "http://www.opengis.net/wps/1.0.0");
        encoder.getNamespaces().declarePrefix("ows",   "http://www.opengis.net/ows/1.1");
        encoder.getNamespaces().declarePrefix("xlink", "http://www.w3.org/1999/xlink");
        encoder.getNamespaces().declarePrefix("xsi",   "http://www.w3.org/2001/XMLSchema-instance");

        encoder.setSchemaLocation(
                "http://www.opengis.net/wps/1.0.0",
                "http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd"
        );
        System.out.println("encoder "+encoder.toString());

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            encoder.encode(capabilities,
                    new QName("http://www.opengis.net/wps/1.0.0", "Capabilities"),
                    out);
            System.out.println("wpstest"+ out.toString(StandardCharsets.UTF_8));
            return out.toString(StandardCharsets.UTF_8);
        } catch (Throwable e) {
            e.printStackTrace();
            System.out.println("erreur "+e.getMessage());
            throw e;
        }
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


    private static String buildDescribeProcessXml(String identifier,Map<String, List<String>> scripts) throws Exception {
        ProcessDescriptionsType processDescriptions = Wps10Factory.eINSTANCE.createProcessDescriptionsType();
        processDescriptions.setLang("en");
        processDescriptions.setService("WPS");
        processDescriptions.setVersion("1.0.0");

        if (identifier == null || identifier.isEmpty() || identifier.equalsIgnoreCase("all")) {
            //Map<String, List<String>> grouped = scanScriptsGrouped();
            for (Map.Entry<String, List<String>> entry : scripts.entrySet()) {
                String group = entry.getKey();
                for (String process : entry.getValue()) {
                    try {
                        addProcessDescription(scripts,processDescriptions, group + ":" + process);
                    } catch (Exception e) {
                        System.err.println("Erreur   processus " + group + ":" + process + ": " + e.getMessage());
                    }
                }
            }
        } else {
            addProcessDescription(scripts,processDescriptions, identifier);
        }

        Encoder encoder = new Encoder(new WPSConfiguration());
        encoder.setIndenting(false);
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

    private static void addProcessDescription(Map<String, List<String>> scripts,ProcessDescriptionsType processDescriptions, String identifier) throws Exception {
        String[] parts = identifier.split(":");
        if (parts.length != 2) throw new IllegalArgumentException("Identifiant doit être au format 'Groupe:Nom'");
        String group = parts[0], processName = parts[1];

        File scriptFile = findScript(scripts,group, processName);
        if (scriptFile == null) throw new Exception("Script non trouvé: " + identifier);

        Map<String, Object> metadata = parseGroovyScriptMetadata(scriptFile);

        ProcessDescriptionType process = Wps10Factory.eINSTANCE.createProcessDescriptionType();
        process.setProcessVersion("1.0.0");
        process.setStatusSupported(true);
        process.setStoreSupported(true);

        CodeType code = Ows11Factory.eINSTANCE.createCodeType();
        code.setValue(identifier);
        process.setIdentifier(code);

        LanguageStringType title = Ows11Factory.eINSTANCE.createLanguageStringType();
        title.setValue(metadata.getOrDefault("title", processName).toString());
        process.setTitle(title);

        LanguageStringType abstractText = Ows11Factory.eINSTANCE.createLanguageStringType();
        abstractText.setValue(metadata.getOrDefault("description", processName).toString());
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

            LiteralOutputType literalOutput = Wps10Factory.eINSTANCE.createLiteralOutputType();
            output.setLiteralOutput(literalOutput);
            outputs.getOutput().add(output);
        }
        if (!outputsMap.isEmpty()) process.setProcessOutputs(outputs);

        processDescriptions.getProcessDescription().add(process);
    }

    private static void configureLiteralInput(InputDescriptionType input, Map<String, Object> inputProps) {
        LiteralInputType literalInput = Wps10Factory.eINSTANCE.createLiteralInputType();


        DomainMetadataType dataType = Ows11Factory.eINSTANCE.createDomainMetadataType();
        String typeName = "xs:string";
        if (inputProps.containsKey("type")) {
            Object typeObj = inputProps.get("type");
            if (typeObj instanceof Class<?>) {
                Class<?> typeClass = (Class<?>) typeObj;
                if (typeClass == String.class) typeName = "xs:string";
                else if (typeClass == Integer.class) typeName = "xs:integer";
                else if (typeClass == Double.class || typeClass == Float.class) typeName = "xs:double";
                else if (typeClass == Boolean.class) typeName = "xs:boolean";
            } else if (typeObj instanceof String) {
                String typeStr = (String) typeObj;
                if (typeStr.contains("String")) typeName = "xs:string";
                else if (typeStr.contains("Integer")) typeName = "xs:integer";
                else if (typeStr.contains("Double") || typeStr.contains("Float")) typeName = "xs:double";
                else if (typeStr.contains("Boolean")) typeName = "xs:boolean";
            }
        }
        dataType.setValue(typeName);
        literalInput.setDataType(dataType);
        AnyValueType anyValue = Ows11Factory.eINSTANCE.createAnyValueType();
        literalInput.setAnyValue(anyValue);

        input.setLiteralData(literalInput);
    }

//    private static Map<String, Object> parseGroovyScriptMetadata(File scriptFile) throws IOException {
//        Map<String, Object> metadata = new HashMap<>();
//        metadata.put("title", scriptFile.getName().replace(".groovy", ""));
//        metadata.put("description", "");
//        metadata.put("inputs", new HashMap<String, Map<String,Object>>());
//        metadata.put("outputs", new HashMap<String, Map<String,Object>>());
//
//        try {
//            List<String> lines = Files.readAllLines(scriptFile.toPath(), StandardCharsets.UTF_8);
//            String scriptContent = String.join("\n", lines);
//
//            Binding binding = new Binding();
//            binding.setVariable("String", String.class);
//            binding.setVariable("Integer", Integer.class);
//            binding.setVariable("Double", Double.class);
//            binding.setVariable("Boolean", Boolean.class);
//
//            GroovyShell shell = new GroovyShell(binding);
//            shell.evaluate(scriptContent);
//
//            if (binding.hasVariable("title")) metadata.put("title", binding.getVariable("title"));
//            if (binding.hasVariable("description")) metadata.put("description", binding.getVariable("description"));
//            if (binding.hasVariable("inputs")) metadata.put("inputs", binding.getVariable("inputs"));
//            if (binding.hasVariable("outputs")) metadata.put("outputs", binding.getVariable("outputs"));
//
//        } catch (Exception e) {
//            System.err.println("Erreur evaluation du script " + scriptFile.getName() + ": " + e.getMessage());
//
//        }
//
//        return metadata;
//    }

private static Map<String, Object> parseGroovyScriptMetadata(File scriptFile) throws IOException {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("title", scriptFile.getName().replace(".groovy", ""));
    metadata.put("description", "");
    metadata.put("inputs", new HashMap<String, Map<String,Object>>());
    metadata.put("outputs", new HashMap<String, Map<String,Object>>());

    String content = Files.readString(scriptFile.toPath(), StandardCharsets.UTF_8);
    Matcher mTitle = Pattern.compile("(?m)^\\s*title\\s*=\\s*(.+)").matcher(content);
    if (mTitle.find()) {
        metadata.put("title", parseGroovyString(mTitle.group(1)));
    }

    Matcher mDesc = Pattern.compile("(?m)^\\s*description\\s*=\\s*(.+?)(?=\\n\\s*inputs\\s*=)", Pattern.DOTALL).matcher(content);
    if (mDesc.find()) {
        metadata.put("description", parseGroovyString(mDesc.group(1)));
    }
    String inputsBlock = extractBlock(content, "inputs");
    if (!inputsBlock.isEmpty()) {
        Map<String, Map<String,Object>> inputsMap = parseInputsOrOutputsBlock(inputsBlock);
        metadata.put("inputs", inputsMap);
    }
    String outputsBlock = extractBlock(content, "outputs");
    if (!outputsBlock.isEmpty()) {
        Map<String, Map<String,Object>> outputsMap = parseInputsOrOutputsBlock(outputsBlock);
        metadata.put("outputs", outputsMap);
    }

    return metadata;
}
    private static String extractBlock(String content, String blockName) {
        int start = content.indexOf(blockName + " = [");
        if (start == -1) return "";
        start += (blockName + " = ").length();

        int depth = 0;
        int end = start;
        while (end < content.length()) {
            char c = content.charAt(end);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) break;
            }
            end++;
        }
        return content.substring(start, end + 1);
    }

    private static String parseGroovyString(String input) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("'([^']*)'").matcher(input);
        while (m.find()) {
            sb.append(m.group(1));
        }
        return sb.toString().trim();
    }

    private static Map<String, Map<String,Object>> parseInputsOrOutputsBlock(String block) {
        Map<String, Map<String,Object>> result = new HashMap<>();
        Matcher entryMatcher = Pattern.compile("(\\w+)\\s*:\\s*\\[(.*?)](,|$)", Pattern.DOTALL).matcher(block);

        while (entryMatcher.find()) {
            String id = entryMatcher.group(1);
            String body = entryMatcher.group(2);

            Map<String, Object> props = new HashMap<>();

            Matcher kv = Pattern.compile("(\\w+)\\s*:\\s*((?:'[^']*'(?:\\s*\\+\\s*)?)*)", Pattern.DOTALL).matcher(body);
            while (kv.find()) {
                String key = kv.group(1);
                String value = parseGroovyString(kv.group(2));
                props.put(key, value);
            }

            Matcher typeM = Pattern.compile("type\\s*:\\s*(\\w+)\\.class").matcher(body);
            if (typeM.find()) {
                String typeStr = typeM.group(1);
                switch (typeStr) {
                    case "String":  props.put("type", String.class); break;
                    case "Integer": props.put("type", Integer.class); break;
                    case "Double":  props.put("type", Double.class); break;
                    case "Boolean": props.put("type", Boolean.class); break;
                    default:        props.put("type", String.class); break;
                }
            }

            result.put(id, props);
        }
        return result;
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

    private static File findScript(Map<String, List<String>> allScripts,String group, String scriptName) {
        if (allScripts.containsKey(group) && allScripts.get(group).contains(scriptName)) {
            String path = scriptWrapper.SCRIPTS_ROOT + "/" + group.replace('.', '/') + "/" + scriptName + ".groovy";
            File f = new File(path);
            return f.exists() ? f : null;
        }
        return null;

    }

    private static Connection openDatabaseConnection() throws SQLException, ClassNotFoundException {
        String databasePath = "jdbc:h2:" + WpsServer.class.getResource("static/database").getPath() + ";AUTO_SERVER=TRUE";
        System.out.println("databasePath:"+databasePath);
        Driver.load();
        Connection connection = DriverManager.getConnection(databasePath, "", "");
        H2GISFunctions.load(connection);
        return new ConnectionWrapper(connection);
    }
}
