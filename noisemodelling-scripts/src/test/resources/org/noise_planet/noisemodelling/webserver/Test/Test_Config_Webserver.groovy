package org.noise_planet.noisemodelling.webserver.Test

import groovy.sql.Sql
import groovy.sql.BatchingPreparedStatementWrapper
import groovy.transform.CompileStatic
import org.h2gis.utilities.wrapper.ConnectionWrapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Connection

title = 'Test Configuration Generator'
description = 'A simple test script that simulates generating a configuration table.'
inputs = [
        numbers: [
                name: 'Numbers',
                title: 'Numbers list',
                description: 'List of numbers, e.g. "1,2,3,4"',
                type: String.class
        ],
        multiplier: [
                name: 'Multiplier',
                title: 'Multiplier factor',
                description: 'Value by which each number will be multiplied',
                type: Double.class
        ]
]

outputs = [
        result: [
                name: 'TEST_CONFIG',
                title: 'TEST_CONFIG',
                description: 'A SQL table named TEST_CONFIG',
                type: String.class
        ]
]

@CompileStatic
def exec(Connection connection, Map input) {
    connection = new ConnectionWrapper(connection)
    Logger logger = LoggerFactory.getLogger("org.noise_planet.noisemodelling.webserver")

    logger.info('Starting test configuration generation')

    // Parse inputs
    String numString = (String) input.get("numbers")
    double multiplier = input.containsKey("multiplier") ? ((Number) input.get("multiplier")).doubleValue() : 1.0d

    // Parse the comma-separated list safely
    List<Double> numberList = []
    for (String token : numString.replaceAll("\\s+", "").split(",")) {
        numberList.add(Double.parseDouble(token))
    }

    generateConfigTable(connection, numberList as double[], multiplier)

    logger.info('End Test configuration generation')
    return "Done! Table TEST_CONFIG has been created."
}

/**
 * Simple helper method that creates a test SQL table named TEST_CONFIG
 * containing multiplied values based on the input list.
 */
def generateConfigTable(Connection connection, double[] numbers, double multiplier) {
    Sql sql = new Sql(connection)

    sql.execute("DROP TABLE IF EXISTS TEST_CONFIG")
    sql.execute("CREATE TABLE TEST_CONFIG (ID INTEGER PRIMARY KEY AUTO_INCREMENT, NUM_VALUE DOUBLE, MULTIPLIED_VALUE DOUBLE)")

    String insertQuery = "INSERT INTO TEST_CONFIG (NUM_VALUE, MULTIPLIED_VALUE) VALUES (?, ?)"

    sql.withBatch(50, insertQuery) { BatchingPreparedStatementWrapper ps ->
        for (double n : numbers) {
            double multiplied = n * multiplier
            ps.addBatch(n, multiplied)
        }
    }
}