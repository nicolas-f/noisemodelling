/**
 * NoiseModelling is an open-source tool designed to produce environmental noise maps on very large urban areas. It can be used as a Java library or be controlled through a user friendly web interface.
 *
 * This version is developed by the DECIDE team from the Lab-STICC (CNRS) and by the Mixt Research Unit in Environmental Acoustics (Université Gustave Eiffel).
 * <http://noise-planet.org/noisemodelling.html>
 *
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software. 
 *
 * Contact: contact@noise-planet.org
 *
 */

/**
 * @Author Pierre Aumond, Université Gustave Eiffel
 * @Author Nicolas Fortin, Université Gustave Eiffel
 */

package org.noise_planet.noisemodelling.wps.Experimental

import geoserver.GeoServer
import geoserver.catalog.Store
import groovy.sql.BatchingPreparedStatementWrapper
import groovy.sql.Sql
import groovy.transform.CompileStatic
import org.geotools.jdbc.JDBCDataStore
import org.h2gis.utilities.JDBCUtilities
import org.h2gis.utilities.TableLocation
import org.h2gis.utilities.URIUtilities
import org.h2gis.utilities.wrapper.ConnectionWrapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Connection
import java.sql.SQLException

// ----------------
// Import sound source directivity pattern sphere
// ----------------

title = 'Import sound source directivity pattern sphere'

description = 'Import sound source directivity pattern sphere</br>' +
        '-  <b> filePath </b> : Path of the input File</br>' +
        '-  <b> tableName </b> : name of output table.'

inputs = [
        pathFile : [
                name       : 'Path of the input File',
                title      : 'Path of the input File',
                description: 'Path of the file you want to import, including its extension. ' +
                        '</br> For example : c:/home/dir1.txt',
                type       : String.class
        ],
        tableName: [
                name       : 'Output table name',
                title      : 'Name of created/updated table',
                description: 'Name of the table you want to create from the file. ' +
                        '</br> <b> Default value : it will take the name of the file without its extension (special characters will be removed and whitespaces will be replace by an underscore. </b> ',
                min        : 0, max: 1,
                type       : String.class
        ]
]

outputs = [
        result: [
                name: 'Result output string', // This is not use in WPS Builder. The real title of the WPS bloc is the title item.
                title: 'Result output string', // Please be short
                description: 'This type of result does not allow the blocks to be linked together.',  // This is not use in WPS Builder.
                type: String.class
        ]
]

// Open Connection to Geoserver
static Connection openGeoserverDataStoreConnection(String dbName) {
    if (dbName == null || dbName.isEmpty()) {
        dbName = new GeoServer().catalog.getStoreNames().get(0)
    }
    Store store = new GeoServer().catalog.getStore(dbName)
    JDBCDataStore jdbcDataStore = (JDBCDataStore) store.getDataStoreInfo().getDataStore(null)
    return jdbcDataStore.getDataSource().getConnection()
}

// run the script
def run(input) {
    // Get name of the database
    // by default an embedded h2gis database is created
    // Advanced user can replace this database for a PostGis or h2Gis server database.
    String dbName = "h2gisdb"

    // Open connection
    openGeoserverDataStoreConnection(dbName).withCloseable {
        Connection connection ->
            return [result: exec(connection, input)]
    }
}
/**
 * @param theta1 Theta of P1 in radian [-Pi Pi]
 * @param phi1 Phi of P1 in radian [0 2Pi]
 * @param theta2 Theta of P2 in radian [-Pi Pi]
 * @param phi2 Phi of P2 in radian [0 2Pi]
 * @return Central angle Δσ (delta sigma)
 */
@CompileStatic
static double distanceSphere(double theta1, double phi1, double theta2, double phi2) {
    return Math.acos(Math.sin(theta1) * Math.sin(theta2) + Math.cos(theta1) * Math.cos(theta2) * Math.cos(phi1 - phi2))
}

/**
 * @param connection Database connection
 * @param is ASCII file stream
 * @param tableName Name of output table
 * @return Identifier of the inserted sphere
 */
@CompileStatic
static int parseFile(Connection connection, InputStream is, String tableName) {
    Sql sql = new Sql(connection)
    Logger logger = LoggerFactory.getLogger("org.noise_planet.noisemodelling")
    final int BUFFER_SIZE = 16384
    BufferedInputStream bof = new BufferedInputStream(is, BUFFER_SIZE)
    String lastWord = ""
    try {
        // Read HEADER
        Scanner scanner = new Scanner(bof);
        while(!lastWord.startsWith("PHIOBSEC")) {
            lastWord = scanner.next()
        }
        int nPhi = scanner.nextInt()
        scanner.nextLine() // skip line
        double[] phiList = new double[nPhi]
        for(int idPhi=0; idPhi<nPhi; idPhi++) {
            lastWord = scanner.next()
            phiList[idPhi] = Double.valueOf(lastWord)
        }
        double deltaPhi = (Double.valueOf(phiList[1]) - Double.valueOf(phiList[0])) / 2.0
        while(!lastWord.startsWith("THETAOBSEC")) {
            lastWord = scanner.next()
        }
        int nTheta = scanner.nextInt()
        scanner.nextLine() // skip line
        double[] thetaList = new double[nTheta]
        for(int idTheta=0; idTheta<nTheta; idTheta++) {
            lastWord = scanner.next()
            thetaList[idTheta] = Double.valueOf(lastWord)
        }
        double deltaTheta = (Double.valueOf(thetaList[1]) - Double.valueOf(thetaList[0])) / 2.0
        // Read frequencies
        while(!lastWord.startsWith("NFREQ")) {
            lastWord = scanner.next()
        }
        int numberOfFreq = scanner.nextInt()
        scanner.nextLine() // skip line
        String[] frequencies = new String[numberOfFreq]
        for (int idFreq = 0; idFreq < numberOfFreq; idFreq++) {
            lastWord = scanner.next()
            frequencies[idFreq] = String.valueOf((int) Double.parseDouble(lastWord)) //read frequency
        }
        // Create table if necessary
        int sphereIndex = 1
        if(!JDBCUtilities.tableExists(connection, tableName)) {
            StringBuilder sb = new StringBuilder("CREATE TABLE ")
            sb.append(tableName)
            sb.append(" (PK SERIAL PRIMARY KEY,D_INDEX INTEGER, THETA REAL, PHI REAL, ZERO_DIST REAL")
            for (int idFreq = 0; idFreq < numberOfFreq; idFreq++) {
                sb.append(", HZ")
                sb.append(frequencies[idFreq])
                sb.append(" REAL")
            }
            sb.append(")")
            sql.execute(sb.toString())
            // composite index
            sql.execute("CREATE INDEX ON " + tableName + "(D_INDEX)")
            sql.execute("CREATE INDEX ON " + tableName + "(ZERO_DIST)")
        } else {
            // fetch last directivity index
            sphereIndex = sql.firstRow("SELECT MAX(D_INDEX) + 1 DINDEX FROM " + tableName)[0] as Integer
        }
        while (!lastWord.startsWith("thetaObsEC=")) {
            lastWord = scanner.next()
        }
        // loop over theta tables
        StringBuilder insertQuery = new StringBuilder("INSERT INTO ")
        insertQuery.append(tableName)
        insertQuery.append(" (D_INDEX, THETA, PHI, ZERO_DIST")
        for (int idFreq = 0; idFreq < numberOfFreq; idFreq++) {
            insertQuery.append(", HZ")
            insertQuery.append(frequencies[idFreq])
        }
        insertQuery.append(") VALUES ( :dindex, :theta, :phi, :zerodist")
        for (int idFreq = 0; idFreq < numberOfFreq; idFreq++) {
            insertQuery.append(", :hz")
            insertQuery.append(frequencies[idFreq])
        }
        insertQuery.append(")")
        sql.withBatch(insertQuery.toString()) { BatchingPreparedStatementWrapper batch ->
            for (int ti = 0; ti < nTheta; ti++) {
                while (!lastWord.startsWith("thetaObsEC=")) {
                    lastWord = scanner.next()
                }
                double theta = Double.parseDouble(lastWord.substring(lastWord.lastIndexOf("=") + 1, lastWord.length()))
                logger.info(String.valueOf(theta))
                scanner.nextLine() // skip line
                // loop over phi
                for (int iphi = 0; iphi < nPhi; iphi++) {
                    scanner.nextLine()
                    //skip TAS(kts) Gamma(°) Temperature(K) RH(%) Air_p(Pa) isTonal(bool) isDoppler(bool)
                    lastWord = scanner.next()
                    double phi = Double.parseDouble(lastWord)
                    // insert row
                    def data = [:]
                    data.put("dindex", sphereIndex)
                    data.put("theta", theta)
                    data.put("phi", phi)
                    // Compute distance with forward vector
                    double zerodist = distanceSphere(Math.toRadians(theta), Math.toRadians(phi), 0, 0);
                    data.put("zerodist", zerodist)
                    for (int idFreq = 0; idFreq < numberOfFreq; idFreq++) {
                        lastWord = scanner.next()
                        data.put("hz" + frequencies[idFreq], Double.parseDouble(lastWord))
                    }
                    batch.addBatch(data)
                }
            }
        }
        return sphereIndex
    } catch (NoSuchElementException | NumberFormatException ex) {
        throw new SQLException("Unexpected word " + lastWord, ex);
    } catch(SQLException ex) {
        throw new SQLException("SQL Error " + ex.getNextException() != null ? ex.getLocalizedMessage() +
                ex.getNextException().localizedMessage : ex.getLocalizedMessage(), ex)
    }
}


// Main function of the script
def exec(Connection connection, input) {

    //Need to change the ConnectionWrapper to WpsConnectionWrapper to work under postGIS database   
    connection = new ConnectionWrapper(connection)

    // Create a sql connection to interact with the database in SQL
    Sql sql = new Sql(connection)

    // Create a logger to display messages in the geoserver logs and in the command prompt.
    // Please use logger.info at least when the script starts, ends and creates a table. 
    // You can use the warning but the user could not be noticed. Please fill in the resultString variable with your warnings.
    // Don't register errors by this logger, use "throw Exception" instead.
    Logger logger = LoggerFactory.getLogger("org.noise_planet.noisemodelling")

    // print to command window
    logger.info('Start : Import_Asc_Directivity')
    logger.info("inputs {}", input) // log inputs of the run

    // -------------------
    // Get every inputs
    // -------------------

    String output_table_name = input['tableName'] as String

    String directivityPath = input['pathFile'] as String

    if(output_table_name == null || output_table_name.trim().isEmpty()) {
        final String name = URIUtilities.fileFromString(directivityPath).getName();
        String tableName = name.substring(0, name.lastIndexOf(".")).toUpperCase();
        if (tableName.matches("^[a-zA-Z][a-zA-Z0-9_]*\$")) {
            output_table_name = tableName
        } else {
            output_table_name = "SPHERE"
        }
    }

    if(!new File(directivityPath).exists()) {
        throw new IllegalArgumentException("The specified file does not exists ("+directivityPath+")")
    }

    FileInputStream fis = new FileInputStream(directivityPath)

    parseFile(connection, fis, output_table_name)

    fis.close()
    // -------------------
    // Print results
    // -------------------

    // output string, the information given back to the user
    // This is the output displayed in the WPS Builder. HTML code can be used.
    // Please inform the user about the actions that have been performed by the script (e.g. table creation), and about any warnings he should receive.


    resultString = "Import Done ! "+output_table_name+" table has been created."

    // print to command window and geoserver log
    logger.info('Result : ' + resultString)
    logger.info('End : Import_Asc_Directivity')

    // send resultString to WPS Builder
    return resultString

}

