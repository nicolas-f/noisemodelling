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

package org.noise_planet.noisemodelling.wps.Plamade

import geoserver.GeoServer
import geoserver.catalog.Store
import org.geotools.jdbc.JDBCDataStore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import groovy.sql.Sql

import java.sql.Connection

title = 'Extract department'
description = 'Connect to a distant PostGIS database and extract departments according to Plamade specification'

inputs = [
        databaseUrl : [
                name       : 'PostGIS host',
                title      : 'Url of the PostGIS database',
                description: 'Plamade server url in the form of jdbc:postgresql_h2://ip_adress:port/db_name',
                type       : String.class
        ],
        databaseUser : [
                name       : 'PostGIS user',
                title      : 'PostGIS username',
                description: 'PostGIS username for authentication',
                type       : String.class
        ],
        databasePassword : [
                name       : 'PostGIS password',
                title      : 'PostGIS password',
                description: 'PostGIS password for authentication',
                type       : String.class
        ],
        fetchDistance : [
                name       : 'Fetch distance',
                title      : 'Fetch distance',
                description: 'Fetch distance around the selected area in meters. Default 1000',
                min : 0, max: 1,
                type       : Integer.class
        ],
        inseeDepartment : [
                name       : 'Insee department code',
                title      : 'Insee department code',
                description: 'Insee code for the area ex:75',
                type       : String.class
        ],
]

outputs = [
        result: [
                name       : 'Result output string',
                title      : 'Result output string',
                description: 'This type of result does not allow the blocks to be linked together.',
                type       : String.class
        ]
]

static Connection openGeoserverDataStoreConnection(String dbName) {
    if (dbName == null || dbName.isEmpty()) {
        dbName = new GeoServer().catalog.getStoreNames().get(0)
    }
    Store store = new GeoServer().catalog.getStore(dbName)
    JDBCDataStore jdbcDataStore = (JDBCDataStore) store.getDataStoreInfo().getDataStore(null)
    return jdbcDataStore.getDataSource().getConnection()
}

def run(input) {

    // Get name of the database
    // by default an embedded h2gis database is created
    // Advanced user can replace this database for a postGis or h2Gis server database.
    String dbName = "h2gisdb"

    // Open connection
    openGeoserverDataStoreConnection(dbName).withCloseable {
        Connection connection ->
            return [result: exec(connection, input)]
    }
}

def exec(Connection connection, input) {

    // output string, the information given back to the user
    String resultString = null


    // Create a logger to display messages in the geoserver logs and in the command prompt.
    Logger logger = LoggerFactory.getLogger("org.noise_planet.noisemodelling")

    // print to command window
    logger.info('Start linking with PostGIS')

    // Get provided parameters
    String codeDep = input["inseeDepartment"] as String
    Integer buffer = 1000
    if ('fetchDistance' in input) {
        buffer = input["fetchDistance"] as Integer
    }

    def sql = new Sql(connection)

    // Create linked tables
    def createdTables = "ign_admin_express_dept_l93"

    sql.execute('drop table if exists ign_admin_express_dept_l93')

    def databaseUrl = input["databaseUrl"] as String
    def user = input["databaseUser"] as String
    def pwd = input["databasePassword"] as String

    def tableQuery = "(SELECT ST_BUFFER(the_geom, 1000) as the_geom, id, nom_dep_m, nom_dep, insee_dep, insee_reg FROM noisemodelling.ign_admin_express_dept_l93 WHERE insee_dep=''"+codeDep+"'')"

    sql.execute("CREATE LINKED TABLE ign_admin_express_dept_l93 ('org.h2gis.postgis_jts.Driver','"+databaseUrl+"','"+user+"','"+pwd+"','noisemodelling', '"+tableQuery+"')")

    tableQuery = "stations_param"
    createdTables+=", " + tableQuery
    sql.execute("DROP TABLE IF EXISTS zone")
    sql.execute("CREATE LINKED TABLE zone ('org.h2gis.postgis_jts.Driver','"+databaseUrl+"','"+user+"','"+pwd+"','noisemodelling', '"+tableQuery+"')")


    // TODO Copy data into H2GIS and create spatial index

    // TODO remove linked tables

    // print to WPS Builder
    return "Table "+createdTables+" fetched"

}

