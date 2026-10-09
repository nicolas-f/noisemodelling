/**
 * NoiseModelling is a library capable of producing noise maps. It can be freely used either for research and education, as well as by experts in a professional use.
 * <p>
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software.
 * <p>
 * Official webpage : http://noise-planet.org/noisemodelling.html
 * Contact: contact@noise-planet.org
 */
package org.noise_planet.noisemodelling.jdbc.utils;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;

import java.util.ArrayList;
import java.util.List;


/**
 * This class collects coordinates and keeps the Z values of points nearest to each corner of the envelope.
 * It stores only the 4 best candidates found so far, avoiding a full list in memory.
 */

public class CornerZCollector {

    private final Envelope envelope = new Envelope();
    private final List<Coordinate> coordinates = new ArrayList<>();

    /**
     * Adds a coordinate to the collector.
     *
     * @param c The coordinate to add.
     */
    public void addCoordinate(Coordinate c) {
        if (c == null) {
            return;
        }
        envelope.expandToInclude(c);
        // Store a defensive copy in case the caller mutates 'c' during iteration
        coordinates.add(new Coordinate(c));
    }

    /**
     * Expands the envelope by the specified margin in all directions.
     *
     * @param distance Distance to expand the envelope by.
     */
    public void expandEnvelope(double distance) {
        if (!envelope.isNull()) {
            envelope.expandBy(distance);
        }
    }

    /**
     * Returns the 4 corners of the envelope, each with the Z value of the nearest
     * DEM point collected.
     *
     * The corners are ordered:
     * 0: Bottom-Left  (minX, minY)
     * 1: Bottom-Right (maxX, minY)
     * 2: Top-Right    (maxX, maxY)
     * 3: Top-Left     (minX, maxY)
     *
     * @return An array of 4 coordinates, or null if no coordinates were added.
     */
    public Coordinate[] getCorners() {
        if (coordinates.isEmpty() || envelope.isNull()) {
            return null;
        }

        // Define the 4 target corners based on the final envelope
        Coordinate[] corners = new Coordinate[] {
                new Coordinate(envelope.getMinX(), envelope.getMinY()),
                new Coordinate(envelope.getMaxX(), envelope.getMinY()),
                new Coordinate(envelope.getMaxX(), envelope.getMaxY()),
                new Coordinate(envelope.getMinX(), envelope.getMaxY())
        };

        Coordinate[] bestPoints = new Coordinate[4];
        double[] minDistSq = new double[] {
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY
        };

        // Find the closest DEM point to each corner (using 2D squared distance)
        for (Coordinate pt : coordinates) {
            for (int i = 0; i < 4; i++) {
                double dx = corners[i].x - pt.x;
                double dy = corners[i].y - pt.y;
                double distSq = dx * dx + dy * dy;

                if (distSq < minDistSq[i]) {
                    minDistSq[i] = distSq;
                    bestPoints[i] = pt;
                }
            }
        }

        return new Coordinate[] {
                new Coordinate(corners[0].x, corners[0].y, bestPoints[0].getZ()),
                new Coordinate(corners[1].x, corners[1].y, bestPoints[1].getZ()),
                new Coordinate(corners[2].x, corners[2].y, bestPoints[2].getZ()),
                new Coordinate(corners[3].x, corners[3].y, bestPoints[3].getZ())
        };
    }
}