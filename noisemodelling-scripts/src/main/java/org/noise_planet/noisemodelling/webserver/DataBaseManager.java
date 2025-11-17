package org.noise_planet.noisemodelling.webserver;

import org.h2.Driver;
import org.h2gis.functions.factory.H2GISFunctions;
import org.h2gis.utilities.wrapper.ConnectionWrapper;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Provides functionality for managing and connecting to a database.
 *
 * This class sets up and manages connections to an H2 database, initializes
 * GIS functions, and handles database directory management. The default database
 * name and directory are configured during instantiation.
 */
public class DataBaseManager {

    /**
     * Represents the name of the currently active database.
     *
     * This value is initialized during the instantiation of the {@code DataBaseManager} class
     * with a default value of "db_webserver". It can be accessed using the
     * {@code getCurrentDbName()} method.
     */
    private  String currentDbName;
    /**
     * Specifies the directory path where the database files are stored.
     * This directory is initialized during the construction of the {@code DataBaseManager} class
     * and defaults to a hidden directory under the user's home directory.
     * If the directory does not already exist, it is created automatically.
     */
    private  String dbDirectory = "";

    /**
     * Constructs a new {@code DataBaseManager} instance.
     *
     * This constructor initializes the following:
     * - Sets the default database name to "db_webserver".
     * - Configures the database directory to a hidden folder named ".noisemodelling"
     *   located under the user's home directory.
     *
     * If the specified database directory does not exist, it is automatically created.
     */
    public DataBaseManager() {
        this.currentDbName = "db_webserver";
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
    public  String getCurrentDbName() {
        return currentDbName;
    }



    /**
     * Gets the database directory path.
     * @return Directory path
     */
    public  String getDbDirectory() {
        return dbDirectory;
    }

    /**
     * Opens a connection to the H2 database and initializes GIS functions.
     * If the database directory does not exist, it will be created automatically.
     * The connection URL is formed using the database directory and current database name.
     * The connection also supports auto-server mode for concurrent access.
     *
     * @return An active database connection wrapped in a {@link ConnectionWrapper} instance.
     * @throws SQLException If unable to establish a connection to the database.
     */
     Connection openDatabaseConnection() throws SQLException {
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
