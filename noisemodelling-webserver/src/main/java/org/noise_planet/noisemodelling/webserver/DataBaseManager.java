package org.noise_planet.noisemodelling.webserver;

import org.h2.Driver;
import org.h2gis.functions.factory.H2GISFunctions;
import org.h2gis.utilities.wrapper.ConnectionWrapper;

import java.io.File;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DataBaseManager {

    private static String currentDbName;
    private static String dbDirectory = "";

    /**
     * Creates a new DatabaseManager.
     * @param defaultDbName The default database name
     */
    public DataBaseManager(String defaultDbName) {
        this.currentDbName = defaultDbName;
        this.dbDirectory = System.getProperty("user.home") + "/.noisemodelling";

        File dir = new File(dbDirectory);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * Gets the current active database name.
     * @return Current database name
     */
    public static String getCurrentDbName() {
        return currentDbName;
    }



    /**
     * Gets the database directory path.
     * @return Directory path
     */
    public static String getDbDirectory() {
        return dbDirectory;
    }

    /**
     * Opens a connection to the H2 database using the specified database directory and current database name.
     * If the database directory does not exist, it will be created. The database is configured to run in
     * AUTO_SERVER mode, allowing multiple connections to the same database.
     *
     * @return A {@link Connection} object wrapped in a {@code ConnectionWrapper}, representing the database connection.
     * @throws SQLException If a database access error occurs.
     */
    static Connection openDatabaseConnection() throws SQLException{
        String dbDir = getDbDirectory();
        File dbDirFile = new File(dbDir);
        if (!dbDirFile.exists()) {
            dbDirFile.mkdirs();
        }
        String databasePath = "jdbc:h2:" + dbDir + "/" + getCurrentDbName() + ";AUTO_SERVER=TRUE";
        Driver.load();
        Connection connection = DriverManager.getConnection(databasePath, "", "");
        H2GISFunctions.load(connection);
        return new ConnectionWrapper(connection);
    }
}
