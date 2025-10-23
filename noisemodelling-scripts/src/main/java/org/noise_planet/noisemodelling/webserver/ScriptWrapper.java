package org.noise_planet.noisemodelling.webserver;


import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import net.opengis.wps10.DataInputsType1;
import net.opengis.wps10.ExecuteType;
import net.opengis.wps10.InputType;
import org.h2gis.utilities.wrapper.ConnectionWrapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a wrapper for a script, encapsulating its metadata, configuration,
 * and execution logic. This class allows the execution of Groovy scripts by
 * providing the necessary inputs and context and handles the outputs generated
 * by the script. It also provides utility methods for extracting inputs from a
 * specific execution type.
 */
public class ScriptWrapper {

    /**
     * Represents an input configuration for a script.
     * This class is designed to encapsulate the metadata and properties that define
     * an input to a script, such as its identifier, title, description, type, and
     * whether it is optional.
     */
    public static class ScriptInput {
        public String id;
        public String title;
        public String description;
        public String type;
        public boolean optional = false;
    }
    /**
     * Represents an output configuration for a script.
     * This class is designed to encapsulate the metadata that defines
     * an output of a script, such as its identifier and title.
     */
    public static class ScriptOutput {
        public String id;
        public String title;
    }

    public String id;
    public String title;
    public String description;
    public Path path;

    public Map<String, ScriptInput> inputs;
    public Map<String, ScriptOutput> outputs;

    /**
     * Executes a Groovy script with the provided connection and inputs.
     *
     * @param connection an active database connection used in the script execution context.
     * @param inputs a map of key-value pairs representing the inputs required by the script.
     * @return the result of the script execution, which can be of any type.
     * @throws IOException if there is an issue reading or processing the script file.
     */
    public Object execute(Connection connection, Map<String, Object> inputs) throws IOException {

        Binding binding = new Binding();
        binding.setVariable("input", inputs);
        binding.setVariable("connection", new ConnectionWrapper(connection));

        File scriptFile = path.toFile();
        GroovyShell shell = new GroovyShell(binding);
        Script script = shell.parse(scriptFile);

        return script.invokeMethod("exec", new Object[]{connection, inputs});
    }

    /**
     * Extracts input data from the provided {@code ExecuteType} object and returns a map of input names
     * to their corresponding values. It processes the data inputs defined in the {@code ExecuteType}
     * object to construct this mapping.
     *
     * @param execute the {@code ExecuteType} object that contains the data inputs to extract.
     * @return a map where keys are input names (as strings) and values are the corresponding input data
     *         values (as objects), or {@code null} if no data is associated with an input.
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

