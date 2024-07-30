/**
 * NoiseModelling is a library capable of producing noise maps. It can be freely used either for research and education, as well as by experts in a professional use.
 * <p>
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software.
 * <p>
 * Official webpage : http://noise-planet.org/noisemodelling.html
 * Contact: contact@noise-planet.org
 */

package org.noise_planet.noisemodelling.pathfinder;

import org.apache.commons.math3.geometry.euclidean.threed.Line;
import org.apache.commons.math3.geometry.euclidean.threed.Plane;
import org.h2gis.api.ProgressVisitor;
import org.locationtech.jts.algorithm.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.math.Vector2D;
import org.locationtech.jts.math.Vector3D;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.locationtech.jts.triangulate.quadedge.Vertex;
import org.noise_planet.noisemodelling.pathfinder.cnossos.CnossosPath;
import org.noise_planet.noisemodelling.pathfinder.profilebuilder.ProfileBuilder;
import org.noise_planet.noisemodelling.pathfinder.path.*;
import org.noise_planet.noisemodelling.pathfinder.path.SegmentPath;
import org.noise_planet.noisemodelling.pathfinder.path.MirrorReceiversCompute;
import org.noise_planet.noisemodelling.pathfinder.path.PointPath;
import org.noise_planet.noisemodelling.pathfinder.path.MirrorReceiver;
import org.noise_planet.noisemodelling.pathfinder.profilebuilder.*;
import org.noise_planet.noisemodelling.pathfinder.utils.geometry.Orientation;
import org.noise_planet.noisemodelling.pathfinder.utils.geometry.JTSUtility;
import org.noise_planet.noisemodelling.pathfinder.utils.profiler.ProfilerThread;
import org.noise_planet.noisemodelling.pathfinder.utils.profiler.ReceiverStatsMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.lang.Double.isNaN;
import static java.lang.Math.*;
import static org.noise_planet.noisemodelling.pathfinder.PathFinder.ComputationSide.LEFT;
import static org.noise_planet.noisemodelling.pathfinder.PathFinder.ComputationSide.RIGHT;
//import static org.noise_planet.noisemodelling.pathfinder.utils.geometry.JTSUtility.dist2D;
import static org.noise_planet.noisemodelling.pathfinder.path.PointPath.POINT_TYPE.*;
import static org.noise_planet.noisemodelling.pathfinder.profilebuilder.ProfileBuilder.IntersectionType.*;
import static org.noise_planet.noisemodelling.pathfinder.utils.geometry.GeometricAttenuation.getADiv;
import static org.noise_planet.noisemodelling.pathfinder.utils.geometry.GeometryUtils.projectPointOnLine;
import static org.noise_planet.noisemodelling.pathfinder.utils.Utils.*;

/**
 * @author Nicolas Fortin
 * @author Pierre Aumond
 * @author Sylvain Palominos
 */
public class PathFinder {
    private static final double ALPHA0 = 2e-4;
    private static final double wideAngleTranslationEpsilon = 0.01;
    private static final double epsilon = 1e-7;
    private static final double MAX_RATIO_HULL_DIRECT_PATH = 4;
    public static final Logger LOGGER = LoggerFactory.getLogger(PathFinder.class);

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    public List<Coordinate> getZ_profile() {
        return z_profile;
    }

    private List<Coordinate> z_profile = new ArrayList<>();

    public static List<Coordinate> getMirrorPoints() {
        return mirrorPoints;
    }

    private static final List<Coordinate> mirrorPoints = new ArrayList<>();
    public Scene getData() {
        return data;
    }

    /** Propagation data to use for computation. */
    private final Scene data;

    /** Number of thread used for ray computation. */
    private int threadCount ;
    private ProfilerThread profilerThread;

    /**
     * Create new instance from the propagation data.
     * @param data Propagation data used for ray computation.
     */
    public PathFinder(Scene data) {
        this.data = data;
        this.threadCount = Runtime.getRuntime().availableProcessors();
    }

    /**
     * Computation stacks and timing are collected by this class in order
     * to profile the execution of the simulation
     * @return Instance of ProfilerThread or null
     */
    public ProfilerThread getProfilerThread() {
        return profilerThread;
    }

    /**
     * Computation stacks and timing are collected by this class in order
     * to profile the execution of the simulation
     * @param profilerThread Instance of ProfilerThread
     */
    public void setProfilerThread(ProfilerThread profilerThread) {
        this.profilerThread = profilerThread;
    }

    /**
     * Sets the number of thread to use.
     * @param threadCount Number of thread.
     */
    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }

    /**
     * Run computation and store the results in the given output.
     * @param computeRaysOut Result output.
     */
    public void run(IComputePathsOut computeRaysOut) {
        ProgressVisitor visitor = data.cellProg;
        ThreadPool threadManager = new ThreadPool(threadCount, threadCount + 1, Long.MAX_VALUE, TimeUnit.SECONDS);
        int maximumReceiverBatch = (int) Math.ceil(data.receivers.size() / (double) threadCount);
        int endReceiverRange = 0;
        //Launch execution of computation by batch
        while (endReceiverRange < data.receivers.size()) {
            //Break if the progress visitor is cancelled
            if (visitor != null && visitor.isCanceled()) {
                break;
            }
            int newEndReceiver = Math.min(endReceiverRange + maximumReceiverBatch, data.receivers.size());
            ThreadPathFinder batchThread = new ThreadPathFinder(endReceiverRange, newEndReceiver,
                    this, visitor, computeRaysOut, data);
            if (threadCount != 1) {
                threadManager.executeBlocking(batchThread);
            } else {
                batchThread.run();
            }
            endReceiverRange = newEndReceiver;
        }
        //Once the execution ends, shutdown the thread manager and await termination
        threadManager.shutdown();
        try {
            if(!threadManager.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("Timeout elapsed before termination.");
            }
        } catch (InterruptedException ex) {
            LOGGER.error(ex.getLocalizedMessage(), ex);
        }
    }

    /**
     * Compute the rays to the given receiver.
     * @param rcv     Receiver point.
     * @param dataOut Computation output.
     * @param visitor Progress visitor used for cancellation and progression managing.
     */
    public void computeRaysAtPosition(PointPath.ReceiverPointInfo rcv, IComputePathsOut dataOut, ProgressVisitor visitor) {
        MirrorReceiversCompute receiverMirrorIndex = null;

        if(data.reflexionOrder > 0) {
            Envelope receiverPropagationEnvelope = new Envelope(rcv.getCoord());
            receiverPropagationEnvelope.expandBy(data.maxSrcDist);
            List<Wall> buildWalls = data.profileBuilder.getWallsIn(receiverPropagationEnvelope);
            receiverMirrorIndex = new MirrorReceiversCompute(buildWalls, rcv.position, data.reflexionOrder,
                    data.maxSrcDist, data.maxRefDist);
        }

        //Compute the source search area
        double searchSourceDistance = data.maxSrcDist;
        Envelope receiverSourceRegion = new Envelope(
                rcv.getCoord().x - searchSourceDistance,
                rcv.getCoord().x + searchSourceDistance,
                rcv.getCoord().y - searchSourceDistance,
                rcv.getCoord().y + searchSourceDistance
        );
        Iterator<Integer> regionSourcesLst = data.sourcesIndex.query(receiverSourceRegion);
        List<PointPath.SourcePointInfo> sourceList = new ArrayList<>();
        //Already processed Raw source (line and/or points)
        HashSet<Integer> processedLineSources = new HashSet<>();
        // Sum of all sources power using only geometric dispersion with direct field
        double totalPowerRemaining = 0;
        while (regionSourcesLst.hasNext()) {
            Integer srcIndex = regionSourcesLst.next();
            if (!processedLineSources.contains(srcIndex)) {
                processedLineSources.add(srcIndex);
                Geometry source = data.sourceGeometries.get(srcIndex);
                double[] wj = data.getMaximalSourcePower(srcIndex);
                if (source instanceof Point) {
                    Coordinate ptpos = source.getCoordinate();
                    if (ptpos.distance(rcv.getCoord()) < data.maxSrcDist) {
                        Orientation orientation = null;
                        if(data.sourcesPk.size() > srcIndex) {
                            orientation = data.sourceOrientation.get(data.sourcesPk.get(srcIndex));
                        }
                        if(orientation == null) {
                            orientation = new Orientation(0,0, 0);
                        }
                        totalPowerRemaining += insertPtSource((Point) source, rcv.getCoord(), srcIndex, sourceList, wj, 1., orientation);
                    }
                } else if (source instanceof LineString) {
                    totalPowerRemaining += addLineSource((LineString) source, rcv.getCoord(), srcIndex, sourceList, wj);
                } else if (source instanceof MultiLineString) {
                    for (int id = 0; id < source.getNumGeometries(); id++) {
                        Geometry subGeom = source.getGeometryN(id);
                        if (subGeom instanceof LineString) {
                            totalPowerRemaining += addLineSource((LineString) subGeom, rcv.getCoord(), srcIndex, sourceList, wj);
                        }
                    }
                } else {
                    throw new IllegalArgumentException(
                            String.format("Sound source %s geometry are not supported", source.getGeometryType()));
                }
            }
        }
        // Sort sources by power contribution descending
        Collections.sort(sourceList);
        double powerAtSource = 0;
        // For each Pt Source - Pt Receiver
        AtomicInteger raysCount = new AtomicInteger(0);
        for (PointPath.SourcePointInfo src : sourceList) {
            double[] power = rcvSrcPropagation(src, src.li, rcv, dataOut, raysCount, receiverMirrorIndex);
            double global = sumArray(power.length, dbaToW(power));
            totalPowerRemaining -= src.globalWj;
            if (power.length > 0) {
                powerAtSource += global;
            } else {
                powerAtSource += src.globalWj;
            }
            totalPowerRemaining = max(0, totalPowerRemaining);
            // If the delta between already received power and maximal potential power received is inferior than than data.maximumError
            if ((visitor != null && visitor.isCanceled()) || (data.maximumError > 0 &&
                            wToDba(powerAtSource + totalPowerRemaining) - wToDba(powerAtSource) < data.maximumError)) {
                break; //Stop looking for more rays
            }
        }

        if(profilerThread != null &&
                profilerThread.getMetric(ReceiverStatsMetric.class) != null) {
            profilerThread.getMetric(ReceiverStatsMetric.class).onReceiverRays(rcv.getId(), raysCount.get());
        }

        // No more rays for this receiver
        dataOut.finalizeReceiver(rcv.getId());
    }

    /**
     * Calculation of the propagation between the given source and receiver. The result is registered in the given
     * output.
     * @param src     Source point.
     * @param srcLi   Source power per meter coefficient.
     * @param rcv     Receiver point.
     * @param dataOut Output.
     * @return
     */
    private double[] rcvSrcPropagation(PointPath.SourcePointInfo src, double srcLi, PointPath.ReceiverPointInfo rcv,
                                       IComputePathsOut dataOut, AtomicInteger raysCount,
                                       MirrorReceiversCompute receiverMirrorIndex) {

        double propaDistance = src.getCoord().distance(rcv.getCoord());
        if (propaDistance < data.maxSrcDist) {
            // Process direct : horizontal and vertical diff
            List<CnossosPath> pathParameters = new ArrayList<>(directPath(src, rcv,
                    data.computeVerticalDiffraction, data.computeHorizontalDiffraction, data.isBodyBarrier()));
            // Process reflection
            if (data.reflexionOrder > 0) {
                pathParameters.addAll(computeReflexion(rcv.getCoord(), src.getCoord(), false,
                        src.getOrientation(), receiverMirrorIndex));
            }
            if (!pathParameters.isEmpty()) {
                if(raysCount != null) {
                    raysCount.addAndGet(pathParameters.size());
                }
                return dataOut.addPropagationPaths(src.getId(), srcLi, rcv.getId(), pathParameters);
            }
        }
        return new double[0];
    }

    /**
     * Direct Path computation.
     * @param src Source point.
     * @param rcv Receiver point.
     * @return Calculated propagation paths.
     */
    public List<CnossosPath> directPath(PointPath.SourcePointInfo src,
                                        PointPath.ReceiverPointInfo rcv, boolean verticalDiffraction, boolean horizontalDiffraction, boolean bodyBarrier) {
        return directPath(src.getCoord(), src.getId(), src.getOrientation(), rcv.getCoord(), rcv.getId(),
                verticalDiffraction, horizontalDiffraction, bodyBarrier);
    }

    /**
     * Direct Path computation.
     * @param srcCoord Source point coordinate.
     * @param srcId    Source point identifier.
     * @param rcvCoord Receiver point coordinate.
     * @param rcvId    Receiver point identifier.
     * @return Calculated propagation paths.
     */
    public List<CnossosPath> directPath(Coordinate srcCoord, int srcId, Orientation orientation, Coordinate rcvCoord, int rcvId, boolean verticalDiffraction, boolean horizontalDiffraction, boolean bodyBarrier) {
        List<CnossosPath> pathsParameters = new ArrayList<>();
        CutProfile cutProfile = data.profileBuilder.getProfile(srcCoord, rcvCoord, data.gS);
        cutProfile.setSrcOrientation(orientation);
        //If the field is free, simplify the computation
        if(cutProfile.isFreeField()) {
            pathsParameters.add(computeFreeField(cutProfile, data, true));
        }
        else if(verticalDiffraction || horizontalDiffraction) {
            if (verticalDiffraction) {
                CnossosPath pathParameters = computeHEdgeDiffraction(cutProfile, bodyBarrier);
                if(pathParameters != null) {
                    pathsParameters.add(pathParameters);
                }
            }
            if (horizontalDiffraction) {
                CnossosPath pathParameters = computeVEdgeDiffraction(srcCoord, rcvCoord, data, LEFT, orientation);
                if (pathParameters != null && pathParameters.getPointList() != null) {
                    pathsParameters.add(pathParameters);
                }
                pathParameters = computeVEdgeDiffraction(srcCoord, rcvCoord, data, RIGHT, orientation);
                if (pathParameters != null && pathParameters.getPointList() != null) {
                    pathsParameters.add(pathParameters);
                }
            }
        }

        for(CnossosPath pathParameters : pathsParameters) {
            pathParameters.idSource = srcId;
            pathParameters.idReceiver = rcvId;
            pathParameters.setSourceOrientation(orientation);
        }

        return pathsParameters;
    }


    /**
     * Eq.2.5.24 and Eq. 2.5.25
     * @param mn
     * @param d
     * @return
     */

    private static double toCurve(double mn, double d){
        return 2*max(1000, 8*d)* asin(mn/(2*max(1000, 8*d)));
    }


    /**
     *
     * @param src
     * @param rcv
     * @param meanPlane
     * @param gPath
     * @param gS
     * @return
     */
    private static SegmentPath computeSegment(Coordinate src, Coordinate rcv, double[] meanPlane, double gPath, double gS) {
        return computeSegment(src, src.y, rcv, rcv.y, meanPlane, gPath, gS);
    }


    /**
     * Compute the segment path
     * @param src
     * @param rcv
     * @param meanPlane
     * @return the calculated segment
     */
    private static SegmentPath computeSegment(Coordinate src, Coordinate rcv, double[] meanPlane) {
        return computeSegment(src, src.y, rcv, rcv.y, meanPlane, 0, 0);
    }

    /**
     * Compute the segment path with more attribute
     * @param src
     * @param sz
     * @param rcv
     * @param rz
     * @param meanPlane
     * @param gPath
     * @param gS
     * @return the computed segment path
     */

    private static SegmentPath computeSegment(Coordinate src, double sz, Coordinate rcv, double rz, double[] meanPlane, double gPath, double gS) {
        SegmentPath seg = new SegmentPath();
        Coordinate srcZ = new Coordinate(src.x, sz);
        Coordinate rcvZ = new Coordinate(rcv.x, rz);
        Coordinate srcMeanPlane = projectPointOnLine(srcZ, meanPlane[0], meanPlane[1]);
        Coordinate rcvMeanPlane = projectPointOnLine(rcvZ, meanPlane[0], meanPlane[1]);
        seg.s = srcZ;
        seg.r = rcvZ;
        seg.sMeanPlane = srcMeanPlane;
        seg.rMeanPlane = rcvMeanPlane;
        seg.sPrime = new Coordinate(seg.s.x+(seg.sMeanPlane.x-seg.s.x)*2, seg.s.y+(seg.sMeanPlane.y-seg.s.y)*2);
        seg.rPrime = new Coordinate(seg.r.x+(seg.rMeanPlane.x-seg.r.x)*2, seg.r.y+(seg.rMeanPlane.y-seg.r.y)*2);
        mirrorPoints.add(seg.sPrime);
        mirrorPoints.add(seg.rPrime);

        seg.d = src.distance(rcv);
        seg.dp =srcMeanPlane.distance(rcvMeanPlane);
        seg.zsH = srcZ.distance(srcMeanPlane);
        seg.zrH = rcvZ.distance(rcvMeanPlane);
        seg.a = meanPlane[0];
        seg.b = meanPlane[1];
        //double deltaZs = a0*(seg.zsH/(seg.zsH+seg.zrH) * seg.zsH/(seg.zsH+seg.zrH))* (seg.dp*seg.dp)/2;
        //double deltaZr = a0*(seg.zrH/(seg.zsH+seg.zrH) * seg.zrH/(seg.zsH+seg.zrH))* (seg.dp*seg.dp)/2;
        //double deltaZt = 6*(10^-3)*seg.dp/seg.zsH+seg.zrH;
        seg.testFormH = seg.dp/(30*(seg.zsH +seg.zrH));
        seg.gPath = gPath;
        seg.gPathPrime = seg.testFormH <= 1 ? seg.gPath*(seg.testFormH) + gS*(1-seg.testFormH) : seg.gPath;
        double deltaZT = 6e-3 * seg.dp / (seg.zsH + seg.zrH);
        double deltaZS = ALPHA0 * Math.pow((seg.zsH / (seg.zsH + seg.zrH)), 2) * (seg.dp*seg.dp / 2);
        seg.zsF = seg.zsH + deltaZS + deltaZT;
        double deltaZR = ALPHA0 * Math.pow((seg.zrH / (seg.zsH + seg.zrH)), 2) * (seg.dp*seg.dp / 2);
        seg.zrF = seg.zrH + deltaZR + deltaZT;
        seg.testFormF = seg.dp/(30*(seg.zsF +seg.zrF));
        return seg;
    }


    /**
     *
     * @param pts
     * @return @return the computed coordinate list
     */
    private static List<Coordinate> computePts2D(List<CutPoint> pts) {
        List<Coordinate> pts2D = pts.stream()
                .map(CutPoint::getCoordinate)
                .collect(Collectors.toList());
        pts2D = JTSUtility.getNewCoordinateSystem(pts2D);
        return pts2D;
    }


    /**
     *
     * @param cutProfile
     * @param data
     * @return the computed coordinate list
     */
    List<Coordinate> computePts2DGround(CutProfile cutProfile, Scene data) {
        List<Coordinate> pts2D= cutProfile.getCutPoints().stream()
                .filter(cut -> cut.getType() != GROUND_EFFECT)
                .map(cut -> new Coordinate(cut.getCoordinate().x, cut.getCoordinate().y, data.profileBuilder.getZGround(cut)))
                .collect(Collectors.toList());
        List<Coordinate> pointts2D = JTSUtility.getNewCoordinateSystem(pts2D);
        List<Coordinate> toRemove = new ArrayList<>();
        for(int i=1; i<pointts2D.size(); i++) {
            if(pointts2D.get(i).x == pointts2D.get(i-1).x) {
                toRemove.add(pointts2D.get(i));
            }
        }
        for(Coordinate c : toRemove) {
            pointts2D.remove(c);
        }

        List<Coordinate> pts2DGround = new ArrayList<>();
        for(int i=0; i<pointts2D.size(); i++) {
            Coordinate c = new Coordinate(pointts2D.get(i));
            if(i==0) {
                c = new Coordinate(pointts2D.get(i).x, data.profileBuilder.getZGround(cutProfile.getSource()));
            }
            else if(i == pointts2D.size()-1) {
                c = new Coordinate(pointts2D.get(i).x, data.profileBuilder.getZGround(cutProfile.getReceiver()));
            }
            pts2DGround.add(c);

        }
        if(z_profile.isEmpty()){
            z_profile = pts2DGround;
        }
        return pts2DGround;
    }



    /**
     *
     * @param sourceOrientation
     * @param src
     * @param next
     * @return the computed orientation
     */
    private static Orientation computeOrientation(Orientation sourceOrientation, CutPoint src, CutPoint next){
        return computeOrientation(sourceOrientation, src.getCoordinate(), next.getCoordinate());
    }


    /**
     *
     * @param sourceOrientation
     * @param src
     * @param next
     * @return
     */
    private static Orientation computeOrientation(Orientation sourceOrientation, Coordinate src, Coordinate next){
        if(sourceOrientation == null) {
            return null;
        }
        Vector3D outgoingRay = new Vector3D(new Coordinate(next.x - src.x,
                next.y - src.y,
                next.z - src.z)).normalize();
        return Orientation.fromVector(Orientation.rotate(sourceOrientation, outgoingRay, true), 0);
    }

    /**
     * Compute the propagation in case of free field.
     * @param cutProfile CutProfile containing all the data for propagation computation.
     * @return The calculated propagation path.
     */
    public CnossosPath computeFreeField(CutProfile cutProfile, Scene data, boolean isSrSeg) {
        CutPoint srcCut = cutProfile.getSource();
        CutPoint rcvCut = cutProfile.getReceiver();
        List<CutPoint> cuts = cutProfile.getCutPoints().stream()
                .filter(cut -> cut.getType() != GROUND_EFFECT)
                .collect(Collectors.toList());
        List<Coordinate> pts2DGround = computePts2DGround(cutProfile, data);
        Coordinate src = new Coordinate(pts2DGround.get(0));
        if(!isNaN(srcCut.getCoordinate().z)) {
            src.y = srcCut.getCoordinate().z;
        }
        Coordinate rcv = new Coordinate(pts2DGround.get(pts2DGround.size()-1));
        if(!isNaN(rcvCut.getCoordinate().z)) {
            rcv.y = rcvCut.getCoordinate().z;
        }
        double[] meanPlane;

        double firstY = pts2DGround.get(0).y;
        if (pts2DGround.stream().allMatch(c -> c.y == firstY)) {
            meanPlane = new double[]{0, 0};
        } else {
            meanPlane = JTSUtility.getMeanPlaneCoefficients(pts2DGround.toArray(new Coordinate[0]));
        }
        SegmentPath srSeg;
        if(isSrSeg) {
            srSeg = computeSegment(new Coordinate(src.x, srcCut.getCoordinate().z), new Coordinate(rcv.x, rcvCut.getCoordinate().z), meanPlane, cutProfile.getGPath(srcCut, rcvCut), data.gS);
        }
        else {
            srSeg = computeSegment(src, rcv, meanPlane, cutProfile.getGPath(srcCut, rcvCut), data.gS);
        }
        LineSegment dSR = new LineSegment(src, rcv);

        List<SegmentPath> segments = new ArrayList<>();

        List<PointPath> points = new ArrayList<>();
        PointPath srcPP = new PointPath(src, data.profileBuilder.getZGround(srcCut), srcCut.getWallAlpha(), PointPath.POINT_TYPE.SRCE);
        srcPP.buildingId = srcCut.getBuildingId();
        srcPP.wallId = srcCut.getWallId();
        srcPP.orientation = computeOrientation(cutProfile.getSrcOrientation(), srcCut, rcvCut);
        points.add(srcPP);
        boolean favorable= false;
        CnossosPath pathParameters = new CnossosPath();
        //(favorable=false, points, segments, srSeg, Angle.angle(rcvCut.getCoordinate(), srcCut.getCoordinate()));
        pathParameters.setFavorable(favorable);
        pathParameters.setPointList(points);
        pathParameters.setSegmentList(segments);
        pathParameters.setSRSegment(srSeg);
        pathParameters.angle=Angle.angle(rcvCut.getCoordinate(), srcCut.getCoordinate());
        pathParameters.setCutPoints(cuts);
        pathParameters.init(data.freq_lvl.size());
        pathParameters.raySourceReceiverDirectivity = srcPP.orientation;
        if(data.isComputeDiffraction()) {
            //Check for Rayleigh criterion for segments computation
            // Compute mean ground plan
            computeDiff(pts2DGround, src, rcv, srcCut, rcvCut, srSeg, cutProfile, pathParameters, dSR, cuts, segments, points);
        }
        if(segments.isEmpty()) {
            segments.add(srSeg);
        }
        PointPath rcvPP = new PointPath(rcv, data.profileBuilder.getZGround(rcvCut), rcvCut.getWallAlpha(), PointPath.POINT_TYPE.RECV);
        rcvPP.buildingId = rcvCut.getBuildingId();
        rcvPP.wallId = rcvCut.getWallId();
        points.add(rcvPP);

        return pathParameters;
    }


    /*
     *
     * @param pts2DGround
     * @param src
     * @param rcv
     * @param srcCut
     * @param rcvCut
     * @param srSeg
     * @param cutProfile
     * @param pathParameters
     * @param dSR
     * @param cuts
     * @param segments
     * @param points

    private void computeDiff(List<Coordinate> pts2DGround, Coordinate src, Coordinate rcv,
                             CutPoint srcCut, CutPoint rcvCut,
                             SegmentPath srSeg, CutProfile cutProfile, CnossosPath pathParameters,
                             LineSegment dSR, List<CutPoint> cuts, List<SegmentPath> segments, List<PointPath> points) {
        for (int iO = 1; iO < pts2DGround.size() - 1; iO++) {
            Coordinate o = pts2DGround.get(iO);

            double dSO = src.distance(o);
            double dOR = o.distance(rcv);
            pathParameters.deltaH = dSR.orientationIndex(o) * (dSO + dOR - srSeg.d);
            List<Integer> freqs = data.freq_lvl;
            boolean rcrit = false;
            for(int f : freqs) {
                if(pathParameters.deltaH > -(340./f) / 20) {
                    rcrit = true;
                    break;
                }
            }
            if (rcrit) {
                rcrit = false;
                //Add point path

                //Plane S->O
                Coordinate[] soCoords = Arrays.copyOfRange(pts2DGround.toArray(new Coordinate[0]), 0, iO + 1);
                double[] abs = JTSUtility.getMeanPlaneCoefficients(soCoords);
                SegmentPath seg1 = computeSegment(src, o, abs);

                //Plane O->R
                Coordinate[] orCoords = Arrays.copyOfRange(pts2DGround.toArray(new Coordinate[0]), iO, pts2DGround.size());
                double[] abr = JTSUtility.getMeanPlaneCoefficients(orCoords);
                SegmentPath seg2 = computeSegment(o, rcv, abr);

                Coordinate srcPrime = new Coordinate(src.x + (seg1.sMeanPlane.x - src.x) * 2, src.y + (seg1.sMeanPlane.y - src.y) * 2);
                Coordinate rcvPrime = new Coordinate(rcv.x + (seg2.rMeanPlane.x - rcv.x) * 2, rcv.y + (seg2.rMeanPlane.y - rcv.y) * 2);

                LineSegment dSPrimeRPrime = new LineSegment(srcPrime, rcvPrime);
                srSeg.dPrime = srcPrime.distance(rcvPrime);
                seg1.dPrime = srcPrime.distance(o);
                seg2.dPrime = o.distance(rcvPrime);

                pathParameters.deltaPrimeH = dSPrimeRPrime.orientationIndex(o) * (seg1.dPrime + seg2.dPrime - srSeg.dPrime);
                for(int f : freqs) {
                    if(pathParameters.deltaH > (340./f) / 4 - pathParameters.deltaPrimeH) {
                        rcrit = true;
                        break;
                    }
                }
                if (rcrit) {
                    seg1.setGpath(cutProfile.getGPath(srcCut, cuts.get(iO)), srcCut.getGroundCoef());
                    seg2.setGpath(cutProfile.getGPath(cuts.get(iO), rcvCut), srcCut.getGroundCoef());

                    if(dSR.orientationIndex(o) == 1) {
                        pathParameters.deltaF = toCurve(dSO, srSeg.d) + toCurve(dOR, srSeg.d) - toCurve(srSeg.d, srSeg.d);
                    }
                    else {
                        Coordinate pA = dSR.pointAlong((o.x-src.x)/(rcv.x-src.x));
                        pathParameters.deltaF =2*toCurve(src.distance(pA), srSeg.d) + 2*toCurve(pA.distance(rcv), srSeg.d) - toCurve(dSO, srSeg.d) - toCurve(dOR, srSeg.d) - toCurve(srSeg.d, srSeg.d);
                    }

                    LineSegment sPrimeR = new LineSegment(seg1.sPrime, rcv);
                    double dSPrimeO = seg1.sPrime.distance(o);
                    double dSPrimeR = seg1.sPrime.distance(rcv);
                    pathParameters.deltaSPrimeRH = sPrimeR.orientationIndex(o)*(dSPrimeO + dOR - dSPrimeR);

                    LineSegment sRPrime = new LineSegment(src, seg2.rPrime);
                    double dORPrime = o.distance(seg2.rPrime);
                    double dSRPrime = src.distance(seg2.rPrime);
                    pathParameters.deltaSRPrimeH = sRPrime.orientationIndex(o)*(dSO + dORPrime - dSRPrime);

                    if(dSPrimeRPrime.orientationIndex(o) == 1) {
                        pathParameters.deltaPrimeF = toCurve(seg1.dPrime, srSeg.dPrime) + toCurve(seg2.dPrime, srSeg.dPrime) - toCurve(srSeg.dPrime, srSeg.dPrime);
                    }
                    else {
                        Coordinate pA = dSPrimeRPrime.pointAlong((o.x-srcPrime.x)/(rcvPrime.x-srcPrime.x));
                        pathParameters.deltaPrimeF =2*toCurve(srcPrime.distance(pA), srSeg.dPrime) + 2*toCurve(pA.distance(srcPrime), srSeg.dPrime) - toCurve(seg1.dPrime, srSeg.dPrime) - toCurve(seg2.dPrime, srSeg.d) - toCurve(srSeg.dPrime, srSeg.dPrime);
                    }

                    segments.add(seg1);
                    segments.add(seg2);

                    points.add(new PointPath(o, o.z, new ArrayList<>(), DIFH_RCRIT));
                    pathParameters.difHPoints.add(points.size() - 1);
                }
            }
        }
    }*/

    private void computeDiff(List<Coordinate> pts2DGround, Coordinate src, Coordinate rcv,
                             CutPoint srcCut, CutPoint rcvCut,
                             SegmentPath srSeg, CutProfile cutProfile, CnossosPath pathParameters,
                             LineSegment dSR, List<CutPoint> cuts, List<SegmentPath> segments, List<PointPath> points) {
        /*List<Coordinate> pts = new ArrayList<>();
        for(PointPath point : points){
            pts.add(point.coordinate);
        }*/
        for (int iO = 1; iO < pts2DGround.size() - 1; iO++) {
            Coordinate o = pts2DGround.get(iO);

            double dSO = src.distance(o);
            double dOR = o.distance(rcv);
            pathParameters.deltaH = dSR.orientationIndex(o) * (dSO + dOR - srSeg.d);
            List<Integer> freqs = data.freq_lvl;
            boolean rcrit = false;
            for(int f : freqs) {
                if(pathParameters.deltaH > -(340./f) / 20) {
                    rcrit = true;
                    break;
                }
            }
            if (rcrit) {
                rcrit = false;
                //Add point path

                //Plane S->O
                Coordinate[] soCoords = Arrays.copyOfRange(pts2DGround.toArray(new Coordinate[0]), 0, iO + 1);
                double[] abs = JTSUtility.getMeanPlaneCoefficients(soCoords);
                SegmentPath seg1 = computeSegment(src, o, abs);

                //Plane O->R
                Coordinate[] orCoords = Arrays.copyOfRange(pts2DGround.toArray(new Coordinate[0]), iO, pts2DGround.size());
                double[] abr = JTSUtility.getMeanPlaneCoefficients(orCoords);
                SegmentPath seg2 = computeSegment(o, rcv, abr);

                Coordinate srcPrime = new Coordinate(src.x + (seg1.sMeanPlane.x - src.x) * 2, src.y + (seg1.sMeanPlane.y - src.y) * 2);
                Coordinate rcvPrime = new Coordinate(rcv.x + (seg2.rMeanPlane.x - rcv.x) * 2, rcv.y + (seg2.rMeanPlane.y - rcv.y) * 2);

                LineSegment dSPrimeRPrime = new LineSegment(srcPrime, rcvPrime);
                srSeg.dPrime = srcPrime.distance(rcvPrime);
                seg1.dPrime = srcPrime.distance(o);
                seg2.dPrime = o.distance(rcvPrime);

                pathParameters.deltaPrimeH = dSPrimeRPrime.orientationIndex(o) * (seg1.dPrime + seg2.dPrime - srSeg.dPrime);
                for(int f : freqs) {
                    if(pathParameters.deltaH > (340./f) / 4 - pathParameters.deltaPrimeH) {
                        rcrit = true;
                        break;
                    }
                }
                if (rcrit) {
                    seg1.setGpath(cutProfile.getGPath(srcCut, cuts.get(iO)), srcCut.getGroundCoef());
                    seg2.setGpath(cutProfile.getGPath(cuts.get(iO), rcvCut), srcCut.getGroundCoef());

                    if(dSR.orientationIndex(o) == 1) {
                        pathParameters.deltaF = toCurve(dSO, srSeg.d) + toCurve(dOR, srSeg.d) - toCurve(srSeg.d, srSeg.d);
                    }
                    else {
                        Coordinate pA = dSR.pointAlong((o.x-src.x)/(rcv.x-src.x));
                        pathParameters.deltaF =2*toCurve(src.distance(pA), srSeg.d) + 2*toCurve(pA.distance(rcv), srSeg.d) - toCurve(dSO, srSeg.d) - toCurve(dOR, srSeg.d) - toCurve(srSeg.d, srSeg.d);
                    }

                    LineSegment sPrimeR = new LineSegment(seg1.sPrime, rcv);
                    double dSPrimeO = seg1.sPrime.distance(o);
                    double dSPrimeR = seg1.sPrime.distance(rcv);
                    pathParameters.deltaSPrimeRH = sPrimeR.orientationIndex(o)*(dSPrimeO + dOR - dSPrimeR);

                    LineSegment sRPrime = new LineSegment(src, seg2.rPrime);
                    double dORPrime = o.distance(seg2.rPrime);
                    double dSRPrime = src.distance(seg2.rPrime);
                    pathParameters.deltaSRPrimeH = sRPrime.orientationIndex(o)*(dSO + dORPrime - dSRPrime);

                    if(dSPrimeRPrime.orientationIndex(o) == 1) {
                        pathParameters.deltaPrimeF = toCurve(seg1.dPrime, srSeg.dPrime) + toCurve(seg2.dPrime, srSeg.dPrime) - toCurve(srSeg.dPrime, srSeg.dPrime);
                    }
                    else {
                        Coordinate pA = dSPrimeRPrime.pointAlong((o.x-srcPrime.x)/(rcvPrime.x-srcPrime.x));
                        pathParameters.deltaPrimeF =2*toCurve(srcPrime.distance(pA), srSeg.dPrime) + 2*toCurve(pA.distance(srcPrime), srSeg.dPrime) - toCurve(seg1.dPrime, srSeg.dPrime) - toCurve(seg2.dPrime, srSeg.d) - toCurve(srSeg.dPrime, srSeg.dPrime);
                    }

                    segments.add(seg1);
                    segments.add(seg2);

                    points.add(new PointPath(o, o.z, new ArrayList<>(), DIFH_RCRIT));
                    pathParameters.difHPoints.add(points.size() - 1);
                }
            }
        }
    }

    /**
     * Compute horizontal diffraction (diffraction of vertical edge.)
     * @param rcvCoord Receiver coordinates.
     * @param srcCoord Source coordinates.
     * @param data     Propagation data.
     * @param side     Side to compute.
     * @return The propagation path of the horizontal diffraction.
     */
    public CnossosPath computeVEdgeDiffraction(Coordinate rcvCoord, Coordinate srcCoord,
                                               Scene data, ComputationSide side, Orientation orientation) {

        CnossosPath pathParameters = null;
        List<Coordinate> coordinates = computeSideHull(side != LEFT, new Coordinate(rcvCoord), new Coordinate(srcCoord), data.profileBuilder);
        List<Coordinate> coords = toDirectLine(coordinates);

        if (!coordinates.isEmpty()) {
            if (coordinates.size() > 2) {
                List<Coordinate> topoPts = new ArrayList<>();
                topoPts.add(coordinates.get(0));
                double g = 0;
                double d = 0;
                List<CutPoint> allCutPoints = new ArrayList<>();
                for(int i=0; i<coordinates.size()-1; i++) {
                    CutProfile profile = data.profileBuilder.getProfile(coordinates.get(i), coordinates.get(i+1), data.gS);
                    profile.setSrcOrientation(orientation);
                    if (!profile.isFreeField()){
                        pathParameters = null;
                        return null;
                    }
                    double dist = coordinates.get(i).distance(coordinates.get(i+1));
                    g+=profile.getGPath()*dist;
                    d+=dist;
                    topoPts.addAll(profile.getCutPoints().stream()
                            .filter(cut -> cut.getType().equals(BUILDING) || cut.getType().equals(TOPOGRAPHY) || cut.getType().equals(RECEIVER))
                            .map(CutPoint::getCoordinate)
                            .collect(Collectors.toList()));
                    allCutPoints.addAll(profile.getCutPoints());
                }
                g/=d;
                //Filter bridge
                List<Integer> toRemove = new ArrayList<>();
                for(int i=0; i<coordinates.size()-1; i++) {
                    Coordinate c0 = coordinates.get(i);
                    Coordinate c1 = coordinates.get(i+1);
                    boolean between = false;
                    for(int j=0; j<topoPts.size(); j++) {
                        Coordinate p = topoPts.get(j);
                        if(p.equals(c1)) {
                            break;
                        }
                        if(p.equals(c0)) {
                            between = true;
                        }
                        if(between && p.z > c1.z) {
                            toRemove.add(j);
                        }
                    }
                }
                Collections.sort(toRemove);
                Collections.reverse(toRemove);
                for(int i : toRemove) {
                    topoPts.remove(i);
                }
                //Set z value
                for (final Coordinate pt : topoPts) {
                    coordinates.forEach(c -> {
                        if (c.equals(pt) && c.z == pt.z) {
                            pt.z = data.profileBuilder.getZGround(pt);
                        }
                    });
                }
                //Filter same pts
                toRemove = new ArrayList<>();
                for(int i=0; i<topoPts.size()-1; i++) {
                    Coordinate pi0 = topoPts.get(i);
                    Coordinate pi1 = topoPts.get(i+1);
                    if(pi0.equals(pi1) && pi0.z == pi1.z) {
                        toRemove.add(i);
                    }
                }
                Collections.sort(toRemove);
                Collections.reverse(toRemove);
                for(int i : toRemove) {
                    topoPts.remove(i);
                }

                List<Coordinate> groundPts = toDirectLine(topoPts);
                PointPath src = new PointPath(coords.get(0), data.profileBuilder.getZ(coordinates.get(0)), new ArrayList<>(), SRCE);
                src.orientation = computeOrientation(orientation, coords.get(0), coords.get(1));
                PointPath rcv = new PointPath(coords.get(coords.size()-1), data.profileBuilder.getZ(coordinates.get(coordinates.size()-1)), new ArrayList<>(), RECV);
                double[] meanPlan = JTSUtility.getMeanPlaneCoefficients(groundPts.toArray(new Coordinate[0]));
                SegmentPath srSeg = computeSegment(src.coordinate, rcv.coordinate, meanPlan, g, data.gS);
                srSeg.dc = sqrt(pow(rcvCoord.x-srcCoord.x, 2) + pow(rcvCoord.y-srcCoord.y, 2) + pow(rcvCoord.z-srcCoord.z, 2));

                List<PointPath> pps = new ArrayList<>();
                pps.add(src);
                PointPath previous = src;
                List<SegmentPath> segs = new ArrayList<>();
                pathParameters = new CnossosPath();
                pathParameters.setFavorable(false);
                pathParameters.setPointList(pps);
                pathParameters.setSegmentList(segs);
                pathParameters.setSRSegment(srSeg);
                pathParameters.init(data.freq_lvl.size());
                pathParameters.angle=Angle.angle(rcvCoord, srcCoord);
                pathParameters.setCutPoints(allCutPoints);
                pathParameters.raySourceReceiverDirectivity = src.orientation;
                double e = 0;
                for(int i=1; i<coordinates.size()-1; i++) {
                    PointPath diff = new PointPath(coords.get(i), data.profileBuilder.getZ(coordinates.get(i)), new ArrayList<>(), DIFV);
                    pps.add(diff);
                    pathParameters.difVPoints.add(i);
                    SegmentPath seg = computeSegment(previous.coordinate, diff.coordinate, meanPlan, g, data.gS);
                    segs.add(seg);
                    if(i>1) {
                        e += seg.d;
                    }
                    previous = diff;
                }
                segs.add(computeSegment(previous.coordinate, coords.get(coords.size()-1), meanPlan, g, data.gS));
                pps.add(rcv);
                pathParameters.deltaH = segs.get(0).d + e + segs.get(segs.size()-1).d - srSeg.dc;
                pathParameters.e = e;
                pathParameters.difVPoints.add(1);
            }
        }
        return pathParameters;
    }


    /**
     * convertit une série de points 3D en une série de points 2D
     * @param coordinates
     * @return
     */
    private List<Coordinate> toDirectLine(List<Coordinate> coordinates) {
        List<Coordinate> coords = new ArrayList<>();
        if(coordinates.isEmpty()) {
            return coords;
        }
        Coordinate prev = coordinates.get(0);
        double d = 0;
        for(Coordinate c : coordinates) {
            d+=prev.distance(c);
            prev = c;
            coords.add(new Coordinate(d, c.z));
        }
        return coords;
    }


    /**
     *
     * @param cutProfile
     * @param bodyBarrier
     * @return
     */
    public CnossosPath computeHEdgeDiffraction(CutProfile cutProfile , boolean bodyBarrier) {
        List<SegmentPath> segments = new ArrayList<>();
        List<PointPath> points = new ArrayList<>();
        List<CutPoint> cutPts = cutProfile.getCutPoints().stream()
                .filter(cutPoint -> cutPoint.getType() != GROUND_EFFECT)
                .collect(Collectors.toList());

        List<Coordinate> pts2D = computePts2D(cutPts);
        if(pts2D.size() != cutPts.size()) {
            throw new IllegalArgumentException("The two arrays size should be the same");
        }
        //Remove aligned cut points thanks to jts DouglasPeuckerSimplifier algo
        List<CutPoint> newCutPts = new ArrayList<>(cutPts.size());
       Geometry lineString = new GeometryFactory().createLineString(pts2D.toArray(new Coordinate[0]));
        List<Coordinate> newPts2D = List.of(DouglasPeuckerSimplifier.simplify(lineString, 0.5*cutProfile.getDistanceToSR()).getCoordinates());

        for (int i = 0; i < newPts2D.size(); i++) {
            newCutPts.add(cutPts.get(pts2D.indexOf(newPts2D.get(i))));
        }


        pts2D = newPts2D;
        cutPts = newCutPts;
        if(pts2D.size() != cutPts.size()) {
            throw new IllegalArgumentException("The two arrays size should be the same");
        }
        
        double[] meanPlane = JTSUtility.getMeanPlaneCoefficients(pts2D.toArray(new Coordinate[0]));
        Coordinate firstPts2D = pts2D.get(0);
        Coordinate lastPts2D = pts2D.get(pts2D.size()-1);
        SegmentPath srPath = computeSegment(firstPts2D, lastPts2D, meanPlane, cutProfile.getGPath(), cutProfile.getSource().getGroundCoef());

        //CnossosPathParameters propagationPathParameters = new CnossosPathParameters(true, points, segments, srPath,
                //Angle.angle(cutProfile.getReceiver().getCoordinate(), cutProfile.getSource().getCoordinate()));
        CnossosPath pathParameters = new CnossosPath();
        //(favorable=false, points, segments, srSeg, Angle.angle(rcvCut.getCoordinate(), srcCut.getCoordinate()));
        pathParameters.setFavorable(true);
        pathParameters.setPointList(points);
        pathParameters.setSegmentList(segments);
        pathParameters.setSRSegment(srPath);
        pathParameters.init(data.freq_lvl.size());
        pathParameters.angle=Angle.angle(cutProfile.getReceiver().getCoordinate(), cutProfile.getSource().getCoordinate());
        pathParameters.setCutPoints(cutPts);
        LineSegment srcRcvLine = new LineSegment(firstPts2D, lastPts2D);
        List<Coordinate> pts = new ArrayList<>();

        // Extract the first and last points to define the line segment
        Coordinate firstPt = pts2D.get(0);
        Coordinate lastPt = pts2D.get(pts2D.size() - 1);

        // Compute the slope and y-intercept of the line segment
        double slope = (lastPt.y - firstPt.y) / (lastPt.x - firstPt.x);
        double yIntercept = firstPt.y - slope * firstPt.x;

         // Filter out points that are below the line segment
        List<Coordinate> filteredCoordinates = new ArrayList<>();
        for (Coordinate coord : pts2D) {
            double lineY = slope * coord.x + yIntercept;
            if (coord.y >= lineY-0.000001) { // espilon to avoir float issues
                filteredCoordinates.add(coord);
            }
        }

       // Compute the convex hull using JTS
        GeometryFactory geomFactory = new GeometryFactory();
        Coordinate[] coordsArray = filteredCoordinates.toArray(new Coordinate[0]);
        ConvexHull convexHull = new ConvexHull(coordsArray, geomFactory);
        Coordinate[] convexHullCoords = convexHull.getConvexHull().getCoordinates();
        int indexFirst = Arrays.asList(convexHull.getConvexHull().getCoordinates()).indexOf(firstPt);
        int indexLast = Arrays.asList(convexHull.getConvexHull().getCoordinates()).lastIndexOf(lastPt);
        convexHullCoords = Arrays.copyOfRange(convexHullCoords, indexFirst, indexLast+1);

        CoordinateSequence coordSequence = geomFactory.getCoordinateSequenceFactory().create(convexHullCoords);
        Geometry geom = geomFactory.createLineString(coordSequence);
        Geometry uniqueGeom = geom.union(); // Removes duplicate coordinates
        convexHullCoords = uniqueGeom.getCoordinates();

        // Convert the result back to your format (List<Point2D> pts)
        List<Coordinate> convexHullPoints = new ArrayList<>();
        if (convexHullCoords.length ==3){
            convexHullPoints = Arrays.asList(convexHullCoords);
       }else {
            for (int j = 0; j < convexHullCoords.length; j++) {
                // Check if the y-coordinate is valid (not equal to Double.MAX_VALUE and not infinite)
                if (convexHullCoords[j].y == Double.MAX_VALUE || Double.isInfinite(convexHullCoords[j].y)) {
                    continue; // Skip this point as it's not part of the hull
                }
                convexHullPoints.add(convexHullCoords[j]);
            }
        }
        pts = convexHullPoints;

        double e = 0;
        Coordinate src = null;
        for (int i = 1; i < pts.size(); i++) {
            int i0 = pts2D.indexOf(pts.get(i-1));
            int i1 = pts2D.indexOf(pts.get(i));
            CutPoint cutPt0 = cutPts.get(i0);
            CutPoint cutPt1 = cutPts.get(i1);
            CutProfile profile = data.profileBuilder.getProfile(cutPt0, cutPt1, data.gS);
            List<Coordinate> subList = pts2D.subList(i0, i1+1).stream().map(Coordinate::new).collect(Collectors.toList());
            for(int j=0; j<=i1-i0; j++){
                if(!cutPts.get(j+i0).getType().equals(BUILDING) && !cutPts.get(j+i0).getType().equals(TOPOGRAPHY)){
                    subList.get(j).y = data.profileBuilder.getZGround(cutPts.get(j+i0));
                }
            }
            //subList = toDirectLine(subList);
            meanPlane = JTSUtility.getMeanPlaneCoefficients(subList.toArray(new Coordinate[0]));
            SegmentPath path = computeSegment(pts2D.get(i0), pts2D.get(i1), meanPlane, profile.getGPath(), profile.getSource().getGroundCoef());
            segments.add(path);
            if(points.isEmpty()) {
                //todo check this getBuildingId when DIFH is on floor or line wall
                points.add(new PointPath(path.s,  data.profileBuilder.getZGround(cutPt0), cutPt0.getWallAlpha(), cutPt1.getBuildingId(),PointPath.POINT_TYPE.SRCE));
                points.get(0).orientation = computeOrientation(cutProfile.getSrcOrientation(), cutPts.get(0), cutPts.get(1));
                pathParameters.raySourceReceiverDirectivity = points.get(0).orientation;
                src = path.s;
            }
            //todo check this getBuildingId when DIFH is on floor or line wall
            points.add(new PointPath(path.r,  data.profileBuilder.getZGround(cutPt1), cutPt1.getWallAlpha(), cutPt1.getBuildingId(),PointPath.POINT_TYPE.RECV));
            if(i != pts.size()-1) {
                if(i != 1) {
                    e += path.d;
                }
                pathParameters.difHPoints.add(i);
                PointPath pt = points.get(points.size()-1);
                pt.type = PointPath.POINT_TYPE.DIFH;
                pt.bodyBarrier = bodyBarrier;
                if(pt.buildingId != -1) {
                    pt.alphaWall = data.profileBuilder.getBuilding(pt.buildingId).getAlphas();
                    pt.setBuildingHeight(data.profileBuilder.getBuilding(pt.buildingId).getHeight());
                }
                else if(pt.wallId != -1) {
                    pt.alphaWall = data.profileBuilder.getWall(pt.wallId).getAlphas();
                    pt.setBuildingHeight(data.profileBuilder.getWall(pt.wallId).getHeight());
                }
            }
        }
        pathParameters.e = e;

        if(points.isEmpty()) {
            return null;
        }

        Coordinate rcv = points.get(points.size()-1).coordinate;
        PointPath p0 = points.stream().filter(p -> p.type.equals(DIFH)).findFirst().orElse(null);
        if(p0==null){
            return null;
        }
        Coordinate c0 = p0.coordinate;
        PointPath pn = points.stream().filter(p -> p.type.equals(DIFH)).reduce((first, second) -> second).orElse(null);
        if(pn==null){
            return null;
        }
        Coordinate cn = pn.coordinate;

        SegmentPath seg1 = segments.get(0);
        SegmentPath seg2 = segments.get(segments.size()-1);

        double dSO0 = src.distance(c0);
        double dOnR = cn.distance(rcv);
        LineSegment sr = new LineSegment(src, rcv);

        LineSegment sPrimeR = new LineSegment(seg1.sPrime, rcv);
        double dSPrimeR = seg1.sPrime.distance(rcv);
        double dSPrimeO = seg1.sPrime.distance(c0);
        pathParameters.deltaSPrimeRH = sPrimeR.orientationIndex(c0)*(dSPrimeO + e + dOnR - dSPrimeR);
        pathParameters.deltaSPrimeRF = toCurve(dSPrimeO, dSPrimeR) + toCurve(e, dSPrimeR) + toCurve(dOnR, dSPrimeR) - toCurve(dSPrimeR, dSPrimeR);

        LineSegment sRPrime = new LineSegment(src, seg2.rPrime);
        double dSRPrime = src.distance(seg2.rPrime);
        double dORPrime = cn.distance(seg2.rPrime);
        pathParameters.deltaSRPrimeH = (src.x>seg2.rPrime.x?-1:1)*sRPrime.orientationIndex(cn)*(dSO0 + e + dORPrime - dSRPrime);
        pathParameters.deltaSRPrimeF = toCurve(dSO0, dSRPrime) + toCurve(e, dSRPrime) + toCurve(dORPrime, dSRPrime) - toCurve(dSRPrime, dSRPrime);

        Coordinate srcPrime = new Coordinate(src.x + (seg1.sMeanPlane.x - src.x) * 2, src.y + (seg1.sMeanPlane.y - src.y) * 2);
        Coordinate rcvPrime = new Coordinate(rcv.x + (seg2.rMeanPlane.x - rcv.x) * 2, rcv.y + (seg2.rMeanPlane.y - rcv.y) * 2);

        LineSegment dSPrimeRPrime = new LineSegment(srcPrime, rcvPrime);
        srPath.dPrime = srcPrime.distance(rcvPrime);
        seg1.dPrime = srcPrime.distance(c0);
        seg2.dPrime = cn.distance(rcvPrime);


        pathParameters.deltaH = sr.orientationIndex(c0) * (dSO0 + e + dOnR - srPath.d);
        if(sr.orientationIndex(c0) == 1) {
            pathParameters.deltaF = toCurve(seg1.d, srPath.d) + toCurve(e, srPath.d)  + toCurve(seg2.d, srPath.d) - toCurve(srPath.d, srPath.d);
        }
        else {
            Coordinate pA = sr.pointAlong((c0.x-srcPrime.x)/(rcvPrime.x-srcPrime.x));
            pathParameters.deltaF =2*toCurve(srcPrime.distance(pA), srPath.dPrime) + 2*toCurve(pA.distance(rcvPrime), srPath.dPrime) - toCurve(seg1.dPrime, srPath.dPrime) - toCurve(seg2.dPrime, srPath.dPrime) - toCurve(srPath.dPrime, srPath.dPrime);
        }

        pathParameters.deltaPrimeH = dSPrimeRPrime.orientationIndex(c0) * (seg1.dPrime + e + seg2.dPrime - srPath.dPrime);

        pathParameters.deltaPrimeH = dSPrimeRPrime.orientationIndex(c0) * (seg1.dPrime + seg2.dPrime - srPath.dPrime);
        if(dSPrimeRPrime.orientationIndex(c0) == 1) {
            pathParameters.deltaPrimeF = toCurve(seg1.dPrime, srPath.dPrime) + toCurve(seg2.dPrime, srPath.dPrime) - toCurve(srPath.dPrime, srPath.dPrime);
        }
        else {
            Coordinate pA = dSPrimeRPrime.pointAlong((c0.x-srcPrime.x)/(rcvPrime.x-srcPrime.x));
            pathParameters.deltaPrimeF =2*toCurve(srcPrime.distance(pA), srPath.dPrime) + 2*toCurve(pA.distance(srcPrime), srPath.dPrime) - toCurve(seg1.dPrime, srPath.dPrime) - toCurve(seg2.dPrime, srPath.d) - toCurve(srPath.dPrime, srPath.dPrime);
        }

        return pathParameters;
    }

    /**
     * Compute Side Hull
     * Create a line between p1 and p2. Find the first intersection of this line with a building then create a ConvexHull
     * with the points of buildings in intersection. While there is an intersection add more points to the convex hull.
     * The side diffraction path is found when there is no more intersection.
     *
     * @param left If true return path between p1 and p2; else p2 to p1
     * @param p1   First point
     * @param p2   Second point
     * @return
     */
    public List<Coordinate> computeSideHull(boolean left, Coordinate p1, Coordinate p2, ProfileBuilder profileBuilder) {
        if (p1.equals(p2)) {
            return new ArrayList<>();
        }

        // Intersection test cache
        Set<LineSegment> freeFieldSegments = new HashSet<>();

        List<Coordinate> input = new ArrayList<>();

        Coordinate[] coordinates = new Coordinate[0];
        int indexp1 = 0;
        int indexp2 = 0;

        boolean convexHullIntersects = true;

        input.add(p1);
        input.add(p2);

        Set<Integer> buildingInHull = new HashSet<>();
        Set<Integer> wallInHull = new HashSet<>();

        Plane cutPlane = computeZeroRadPlane(p1, p2);

        BuildingIntersectionPathVisitor buildingIntersectionPathVisitor = new BuildingIntersectionPathVisitor(
                profileBuilder.getBuildings(), p1, p2, profileBuilder, input, buildingInHull, cutPlane);

        data.profileBuilder.getBuildingsOnPath(p1, p2, buildingIntersectionPathVisitor);

        WallIntersectionPathVisitor wallIntersectionPathVisitor = new WallIntersectionPathVisitor(
                profileBuilder.getWalls(), p1, p2, profileBuilder, input, wallInHull, cutPlane);

        data.profileBuilder.getWallsOnPath(p1, p2, wallIntersectionPathVisitor);

        int k;
        while (convexHullIntersects) {
            ConvexHull convexHull = new ConvexHull(input.toArray(new Coordinate[0]), GEOMETRY_FACTORY);
            Geometry convexhull = convexHull.getConvexHull();

            if (convexhull.getLength() / p1.distance(p2) > MAX_RATIO_HULL_DIRECT_PATH) {
                return new ArrayList<>();
            }

            convexHullIntersects = false;
            coordinates = convexhull.getCoordinates();

            input.clear();
            input.addAll(Arrays.asList(coordinates));

            indexp1 = -1;
            for (int i = 0; i < coordinates.length - 1; i++) {
                if (coordinates[i].equals(p1)) {
                    indexp1 = i;
                    break;
                }
            }
            if (indexp1 == -1) {
                // P1 does not belong to convex vertices, cannot compute diffraction
                // TODO handle concave path
                return new ArrayList<>();
            }
            // Transform array to set p1 at index=0
            Coordinate[] coordinatesShifted = new Coordinate[coordinates.length];
            // Copy from P1 to end in beginning of new array
            int len = (coordinates.length - 1) - indexp1;
            System.arraycopy(coordinates, indexp1, coordinatesShifted, 0, len);
            // Copy from 0 to P1 in the end of array
            System.arraycopy(coordinates, 0, coordinatesShifted, len, coordinates.length - len - 1);
            coordinatesShifted[coordinatesShifted.length - 1] = coordinatesShifted[0];
            coordinates = coordinatesShifted;
            indexp1 = 0;
            indexp2 = -1;
            for (int i = 1; i < coordinates.length - 1; i++) {
                if (coordinates[i].equals(p2)) {
                    indexp2 = i;
                    break;
                }
            }
            if (indexp2 == -1) {
                // P2 does not belong to convex vertices, cannot compute diffraction
                // TODO handle concave path
                return new ArrayList<>();
            }
            for (k = 0; k < coordinates.length - 1; k++) {
                LineSegment freeFieldTestSegment = new LineSegment(coordinates[k], coordinates[k + 1]);
                // Ignore intersection if iterating over other side (not parts of what is returned)
                if (left && k < indexp2 || !left && k >= indexp2) {
                    if (!freeFieldSegments.contains(freeFieldTestSegment)) {
                        // Check if we still are in the propagation domain
                        buildingIntersectionPathVisitor = new BuildingIntersectionPathVisitor(profileBuilder.getBuildings(),
                                coordinates[k], coordinates[k + 1], profileBuilder, input, buildingInHull, cutPlane);
                        profileBuilder.getBuildingsOnPath(coordinates[k], coordinates[k + 1], buildingIntersectionPathVisitor);
                        wallIntersectionPathVisitor = new WallIntersectionPathVisitor(profileBuilder.getWalls(),
                                coordinates[k], coordinates[k + 1], profileBuilder, input, wallInHull, cutPlane);
                        profileBuilder.getWallsOnPath(coordinates[k], coordinates[k + 1], wallIntersectionPathVisitor);
                        if (!buildingIntersectionPathVisitor.doContinue() || !wallIntersectionPathVisitor.doContinue()) {
                            convexHullIntersects = true;
                        }
                        if (!convexHullIntersects) {
                            freeFieldSegments.add(freeFieldTestSegment);
                        } else {
                            break;
                        }
                    }
                }
            }
        }
        // Check for invalid coordinates
        for (Coordinate p : coordinates) {
            if (p.z < 0) {
                return new ArrayList<>();
            }
        }

        if (left) {
            return Arrays.asList(Arrays.copyOfRange(coordinates, indexp1, indexp2 + 1));
        } else {
            List<Coordinate> inversePath = Arrays.asList(Arrays.copyOfRange(coordinates, indexp2, coordinates.length));
            Collections.reverse(inversePath);
            return inversePath;
        }
    }

    /**
     *
     * @param p0
     * @param p1
     * @return
     */
    public static Plane computeZeroRadPlane(Coordinate p0, Coordinate p1) {
        org.apache.commons.math3.geometry.euclidean.threed.Vector3D s = new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(p0.x, p0.y, p0.z);
        org.apache.commons.math3.geometry.euclidean.threed.Vector3D r = new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(p1.x, p1.y, p1.z);
        double angle = Math.atan2(p1.y - p0.y, p1.x - p0.x);
        // Compute rPrime, the third point of the plane that is at -PI/2 with SR vector
        org.apache.commons.math3.geometry.euclidean.threed.Vector3D rPrime = s.add(new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(Math.cos(angle - Math.PI / 2), Math.sin(angle - Math.PI / 2), 0));
        Plane p = new Plane(r, s, rPrime, 1e-6);
        // Normal of the cut plane should be upward
        if (p.getNormal().getZ() < 0) {
            p.revertSelf();
        }
        return p;
    }


    /**
     *
     * @param plane
     * @param roofPts
     * @return
     */
    public static List<Coordinate> cutRoofPointsWithPlane(Plane plane, List<Coordinate> roofPts) {
        List<Coordinate> polyCut = new ArrayList<>(roofPts.size());
        double lastOffset = 0;
        Coordinate cPrev = null;
        for (Coordinate cCur : roofPts) {
            double offset = plane.getOffset(coordinateToVector(cCur));
            if (cPrev != null && ((offset >= 0 && lastOffset < 0) || (offset < 0 && lastOffset >= 0))) {
                // Interpolate vector
                org.apache.commons.math3.geometry.euclidean.threed.Vector3D i = plane.intersection(new Line(coordinateToVector(cPrev), coordinateToVector(cCur), epsilon));
                polyCut.add(new Coordinate(i.getX(), i.getY(), i.getZ()));
            }
            if (offset >= 0) {
                org.apache.commons.math3.geometry.euclidean.threed.Vector3D i = plane.intersection(new Line(new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(cCur.x, cCur.y, Double.MIN_VALUE), coordinateToVector(cCur), epsilon));
                if (i != null) polyCut.add(new Coordinate(i.getX(), i.getY(), i.getZ()));
            }
            lastOffset = offset;
            cPrev = cCur;
        }
        return polyCut;
    }

    /**
     *
     * @param p coordinate
     * @return the three dimensions vector of p
     */
    public static org.apache.commons.math3.geometry.euclidean.threed.Vector3D coordinateToVector(Coordinate p) {
        return new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(p.x, p.y, p.z);
    }


    /**
     *
     * @param rcvCoord
     * @param srcCoord
     * @param favorable
     * @param orientation
     * @param receiverMirrorIndex
     * @return propagation path list
     */
    public List<CnossosPath> computeReflexion(Coordinate rcvCoord, Coordinate srcCoord, boolean favorable,
                                              Orientation orientation, MirrorReceiversCompute receiverMirrorIndex) {

        // Compute receiver mirror
        LineIntersector linters = new RobustLineIntersector();
        //Keep only building walls which are not too far.
        List<MirrorReceiver> mirrorResults = receiverMirrorIndex.findCloseMirrorReceivers(srcCoord);

        List<CnossosPath> reflexionPathParameters = new ArrayList<>();

        for (MirrorReceiver receiverReflection : mirrorResults) {
            Wall seg = receiverReflection.getWall();
            List<MirrorReceiver> rayPath = new ArrayList<>();
            boolean validReflection = false;
            MirrorReceiver receiverReflectionCursor = receiverReflection;
            // Test whether intersection point is on the wall
            // segment or not
            Coordinate destinationPt = new Coordinate(srcCoord);

            linters.computeIntersection(seg.p0, seg.p1,
                    receiverReflection.getReceiverPos(),
                    destinationPt);
            while (linters.hasIntersection() /*&& MirrorReceiverIterator.wallPointTest(seg.getLine(), destinationPt)*/) {
                // There are a probable reflection point on the segment
                Coordinate reflectionPt = new Coordinate(
                        linters.getIntersection(0));
                if (reflectionPt.equals(destinationPt)) {
                    break;
                }
                Coordinate vec_epsilon = new Coordinate(
                        reflectionPt.x - destinationPt.x,
                        reflectionPt.y - destinationPt.y);
                double length = vec_epsilon
                        .distance(new Coordinate(0., 0., 0.));
                // Normalize vector
                vec_epsilon.x /= length;
                vec_epsilon.y /= length;
                // Multiply by epsilon in meter
                vec_epsilon.x *= wideAngleTranslationEpsilon;
                vec_epsilon.y *= wideAngleTranslationEpsilon;
                // Translate reflection pt by epsilon to get outside
                // the wall
                reflectionPt.x -= vec_epsilon.x;
                reflectionPt.y -= vec_epsilon.y;
                // Compute Z interpolation
                reflectionPt.setOrdinate(Coordinate.Z, Vertex.interpolateZ(linters.getIntersection(0),
                        receiverReflectionCursor.getReceiverPos(), destinationPt));

                // Test if there is no obstacles between the
                // reflection point and old reflection pt (or source position)
                validReflection = isNaN(receiverReflectionCursor.getReceiverPos().z) ||
                        isNaN(reflectionPt.z) || isNaN(destinationPt.z) /*|| seg.getOriginId() == 0*/
                        || (
                            (seg.getType().equals(BUILDING) && reflectionPt.z < data.profileBuilder.getBuilding(seg.getOriginId()).getGeometry().getCoordinate().z ||
                            seg.getType().equals(WALL) && reflectionPt.z < data.profileBuilder.getWall(seg.getOriginId()).getLine().getCoordinate().z)
                        && reflectionPt.z > data.profileBuilder.getZGround(reflectionPt)
                        && destinationPt.z > data.profileBuilder.getZGround(destinationPt));
                if (validReflection) // Source point can see receiver image
                {
                    MirrorReceiver reflResult = new MirrorReceiver(receiverReflectionCursor);
                    reflResult.setReceiverPos(reflectionPt);
                    rayPath.add(reflResult);
                    if (receiverReflectionCursor
                            .getParentMirror() == null) { // Direct to the receiver
                        break; // That was the last reflection
                    } else {
                        // There is another reflection
                        destinationPt.setCoordinate(reflectionPt);
                        // Move reflection information cursor to a
                        // reflection closer
                        receiverReflectionCursor = receiverReflectionCursor.getParentMirror();
                        // Update intersection data
                        seg = receiverReflectionCursor.getWall();
                        linters.computeIntersection(seg.p0, seg.p1,
                                receiverReflectionCursor
                                        .getReceiverPos(),
                                destinationPt
                        );
                        validReflection = false;
                    }
                } else {
                    break;
                }
            }
            if (validReflection) {
                // Check intermediate reflections
                for (int idPt = 0; idPt < rayPath.size() - 1; idPt++) {
                    Coordinate firstPt = rayPath.get(idPt).getReceiverPos();
                    MirrorReceiver refl = rayPath.get(idPt + 1);
                    CutProfile profile = data.profileBuilder.getProfile(firstPt, refl.getReceiverPos(), data.gS);
                    if (profile.intersectTopography() || profile.intersectBuilding() ) {
                        validReflection = false;
                        break;
                    }
                }
                if (!validReflection) {
                    continue;
                }
                // A valid propagation path as been found
                List<PointPath> points = new ArrayList<>();
                List<SegmentPath> segments = new ArrayList<>();
                SegmentPath srPath = null;
                List<Integer> reflIdx = new ArrayList<>();
                //CnossosPathParameters proPathParameters = new CnossosPathParameters(favorable, points, segments, srPath, Angle.angle(rcvCoord, srcCoord));
                CnossosPath pathParameters = new CnossosPath();
                //(favorable=false, points, segments, srSeg, Angle.angle(rcvCut.getCoordinate(), srcCut.getCoordinate()));
                pathParameters.setFavorable(favorable);
                pathParameters.setPointList(points);
                pathParameters.setSegmentList(segments);
                pathParameters.setSRSegment(srPath); //null
               // pathParameters.init(8);
                pathParameters.angle=Angle.angle(rcvCoord, srcCoord);
                pathParameters.refPoints = reflIdx;
                // Compute direct path between source and first reflection point, add profile to the data
                computeReflexionOverBuildings(srcCoord, rayPath.get(0).getReceiverPos(), points, segments, data, orientation, pathParameters.difHPoints, pathParameters.difVPoints);
                if (points.isEmpty()) {
                    continue;
                }
                PointPath reflPoint = points.get(points.size() - 1);
                reflIdx.add(points.size() - 1);
                reflPoint.setType(PointPath.POINT_TYPE.REFL);
                if(rayPath.get(0).getType().equals(BUILDING)) {
                    reflPoint.setBuildingId(rayPath.get(0).getBuildingId());
                    reflPoint.buildingHeight = data.profileBuilder.getBuilding(reflPoint.getBuildingId()).getHeight();
                    reflPoint.setAlphaWall(data.profileBuilder.getBuilding(reflPoint.getBuildingId()).getAlphas());
                } else {
                    reflPoint.buildingHeight = rayPath.get(0).getWall().p0.getZ();
                    reflPoint.setWallId(rayPath.get(0).getWall().getProcessedWallIndex());
                    reflPoint.setAlphaWall(rayPath.get(0).getWall().getAlphas());
                }
                reflPoint.altitude = data.profileBuilder.getZGround(reflPoint.coordinate);
                // Add intermediate reflections
                for (int idPt = 0; idPt < rayPath.size() - 1; idPt++) {
                    Coordinate firstPt = rayPath.get(idPt).getReceiverPos();
                    MirrorReceiver refl = rayPath.get(idPt + 1);
                    reflPoint = new PointPath(refl.getReceiverPos(), 0, new ArrayList<>(), PointPath.POINT_TYPE.REFL);

                    if(rayPath.get(0).getType().equals(BUILDING)) {
                        reflPoint.setBuildingId(rayPath.get(0).getBuildingId());
                        reflPoint.buildingHeight = data.profileBuilder.getBuilding(reflPoint.getBuildingId()).getHeight();
                        reflPoint.setAlphaWall(data.profileBuilder.getBuilding(reflPoint.getBuildingId()).getAlphas());
                    } else {
                        reflPoint.buildingHeight = rayPath.get(0).getWall().p0.getZ();
                        reflPoint.setWallId(rayPath.get(0).getWall().getProcessedWallIndex());
                        reflPoint.setAlphaWall(rayPath.get(0).getWall().getAlphas());
                    }
                    reflPoint.altitude = data.profileBuilder.getZGround(reflPoint.coordinate);
                    points.add(reflPoint);
                    segments.add(new SegmentPath(1, new Vector3D(firstPt), refl.getReceiverPos()));
                }
                // Compute direct path between receiver and last reflection point, add profile to the data
                List<PointPath> lastPts = new ArrayList<>();
                computeReflexionOverBuildings(rayPath.get(rayPath.size() - 1).getReceiverPos(), rcvCoord, lastPts, segments, data, orientation, pathParameters.difHPoints, pathParameters.difVPoints);
                if (lastPts.isEmpty()) {
                    continue;
                }
                points.addAll(lastPts.subList(1, lastPts.size()));
                double baseX = 0;
                for (PointPath point : points) {
                    Coordinate c = point.coordinate;
                    c.setX(c.getX() + baseX);
                    if (point.type.equals(REFL)) {
                        baseX = point.coordinate.getX();
                    }
                }
                for (int i = 1; i < points.size()-  1; i++) {
                    if (points.get(i).type == REFL) {
                        // A diffraction point may have offset in height the reflection coordinate
                        final Coordinate p0 = points.get(i - 1).coordinate;
                        final Coordinate p1 = points.get(i).coordinate;
                        final Coordinate p2 = points.get(i + 1).coordinate;
                        // compute Y value (altitude) by interpolating the Y values of the two neighboring points
                        points.get(i).coordinate = new CoordinateXY(p1.x, (p1.x-p0.x)/(p2.x-p0.x)*(p2.y-p0.y)+p0.y);
                        //TODO check if ray is intersecting over the wall now
                    }
                }


                if (!rayPath.isEmpty()) {
                    List<Coordinate> pts = new ArrayList<>();
                    pts.add(srcCoord);
                    rayPath.forEach(mrr -> pts.add(mrr.getReceiverPos()));
                    pts.add(rcvCoord);
                    List<Coordinate> topoPts = new ArrayList<>();
                    topoPts.add(new Coordinate(srcCoord));
                    double g = 0;
                    double d = 0;
                    List<CutPoint> allCutPoints = new ArrayList<>();
                    for(int i=0; i<pts.size()-1; i++) {
                        CutProfile profile = data.profileBuilder.getProfile(pts.get(i), pts.get(i+1), data.gS);
                        topoPts.addAll(profile.getCutPoints().stream()
                                .filter(cut -> cut.getType().equals(BUILDING) || cut.getType().equals(TOPOGRAPHY) || cut.getType().equals(RECEIVER))
                                .map(CutPoint::getCoordinate)
                                .collect(Collectors.toList()));
                        allCutPoints.addAll(profile.getCutPoints());
                        if(i<pts.size()-2){
                            topoPts.add(topoPts.get(topoPts.size()-1));
                            topoPts.add(topoPts.get(topoPts.size()-1));
                        }
                        double dist =pts.get(i).distance(pts.get(i+1));
                        g+=profile.getGPath()*dist;
                        d+=dist;
                    }
                    g/=d;
                    for (final Coordinate pt : topoPts) {
                        pt.z = data.profileBuilder.getZGround(pt);
                    }
                    pathParameters.setCutPoints(allCutPoints);
                    topoPts = toDirectLine(topoPts);
                    double[] meanPlan = JTSUtility.getMeanPlaneCoefficients(topoPts.toArray(new Coordinate[0]));
                    pathParameters.setSRSegment(computeSegment(topoPts.get(0), srcCoord.z, topoPts.get(topoPts.size()-1), rcvCoord.z, meanPlan, g, data.gS));
                    reflexionPathParameters.add(pathParameters);

                    //Restore the diffraction points
                    for (int i = 0; i < points.size(); i++) {
                        PointPath pp = points.get(i);
                        if (pp.type == DIFH) {
                            pathParameters.difHPoints.add(i);
                        }
                        else if (pp.type == DIFV) {
                            pathParameters.difVPoints.add(i);
                        }
                    }
                }
            }
        }
        return reflexionPathParameters;
    }


    /**
     *
     * @param p0
     * @param p1
     * @param points
     * @param segments
     * @param data
     * @param orientation
     * @param diffHPts
     * @param diffVPts
     */
    public void computeReflexionOverBuildings(Coordinate p0, Coordinate p1, List<PointPath> points,
                                              List<SegmentPath> segments, Scene data,
                                              Orientation orientation, List<Integer> diffHPts, List<Integer> diffVPts) {
        List<CnossosPath> pathsParameters = directPath(p0, -1, orientation, p1, -1,
                data.isComputeHEdgeDiffraction(), false, false);
        if (!pathsParameters.isEmpty()) {
            CnossosPath pathParameters = pathsParameters.get(0);
            points.addAll(pathParameters.getPointList());
            segments.addAll(pathParameters.getSegmentList());
            diffVPts.addAll(pathParameters.difVPoints);
            diffHPts.addAll(pathParameters.difHPoints);
        }
    }
    /**
     * @param geom Geometry
     * @param segmentSizeConstraint Maximal distance between points
     * @return Fixed distance between points
     * @param pts computed points
     */
    public static double splitLineStringIntoPoints(LineString geom, double segmentSizeConstraint,
                                                   List<Coordinate> pts) {
        // If the linear sound source length is inferior than half the distance between the nearest point of the sound
        // source and the receiver then it can be modelled as a single point source
        double geomLength = geom.getLength();
        if (geomLength < segmentSizeConstraint) {
            // Return mid point
            Coordinate[] points = geom.getCoordinates();
            double segmentLength = 0;
            final double targetSegmentSize = geomLength / 2.0;
            for (int i = 0; i < points.length - 1; i++) {
                Coordinate a = points[i];
                final Coordinate b = points[i + 1];
                double length = a.distance3D(b);
                if (length + segmentLength > targetSegmentSize) {
                    double segmentLengthFraction = (targetSegmentSize - segmentLength) / length;
                    Coordinate midPoint = new Coordinate(a.x + segmentLengthFraction * (b.x - a.x),
                            a.y + segmentLengthFraction * (b.y - a.y),
                            a.z + segmentLengthFraction * (b.z - a.z));
                    pts.add(midPoint);
                    break;
                }
                segmentLength += length;
            }
            return geom.getLength();
        } else {
            double targetSegmentSize = geomLength / Math.ceil(geomLength / segmentSizeConstraint);
            Coordinate[] points = geom.getCoordinates();
            double segmentLength = 0.;

            // Mid point of segmented line source
            Coordinate midPoint = null;
            for (int i = 0; i < points.length - 1; i++) {
                Coordinate a = points[i];
                final Coordinate b = points[i + 1];
                double length = a.distance3D(b);
                if (isNaN(length)) {
                    length = a.distance(b);
                }
                while (length + segmentLength > targetSegmentSize) {
                    //LineSegment segment = new LineSegment(a, b);
                    double segmentLengthFraction = (targetSegmentSize - segmentLength) / length;
                    Coordinate splitPoint = new Coordinate();
                    splitPoint.x = a.x + segmentLengthFraction * (b.x - a.x);
                    splitPoint.y = a.y + segmentLengthFraction * (b.y - a.y);
                    splitPoint.z = a.z + segmentLengthFraction * (b.z - a.z);
                    if (midPoint == null && length + segmentLength > targetSegmentSize / 2) {
                        segmentLengthFraction = (targetSegmentSize / 2.0 - segmentLength) / length;
                        midPoint = new Coordinate(a.x + segmentLengthFraction * (b.x - a.x),
                                a.y + segmentLengthFraction * (b.y - a.y),
                                a.z + segmentLengthFraction * (b.z - a.z));
                    }
                    pts.add(midPoint);
                    a = splitPoint;
                    length = a.distance3D(b);
                    if (isNaN(length)) {
                        length = a.distance(b);
                    }
                    segmentLength = 0;
                    midPoint = null;
                }
                if (midPoint == null && length + segmentLength > targetSegmentSize / 2) {
                    double segmentLengthFraction = (targetSegmentSize / 2.0 - segmentLength) / length;
                    midPoint = new Coordinate(a.x + segmentLengthFraction * (b.x - a.x),
                            a.y + segmentLengthFraction * (b.y - a.y),
                            a.z + segmentLengthFraction * (b.z - a.z));
                }
                segmentLength += length;
            }
            if (midPoint != null) {
                pts.add(midPoint);
            }
            return targetSegmentSize;
        }
    }


    /**
     *
     * @param lineString
     * @param profileBuilder
     * @return computed lineString
     */
    private static LineString splitLineString(LineString lineString, ProfileBuilder profileBuilder) {
        List<Coordinate> newGeomCoordinates = new ArrayList<>();
        Coordinate[] coordinates = lineString.getCoordinates();
        for(int idPoint = 0; idPoint < coordinates.length - 1; idPoint++) {
            Coordinate p0 = coordinates[idPoint];
            Coordinate p1 = coordinates[idPoint + 1];
            double p1p0Length = p1.distance(p0);
            List<Coordinate> groundProfileCoordinates = profileBuilder.getTopographicProfile(p0, p1);
            // add first point of source
            newGeomCoordinates.add(p0);
            // add intermediate points located at the edges of MNT triangle mesh
            // but the Z value should be still relative to the ground
            // ,so we interpolate the Z (height) values of the source
            for(Coordinate intermediatePoint : groundProfileCoordinates) {
                Vector2D v = new Vector2D(p0, intermediatePoint);
                Coordinate relativePoint = new Coordinate(intermediatePoint.x, intermediatePoint.y,
                        p0.z + ((v.length() / p1p0Length) * (p1.z - p0.z)));
                newGeomCoordinates.add(relativePoint);
            }
        }
        newGeomCoordinates.add(coordinates[coordinates.length - 1]);
        return GEOMETRY_FACTORY.createLineString(newGeomCoordinates.toArray(new Coordinate[0]));
    }

    /**
     * Update ground Z coordinates of sound sources absolute to sea levels
     */
    public void makeSourceRelativeZToAbsolute() {
        ElevationFilter filter = new ElevationFilter(data.profileBuilder, true);
        List<Geometry> sourceCopy = new ArrayList<>(data.sourceGeometries.size());
        for (Geometry source : data.sourceGeometries) {

            Geometry offsetGeometry = source.copy();
            if(source instanceof LineString) {
                offsetGeometry = splitLineString((LineString) source, data.profileBuilder);
            } else if(source instanceof MultiLineString) {
                LineString[] newGeom = new LineString[source.getNumGeometries()];
                for(int idGeom = 0; idGeom < source.getNumGeometries(); idGeom++) {
                    newGeom[idGeom] = splitLineString((LineString) source.getGeometryN(idGeom),
                            data.profileBuilder);
                }
                offsetGeometry = GEOMETRY_FACTORY.createMultiLineString(newGeom);
            }
            // Offset the geometry with value of elevation for each coordinate
            filter.reset();
            offsetGeometry.apply(filter);
            sourceCopy.add(offsetGeometry);
        }
        data.sourceGeometries = sourceCopy;
    }

    /**
     * Update ground Z coordinates of sound sources and receivers absolute to sea levels
     */
    public void makeRelativeZToAbsolute() {
        makeSourceRelativeZToAbsolute();
        makeReceiverRelativeZToAbsolute();
    }

    /**
     * Update ground Z coordinates of receivers absolute to sea levels
     */
    public void makeReceiverRelativeZToAbsolute() {
        ElevationFilter filter = new ElevationFilter(data.profileBuilder, true);
        CoordinateSequence sequence = new CoordinateArraySequence(data.receivers.toArray(new Coordinate[data.receivers.size()]));
        for (int i = 0; i < sequence.size(); i++) {
            filter.filter(sequence, i);
        }
        data.receivers = Arrays.asList(sequence.toCoordinateArray());
    }


    /**
     * Compute maximal power at freefield at the receiver position with reflective ground
     * @param source
     * @param receiverPos
     * @param sourceId
     * @param sourceList
     * @param wj
     * @param li
     * @param orientation
     * @return
     */
    private static double insertPtSource(Coordinate source, Coordinate receiverPos, Integer sourceId,
                                         List<PointPath.SourcePointInfo> sourceList, double[] wj, double li, Orientation orientation) {
        //
        double aDiv = -getADiv(CGAlgorithms3D.distance(receiverPos, source));
        double[] srcWJ = new double[wj.length];
        for (int idFreq = 0; idFreq < srcWJ.length; idFreq++) {
            srcWJ[idFreq] = wj[idFreq] * li * dbaToW(aDiv) * dbaToW(3);
        }
        sourceList.add(new PointPath.SourcePointInfo(srcWJ, sourceId, source, li, orientation));
        return sumArray(srcWJ.length, srcWJ);
    }


    /**
     *
     * @param source
     * @param receiverPos
     * @param sourceId
     * @param sourceList
     * @param wj
     * @param li
     * @param orientation
     * @return
     */
    private static double insertPtSource(Point source, Coordinate receiverPos, Integer sourceId,
                                         List<PointPath.SourcePointInfo> sourceList, double[] wj, double li, Orientation orientation) {
        // Compute maximal power at freefield at the receiver position with reflective ground
        double aDiv = -getADiv(CGAlgorithms3D.distance(receiverPos, source.getCoordinate()));
        double[] srcWJ = new double[wj.length];
        for (int idFreq = 0; idFreq < srcWJ.length; idFreq++) {
            srcWJ[idFreq] = wj[idFreq] * li * dbaToW(aDiv) * dbaToW(3);
        }
        sourceList.add(new PointPath.SourcePointInfo(srcWJ, sourceId, source.getCoordinate(), li, orientation));
        return sumArray(srcWJ.length, srcWJ);
    }

    /**
     * Compute li to equation 4.1 NMPB 2008 (June 2009)
     * @param source
     * @param receiverCoord
     * @param srcIndex
     * @param sourceList
     * @param wj
     * @return
     */
    private double addLineSource(LineString source, Coordinate receiverCoord, int srcIndex, List<PointPath.SourcePointInfo> sourceList, double[] wj) {
        double totalPowerRemaining = 0;
        ArrayList<Coordinate> pts = new ArrayList<>();
        Coordinate nearestPoint = JTSUtility.getNearestPoint(receiverCoord, source);
        double segmentSizeConstraint = max(1, receiverCoord.distance3D(nearestPoint) / 2.0);
        if (isNaN(segmentSizeConstraint)) {
            segmentSizeConstraint = max(1, receiverCoord.distance(nearestPoint) / 2.0);
        }
        double li = splitLineStringIntoPoints(source, segmentSizeConstraint, pts);
        for (int ptIndex = 0; ptIndex < pts.size(); ptIndex++) {
            Coordinate pt = pts.get(ptIndex);
            if (pt.distance(receiverCoord) < data.maxSrcDist) {
                // use the orientation computed from the line source coordinates
                Vector3D v;
                if(ptIndex == 0) {
                    v = new Vector3D(source.getCoordinates()[0], pts.get(ptIndex));
                } else {
                    v = new Vector3D(pts.get(ptIndex - 1), pts.get(ptIndex));
                }
                Orientation orientation;
                if(data.sourcesPk.size() > srcIndex && data.sourceOrientation.containsKey(data.sourcesPk.get(srcIndex))) {
                    // If the line source already provide an orientation then alter the line orientation
                    orientation = data.sourceOrientation.get(data.sourcesPk.get(srcIndex));
                    orientation = Orientation.fromVector(
                            Orientation.rotate(new Orientation(orientation.yaw, orientation.roll, 0),
                                    v.normalize()), orientation.roll);
                } else {
                    orientation = Orientation.fromVector(Orientation.rotate(new Orientation(0,0,0), v.normalize()), 0);
                }
                totalPowerRemaining += insertPtSource(pt, receiverCoord, srcIndex, sourceList, wj, li, orientation);
            }
        }
        return totalPowerRemaining;
    }

    enum ComputationSide {LEFT, RIGHT}


}
