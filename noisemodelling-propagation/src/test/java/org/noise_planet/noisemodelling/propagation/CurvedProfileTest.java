/**
 * NoiseModelling is a library capable of producing noise maps. It can be freely used either for research and education, as well as by experts in a professional use.
 * <p>
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software.
 * <p>
 * Official webpage : http://noise-planet.org/noisemodelling.html
 * Contact: contact@noise-planet.org
 */


package org.noise_planet.noisemodelling.propagation;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.noise_planet.noisemodelling.pathfinder.profilebuilder.CutPoint;
import org.noise_planet.noisemodelling.propagation.cnossos.CurvedProfileGenerator;

import java.util.ArrayList;
import java.util.List;

public class CurvedProfileTest {

    @Test
    public void curvedProfile2() {
        Coordinate from = new Coordinate(100, 25, 110);
        Coordinate to = new Coordinate(500, 25, 110);

        int numberOfIntermediatePoints = 10;

        Coordinate[] coordinates = new Coordinate[numberOfIntermediatePoints+2];
        coordinates[0] = from;
        coordinates[coordinates.length-1] = to;
        for (int i = 1; i <= numberOfIntermediatePoints; i++) {
            double ratio = (double)i / (numberOfIntermediatePoints + 1);
            double x = from.x + ratio * (to.x - from.x);
            double y = from.y + ratio * (to.y - from.y);
            double z = from.z + ratio * (to.z - from.z);
            coordinates[i] = new Coordinate(x, y, z);
        }
        Coordinate[] curvedProfile = CurvedProfileGenerator.applyTransformation(from, to, 1, 1, coordinates);
        // print the transformed curved coordinates for python plot
        for (Coordinate coord : curvedProfile) {
            System.out.println(coord.x + "," + coord.y + "," + coord.z);
        }

    }



    @Test
    public void curvedProfile() {
        // Test the implementation with some dummy data
        List<CutPoint> flatProfile = new ArrayList<>();
        flatProfile.add(new CutPoint(new Coordinate(0, 0, 4)));
        flatProfile.add(new CutPoint(new Coordinate(500, 500, 1)));
        flatProfile.add(new CutPoint(new Coordinate(1000, 1000, 1)));
        flatProfile.add(new CutPoint(new Coordinate(1500, 1500, 1)));
        flatProfile.add(new CutPoint(new Coordinate(2000, 2000, 4)));

        flatProfile.get(0).setZGround(0);
        flatProfile.get(1).setZGround(0);
        flatProfile.get(2).setZGround(0);
        flatProfile.get(3).setZGround(0);
        flatProfile.get(4).setZGround(0);

        double distance = flatProfile.get(0).getCoordinate().distance(flatProfile.get(4).getCoordinate());
        System.out.println("distance = " + distance);
        CurvedProfileGenerator generator = new CurvedProfileGenerator();
        List<CutPoint> curvedProfile = CurvedProfileGenerator.applyTransformation(flatProfile);

        // Print the transformed curved coordinates
        for (CutPoint point : curvedProfile) {
            Coordinate coord = point.getCoordinate();
            System.out.println("Transformed coordinate: x = " + coord.getX() + ", y = " + coord.getY() + ", z = " + coord.getZ()+ ", zG = " + point.zGround);
        }

    }



}
