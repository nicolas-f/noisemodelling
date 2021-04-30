package org.noise_planet.noisemodelling.geoserver;

import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.geoserver.wps.gs.GeoServerProcess;
import org.opengis.util.ProgressListener;

@DescribeProcess(title="Calculation of the Lden,LDay,LEvening,LNight map from the noise emission table",
        description="Calculation of the Lden map from the road noise emission table (DEN format, see input details)." +
                " </br> Tables must be projected in a metric coordinate system (SRID). Use \"Change_SRID\" WPS Block" +
                " if needed. </br> </br>" +
                " <b> The output table is called : LDEN_GEOM, LDAY_GEOM, LEVENING_GEOM, LNIGHT_GEOM </b>" +
                " and contain : </br>" +
                "-  <b> IDRECEIVER  </b> : an identifier (INTEGER, PRIMARY KEY). </br>" +
                "- <b> THE_GEOM </b> : the 3D geometry of the receivers (POINT).</br>" +
                "-  <b> Hz63, Hz125, Hz250, Hz500, Hz1000,Hz2000, Hz4000, Hz8000 </b> : " +
                "8 columns giving the day emission sound level for each octave band (FLOAT).")

public class NoiseLevelFromSource implements GeoServerProcess {

    @DescribeResult(name="result", description="output result")
    public String execute(
            @DescribeParameter(name="Buildings table name",
                    description="<b>Name of the Buildings table.</b>  </br></br>" +
                            "The table shall contain : </br>" +
                            "- <b> THE_GEOM </b> : the 2D geometry of the building (POLYGON or MULTIPOLYGON). </br>" +
                            "- <b> HEIGHT </b> : the height of the building (FLOAT)")
                    String tableBuilding,
            @DescribeParameter(name="Sources table name",
                    description="<b>Name of the Sources table.</b></br></br>" +
                            "The table shall contain : </br>" +
                            "- <b> PK </b> : an identifier. It shall be a primary key (INTEGER, PRIMARY KEY). </br>" +
                            "- <b> THE_GEOM </b> : the 3D geometry of the sources (POINT, MULTIPOINT, LINESTRING," +
                            " MULTILINESTRING). According to CNOSSOS-EU, you need to set a height of 0.05 m for" +
                            " a road traffic emission.</br> " +
                            "- <b> LWD63, LWD125, LWD250, LWD500, LWD1000, LWD2000, LWD4000, LWD8000 </b> :" +
                            " 8 columns giving the day emission sound level for each octave band (FLOAT). </br>" +
                            "- <b> LWE* </b> : 8 columns giving the evening emission sound level for each octave band (FLOAT).</br>" +
                            "- <b> LWN* </b> : 8 columns giving the night emission sound level for each octave band (FLOAT).</br></br></br>" +
                            "<b> This table can be generated from the WPS Block \"Road_Emission_from_Traffic\". </b>")
                    String tableSources,
            @DescribeParameter(name="Receivers table name",
                    description="<b>Name of the Receivers table.</b></br></br>" +
                            "The table shall contain : </br>" +
                            "- <b> PK </b> : an identifier. It shall be a primary key (INTEGER, PRIMARY KEY). </br>" +
                            "- <b> THE_GEOM </b> : the 3D geometry of the sources (POINT, MULTIPOINT).</br></br></br>" +
                            "<b> This table can be generated from the WPS Blocks in the \"Receivers\" folder. </b>")
                    String tableReceivers,
            ProgressListener gtProgress) {






        return "Hello, ";
    }
}