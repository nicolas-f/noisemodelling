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
import org.locationtech.jts.geom.Point
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


    //---------------------------------------------------------------------------------
    //  Select the studied departement and generate a 1km buffer around
    //---------------------------------------------------------------------------------

    sql.execute('drop table if exists ign_admin_express_dept_l93')

    def databaseUrl = input["databaseUrl"] as String
    def user = input["databaseUser"] as String
    def pwd = input["databasePassword"] as String

    def tableQuery = "(SELECT ST_BUFFER(the_geom, "+buffer+") as the_geom, id, nom_dep_m, nom_dep, insee_dep, insee_reg FROM noisemodelling.ign_admin_express_dept_l93 WHERE insee_dep=''"+codeDep+"'')"

    sql.execute("CREATE LINKED TABLE ign_admin_express_dept_l93 ('org.h2gis.postgis_jts.Driver','"+databaseUrl+"','"+user+"','"+pwd+"','noisemodelling', '"+tableQuery+"')")

   def createdTables = "dept"

    //---------------------------------------------------------------------------------
    //        -- 2- Select the studied departement and generate a 1km buffer around
    //---------------------------------------------------------------------------------

    tableQuery = "(SELECT b.insee_dep, a.*   " +
            "FROM noisemodelling.station_pfav a, noisemodelling.ign_admin_express_dept_l93 b WHERE insee_dep=''"+codeDep+"'' " +
            "ORDER BY st_distance(a.the_geom, st_centroid(b.the_geom)) LIMIT 1)"

    sql.execute('drop table if exists dept_pfav')
    sql.execute("CREATE LINKED TABLE dept_pfav ('org.h2gis.postgis_jts.Driver','"+databaseUrl+"','"+user+"','"+pwd+"','noisemodelling', '"+tableQuery+"')")

    sql.execute('drop table if exists dept')
    sql.execute("CREATE TABLE dept as select * from ign_admin_express_dept_l93")
    sql.execute("ALTER TABLE DEPT ALTER COLUMN ID varchar NOT NULL")
    sql.execute("ALTER TABLE DEPT ADD PRIMARY KEY ( ID)")
    sql.execute("CREATE SPATIAL INDEX ON DEPT (THE_GEOM)")

    // remove linked table with postgis
    sql.execute('drop table if exists ign_admin_express_dept_l93')

    //---------------------------------------------------------------------------------
    // Select the closest station from the (centroid of the) studied department
    //---------------------------------------------------------------------------------


    tableQuery = "(SELECT codedept, temp_d, temp_e, temp_n, hygro_d, hygro_e, hygro_n, wall_alpha, ts_stud, pm_stud" +
            " FROM echeance4.\"C_METEO_S_FRANCE\" " +
            "WHERE codedept=''0"+codeDep+"'')"

    sql.execute('drop table if exists dept_meteo')
    sql.execute("CREATE LINKED TABLE dept_meteo ('org.h2gis.postgis_jts.Driver','"+databaseUrl+"','"+user+"','"+pwd+"','echeance4', '"+tableQuery+"')")


    sql.execute("DROP TABLE IF EXISTS zone;")
    createdTables += "zone"
    sql.execute("CREATE TABLE zone AS SELECT a.pfav_06_18, a.pfav_18_22, a.pfav_22_06, a.pfav_06_22, b.temp_d," +
            " b.temp_e, b.temp_n, b.hygro_d, b.hygro_e, b.hygro_n, b.wall_alpha, b.ts_stud, b.pm_stud " +
            "FROM dept_pfav a, dept_meteo b;")

    // remove linked table with postgis
    sql.execute('drop table if exists dept_pfav')
    sql.execute('drop table if exists dept_meteo')



    //    ---------------------------------------------------------------------------------
    //            -- 4- Select and format the roads
    //    ---------------------------------------------------------------------------------
    //
    //            -- A z-value is added and set at 5 centimetres above the ground.

    sql.execute("DROP TABLE IF EXISTS roads")
    tableQuery = "(SELECT  st_translate(st_force3dz(a.the_geom), 0, 0, 0.05) as the_geom, a.\"IDTRONCON\" as id_road," +
            "b.\"TMHVLD\" as lv_d, b.\"TMHVLS\" as lv_e, b.\"TMHVLN\" as lv_n, b.\"TMHPLD\" * b.\"PCENTMPL\" as mv_d," +
            "b.\"TMHPLS\" * b.\"PCENTMPL\" as mv_e, b.\"TMHPLN\" * b.\"PCENTMPL\" as mv_n, " +
            "b.\"TMHPLD\" * b.\"PCENTHPL\" as hgv_d, b.\"TMHPLS\" * b.\"PCENTHPL\" as hgv_e, " +
            "b.\"TMHPLN\" * b.\"PCENTHPL\" as hgv_n, b.\"TMH2RD\" * b.\"PCENT2R4A\" as wav_d," +
            "b.\"TMH2RS\" * b.\"PCENT2R4A\" as wav_e, b.\"TMH2RN\" * b.\"PCENT2R4A\" as wav_n, " +
            "b.\"TMH2RD\" * b.\"PCENT2R4B\" as wbv_d, b.\"TMH2RS\" * b.\"PCENT2R4B\" as wbv_e," +
            "b.\"TMH2RN\" * b.\"PCENT2R4B\" as wbv_n, c.\"VITESSEVL\" as lv_spd_d, " +
            "c.\"VITESSEVL\" as lv_spd_e, c.\"VITESSEVL\" as lv_spd_n, c.\"VITESSEPL\" as mv_spd_d," +
            "c.\"VITESSEPL\" as mv_spd_e, c.\"VITESSEPL\" as mv_spd_n, c.\"VITESSEPL\" as hgv_spd_d," +
            "c.\"VITESSEPL\" as hgv_spd_e, c.\"VITESSEPL\" as hgv_spd_n, c.\"VITESSEVL\" as wav_spd_d, " +
            "c.\"VITESSEVL\" as wav_spd_e, c.\"VITESSEVL\" as wav_spd_n, c.\"VITESSEVL\" as wbv_spd_d, " +
            "c.\"VITESSEVL\" as wbv_spd_e, c.\"VITESSEVL\" as wbv_spd_n,\n" +
            "\td.\"REVETEMENT\" as revetement,\n" +
            "\td.\"GRANULO\" as granulo,\n" +
            "\td.\"CLASSACOU\" as classacou,\n" +
            "\tROUND((a.\"ZFIN\"-a.\"ZDEB\")/ ST_LENGTH(a.the_geom)*100) as slope, \n" +
            "\ta.\"ZDEB\" as z_start, \n" +
            "\ta.\"ZFIN\" as z_end,\n" +
            "\ta.\"SENS\" as sens,\n" +
            "\t(CASE \tWHEN a.\"SENS\" = ''01'' THEN ''01'' \n" +
            "\t\t\tWHEN a.\"SENS\" = ''02'' THEN ''02'' \n" +
            "\t\t\tELSE ''03''\n" +
            "\t END) as way\n" +
            "FROM \n" +
            "\tnoisemodelling.\"N_ROUTIER_TRONCON_L_l93\" a,\n" +
            "\techeance4.\"N_ROUTIER_TRAFIC\" b,\n" +
            "\techeance4.\"N_ROUTIER_VITESSE\" c,\n" +
            "\techeance4.\"N_ROUTIER_REVETEMENT\" d, \n" +
            "\t(select ST_BUFFER(the_geom, "+buffer+") the_geom from noisemodelling.ign_admin_express_dept_l93 e e.insee_dep=''"+codeDep+"'' LIMIT 1) \n" +
            "WHERE \n" +
            "\ta.\"CBS_GITT\"=''O'' and\n" +
            "\ta.\"IDTRONCON\"=b.\"IDTRONCON\" and\n" +
            "\ta.\"IDTRONCON\"=c.\"IDTRONCON\" and\n" +
            "\ta.\"IDTRONCON\"=d.\"IDTRONCON\" and \n" +
            "\ta.the_geom && e.the_geom and \n" +
            "\tST_INTERSECTS(a.the_geom, e.the_geom))"

    sql.execute("DROP TABLE IF EXISTS roads_link")
    sql.execute("CREATE LINKED TABLE roads_link ('org.h2gis.postgis_jts.Driver','"+databaseUrl+"','"+user+"','"+pwd+"','echeance4', '"+tableQuery+"')")
    sql.execute("CREATE TABLE ROADS AS SELECT * FROM roads_link")
    sql.execute("DROP TABLE roads_link")

    sql.execute("ALTER TABLE roads ADD COLUMN pvmt varchar(4)")

    // fetch pavements table
    tableQuery = "pvmt"

    sql.execute("CREATE LINKED TABLE pvmt_link ('org.h2gis.postgis_jts.Driver','"+databaseUrl+"','"+user+"','"+pwd+"','noisemodelling', '"+tableQuery+"')")
    sql.execute("DROP TABLE IF EXISTS PVMT")
    sql.execute("CREATE TABLE PVMT as select * from pvmt_link")
    // break link with postgis
    sql.execute("DROP TABLE pvmt_link")
    sql.execute("CREATE INDEX ON PVMT (revetement)")
    sql.execute("CREATE INDEX ON PVMT (granulo)")
    sql.execute("CREATE INDEX ON PVMT (classacou)")

    // add data to roads
    sql.execute("UPDATE roads b SET pvmt = (select a.pvmt FROM pvmt a WHERE a.revetement=b.revetement AND a.granulo=b.granulo AND a.classacou=b.classacou)")
    // Add a primary
    sql.execute("ALTER TABLE roads ADD COLUMN pk serial PRIMARY KEY")
    // Create a spatial index
    sql.execute("CREATE spatial index ON roads (the_geom)")


    // print to WPS Builder
    return "Table "+createdTables+" fetched"

}

