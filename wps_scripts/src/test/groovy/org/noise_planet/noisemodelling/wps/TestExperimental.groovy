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

package org.noise_planet.noisemodelling.wps

import groovy.sql.Sql
import org.h2gis.functions.io.geojson.GeoJsonRead
import org.h2gis.functions.io.shp.SHPRead
import org.junit.Test
import org.noise_planet.noisemodelling.wps.Database_Manager.Add_Primary_Key
import org.noise_planet.noisemodelling.wps.Database_Manager.Table_Visualization_Data
import org.noise_planet.noisemodelling.wps.Experimental.Drone_Dynamic_Third_map
import org.noise_planet.noisemodelling.wps.Experimental.Get_Rayz
import org.noise_planet.noisemodelling.wps.Experimental.Import_Asc_Directivity
import org.noise_planet.noisemodelling.wps.Experimental.Multi_Runs
import org.noise_planet.noisemodelling.wps.Geometric_Tools.Change_SRID
import org.noise_planet.noisemodelling.wps.Import_and_Export.Export_Table
import org.noise_planet.noisemodelling.wps.Import_and_Export.Import_File
import org.noise_planet.noisemodelling.wps.Receivers.Regular_Grid

class TestExperimental extends JdbcTestCase  {

    @Test
    void testMultiRun() {

        GeoJsonRead.readGeoJson(connection, TestExperimental.class.getResource("multirun/buildings.geojson").getPath())
        GeoJsonRead.readGeoJson(connection, TestExperimental.class.getResource("multirun/receivers.geojson").getPath())
        GeoJsonRead.readGeoJson(connection, TestExperimental.class.getResource("multirun/sources.geojson").getPath())

        new Add_Primary_Key().exec(connection,
                ["pkName":"PK",
                 "tableName" : "RECEIVERS"])

        new Add_Primary_Key().exec(connection,
                 ["pkName":"PK",
                  "tableName" : "SOURCES"])


        new Get_Rayz().exec(connection,
                ["tableBuilding"   : "BUILDINGS",
                 "roadsTableName"   : "SOURCES",
                 "tableReceivers": "RECEIVERS",
                 "exportPath"   : TestExperimental.class.getResource("multirun/").getPath()])


        new Multi_Runs().exec(connection,
                ["workingDir":TestExperimental.class.getResource("multirun/").getPath()])


      String res =   new Table_Visualization_Data().exec(connection,
                ["tableName": "MultiRunsResults_geom"])

        new Get_Rayz().exec(connection,
                ["tableBuilding"   : "BUILDINGS",
                 "roadsTableName"   : "SOURCES",
                 "tableReceivers": "RECEIVERS",
                 "confReflOrder": 1,
                 "exportPath"   : TestExperimental.class.getResource("multirun/").getPath()])

        new Multi_Runs().exec(connection,
                ["workingDir":TestExperimental.class.getResource("multirun/").getPath()])

        String res3 =   new Table_Visualization_Data().exec(connection,
                ["tableName": "MultiRunsResults_geom"])



        //assertEquals(res, res3)
    }

    void testReadDirectivity() {
        new Import_Asc_Directivity().exec(connection, ["pathFile": "/home/nicolas/data/airbus/RE Taxis Volants/NOISE_SOURCE/idNoiseSphere_1.txt"])
    }

//    void testDrone() {
//        new Import_File().exec(connection, ["pathFile": "/home/nicolas/data/airbus/buildings.geojson"])
//        new Import_File().exec(connection, ["pathFile": "/home/nicolas/data/airbus/receivers.geojson"])
//        new Import_File().exec(connection, ["pathFile" : "/home/nicolas/data/airbus/Drone_position.geojson",
//                                            "inputSRID": 4326])
//        new Import_File().exec(connection, ["pathFile" : "/home/nicolas/data/airbus/Drone_time.geojson",
//                                            "inputSRID": 4326])
//
//        Sql sql = new Sql(connection)
//
//        sql.execute("DELETE FROM RECEIVERS WHERE (RAND() * 1000)::int != 1")
//
//        // change coordinate system to metric
//        new Change_SRID().exec(connection, ["newSRID": 2154, "tableName":"DRONE_POSITION"])
//        new Change_SRID().exec(connection, ["newSRID": 2154, "tableName":"DRONE_TIME"])
//
//
//        new Drone_Dynamic_Third_map().exec(connection, ["buildingTableName":"BUILDINGS",
//        "sourcesTimeTableName":"DRONE_TIME",
//        "sourcesPositionTableName": "DRONE_POSITION",
//        "receiversTableName": "RECEIVERS",
//        "computeVertical": true,
//        "computeHorizontal":true,
//        "threadNumber": 4,
//        "maxSrcDistance" : 3000])
//
//        new Export_Table().exec(connection, ["exportPath": "target/LDRONE_GEOM.shp",
//        "tableToExport" : "LDRONE_GEOM"])
//    }

}
