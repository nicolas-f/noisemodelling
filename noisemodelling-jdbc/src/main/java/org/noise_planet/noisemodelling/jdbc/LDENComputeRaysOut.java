package org.noise_planet.noisemodelling.jdbc;

import org.noise_planet.noisemodelling.pathfinder.IComputeRaysOut;
import org.noise_planet.noisemodelling.pathfinder.PropagationPath;
import org.noise_planet.noisemodelling.pathfinder.utils.PowerUtils;
import org.noise_planet.noisemodelling.propagation.ComputeRaysOutAttenuation;
import org.noise_planet.noisemodelling.propagation.PropagationProcessPathData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import static org.noise_planet.noisemodelling.pathfinder.utils.PowerUtils.*;

public class LDENComputeRaysOut extends ComputeRaysOutAttenuation {
    LdenData ldenData;
    LDENPropagationProcessData ldenPropagationProcessData;
    public PropagationProcessPathData dayPathData;
    public PropagationProcessPathData eveningPathData;
    public PropagationProcessPathData nightPathData;
    public LDENConfig ldenConfig;

    public LDENComputeRaysOut(PropagationProcessPathData dayPathData, PropagationProcessPathData eveningPathData,
                              PropagationProcessPathData nightPathData, LDENPropagationProcessData inputData,
                              LdenData ldenData, LDENConfig ldenConfig) {
        super(inputData.ldenConfig.exportRaysMethod != LDENConfig.ExportRaysMethods.NONE, null, inputData);
        this.keepAbsorption = inputData.ldenConfig.keepAbsorption;
        this.ldenData = ldenData;
        this.ldenPropagationProcessData = inputData;
        this.dayPathData = dayPathData;
        this.eveningPathData = eveningPathData;
        this.nightPathData = nightPathData;
        this.ldenConfig = ldenConfig;
    }

    public LdenData getLdenData() {
        return ldenData;
    }

    @Override
    public IComputeRaysOut subProcess() {
        return new ThreadComputeRaysOut(this);
    }

    public static class DENAttenuation {
        public double [] dayLevels = null;
        public double [] eveningLevels = null;
        public double [] nightLevels = null;

        public double[] getTimePeriodLevel(LDENConfig.TIME_PERIOD timePeriod) {
            switch (timePeriod) {
                case TIME_PERIOD_DAY:
                    return dayLevels;
                case TIME_PERIOD_EVENING:
                    return eveningLevels;
                default:
                    return nightLevels;
            }
        }
        public void setTimePeriodLevel(LDENConfig.TIME_PERIOD timePeriod, double [] levels) {
            switch (timePeriod) {
                case TIME_PERIOD_DAY:
                    dayLevels = levels;
                case TIME_PERIOD_EVENING:
                    eveningLevels = levels;
                default:
                    nightLevels = levels;
            }
        }
    }

    public static class ThreadComputeRaysOut implements IComputeRaysOut {
        LDENComputeRaysOut ldenComputeRaysOut;
        LDENConfig ldenConfig;
        ThreadRaysOut[] lDENThreadRaysOut = new ThreadRaysOut[3];
        public List<PropagationPath> propagationPaths = new ArrayList<PropagationPath>();

        public ThreadComputeRaysOut(LDENComputeRaysOut multiThreadParent) {
            this.ldenComputeRaysOut = multiThreadParent;
            this.ldenConfig = multiThreadParent.ldenPropagationProcessData.ldenConfig;
            lDENThreadRaysOut[0] = new ThreadRaysOut(multiThreadParent, multiThreadParent.dayPathData);
            lDENThreadRaysOut[1] = new ThreadRaysOut(multiThreadParent, multiThreadParent.eveningPathData);
            lDENThreadRaysOut[2] = new ThreadRaysOut(multiThreadParent, multiThreadParent.nightPathData);
            for (ThreadRaysOut threadRaysOut : lDENThreadRaysOut) {
                threadRaysOut.keepRays = false;
            }

        }

        /**
         * Energetic sum of VerticeSL attenuation with WJ sources
         * @param wjSources
         * @param receiverAttenuationLevels
         * @return
         */
        double[] sumLevels(List<double[]> wjSources,List<VerticeSL> receiverAttenuationLevels) {
            double[] levels = new double[ldenComputeRaysOut.dayPathData.freq_lvl.size()];
            for (VerticeSL lvl : receiverAttenuationLevels) {
                levels = sumArray(levels,
                        dbaToW(sumArray(wToDba(wjSources.get((int) lvl.sourceId)), lvl.value)));
            }
            return levels;
        }

        double[] processAndPushResult(long receiverPK, List<double[]> wjSources,List<VerticeSL> receiverAttenuationLevels, ConcurrentLinkedDeque<VerticeSL> result, boolean feedStack) {
            double[] levels = sumLevels(wjSources, receiverAttenuationLevels);
            if(feedStack) {
                pushInStack(result, new VerticeSL(receiverPK, -1, wToDba(levels)));
            }
            return levels;
        }


        @Override
        public double[] addPropagationPaths(long sourceId, double sourceLi, long receiverId, PropagationPath propagationPath) {
            ldenComputeRaysOut.rayCount.addAndGet(1);
            if(ldenConfig.getExportRaysMethod() != LDENConfig.ExportRaysMethods.NONE) {
                if(ldenComputeRaysOut.inputData != null && sourceId < ldenComputeRaysOut.inputData.sourcesPk.size() &&
                    receiverId < ldenComputeRaysOut.inputData.receiversPk.size()) {
                    // Copy path content in order to keep original ids for other method calls
                    PropagationPath pathPk = new PropagationPath(propagationPath);
                    pathPk.setIdReceiver(ldenComputeRaysOut.inputData.receiversPk.get((int)receiverId).intValue());
                    pathPk.setIdSource(ldenComputeRaysOut.inputData.sourcesPk.get((int)sourceId).intValue());
                    propagationPaths.add(pathPk);
                } else {
                    propagationPaths.add(propagationPath);
                }
            }
            double[] globalLevel = lDENThreadRaysOut[0].addPropagationPaths(sourceId, sourceLi, receiverId, propagationPath);
            globalLevel = PowerUtils.sumDbArray(globalLevel, lDENThreadRaysOut[1].addPropagationPaths(sourceId, sourceLi,
                    receiverId, propagationPath));
            globalLevel = PowerUtils.sumDbArray(globalLevel, lDENThreadRaysOut[2].addPropagationPaths(sourceId, sourceLi,
                    receiverId, propagationPath));
            return globalLevel;
        }

        /**
         * @param stack Stack to feed
         * @param data receiver noise level in dB
         */
        public void pushInStack(ConcurrentLinkedDeque<VerticeSL> stack, VerticeSL data) {
            while(ldenComputeRaysOut.ldenData.queueSize.get() > ldenConfig.outputMaximumQueue) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ex) {
                    ldenConfig.aborted = true;
                    break;
                }
                if(ldenConfig.aborted) {
                    if(ldenComputeRaysOut != null && this.ldenComputeRaysOut.inputData != null &&
                            this.ldenComputeRaysOut.inputData.cellProg != null) {
                        this.ldenComputeRaysOut.inputData.cellProg.cancel();
                    }
                    return;
                }
            }
            stack.add(data);
            ldenComputeRaysOut.ldenData.queueSize.incrementAndGet();
        }

        @Override
        public IComputeRaysOut subProcess() {
            return null;
        }

        /**
         * @param stack Stack to feed
         * @param data rays
         */
        public void pushInStack(ConcurrentLinkedDeque<PropagationPath> stack, Collection<PropagationPath> data) {
            while(ldenComputeRaysOut.ldenData.queueSize.get() > ldenConfig.outputMaximumQueue) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ex) {
                    ldenConfig.aborted = true;
                    break;
                }
                if(ldenConfig.aborted) {
                    if(ldenComputeRaysOut != null && this.ldenComputeRaysOut.inputData != null &&
                            this.ldenComputeRaysOut.inputData.cellProg != null) {
                        this.ldenComputeRaysOut.inputData.cellProg.cancel();
                    }
                    return;
                }
            }
            stack.addAll(data);
            ldenComputeRaysOut.ldenData.queueSize.addAndGet(data.size());
        }

        @Override
        public void finalizeReceiver(final long receiverId) {
            if(!propagationPaths.isEmpty()) {
                if(ldenConfig.getExportRaysMethod() == LDENConfig.ExportRaysMethods.TO_RAYS_TABLE) {
                    // Push propagation rays
                    pushInStack(ldenComputeRaysOut.ldenData.rays, propagationPaths);
                } else if(ldenConfig.getExportRaysMethod() == LDENConfig.ExportRaysMethods.TO_MEMORY){
                    ldenComputeRaysOut.propagationPaths.addAll(propagationPaths);
                }
                propagationPaths.clear();
            }
            long receiverPK = receiverId;
            if(ldenComputeRaysOut.inputData != null) {
                if(receiverId < ldenComputeRaysOut.inputData.receiversPk.size()) {
                    receiverPK = ldenComputeRaysOut.inputData.receiversPk.get((int)receiverId);
                }
            }
            double[] dayLevels = new double[0], eveningLevels = new double[0], nightLevels = new double[0];
            if (!ldenConfig.mergeSources) {
                ConcurrentLinkedDeque<VerticeSL>[] queues = new ConcurrentLinkedDeque[] {ldenComputeRaysOut.ldenData.lDayLevels,
                        ldenComputeRaysOut.ldenData.lEveningLevels,
                        ldenComputeRaysOut.ldenData.lNightLevels};
                List<double[]>[] soundSourceLevels = new List[] {
                        ldenComputeRaysOut.ldenPropagationProcessData.wjSourcesD,
                        ldenComputeRaysOut.ldenPropagationProcessData.wjSourcesE,
                        ldenComputeRaysOut.ldenPropagationProcessData.wjSourcesN
                };
                int stackSize = Math.max(Math.max(lDENThreadRaysOut[0].receiverAttenuationLevels.size(),
                        lDENThreadRaysOut[1].receiverAttenuationLevels.size()),
                        lDENThreadRaysOut[2].receiverAttenuationLevels.size());
                for(int idRay = 0; idRay < stackSize; idRay ++) {
                    if(lDENThreadRaysOut[0].receiverAttenuationLevels.size() > idRay) {
                        VerticeSL attLevel = lDENThreadRaysOut[0].receiverAttenuationLevels.get(idRay);
                        dayLevels = sumArray(wToDba(
                                ldenComputeRaysOut.ldenPropagationProcessData.wjSourcesD.get((int) attLevel.sourceId)),
                                attLevel.value);
                        pushInStack();
                    }
                }
                for (LDENConfig.TIME_PERIOD timePeriod : LDENConfig.TIME_PERIOD.values()) {
                    ThreadRaysOut threadRaysOut = lDENThreadRaysOut[timePeriod.ordinal()];
                    for (VerticeSL lvl : threadRaysOut.receiverAttenuationLevels) {
                        final long sourceId = lvl.sourceId;
                        long sourcePK = sourceId;
                        if (ldenComputeRaysOut.inputData != null) {
                            // Retrieve original source identifier
                            if (sourceId < ldenComputeRaysOut.inputData.sourcesPk.size()) {
                                sourcePK = ldenComputeRaysOut.inputData.sourcesPk.get((int) sourceId);
                            }
                        }
                        VerticeSL receiverLevel = new VerticeSL(receiverPK, sourcePK,
                                sumArray(wToDba(soundSourceLevels[timePeriod.ordinal()].get((int) sourceId)), lvl.value),
                                lvl.phi, lvl.theta, lvl.distance);
                        pushInStack(queues[timePeriod.ordinal()], receiverLevel);
                    }
                }
            } else {
                // Merge all results
                if (ldenConfig.computeLDay || ldenConfig.computeLDEN) {
                    dayLevels = processAndPushResult(receiverPK,
                            ldenComputeRaysOut.ldenPropagationProcessData.wjSourcesD,
                            lDENThreadRaysOut[0].receiverAttenuationLevels, ldenComputeRaysOut.ldenData.lDayLevels,
                            ldenConfig.computeLDay);
                }
                if (ldenConfig.computeLEvening || ldenConfig.computeLDEN) {
                    eveningLevels = processAndPushResult(receiverPK,
                            ldenComputeRaysOut.ldenPropagationProcessData.wjSourcesE,
                            lDENThreadRaysOut[1].receiverAttenuationLevels, ldenComputeRaysOut.ldenData.lEveningLevels,
                            ldenConfig.computeLEvening);
                }
                if (ldenConfig.computeLNight || ldenConfig.computeLDEN) {
                    nightLevels = processAndPushResult(receiverPK,
                            ldenComputeRaysOut.ldenPropagationProcessData.wjSourcesN,
                            lDENThreadRaysOut[2].receiverAttenuationLevels, ldenComputeRaysOut.ldenData.lNightLevels,
                            ldenConfig.computeLNight);
                }
                if (ldenConfig.computeLDEN) {
                    double[] levels = new double[dayLevels.length];
                    for(int idFrequency = 0; idFrequency < levels.length; idFrequency++) {
                        levels[idFrequency] = (12 * dayLevels[idFrequency] +
                                4 * dbaToW(wToDba(eveningLevels[idFrequency]) + 5) +
                                8 * dbaToW(wToDba(nightLevels[idFrequency]) + 10)) / 24.0;
                    }
                    pushInStack(ldenComputeRaysOut.ldenData.lDenLevels, new VerticeSL(receiverPK, -1, wToDba(levels)));
                }
            }
            for (ThreadRaysOut threadRaysOut : lDENThreadRaysOut) {
                threadRaysOut.receiverAttenuationLevels.clear();
            }
        }
    }

    public static class LdenData {
        public final AtomicLong queueSize = new AtomicLong(0);
        public final ConcurrentLinkedDeque<VerticeSL> lDayLevels = new ConcurrentLinkedDeque<>();
        public final ConcurrentLinkedDeque<VerticeSL> lEveningLevels = new ConcurrentLinkedDeque<>();
        public final ConcurrentLinkedDeque<VerticeSL> lNightLevels = new ConcurrentLinkedDeque<>();
        public final ConcurrentLinkedDeque<VerticeSL> lDenLevels = new ConcurrentLinkedDeque<>();
        public final ConcurrentLinkedDeque<PropagationPath> rays = new ConcurrentLinkedDeque<>();
    }
}
