package org.noise_planet.noisemodelling.webserver;

import net.opengis.ows11.*;
import net.opengis.wps10.*;
import org.geotools.wps.WPSConfiguration;
import org.geotools.xsd.Encoder;
import javax.xml.namespace.QName;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The `ScriptParser` class provides methods and utilities for parsing and processing
 * metadata from Groovy script files to construct XML representations,
 * configure process descriptions, and handle input/output mappings
 * for Web Processing Service (WPS) capabilities.
 *
 * This class includes functionality to:
 * - Build WPS Capabilities and DescribeProcess XML.
 * - Extract and parse metadata from Groovy script files.
 * - Configure process inputs, outputs, and operation descriptions.
 * - Handle data extraction from WPS Execute requests.
 */
public class ScriptParser {
    /**
     * A static and final instance of the GroovyScriptExtractor class used for managing or interacting with script functionalities
     * within the ScriptParser class.
     *
     * This instance serves as a key component in handling operations related to scripts, such as metadata extraction,
     * XML generation, or script processing, and is intended to be globally accessible within the class.
     */
    public static final GroovyScriptExtractor groovyScriptExtractor = new GroovyScriptExtractor();



    /**
     * Builds an XML representation of WPS capabilities based on the provided grouped scripts.
     *
     * This method constructs the XML representation of Web Processing Service (WPS) capabilities
     * using the provided map, where each key represents a group and the corresponding value is
     * a list of process names. The resulting XML includes metadata about the service, its processes,
     * and supported operations. If any script required to build the capabilities XML is not found,
     * an exception is thrown.
     *
     * @param grouped a map where keys are group names and values are lists of process names belonging to each group
     * @return a String containing the generated WPS capabilities XML
     * @throws Exception if a required script is not found or if there is an error during XML generation
     */
    public static String buildCapabilitiesXml(Map<String, List<String>> grouped) throws Exception {

        WPSCapabilitiesType capabilities = Wps10Factory.eINSTANCE.createWPSCapabilitiesType();
        capabilities.setLang("en");
        capabilities.setService("WPS");
        capabilities.setVersion("1.0.0");

        ServiceIdentificationType serviceId = Ows11Factory.eINSTANCE.createServiceIdentificationType();
        LanguageStringType title = Ows11Factory.eINSTANCE.createLanguageStringType();
        title.setValue("Prototype GeoServer WPS");
        serviceId.getTitle().add(title);
        CodeType serviceType = Ows11Factory.eINSTANCE.createCodeType();
        serviceType.setValue("WPS");
        serviceId.setServiceType(serviceType);
        serviceId.setServiceTypeVersion("1.0.0");

        LanguageStringType abs = Ows11Factory.eINSTANCE.createLanguageStringType();
        serviceId.getAbstract().add(abs);

        capabilities.setServiceIdentification(serviceId);

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

        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            String group = entry.getKey();
            for (String process : entry.getValue()) {
                File scriptFile = groovyScriptExtractor.findScript(groovyScriptExtractor.scripts,group, process);
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



        Encoder encoder = new Encoder(new WPSConfiguration());
        encoder.setIndenting(false);

        encoder.getNamespaces().declarePrefix("ows",   "http://www.opengis.net/ows/1.1");
        encoder.getNamespaces().declarePrefix("wps",   "http://www.opengis.net/wps/1.0.0");
        encoder.getNamespaces().declarePrefix("xlink", "http://www.w3.org/1999/xlink");
        encoder.getNamespaces().declarePrefix("xsi",   "http://www.w3.org/2001/XMLSchema-instance");


        encoder.setSchemaLocation(
                "http://www.opengis.net/wps/1.0.0",
                "http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd"
        );

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            encoder.encode(capabilities,
                    new QName("http://www.opengis.net/wps/1.0.0", "Capabilities"),
                    out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Throwable e) {
            e.printStackTrace();
            System.out.println("erreur "+e.getMessage());
            throw e;
        }
    }

    /**
     * Creates and configures an `OperationType` object for a specific operation.
     *
     * This method initializes a new `OperationType` instance and sets up its name
     * and supported communication protocols (GET and POST) with appropriate URLs.
     * The generated `OperationType` object can be used to describe an operation
     * in a Web Processing Service (WPS) or similar context.
     *
     * @param name the name of the operation to be created
     * @param baseUrl the base URL used to construct the communication endpoints
     * @return an `OperationType` object configured with the provided operation name and base URL
     */
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


    /**
     * Builds an XML representation of a WPS DescribeProcess response for the specified process or all available processes.
     *
     * This method generates the XML response for a Web Processing Service (WPS) DescribeProcess request,
     * based on the provided process identifier and scripts. If the identifier is 'null', empty, or set to "all",
     * the XML is constructed for all available processes. If a specific identifier is provided, only the corresponding
     * process is included in the output. An exception is thrown in case of errors during the XML generation or
     * if required scripts are not found.
     *
     * @param identifier the unique identifier of the process to describe, or "all" to describe all available processes
     * @param scripts a map where keys represent process groups and values are lists of process names within each group
     * @return a String containing the generated WPS DescribeProcess XML
     * @throws Exception if there is an error during XML generation or if required scripts cannot be found
     */
    public static String buildDescribeProcessXml(String identifier, Map<String, List<String>> scripts) throws Exception {
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

    /**
     * Adds a process description to the provided `ProcessDescriptionsType` object, based on the
     * metadata extracted from a script file associated with the given identifier.
     *
     * This method parses the metadata of a script to create and set up a process description,
     * including its identifier, title, inputs, and outputs. The process description is then added
     * to the given `ProcessDescriptionsType`. If the script is not found or an error occurs during
     * processing, an exception is thrown.
     * @param processDescriptions  an instance of `ProcessDescriptionsType` to which the generated process description will be added
     * @param identifier           the identifier of the process to describe, in the format "Group:ProcessName"
     * @throws Exception           if the script corresponding to the identifier cannot be found or if there is an error during processing
     */
    private static void addProcessDescription(ProcessDescriptionsType processDescriptions, String identifier) throws Exception {
        String[] parts = identifier.split(":");
        if (parts.length != 2) throw new IllegalArgumentException("Identifiant doit être au format de 'Groupe:Nom'");
        String group = parts[0], processName = parts[1];

        File scriptFile = groovyScriptExtractor.findScript(groovyScriptExtractor.scripts,group, processName);
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
            input.setMinOccurs(BigInteger.ZERO);
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

    /**
     * Configures a literal input type for an InputDescriptionType object.
     *
     * This method creates a LiteralInputType instance and sets its data type
     * based on the provided input properties. It determines the appropriate XML schema
     * data type (e.g., xs:string, xs:integer, xs:double, xs:boolean) for the literal input
     * using the "type" key in the input properties. If no type is provided, it defaults
     * to "xs:string". Additionally, the method sets AnyValue to allow any valid value
     * for the literal input.
     *
     * @param input the InputDescriptionType object to configure with a literal input
     * @param inputProps a map of properties describing the input; the "type" key is used
     *                   to determine the data type of the literal input
     */
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

    /**
     * Parses metadata from a provided Groovy script file and extracts details such as title,
     * description, inputs, and outputs defined within the script. The method analyzes the script
     * content to populate a metadata map, which includes blocks of inputs and outputs if defined.
     *
     * @param scriptFile the Groovy script file to parse for metadata
     * @return a map containing metadata fields such as "title", "description", "inputs", and "outputs",
     *         where "inputs" and "outputs" are themselves maps with their respective properties
     * @throws IOException if an error occurs while reading the script file
     */
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
    /**
     * Extracts a specific block of content defined by a block name from the provided string.
     *
     * This method searches for a block of text that starts with a specific block name followed
     * by " = [" and extracts the entire block until the matching closing bracket "]" is found.
     * If the block is not found in the content, an empty string is returned.
     *
     * @param content the input string containing the blocks of text to search
     * @param blockName the name of the block to extract
     * @return the extracted block of text, including its enclosing brackets, or an empty string if the block is not found
     */
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

    /**
     * Parses a Groovy-style string and extracts concatenated substrings enclosed in single quotes.
     *
     * This method matches all substrings separated by single quotes within the input string
     * and concatenates them in the order they are found. Leading and trailing whitespace
     * in the result is trimmed before returning.
     *
     * @param input the Groovy-style string to be parsed
     * @return a single string resulting from the concatenation of all substrings found
     *         within single quotes in the input, or an empty string if no matches are found
     */
    private static String parseGroovyString(String input) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("'([^']*)'").matcher(input);
        while (m.find()) {
            sb.append(m.group(1));
        }
        return sb.toString().trim();
    }

    /**
     * Parses a string representing a block of inputs or outputs and extracts
     * their properties into a structured map.
     *
     * This method identifies individual entries within the block, retrieves their
     * properties (e.g., key-value pairs), and determines the data type for each
     * entry based on the "type" property. If the type is not specified or unrecognized,
     * it defaults to `String.class`.
     *
     * @param block the string representing the block of inputs or outputs to be parsed
     * @return a map where each key corresponds to an input or output identifier
     *         and its value is another map containing the properties of the input
     *         or output
     */
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


    /**
     * Extracts the process name from the given `ExecuteType` object.
     *
     * This method retrieves the identifier value from the provided `ExecuteType` object.
     * If the identifier is null, the method returns null. Otherwise, it returns the value
     * of the identifier.
     *
     * @param execute the `ExecuteType` object from which to extract the process name
     * @return the extracted process name as a String, or null if the identifier is not set
     */
    public static String extractProcessName(ExecuteType execute) {
        return execute.getIdentifier() != null ? execute.getIdentifier().getValue() : null;
    }

    /**
     * Extracts inputs from the provided ExecuteType object and maps them to a key-value pair structure.
     *
     * This method processes the inputs defined within the ExecuteType instance, iterating through its
     * DataInputs, and mapping each input's identifier to its corresponding literal data value. If the
     * DataInputs are null or no valid inputs are found, an empty map is returned.
     *
     * @param execute the ExecuteType object containing DataInputs to extract
     * @return a map where the keys are input identifiers (as Strings) and the values are their corresponding data (as Objects)
     */
    static Map<String, Object> extractInputs(ExecuteType execute) {
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

}
