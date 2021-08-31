/**
 * NoiseMap is a scientific computation plugin for OrbisGIS developed in order to
 * evaluate the noise impact on urban mobility plans. This model is
 * based on the French standard method NMPB2008. It includes traffic-to-noise
 * sources evaluation and sound propagation processing.
 * <p>
 * This version is developed at French IRSTV Institute and at IFSTTAR
 * (http://www.ifsttar.fr/) as part of the Eval-PDU project, funded by the
 * French Agence Nationale de la Recherche (ANR) under contract ANR-08-VILL-0005-01.
 * <p>
 * Noisemap is distributed under GPL 3 license. Its reference contact is Judicaël
 * Picaut <judicael.picaut@ifsttar.fr>. It is maintained by Nicolas Fortin
 * as part of the "Atelier SIG" team of the IRSTV Institute <http://www.irstv.fr/>.
 * <p>
 * Copyright (C) 2011 IFSTTAR
 * Copyright (C) 2011-2012 IRSTV (FR CNRS 2488)
 * <p>
 * Noisemap is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * <p>
 * Noisemap is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * Noisemap. If not, see <http://www.gnu.org/licenses/>.
 * <p>
 * For more information, please consult: <http://www.orbisgis.org/>
 * or contact directly:
 * info_at_ orbisgis.org
 */
package org.noise_planet.noisemodelling.pathfinder.utils;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Generate stats about receiver computation time
 */
public class ReceiverStatsMetric implements ProfilerThread.Metric {
    private ConcurrentLinkedDeque<ReceiverComputationTime> receiverComputationTimes = new ConcurrentLinkedDeque<>();
    private ConcurrentLinkedDeque<ReceiverRays> receiverRaysDeque = new ConcurrentLinkedDeque<>();
    private DescriptiveStatistics computationTime = new DescriptiveStatistics();
    private DescriptiveStatistics computationRays = new DescriptiveStatistics();
    private int maxReceiverTime = 0;
    private Coordinate maxReceiverCoordinate = new Coordinate();
    private Logger logger = LoggerFactory.getLogger(ReceiverStatsMetric.class);

    public ReceiverStatsMetric() {
    }

    @Override
    public void tick(long currentMillis) {
        while (!receiverComputationTimes.isEmpty()) {
            ReceiverComputationTime receiverProfile = receiverComputationTimes.pop();
            computationTime.addValue(receiverProfile.computationTime);
            if(receiverProfile.computationTime > maxReceiverTime) {
                maxReceiverTime = receiverProfile.computationTime;
                maxReceiverCoordinate = receiverProfile.receiverCoordinate;
                logger.info(String.format(Locale.ROOT, "Receiver POINT(%.2f %.2f %.2f) : %d ms", maxReceiverCoordinate.x,
                        maxReceiverCoordinate.y, maxReceiverCoordinate.z, maxReceiverTime));
            }
        }
        while (!receiverRaysDeque.isEmpty()) {
            ReceiverRays receiverProfile = receiverRaysDeque.pop();
            computationRays.addValue(receiverProfile.numberOfRays);
        }
    }

    @Override
    public String[] getColumnNames() {
        return new String[] {"receiver_min","receiver_median","receiver_mean","receiver_max", "receiver_median_rays", "receiver_max_rays", "receiver_max_coordinate"};
    }

    public void onEndComputation(Coordinate receiverCoordinate, int computationTime) {
        receiverComputationTimes.add(new ReceiverComputationTime(receiverCoordinate, computationTime));
    }

    public void onReceiverRays(int receiverId, int receiverRays) {
        receiverRaysDeque.add(new ReceiverRays(receiverId, receiverRays));
    }

    @Override
    public String[] getCurrentValues() {
        String[] res = new String[] {
                Integer.toString((int) computationTime.getMin()),
                Integer.toString((int) computationTime.getPercentile(50)),
                Integer.toString((int) computationTime.getMean()),
                Integer.toString((int) computationTime.getMax()),
                Integer.toString((int) computationRays.getPercentile(50)),
                Integer.toString((int) computationRays.getMax()),
                String.format(Locale.ROOT, "POINT(%.2f %.2f %.2f)", maxReceiverCoordinate.x,
                        maxReceiverCoordinate.y, maxReceiverCoordinate.z)
        };
        computationTime.clear();
        computationRays.clear();
        return res;
    }

    private static class ReceiverComputationTime {
        public Coordinate receiverCoordinate;
        public int computationTime;

        public ReceiverComputationTime(Coordinate receiverCoordinate, int computationTime) {
            this.receiverCoordinate = receiverCoordinate;
            this.computationTime = computationTime;
        }
    }

    private static class ReceiverRays {
        public int receiverId;
        public int numberOfRays;

        public ReceiverRays(int receiverId, int numberOfRays) {
            this.receiverId = receiverId;
            this.numberOfRays = numberOfRays;
        }
    }
}