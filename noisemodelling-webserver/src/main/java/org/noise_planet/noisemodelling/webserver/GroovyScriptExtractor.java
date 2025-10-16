package org.noise_planet.noisemodelling.webserver;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The GroovyScriptExtractor class provides functionality to locate a NoiseModelling JAR file,
 * scan and group Groovy scripts within it, and extract scripts for external usage.
 *
 * This class is designed to work with a specific JAR file containing scripts in a
 * well-defined directory structure. It ensures that scripts are organized by categories
 * and facilitates their extraction for execution or further processing.
 *
 * Key Features:
 * - Locates the NoiseModelling JAR file in the project structure.
 * - Scans the JAR file for Groovy scripts and groups them by directory structure.
 * - Extracts specific scripts to temporary files for external purposes.
 *
 * Instances of this class initialize the JAR location and script mappings at construction
 * time and provide static methods for extracting and finding scripts within the JAR file.
 */
public class GroovyScriptExtractor {
    /**
     * The location of the NoiseModelling JAR file.
     *
     * This static file object represents the NoiseModelling JAR file used by the GroovyScriptExtractor
     * class to locate and load scripts. It is initialized during the creation of a GroovyScriptExtractor
     * instance and corresponds to the file identified by the `locateNoiseModellingJar` method.
     *
     * The JAR file contains Groovy scripts organized in the "scripts/" directory, which are
     * scanned and grouped for further processing. The correctness and accessibility of this
     * file are vital for the successful operation of the GroovyScriptExtractor class.
     *
     * If the required JAR file cannot be located or does not exist, the initialization process
     * will throw a RuntimeException, preventing further execution.
     */
    private static  File jarFile;
    /**
     * A map that represents grouped Groovy scripts extracted from a NoiseModelling JAR file.
     *
     * The keys in this map are the names of groups or categories, derived from the directory structure
     * within the "scripts/" folder of the JAR file. Each key maps to a list containing the names of
     * Groovy scripts (.groovy) that belong to that specific group.
     *
     * This structure is populated by the {@code scanScriptsGrouped()} method, which scans the JAR file
     * for scripts, organizes them by group, and assigns them to this map. It is used for managing
     * and accessing available scripts by group and script name, enabling efficient script identification
     * and retrieval.
     */
    static Map<String, List<String>> scripts;

    /**
     * Constructs a new instance of the GroovyScriptExtractor class.
     *
     * This constructor initializes the instance by locating the NoiseModelling JAR file
     * and scanning for available scripts, grouping them into categories. If the required
     * JAR file cannot be located or does not exist, a RuntimeException will be thrown.
     *
     * The constructor performs the following operations:
     * 1. Calls the locateNoiseModellingJar method to find the NoiseModelling JAR file.
     * 2. Calls the scanScriptsGrouped method to identify and group available scripts
     *    stored in the located JAR file.
     * 3. Verifies that the JAR file is present and accessible; throws an exception in
     *    case of failure.
     *
     * Throws:
     * - RuntimeException if the JAR file cannot be found or does not exist.
     */
    public GroovyScriptExtractor() {
        this.jarFile = locateNoiseModellingJar();
        this.scripts = scanScriptsGrouped();
        if (this.jarFile == null || !this.jarFile.exists()) {
            throw new RuntimeException("Jar file not found");
        }

    }

    /**
     * Locates the NoiseModelling JAR file within the project directory structure by searching
     * for a JAR file named with "NoiseModelling_without_gui" in the "target" directory.
     *
     * The method determines the module's root directory and searches for the latest modified JAR file
     * matching the criteria. If no such file exists or an error occurs during the process, it returns null.
     *
     * @return The latest modified NoiseModelling JAR file, or null if it cannot be found or an error occurs.
     */
    private File locateNoiseModellingJar() {
        try {
            URL location = getClass().getProtectionDomain().getCodeSource().getLocation();
            File codeLocation = new File(location.toURI());

            File moduleRoot = codeLocation;
            while (moduleRoot != null && !new File(moduleRoot, "target").exists()) {
                moduleRoot = moduleRoot.getParentFile();
            }

            if (moduleRoot == null) {
                return null;
            }

            File targetDir = new File(moduleRoot, "target");
            File[] jars = targetDir.listFiles(f ->
                    f.isFile() && f.getName().endsWith(".jar") && f.getName().contains("NoiseModelling_without_gui"));

            if (jars == null || jars.length == 0) return null;

            Arrays.sort(jars, Comparator.comparingLong(File::lastModified).reversed());
            return jars[0];
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Scans and groups Groovy script files from a specified JAR file into categories based on their directory structure.
     *
     * The method analyzes the "scripts/" directory within the JAR file, extracting script names and organizing them
     * into groups. Grouping is determined by the second-to-last directory in the file path before the script name.
     * Only files with a ".groovy" extension within the "scripts/" directory are considered.
     *
     * @return a map where each key is a group name, and the corresponding value is a list of script names belonging
     * to that group
     * @throws RuntimeException if an I/O error occurs while reading the JAR file
     */
    public Map<String, List<String>> scanScriptsGrouped() {
        Map<String, List<String>> grouped = new TreeMap<>();

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                if (entry.getName().endsWith(".groovy") && entry.getName().startsWith("scripts/")) {
                    String relative = entry.getName().substring("scripts/".length());
                    String[] parts = relative.split("/");
                    if (parts.length < 2) continue;

                    String group = parts[parts.length - 2];
                    String scriptName = parts[parts.length - 1].replace(".groovy", "");

                    grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(scriptName);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading jar fife : " + jarFile.getAbsolutePath(), e);
        }

        return grouped;
    }

    /**
     * Extracts a Groovy script from the JAR file to a temporary file.
     *
     * This method locates a script based on the specified group and script name within the "scripts/"
     * directory of the JAR file. It then writes the script to a temporary file, which is marked to be
     * deleted on exit. This allows the script to be executed or utilized externally.
     *
     * @param group the group or category under which the script is organized within the "scripts/" directory
     * @param scriptName the name of the script to be extracted (without the ".groovy" extension)
     * @return the temporary file containing the extracted script
     * @throws IOException if an I/O error occurs while accessing the JAR file, reading the script, or creating
     * the temporary file
     */
    public static File extractScriptToTemp(String group, String scriptName) throws IOException {
        String entryPath = "scripts/" + group + "/" + scriptName + ".groovy";
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(entryPath);
            if (entry == null) {
                throw new FileNotFoundException("Script not found: " + entryPath);
            }

            File temp = File.createTempFile(scriptName + "_", ".groovy");
            temp.deleteOnExit();

            try (InputStream in = jar.getInputStream(entry);
                 OutputStream out = new FileOutputStream(temp)) {
                in.transferTo(out);
            }
            return temp;
        }
    }

    /**
     * Finds and extracts a script from the provided collection of grouped scripts based on the specified group and script name.
     *
     * This method checks if the given group and script name exist in the provided map of grouped scripts.
     * If a match is found, it attempts to extract the script to a temporary file using the {@code extractScriptToTemp} method.
     * If an I/O exception occurs during extraction, the exception is printed, and the method returns null.
     *
     * @param allScripts a map where each key is a group name, and the corresponding value is a list of script names in that group
     * @param group the name of the group to search for the script
     * @param scriptName the name of the script to be located and extracted
     * @return a temporary {@code File} containing the extracted script, or {@code null} if the script is not found
     * or an error occurs during extraction
     */
    static File findScript(Map<String, List<String>> allScripts, String group, String scriptName) {
        if (allScripts.containsKey(group) && allScripts.get(group).contains(scriptName)) {
            try {
                File tempFile = extractScriptToTemp(group, scriptName);
                return tempFile;
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
        return null;
    }
}
