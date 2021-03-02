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
import groovy.text.SimpleTemplateEngine
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

    def databaseUrl = input["databaseUrl"] as String
    def user = input["databaseUser"] as String
    def pwd = input["databasePassword"] as String

    def sql = new Sql(connection)

    def queries = """
    drop table if exists ign_admin_express_dept_l93;
    CREATE LINKED TABLE ign_admin_express_dept_l93 ('org.h2gis.postgis_jts.Driver','$databaseUrl','$user','$pwd','noisemodelling', '(SELECT ST_BUFFER(the_geom, $buffer) as the_geom, id, nom_dep_m, nom_dep, insee_dep, insee_reg FROM noisemodelling.ign_admin_express_dept_l93 WHERE insee_dep=''$codeDep'')');
    drop table if exists dept_pfav;
    CREATE LINKED TABLE dept_pfav ('org.h2gis.postgis_jts.Driver','$databaseUrl','$user','$pwd','noisemodelling', '(SELECT b.insee_dep, a.*   FROM noisemodelling.station_pfav a, noisemodelling.ign_admin_express_dept_l93 b WHERE insee_dep=''$codeDep'' ORDER BY st_distance(a.the_geom, st_centroid(b.the_geom)) LIMIT 1)');
    drop table if exists dept;
    CREATE TABLE dept as select * from ign_admin_express_dept_l93;
    ALTER TABLE DEPT ALTER COLUMN ID varchar NOT NULL;
    ALTER TABLE DEPT ADD PRIMARY KEY ( ID);
    CREATE SPATIAL INDEX ON DEPT (THE_GEOM);
    drop table if exists ign_admin_express_dept_l93;
    drop table if exists dept_meteo;
    CREATE LINKED TABLE dept_meteo ('org.h2gis.postgis_jts.Driver','$databaseUrl','$user','$pwd','echeance4', '(SELECT codedept, temp_d, temp_e, temp_n, hygro_d, hygro_e, hygro_n, wall_alpha, ts_stud, pm_stud FROM echeance4."C_METEO_S_FRANCE" WHERE codedept=''0$codeDep'')');
    DROP TABLE IF EXISTS zone;
    CREATE TABLE zone AS SELECT a.pfav_06_18, a.pfav_18_22, a.pfav_22_06, a.pfav_06_22, b.temp_d, b.temp_e, b.temp_n, b.hygro_d, b.hygro_e, b.hygro_n, b.wall_alpha, b.ts_stud, b.pm_stud FROM dept_pfav a, dept_meteo b;
    drop table if exists dept_pfav;
    drop table if exists dept_meteo;
    DROP TABLE IF EXISTS roads;
    DROP TABLE IF EXISTS roads_link;
    CREATE LINKED TABLE roads_link ('org.h2gis.postgis_jts.Driver','$databaseUrl','$user','$pwd','echeance4', '(SELECT  st_translate(st_force3dz(a.the_geom), 0, 0, 0.05) as the_geom, a."IDTRONCON" as id_road,b."TMHVLD" as lv_d, b."TMHVLS" as lv_e, b."TMHVLN" as lv_n, b."TMHPLD" * b."PCENTMPL" as mv_d,b."TMHPLS" * b."PCENTMPL" as mv_e, b."TMHPLN" * b."PCENTMPL" as mv_n, b."TMHPLD" * b."PCENTHPL" as hgv_d, b."TMHPLS" * b."PCENTHPL" as hgv_e, b."TMHPLN" * b."PCENTHPL" as hgv_n, b."TMH2RD" * b."PCENT2R4A" as wav_d,b."TMH2RS" * b."PCENT2R4A" as wav_e, b."TMH2RN" * b."PCENT2R4A" as wav_n, b."TMH2RD" * b."PCENT2R4B" as wbv_d, b."TMH2RS" * b."PCENT2R4B" as wbv_e,b."TMH2RN" * b."PCENT2R4B" as wbv_n, c."VITESSEVL" as lv_spd_d, c."VITESSEVL" as lv_spd_e, c."VITESSEVL" as lv_spd_n, c."VITESSEPL" as mv_spd_d,c."VITESSEPL" as mv_spd_e, c."VITESSEPL" as mv_spd_n, c."VITESSEPL" as hgv_spd_d,c."VITESSEPL" as hgv_spd_e, c."VITESSEPL" as hgv_spd_n, c."VITESSEVL" as wav_spd_d, c."VITESSEVL" as wav_spd_e, c."VITESSEVL" as wav_spd_n, c."VITESSEVL" as wbv_spd_d, c."VITESSEVL" as wbv_spd_e, c."VITESSEVL" as wbv_spd_n,
     d."REVETEMENT" as revetement,
     d."GRANULO" as granulo,
     d."CLASSACOU" as classacou,
     ROUND((a."ZFIN"-a."ZDEB")/ ST_LENGTH(a.the_geom)*100) as slope, 
     a."ZDEB" as z_start, 
     a."ZFIN" as z_end,
     a."SENS" as sens,
     (CASE  WHEN a."SENS" = ''01'' THEN ''01'' 
       WHEN a."SENS" = ''02'' THEN ''02'' 
       ELSE ''03''
      END) as way
    FROM 
     noisemodelling."N_ROUTIER_TRONCON_L_l93" a,
     echeance4."N_ROUTIER_TRAFIC" b,
     echeance4."N_ROUTIER_VITESSE" c,
     echeance4."N_ROUTIER_REVETEMENT" d, 
     (select ST_BUFFER(the_geom, $buffer) the_geom from noisemodelling.ign_admin_express_dept_l93 e WHERE e.insee_dep=''$codeDep'' LIMIT 1) e
    WHERE 
     a."CBS_GITT"=''O'' and
     a."IDTRONCON"=b."IDTRONCON" and
     a."IDTRONCON"=c."IDTRONCON" and
     a."IDTRONCON"=d."IDTRONCON" and 
     a.the_geom && e.the_geom and 
     ST_INTERSECTS(a.the_geom, e.the_geom))');
    CREATE TABLE ROADS AS SELECT * FROM roads_link;
    DROP TABLE roads_link;
    ALTER TABLE roads ADD COLUMN pvmt varchar(4);
    CREATE LINKED TABLE pvmt_link ('org.h2gis.postgis_jts.Driver','$databaseUrl','$user','$pwd','noisemodelling', 'pvmt');
    DROP TABLE IF EXISTS PVMT;
    CREATE TABLE PVMT as select * from pvmt_link;
    DROP TABLE pvmt_link;
    CREATE INDEX ON PVMT (revetement);
    CREATE INDEX ON PVMT (granulo);
    CREATE INDEX ON PVMT (classacou);
    UPDATE roads b SET pvmt = (select a.pvmt FROM pvmt a WHERE a.revetement=b.revetement AND a.granulo=b.granulo AND a.classacou=b.classacou);
    ALTER TABLE roads ADD COLUMN pk serial PRIMARY KEY;
    CREATE spatial index ON roads (the_geom);
    drop table if exists allbuildings_link;
    CREATE LINKED TABLE allbuildings_link ('org.h2gis.postgis_jts.Driver','$databaseUrl','$user','$pwd','noisemodelling', '(SELECT 
     a.the_geom, 
     a."IDBAT" as id_bat, 
     a."BAT_HAUT" as height,
     b."POP_BAT" as pop
    FROM 
     noisemodelling."C_BATIMENT_S_l93" a,
     echeance4."C_POPULATION" b,
     (select ST_BUFFER(the_geom, $buffer) the_geom from noisemodelling.ign_admin_express_dept_l93 e WHERE e.insee_dep=''$codeDep'' LIMIT 1) c 
    where
     a.the_geom && c.the_geom and 
     ST_INTERSECTS(a.the_geom, c.the_geom) and 
     a."IDBAT"=b."IDBAT")');
    DROP TABLE IF EXISTS allbuildings;
    DROP TABLE IF EXISTS buildings;
    CREATE TABLE buildings as select * from allbuildings_link;
    DROP TABLE allbuildings_link;
    CREATE SPATIAL INDEX ON buildings(the_geom);
    DELETE FROM buildings B WHERE NOT EXISTS (SELECT 1 FROM ROADS R WHERE ST_EXPAND(B.THE_GEOM, $buffer) && R.THE_GEOM AND ST_DISTANCE(b.the_geom, r.the_geom) < $buffer LIMIT 1);
    ALTER TABLE buildings ADD COLUMN pk serial PRIMARY KEY;
    """


    def binding = ["buffer": buffer, "databaseUrl": databaseUrl, "user": user, "pwd": pwd, "codeDep": codeDep]

    def engine = new SimpleTemplateEngine()
    def template = engine.createTemplate(queries).make(binding)

    sql.execute(template.toString())
    // print to WPS Builder
    return "Table BUILDINGS, DEPT, PVMT, ROADS, ZONE fetched"

}

