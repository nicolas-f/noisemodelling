package org.noise_planet.noisemodelling.pathfinder.utils;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import org.cts.CRSFactory;
import org.cts.IllegalCoordinateException;
import org.cts.crs.CRSException;
import org.cts.crs.CoordinateReferenceSystem;
import org.cts.crs.GeodeticCRS;
import org.cts.op.CoordinateOperation;
import org.cts.op.CoordinateOperationException;
import org.cts.op.CoordinateOperationFactory;
import org.cts.registry.EPSGRegistry;
import org.cts.registry.RegistryManager;
import org.locationtech.jts.geom.Coordinate;
import org.noise_planet.noisemodelling.pathfinder.PointPath;
import org.noise_planet.noisemodelling.pathfinder.PropagationPath;
import org.noise_planet.noisemodelling.pathfinder.SegmentPath;
import org.noise_planet.noisemodelling.pathfinder.Triangle;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Export rays for validation
 */
public class GeoJSONDocument {
    JsonGenerator jsonGenerator;
    String crs = "EPSG:4326";
    private CoordinateOperation transform = null;
    int rounding = -1;

    public GeoJSONDocument(OutputStream outputStream) throws IOException {
        JsonFactory jsonFactory = new JsonFactory();
        jsonGenerator = jsonFactory.createGenerator(outputStream, JsonEncoding.UTF8);
        jsonGenerator.setPrettyPrinter(new DefaultPrettyPrinter("\n"));
    }

    public void setRounding(int rounding) {
        this.rounding = rounding;
    }

    public void writeFooter() throws IOException {
        jsonGenerator.writeEndArray(); // features
        jsonGenerator.writeObjectFieldStart("crs");
        jsonGenerator.writeStringField("type", "name");
        jsonGenerator.writeObjectFieldStart("properties");
        jsonGenerator.writeStringField("name", crs);
        jsonGenerator.writeEndObject(); // properties
        jsonGenerator.writeEndObject(); // crs
        jsonGenerator.writeEndObject(); // {
        jsonGenerator.flush();
        jsonGenerator.close();
    }
    public void writeHeader() throws IOException {
        jsonGenerator.writeStartObject();
        jsonGenerator.writeStringField("type", "FeatureCollection");
        jsonGenerator.writeArrayFieldStart("features");
    }

    public void writeRay(PropagationPath path) throws IOException {
        jsonGenerator.writeStartObject();
        jsonGenerator.writeStringField("type", "Feature");
        jsonGenerator.writeObjectFieldStart("geometry");
        jsonGenerator.writeStringField("type", "LineString");
        jsonGenerator.writeFieldName("coordinates");
        jsonGenerator.writeStartArray();
        for(PointPath pointPath : path.getPointList()) {
            writeCoordinate(new Coordinate(pointPath.coordinate));
        }
        jsonGenerator.writeEndArray();
        jsonGenerator.writeEndObject(); // geometry
        // Write properties
        jsonGenerator.writeObjectFieldStart("properties");
        jsonGenerator.writeNumberField("receiver", path.getIdReceiver());
        jsonGenerator.writeNumberField("source", path.getIdSource());
        if(path.getSRList() == null || path.getSRList().isEmpty()) {
            path.computeAugmentedSRPath();
        }
        jsonGenerator.writeArrayFieldStart("gPath");
        for(SegmentPath sr : path.getSegmentList()) {
            jsonGenerator.writeNumber(String.format(Locale.ROOT, "%.2f", sr.gPath));
        }
        jsonGenerator.writeEndArray(); //gPath
        jsonGenerator.writeEndObject(); // properties
        jsonGenerator.writeEndObject();
    }

    public void setInputCRS(String crs) throws CRSException, CoordinateOperationException {
        // Create a new CRSFactory, a necessary element to create a CRS without defining one by one all its components
        CRSFactory cRSFactory = new CRSFactory();

        // Add the appropriate registry to the CRSFactory's registry manager. Here the EPSG registry is used.
        RegistryManager registryManager = cRSFactory.getRegistryManager();
        registryManager.addRegistry(new EPSGRegistry());

        // CTS will read the EPSG registry seeking the 4326 code, when it finds it,
        // it will create a CoordinateReferenceSystem using the parameters found in the registry.
        CoordinateReferenceSystem crsKML = cRSFactory.getCRS("EPSG:4326");
        CoordinateReferenceSystem crsSource = cRSFactory.getCRS(crs);
        if(crsKML instanceof GeodeticCRS && crsSource instanceof GeodeticCRS) {
            transform = CoordinateOperationFactory.createCoordinateOperations((GeodeticCRS) crsSource, (GeodeticCRS) crsKML).iterator().next();
        }
    }

    /**
     * Write topography triangles
     * @param triVertices
     * @param vertices
     * @throws IOException
     */
    public void writeTopographic(List<Triangle> triVertices, List<Coordinate> vertices) throws IOException {
        for(Triangle triangle : triVertices) {
            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField("type", "Feature");
            jsonGenerator.writeObjectFieldStart("geometry");
            jsonGenerator.writeStringField("type", "Polygon");
            jsonGenerator.writeFieldName("coordinates");
            jsonGenerator.writeStartArray();
            jsonGenerator.writeStartArray(); // Outer line
            writeCoordinate(new Coordinate(vertices.get(triangle.getA())));
            writeCoordinate(new Coordinate(vertices.get(triangle.getB())));
            writeCoordinate(new Coordinate(vertices.get(triangle.getC())));
            writeCoordinate(new Coordinate(vertices.get(triangle.getA())));
            jsonGenerator.writeEndArray();
            jsonGenerator.writeEndArray();
            jsonGenerator.writeEndObject(); // geometry
            // Write properties
            jsonGenerator.writeObjectFieldStart("properties");
            jsonGenerator.writeNumberField("b", triangle.getAttribute());
            jsonGenerator.writeEndObject(); // properties
            jsonGenerator.writeEndObject();
        }
    }

    /**
     * Write coordinate positions.
     *
     * @param coordinate
     * @throws IOException
     */
    private void writeCoordinate(Coordinate coordinate) throws IOException {
        Coordinate c = new Coordinate(coordinate);
        if(transform != null) {
            try {
                double[] result = transform.transform(new double[]{c.x, c.y});
                c.x = result[0];
                c.y = result[1];
            } catch (IllegalCoordinateException | CoordinateOperationException ex) {
                throw new IOException(ex);
            }
        }
        jsonGenerator.writeStartArray();
        writeNumber(c.x);
        writeNumber(c.y);
        if (!Double.isNaN(c.z)) {
            writeNumber(c.z);
        }
        jsonGenerator.writeEndArray();
    }

    private void writeNumber(double number) throws IOException {
        if(rounding >= 0) {
            jsonGenerator.writeNumber(String.format(Locale.ROOT,"%."+rounding+"f", number));
        } else {
            jsonGenerator.writeNumber(number);
        }
    }
}
