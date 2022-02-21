package org.noise_planet.noisemodelling.pathfinder.utils;

import org.locationtech.jts.algorithm.RectangleLineIntersector;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.ItemVisitor;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.ArrayList;
import java.util.List;

/**
 * Slightly modified STRTree that use a ray envelope query instead of a rectangular envelope
 */
public class STRTreeRay extends STRtree {
    private static final IntersectsOp intersectsOp = (aBounds, bBounds) -> {
        if(aBounds instanceof Envelope) {
            if(bBounds instanceof Envelope) {
                return ((Envelope) aBounds).intersects((Envelope) bBounds);
            } else {
                return ((RayEnvelope) bBounds).intersects((Envelope) aBounds);
            }
        } else {
            return ((RayEnvelope) aBounds).intersects((Envelope) bBounds);
        }
    };

    public STRTreeRay() {
    }

    public STRTreeRay(int nodeCapacity) {
        super(nodeCapacity);
    }

    public STRTreeRay(int nodeCapacity, ArrayList itemBoundables) {
        super(nodeCapacity, itemBoundables);
    }


    public List query(RayEnvelope searchEnv) {
        return super.query(searchEnv);
    }


    public void query(RayEnvelope searchEnv, ItemVisitor visitor) {
        super.query(searchEnv, visitor);
    }

    @Override
    protected IntersectsOp getIntersectsOp() {
        return intersectsOp;
    }

    public static class RayEnvelope {
        private Coordinate p0;
        private Coordinate p1;
        private final Envelope rayEnvelope;
        private final LineString lineString;
        private final double distance;
        private final GeometryFactory geometryFactory = new GeometryFactory();

        public RayEnvelope(Coordinate p0, Coordinate p1, double distance) {
            this.p0 = p0;
            this.p1 = p1;
            this.rayEnvelope = new Envelope(p0, p1);
            this.lineString = geometryFactory.createLineString(new Coordinate[]{p0,
                    p1});
            this.distance = distance;
        }

        public boolean intersects(Envelope env) {
            if(!rayEnvelope.intersects(env)) {
                return false;
            }
            if(Double.isNaN(distance) || Double.compare(distance, 0) == 0) {
                RectangleLineIntersector rectangleLineIntersector = new RectangleLineIntersector(env);
                return rectangleLineIntersector.intersects(p0, p1);
            } else {
                DistanceOp distanceOp = new DistanceOp(lineString, geometryFactory.toGeometry(env));
                return distanceOp.distance() < distance;
            }
        }
    }
}
