/**
 * NoiseModelling is a library capable of producing noise maps. It can be freely used either for research and education, as well as by experts in a professional use.
 * <p>
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software.
 * <p>
 * Official webpage : http://noise-planet.org/noisemodelling.html
 * Contact: contact@noise-planet.org
 */

package org.noise_planet.noisemodelling.propagation;

import org.locationtech.jts.math.Vector3D;
import org.noise_planet.noisemodelling.pathfinder.CutPlaneVisitor;
import org.noise_planet.noisemodelling.pathfinder.PathFinder;
import org.noise_planet.noisemodelling.pathfinder.profilebuilder.CutProfile;
import org.noise_planet.noisemodelling.pathfinder.utils.AcousticIndicatorsFunctions;
import org.noise_planet.noisemodelling.pathfinder.utils.geometry.Orientation;
import org.noise_planet.noisemodelling.propagation.cnossos.AttenuationCnossos;
import org.noise_planet.noisemodelling.propagation.cnossos.CnossosPath;
import org.noise_planet.noisemodelling.propagation.cnossos.CnossosPathBuilder;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.noise_planet.noisemodelling.pathfinder.utils.AcousticIndicatorsFunctions.*;
import static org.noise_planet.noisemodelling.pathfinder.utils.AcousticIndicatorsFunctions.dBToW;

/**
 * Receive vertical cut plane, compute the attenuation corresponding to this plane
 */
public class AttenuationVisitor implements CutPlaneVisitor {
    public AttenuationComputeOutput multiThreadParent;
    public List<ReceiverNoiseLevel> receiverAttenuationLevels = new ArrayList<>();
    public List<CnossosPath> pathParameters = new ArrayList<CnossosPath>();
    public boolean keepRays = false;

    public AttenuationVisitor(AttenuationComputeOutput multiThreadParent) {
        this.multiThreadParent = multiThreadParent;
        this.keepRays = multiThreadParent.exportPaths;
    }

    @Override
    public PathSearchStrategy onNewCutPlane(CutProfile cutProfile) {
        final SceneWithAttenuation scene = multiThreadParent.scene;
        // Source surface reflectivity
        double gs = scene.sourceGs.getOrDefault(cutProfile.getSource().sourcePk, SceneWithAttenuation.DEFAULT_GS);


        CnossosPath cnossosPathH = CnossosPathBuilder.computeCnossosPathFromCutProfile(cutProfile, scene.isBodyBarrier(),
                scene.profileBuilder.exactFrequencyArray, gs, false);

        CnossosPath cnossosPathF = CnossosPathBuilder.computeCnossosPathFromCutProfile(cutProfile, scene.isBodyBarrier(),
                scene.profileBuilder.exactFrequencyArray, gs, true);

        if(cnossosPathF != null) {
            cnossosPathF.setFavorable(true);
            if(pathParameters.size() >= 2){
                cnossosPathF.double_aBoundary = pathParameters.get(0).double_aBoundary; // double_aBoundary is calculated once for the direct path and will be the same for all paths.
            }
            addPropagationPath(cnossosPathF);
        }

        if(cnossosPathH != null) {
            cnossosPathH.setFavorable(false);
            if(pathParameters.size() >= 2){
                cnossosPathH.double_aBoundary = pathParameters.get(1).double_aBoundary;
            }
            addPropagationPath(cnossosPathH);
        }

        return PathSearchStrategy.CONTINUE;
    }

    @Override
    public void startReceiver(PathFinder.ReceiverPointInfo receiver, Collection<PathFinder.SourcePointInfo> sourceList, AtomicInteger cutProfileCount) {

    }

    private void processPath(String period, AttenuationParameters AttenuationParameters, CnossosPath path) {
        double[] aglobalSR = null;
        Vector3D fieldVectorPropagation = Orientation.rotate(path.getSourceOrientation(),
                Orientation.toVector(path.raySourceReceiverDirectivity), false);
        int roseIndex = AttenuationParameters.getRoseIndex(Math.atan2(fieldVectorPropagation.getY(), fieldVectorPropagation.getX()));

        AttenuationCnossos.computeCnossosAttenuation(AttenuationParameters, path,
                multiThreadParent.scene, multiThreadParent.exportAttenuationMatrix);

        if(!pathParameters.isEmpty()) {
            if (pathParameters.get(pathParameters.size() - 1).getSRSegment().s.equals(path.getSRSegment().s) && pathParameters.get(pathParameters.size() - 1).getSRSegment().r.equals(path.getSRSegment().r)) {
                aglobalSR = sumArrayWithPonderation(pathParameters.get(pathParameters.size() - 1).aGlobal, path.aGlobal, AttenuationParameters.getWindRose()[roseIndex]);
                int sourceId = path.getCutProfile().getSource().id;
                double sourceLi = path.getCutProfile().getSource().li;

                if(multiThreadParent.scene != null && !multiThreadParent.scene.isOmnidirectional(sourceId)) {
                    Orientation directivityToPick = path.raySourceReceiverDirectivity;
                    double[] attSource = multiThreadParent.scene.getSourceAttenuation( sourceId,
                            multiThreadParent.scene.profileBuilder.frequencyArray.stream().mapToDouble(value -> value).toArray(), Math.toRadians(directivityToPick.yaw),
                            Math.toRadians(directivityToPick.pitch));
                    if(multiThreadParent.exportAttenuationMatrix) {
                        path.aSource = attSource;
                    }
                    aglobalSR = sumArray(aglobalSR, attSource);
                }

                // For line source, take account of li coefficient
                if(sourceLi > 1.0) {
                    for (int i = 0; i < aglobalSR.length; i++) {
                        aglobalSR[i] = wToDb(dBToW(aglobalSR[i]) * sourceLi);
                    }
                }
                // Keep global attenuation
                if(multiThreadParent.exportAttenuationMatrix) {
                    path.aGlobalL = aglobalSR.clone();
                    pathParameters.get(pathParameters.size() - 1).aGlobalL = aglobalSR.clone();
                }
            }else {
                aglobalSR = path.aGlobal.clone();
            }
        }else{
            aglobalSR = path.aGlobal.clone();
        }

        if (aglobalSR != null && aglobalSR.length > 0) {
            multiThreadParent.cnossosPathCount.addAndGet(1);
            if(keepRays) {
                pathParameters.add(path);
            }
            receiverAttenuationLevels.add(new ReceiverNoiseLevel(
                    new PathFinder.SourcePointInfo(path.getCutProfile().getSource()),
                    new PathFinder.ReceiverPointInfo(path.getCutProfile().getReceiver()),
                    period, aglobalSR));
        }
    }

    /**
     * Get propagation path result
     * @param path Propagation path result
     */
    public void addPropagationPath(CnossosPath path) {
        if(!multiThreadParent.scene.cnossosParametersPerPeriod.isEmpty()) {
            for (Map.Entry<String, AttenuationParameters> cnossosParametersEntry :
                    multiThreadParent.scene.cnossosParametersPerPeriod.entrySet()) {
                processPath(cnossosParametersEntry.getKey(), cnossosParametersEntry.getValue(), path);
            }
        } else {
            processPath("", multiThreadParent.scene.defaultCnossosParameters, path);
        }
    }

    /**
     * No more propagation paths will be pushed for this receiver identifier
     *
     * @param receiver
     */
    @Override
    public void finalizeReceiver(PathFinder.ReceiverPointInfo receiver) {
        if(keepRays && !pathParameters.isEmpty()) {
            multiThreadParent.pathParameters.addAll(this.pathParameters);
            multiThreadParent.propagationPathsSize.addAndGet(pathParameters.size());
            this.pathParameters.clear();
        }
        if(multiThreadParent.receiversAttenuationLevels != null) {
            // Push merged sources into multi-thread parent
            // Merge levels for each receiver for lines sources
            Map<PathFinder.SourcePointInfo, double[]> levelsPerSourceLines = new HashMap<>();
            for (ReceiverNoiseLevel lvl : receiverAttenuationLevels) {
                if (!levelsPerSourceLines.containsKey(lvl.source)) {
                    levelsPerSourceLines.put(lvl.source, lvl.levels);
                } else {
                    // merge
                    levelsPerSourceLines.put(lvl.source,
                            AcousticIndicatorsFunctions.sumDbArray(levelsPerSourceLines.get(lvl.source),
                            lvl.levels));
                }
            }
            for (Map.Entry<PathFinder.SourcePointInfo, double[]> entry : levelsPerSourceLines.entrySet()) {
                multiThreadParent.receiversAttenuationLevels.add(
                        new ReceiverNoiseLevel(entry.getKey(), receiver , "", entry.getValue()));
            }
        }
        receiverAttenuationLevels.clear();
    }

}