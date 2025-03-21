package org.noise_planet.nmtutorial01;

import org.h2.value.ValueBoolean;
import org.h2gis.functions.factory.H2GISDBFactory;
import org.h2gis.functions.io.fgb.FGBWrite;
import org.h2gis.functions.io.shp.SHPRead;
import org.h2gis.functions.io.shp.SHPWrite;
import org.h2gis.utilities.JDBCUtilities;
import org.noise_planet.noisemodelling.jdbc.NoiseMapByReceiverMaker;
import org.noise_planet.noisemodelling.jdbc.NoiseMapDatabaseParameters;
import org.noise_planet.noisemodelling.pathfinder.utils.profiler.RootProgressVisitor;
import java.sql.Connection;
import java.sql.Statement;

public class DebugPierre {

    /**
     * @param args var args
     * @throws Exception exception
     */
    public static void main(String[] args) throws Exception {
        try(Connection connection = JDBCUtilities.wrapConnection(
                H2GISDBFactory.createSpatialDataBase(DebugPierre.class.getSimpleName(),
                        true, ""));) {

            SHPRead.importTable(connection, "/Users/fortin/Downloads/NoiseModelling_without_gui(2)/resources/Lavaur/Buildings_Lavaur.shp");
            SHPRead.importTable(connection, "/Users/fortin/Downloads/NoiseModelling_without_gui(2)/RECEIVERS_HOME.shp");
            SHPRead.importTable(connection, "/Users/fortin/Downloads/NoiseModelling_without_gui(2)/resources/Lavaur/Siren_Lavaur.shp");

            try(Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE SOURCE AS SELECT ST_UPDATEZ(the_geom, 15) the_geom FROM SIREN_LAVAUR");
                st.execute("ALTER TABLE SOURCE ADD COLUMN IDSOURCE SERIAL NOT NULL PRIMARY KEY");
                st.execute("CREATE TABLE RECEIVERS(IRECEIVERS INTEGER PRIMARY KEY, THE_GEOM) AS SELECT PK IDRECEIVER, ST_UPDATEZ(the_geom, 1.5) the_geom FROM RECEIVERS_HOME");
            }

            // Init NoiseModelling
            NoiseMapByReceiverMaker noiseMapByReceiverMaker = new NoiseMapByReceiverMaker("BUILDINGS_LAVAUR",
                    "SOURCE", "RECEIVERS");

            noiseMapByReceiverMaker.setSoundReflectionOrder(3);
            noiseMapByReceiverMaker.setMaximumReflectionDistance(1000);
            noiseMapByReceiverMaker.setMaximumPropagationDistance(10000);
            noiseMapByReceiverMaker.setGridDim(1);
            noiseMapByReceiverMaker.setComputeHorizontalDiffraction(true);
            noiseMapByReceiverMaker.setComputeVerticalDiffraction(true);
            noiseMapByReceiverMaker.getNoiseMapDatabaseParameters().exportReceiverPosition = true;
            noiseMapByReceiverMaker.getNoiseMapDatabaseParameters().raysTable = "RAYS";
            noiseMapByReceiverMaker.getNoiseMapDatabaseParameters().exportRaysMethod = NoiseMapDatabaseParameters.ExportRaysMethods.TO_RAYS_TABLE;
            noiseMapByReceiverMaker.getNoiseMapDatabaseParameters().exportAttenuationMatrix = true;
            noiseMapByReceiverMaker.getNoiseMapDatabaseParameters().exportCnossosPathWithAttenuation = true;

            RootProgressVisitor rootProgressVisitor = new RootProgressVisitor(1, true, 1);

            noiseMapByReceiverMaker.run(connection, rootProgressVisitor);

            SHPWrite.exportTable(connection, "target/RECEIVERS_LEVEL.shp", "RECEIVERS_LEVEL", ValueBoolean.get(true));
            FGBWrite.execute(connection, "target/RAYS.fgb", "RAYS", true);
        }
    }
}
