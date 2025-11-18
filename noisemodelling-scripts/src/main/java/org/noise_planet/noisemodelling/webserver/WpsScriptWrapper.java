package org.noise_planet.noisemodelling.webserver;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The `WpsScriptWrapper` class provides functionalities to manage, organize, and process
 * Groovy scripts for use in a Web Processing Service (WPS) environment. It includes methods
 * for loading scripts, parsing their metadata, grouping them into categories, and generating
 * WPS-compliant XML documents.
 *
 * The class relies on the directory structure of Groovy script files to organize them into
 * groups, and it provides mechanisms for extracting script information, such as inputs,
 * outputs, descriptions, and other metadata. These capabilities facilitate the integration
 * of scripts into a WPS framework by generating necessary XML representations.
 */
public class WpsScriptWrapper {

    /**
     * The root directory where Groovy script files are stored and managed.
     * This variable represents the base directory from which scripts are loaded,
     * grouped, and processed within the WpsScriptWrapper class.
     */
    private Path scriptsRoot;
    Path projectRoot = Paths.get(System.getProperty("user.dir"));

    /**
     * Default constructor for the WpsScriptWrapper class.
     *
     * This constructor initializes the WpsScriptWrapper instance by setting the
     * `scriptsRoot` field to point to the default directory containing Groovy
     * script files. The directory is resolved relative to the current working
     * directory of the application and is expected to exist at:
     * "noisemodelling-scripts/src/main/groovy/org/noise_planet/noisemodelling/scripts".
     */
    public WpsScriptWrapper() {
        if (!Files.exists(projectRoot.resolve("noisemodelling-scripts")) && projectRoot.getParent() != null) {
            projectRoot = projectRoot.getParent();
        }
        Path devScripts = projectRoot.resolve(Paths.get("noisemodelling-scripts/src/main/groovy/org/noise_planet/noisemodelling/scripts")).normalize();

        Path zipScripts = projectRoot.resolve("noisemodelling/scripts");
        if (Files.exists(devScripts)) {
            this.scriptsRoot = devScripts;
        } else if (Files.exists(zipScripts)) {
            this.scriptsRoot = zipScripts;
        } else {
            throw new RuntimeException("Scripts not found in expected locations");
        }
    }


    /**
     * Loads Groovy scripts from a predefined directory structure and organizes them into groups.
     *
     * This method scans the available scripts using the `scanScriptsGrouped` method to organize
     * them by groups, then attempts to locate the corresponding script files for each script
     * name in the directory structure. Only valid script files that exist on the file system
     * are included in the resulting map.
     *
     * @return a map where the keys are script group names and the values are lists of
     *         File objects corresponding to the scripts in each group
     */
    public  Map<String, List<File>> loadScripts(){
        Map<String, List<String>> groupedScripts = scanScriptsGrouped();

        Map<String, List<File>> scriptFiles = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : groupedScripts.entrySet()) {
            String group = entry.getKey();
            List<File> files = new ArrayList<>();

            for (String scriptName : entry.getValue()) {
                File f = findScript(group, scriptName);
                if (f != null && f.exists()) {
                    files.add(f);
                }
            }
            scriptFiles.put(group, files);
        }

        return scriptFiles;
    }

    /**
     * Scans a predefined directory structure containing Groovy scripts and organizes them into groups.
     *
     * This method traverses the directory structure rooted at the `scriptsRoot` location recursively.
     * It identifies Groovy script files (files ending with the `.groovy` extension), extracts their names
     * (excluding the file extension), and groups them into categories based on the directory structure.
     * Each group corresponds to a directory path relative to the root directory.
     *
     * If the root directory does not exist or contains no valid files, an empty map is returned.
     *
     * @return a map where the keys are group names (relative directory paths) and the values are lists
     *         of script names (without file extensions) belonging to each group
     */
    public Map<String, List<String>> scanScriptsGrouped() {
        Map<String, List<String>> grouped = new TreeMap<>();
        File baseDir = scriptsRoot.toFile();
        if (!baseDir.exists()) {
            return grouped;
        }
        scanRecursive(baseDir, "", grouped);
        return grouped;
    }


    /**
     * Finds a Groovy script file based on the specified group and script name.
     *
     * This method builds the path to the desired script file by resolving the group
     * and script name against a predefined root directory. If the file exists, it
     * returns a {@code File} object representing the script; otherwise, it returns null.
     *
     * @param group the name of the group or folder containing the script
     *              (relative to the root directory)
     * @param scriptName the name of the script file (without the ".groovy" extension)
     * @return a {@code File} object representing the script file if it exists,
     *         or null if the file does not exist
     */
    public File findScript(String group, String scriptName) {
        Path path = scriptsRoot.resolve(group).resolve(scriptName + ".groovy");
        return Files.exists(path) ? path.toFile() : null;
    }

    /**
     * Recursively scans a directory for Groovy script files and groups them into categories
     * based on the directory structure. Each group corresponds to a directory path relative
     * to the root directory.
     *
     * @param dir the directory to scan for Groovy script files
     * @param currentGroup the current group name, representing the relative path from the root directory
     * @param grouped a map where keys are group names (relative directory paths) and values are lists
     *        of script names (without file extensions) that belong to each group
     */
    private void scanRecursive(File dir, String currentGroup, Map<String, List<String>> grouped) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String newGroup = currentGroup.isEmpty() ? f.getName() : currentGroup + "/" + f.getName();
                scanRecursive(f, newGroup, grouped);
            } else if (f.getName().endsWith(".groovy")) {
                grouped.computeIfAbsent(currentGroup, k -> new ArrayList<>())
                        .add(f.getName().replace(".groovy", ""));
            }
        }
    }

    /**
     * Builds a list of {@link ScriptWrapper} objects from the Groovy scripts available
     * in the given directory or JAR.
     *
     * <p>This method reads each Groovy script file, parses its metadata (title,
     * description, inputs, and outputs), and wraps it into a {@link ScriptWrapper}
     * instance. The resulting list can be used to generate WPS Capabilities and
     * DescribeProcess documents.</p>
     *
     * @param scriptFiles a map of grouped script files (group → list of script files)
     * @return a list of {@code ScriptWrapper} instances representing available scripts
     * @throws IOException if a script file cannot be read or parsed
     */
    public static List<ScriptWrapper> buildScriptWrappers(Map<String, List<File>> scriptFiles) throws IOException {
        List<ScriptWrapper> wrappers = new ArrayList<>();

        for (Map.Entry<String, List<File>> entry : scriptFiles.entrySet()) {
            String group = entry.getKey();

            for (File file : entry.getValue()) {
                Map<String, Object> metadata = parseGroovyScriptMetadata(file);

                ScriptWrapper wrapper = new ScriptWrapper();
                wrapper.id = group + ":" + file.getName().replace(".groovy", "");
                wrapper.title = metadata.getOrDefault("title", wrapper.id).toString();
                wrapper.description = metadata.getOrDefault("description", "").toString();
                wrapper.path = file.toPath();

                // Convert metadata inputs into ScriptInput instances
                Map<String, Map<String, Object>> inputsMeta =
                        (Map<String, Map<String, Object>>) metadata.getOrDefault("inputs", new HashMap<>());
                Map<String, ScriptWrapper.ScriptInput> inputWrappers = new HashMap<>();

                for (Map.Entry<String, Map<String, Object>> input : inputsMeta.entrySet()) {
                    ScriptWrapper.ScriptInput si = new ScriptWrapper.ScriptInput();
                    si.id = input.getKey();
                    si.title = input.getValue().getOrDefault("title", input.getKey()).toString();
                    si.description = input.getValue().getOrDefault("description", "").toString();
                    Object typeObj = input.getValue().get("type");
                    si.type = (typeObj != null) ? typeObj.toString() : "String";
                    Object minValue = input.getValue().get("min");

                    if (minValue != null) {
                        si.optional = true;
                    }
                    inputWrappers.put(si.id, si);
                }

                Map<String, Map<String, Object>> outputsMeta =
                        (Map<String, Map<String, Object>>) metadata.getOrDefault("outputs", new HashMap<>());
                Map<String, ScriptWrapper.ScriptOutput> outputWrappers = new HashMap<>();

                for (Map.Entry<String, Map<String, Object>> output : outputsMeta.entrySet()) {
                    ScriptWrapper.ScriptOutput si = new ScriptWrapper.ScriptOutput();
                    si.id = output.getKey();
                    si.title = output.getValue().getOrDefault("title", output.getKey()).toString();
                    outputWrappers.put(si.id, si);
                }


                wrapper.inputs = inputWrappers;
                wrapper.outputs = outputWrappers;
                wrappers.add(wrapper);
            }
        }

        return wrappers;
    }

    /**
     * Generates a WPS GetCapabilities XML document listing all available scripts.
     *
     * @param scripts the list of available ScriptWrapper instances
     * @return XML string for WPS GetCapabilities
     */
    public static String generateCapabilitiesXML(List<ScriptWrapper> scripts) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<wps:Capabilities xmlns:wps=\"http://www.opengis.net/wps/1.0.0\"\n");
        sb.append("    xmlns:ows=\"http://www.opengis.net/ows/1.1\"\n");
        sb.append("    xmlns:xlink=\"http://www.w3.org/1999/xlink\"\n");
        sb.append("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("    xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 ");
        sb.append("http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\">\n");

        sb.append("  <ows:ServiceIdentification>\n");
        sb.append("    <ows:Title>Prototype GeoServer WPS</ows:Title>\n");
        sb.append("    <ows:Abstract></ows:Abstract>\n");
        sb.append("    <ows:ServiceType>WPS</ows:ServiceType>\n");
        sb.append("    <ows:ServiceTypeVersion>1.0.0</ows:ServiceTypeVersion>\n");
        sb.append("  </ows:ServiceIdentification>\n");

        sb.append("  <wps:ProcessOfferings>\n");
        for (ScriptWrapper script : scripts) {
            sb.append("    <wps:Process wps:processVersion=\"1.0.0\">\n");
            sb.append("      <ows:Identifier>").append(script.id).append("</ows:Identifier>\n");
            sb.append("      <ows:Title>").append(script.title).append("</ows:Title>\n");
            sb.append("      <ows:Abstract>").append(escapeForWpsXml(script.description)).append("</ows:Abstract>\n");
            sb.append("    </wps:Process>\n");
        }
        sb.append("  </wps:ProcessOfferings>\n");
        sb.append("</wps:Capabilities>\n");
        return sb.toString();
    }

    /**
     * Generates a WPS DescribeProcess XML for a specific Groovy script.
     *
     * @param wrapper the ScriptWrapper representing the script
     * @return XML string for WPS DescribeProcess
     */
    public static String generateDescribeProcessXML(ScriptWrapper wrapper) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<wps:ProcessDescriptions xmlns:wps=\"http://www.opengis.net/wps/1.0.0\"\n");
        sb.append("    xmlns:ows=\"http://www.opengis.net/ows/1.1\"\n");
        sb.append("    xmlns:xlink=\"http://www.w3.org/1999/xlink\"\n");
        sb.append("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("    xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 ");
        sb.append("http://schemas.opengis.net/wps/1.0.0/wpsAll.xsd\"\n");
        sb.append("    xml:lang=\"en-US\">\n");

        sb.append("  <ProcessDescription wps:processVersion=\"1.0.0\" storeSupported=\"true\" statusSupported=\"true\">\n");
        sb.append("    <ows:Identifier>").append(wrapper.id).append("</ows:Identifier>\n");
        sb.append("    <ows:Title>").append(wrapper.title).append("</ows:Title>\n");
        sb.append("    <ows:Abstract>").append(escapeForWpsXml(wrapper.description)).append("</ows:Abstract>\n");

        sb.append("    <DataInputs>\n");
        for (ScriptWrapper.ScriptInput input : wrapper.inputs.values()) {
            input.type = input.type.replace("class java.lang.", "");
            if (input.optional) {
                sb.append("      <Input minOccurs=\"0\" maxOccurs=\"1\">\n");
            }else {
                sb.append("      <Input minOccurs=\"1\" maxOccurs=\"1\">\n");
            }
            sb.append("        <ows:Identifier>").append(input.id).append("</ows:Identifier>\n");
            sb.append("        <ows:Title>").append(input.title).append("</ows:Title>\n");
            sb.append("        <ows:Abstract>").append(escapeForWpsXml(input.description)).append("</ows:Abstract>\n");
            sb.append("        <LiteralData>\n");
            if ("class java.lang.Boolean".equalsIgnoreCase(input.type)) {
                sb.append("      <ows:DataType>xs:boolean</ows:DataType>\n");
                sb.append("      <ows:AllowedValues>\n");
                sb.append("        <ows:Value>true</ows:Value>\n");
                sb.append("        <ows:Value>false</ows:Value>\n");
                sb.append("      </ows:AllowedValues>\n");
                sb.append("      <DefaultValue>false</DefaultValue>\n");
            }else {
                sb.append("          <ows:DataType>xs:").append((input.type).toLowerCase()).append("</ows:DataType>\n");
            }

            sb.append("        </LiteralData>\n");
            sb.append("      </Input>\n");
        }
        sb.append("    </DataInputs>\n");
        sb.append("    <ProcessOutputs>\n");
        for (ScriptWrapper.ScriptOutput output : wrapper.outputs.values()) {
            sb.append("      <Output>\n");
            sb.append("        <ows:Identifier>").append(output.id).append("</ows:Identifier>\n");
            sb.append("        <ows:Title>").append(escapeForWpsXml(output.title)).append("</ows:Title>\n");
            sb.append("        <LiteralOutput>\n");
            sb.append("          <ows:DataType>String</ows:DataType>\n");
            sb.append("        </LiteralOutput>\n");
            sb.append("      </Output>\n");
        }
        sb.append("    </ProcessOutputs>\n");
        sb.append("  </ProcessDescription>\n");
        sb.append("</wps:ProcessDescriptions>\n");

        return sb.toString();
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
     * Escapes a given input string for use in WPS (Web Processing Service) XML documents.
     * This method sanitizes the input by removing HTML tags, replacing certain characters
     * with their XML entity equivalents, and trimming any leading or trailing whitespace.
     *
     * @param input the input string to be escaped; can be null
     * @return the escaped string suitable for inclusion in a WPS XML document;
     *         returns an empty string if the input is null
     */
    private static String escapeForWpsXml(String input) {
        if (input == null) return "";
        String noHtml = input.replaceAll("<[^>]+>", " ");
        return noHtml
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .trim();
    }


}


