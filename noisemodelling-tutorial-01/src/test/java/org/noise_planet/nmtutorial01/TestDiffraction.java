package org.noise_planet.nmtutorial01;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.h2gis.api.EmptyProgressVisitor;
import org.h2gis.functions.io.geojson.GeoJsonRead;
import org.h2gis.utilities.SFSUtilities;
import org.h2gis.utilities.TableLocation;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTWriter;
import org.noise_planet.noisemodelling.jdbc.PointNoiseMap;
import org.noise_planet.noisemodelling.pathfinder.ComputeRays;
import org.noise_planet.noisemodelling.pathfinder.ComputeRaysOut;
import org.noise_planet.noisemodelling.pathfinder.PropagationProcessData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class TestDiffraction {


    @Test
    public void test1() throws SQLException, IOException {
        // Init output logger
        Logger logger = LoggerFactory.getLogger(Main.class);

        // Read working directory argument
        String workingDir = "target/";

        File workingDirPath = new File(workingDir).getAbsoluteFile();
        if(!workingDirPath.exists()) {
            if(!workingDirPath.mkdirs()) {
                logger.error(String.format("Cannot create working directory %s", workingDir));
                return;
            }
        }

        logger.info(String.format("Working directory is %s", workingDirPath.getAbsolutePath()));

        // Create spatial database named to current time
        DateFormat df = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.getDefault());

        // Open connection to database
        String dbName = new File(workingDir + df.format(new Date())).toURI().toString();
        Connection connection = SFSUtilities.wrapConnection(DbUtilities.createSpatialDataBase(dbName, true));
        Statement sql = connection.createStatement();

        // Import BUILDINGS

        logger.info("Import buildings");

        GeoJsonRead.readGeoJson(connection, Main.class.getResource("buildings.geojson").getFile(), "BUILDINGS");

        // Import noise source

        logger.info("Import noise source");

        GeoJsonRead.readGeoJson(connection, Main.class.getResource("lw_roads.geojson").getFile(), "LW_ROADS");
        // Set primary key
        sql.execute("ALTER TABLE LW_ROADS ALTER COLUMN PK INTEGER NOT NULL");
        sql.execute("ALTER TABLE LW_ROADS ADD PRIMARY KEY (PK)");

        // Import BUILDINGS

        logger.info("Import evaluation coordinates");

        GeoJsonRead.readGeoJson(connection, Main.class.getResource("receivers.geojson").getFile(), "RECEIVERS");
        // Set primary key
        sql.execute("ALTER TABLE RECEIVERS ALTER COLUMN PK INTEGER NOT NULL");
        sql.execute("ALTER TABLE RECEIVERS ADD PRIMARY KEY (PK)");


        // Import MNT

        logger.info("Import digital elevation model");

        GeoJsonRead.readGeoJson(connection, Main.class.getResource("dem_lorient.geojson").getFile(), "DEM");

        // Init NoiseModelling
        PointNoiseMap pointNoiseMap = new PointNoiseMap("BUILDINGS", "LW_ROADS", "RECEIVERS");

        pointNoiseMap.setMaximumPropagationDistance(800);
        pointNoiseMap.setSoundReflectionOrder(0);
        pointNoiseMap.setComputeHorizontalDiffraction(true);
        pointNoiseMap.setComputeVerticalDiffraction(true);
        // Building height field name
        pointNoiseMap.setHeightField("HEIGHT");
        // Point cloud height above sea level POINT(X Y Z)
        pointNoiseMap.setDemTable("DEM");
        // Do not propagate for low emission or far away sources.
        // error in dB
        pointNoiseMap.setMaximumError(0.1d);
        pointNoiseMap.setNoiseFloor(35d);

        pointNoiseMap.initialize(connection, null);

        pointNoiseMap.setGridDim(1);

        PropagationProcessData threadData = pointNoiseMap.prepareCell(connection, 0, 0, null, new HashSet<>());

        ComputeRays computeRays = new ComputeRays(threadData);
        computeRays.initStructures();

        int receiverId = 432;
        Coordinate receiverCoord = threadData.receivers.get(receiverId);
        ComputeRaysOut r = new ComputeRaysOut(false, threadData);

        GeometryFactory f = new GeometryFactory();
        Coordinate p1 = new Coordinate(223533.11, 6757437.85, 1.6);
        Coordinate p2 = new Coordinate(223823.95, 6757484.97, 1.6);
        System.out.println("ligne: "+new WKTWriter(3).write(f.createLineString(new Coordinate[]{p1, p2})));


        DescriptiveStatistics computationRays = new DescriptiveStatistics();


        computeRays.computeRaysAtPosition(receiverCoord, receiverId, null, r, new EmptyProgressVisitor());
//
//        // warmup
//        for(int i = 0; i <  10; i++) {
//            computeRays.computeRaysAtPosition(receiverCoord, receiverId, null, r, new EmptyProgressVisitor());
//        }
//
//
//
////        List<Coordinate> ray = computeRays.computeSideHull(true, p1, p2);
////        LineString geom = f.createLineString(ray.toArray(new Coordinate[0]));
//
//        for(int i = 0; i <  10; i++) {
//            long start = System.currentTimeMillis();
//            computeRays.computeRaysAtPosition(receiverCoord, receiverId, null, r, new EmptyProgressVisitor());
//            long elapsed = System.currentTimeMillis() - start;
//            computationRays.addValue(elapsed);
//        }

        //System.out.println(new WKTWriter(3).write(geom));
        System.out.printf("Computed in min:%d median:%d max:%d millisecs", (int)computationRays.getMin(),
                (int)computationRays.getPercentile(50), (int)computationRays.getMax());

    }
}
