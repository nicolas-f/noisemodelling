package org.noise_planet.noisemodelling.webserver;

import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WpsScriptWrapperTest {

    /**
     * Tests the ability to build script wrappers from a Groovy script resource
     * and perform various operations including XML generation and script execution.
     *
     * This method verifies the following functionalities:
     * - Loading a test Groovy script from the resources folder.
     * - Grouping scripts in a simulated map structure.
     * - Building {@code ScriptWrapper} instances using {@code WpsScriptWrapper#buildScriptWrappers}.
     * - Generating GetCapabilities XML and DescribeProcess XML.
     * - Validating that the generated XML includes script inputs and outputs.
     * - Executing the script with provided input parameters.
     * - Verifying the execution result and output messages.
     *
     * @throws IOException if there is an issue reading the script file.
     * @throws URISyntaxException if the resource URI is malformed.
     * @throws SQLException if there is a database-related error during execution.
     */
    @Test
    void testBuildScriptWrappersFromResource() throws IOException, URISyntaxException, SQLException {
        // Load the Groovy script from test resources
        Path scriptPath = Path.of(
                Objects.requireNonNull(
                        WpsScriptWrapperTest.class.getResource("/org/noise_planet/noisemodelling/webserver/Test/Test_Config_Webserver.groovy")
                ).toURI()
        );
        File scriptFile = scriptPath.toFile();
        assertTrue(scriptFile.exists());

        // Simulate grouped map like loadScripts() would return
        Map<String, List<File>> grouped = new HashMap<>();
        grouped.put("TestGroup", List.of(scriptFile));

        // Build ScriptWrapper
        List<ScriptWrapper> wrappers = WpsScriptWrapper.buildScriptWrappers(grouped);
        assertEquals(1, wrappers.size());

        ScriptWrapper sw = wrappers.get(0);

        // Test XML generation for GetCapabilities
        String capabilitiesXml = WpsScriptWrapper.generateCapabilitiesXML(wrappers);
        assertNotNull(capabilitiesXml);
        assertEquals("TestGroup:Test_Config_Webserver", sw.id);
        assertEquals("Test Configuration Generator", sw.title);
        assertEquals("A simple test script that simulates generating a configuration table.", sw.description);

        //DescribeProcess
        String describeXml = WpsScriptWrapper.generateDescribeProcessXML(sw);
        assertNotNull(describeXml);

        for (ScriptWrapper.ScriptInput input : sw.inputs.values()) {
            assertTrue(describeXml.contains(input.id));
            assertTrue(describeXml.contains(input.title));
        }
        for (ScriptWrapper.ScriptOutput output : sw.outputs.values()) {
            assertTrue(describeXml.contains(output.id));
            assertTrue(describeXml.contains(output.title));
        }

        // Test execution
        JdbcConnectionPool cp = JdbcConnectionPool.create("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "");
        Connection connection = cp.getConnection();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("numbers", "1, 2, 3, 4");
        inputs.put("multiplier", 2.0);

        Object result = sw.execute(connection, inputs);

        // Assertions
        String message = (String) result;
        System.out.println("message:" + message);
        assertEquals("Done! Table TEST_CONFIG has been created.", message);

    }
}
