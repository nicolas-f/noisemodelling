/**
 * NoiseMap is a scientific computation plugin for OrbisGIS to quickly evaluate the
 * noise impact on European action plans and urban mobility plans. This model is
 * based on the French standard method NMPB2008. It includes traffic-to-noise
 * sources evaluation and sound propagation processing.
 *
 * This version is developed at French IRSTV Institute and at IFSTTAR
 * (http://www.ifsttar.fr/) as part of the Eval-PDU project, funded by the
 * French Agence Nationale de la Recherche (ANR) under contract ANR-08-VILL-0005-01.
 *
 * Noisemap is distributed under GPL 3 license. Its reference contact is Judicaël
 * Picaut <judicael.picaut@ifsttar.fr>. It is maintained by Nicolas Fortin
 * as part of the "Atelier SIG" team of the IRSTV Institute <http://www.irstv.fr/>.
 *
 * Copyright (C) 2011 IFSTTAR
 * Copyright (C) 2011-1012 IRSTV (FR CNRS 2488)
 *
 * Noisemap is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Noisemap is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Noisemap. If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, please consult: <http://www.orbisgis.org/>
 * or contact directly:
 * info_at_ orbisgis.org
 */
package org.noisemap.core;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import java.util.ArrayList;
import java.util.List;
import org.gdms.data.DataSourceFactory;

/**
 * Data input for a propagation process (SubEnveloppe of BR_TriGrid).
 * 
 * @author Nicolas Fortin
 */
public class PropagationProcessData {
	public List<Coordinate> vertices; // Coordinate of receivers
        public List<Long> receiverRowId;  //Row id of receivers, only for BR_PtGrid
	public List<Triangle> triangles; // Index of vertices of triangles
	public FastObstructionTest freeFieldFinder; // FreeField test
	public QueryGeometryStructure sourcesIndex; // Source Index
	public List<Geometry> sourceGeometries; // Sources geometries. Can be
											// LINESTRING or POINT
	public List<ArrayList<Double>> wj_sources; // Sound level of source. By
												// frequency band, energetic
	public List<Integer> freq_lvl; // Frequency bands values, by third octave
	public int reflexionOrder; // reflexionOrder
	public int diffractionOrder; // diffractionOrder
	public double maxSrcDist; // Maximum source distance
        public double maxRefDist; // Maximum reflection wall distance
	public double minRecDist; // Minimum distance between source and receiver
	public double wallAlpha; // Wall alpha [0-1]
	public int cellId; // cell id
	public DataSourceFactory dsf; // Debug purpose
	public ProgressionProcess cellProg; // Progression information

    public PropagationProcessData(List<Coordinate> vertices, List<Long> receiverRowId, List<Triangle> triangles, FastObstructionTest freeFieldFinder, QueryGeometryStructure sourcesIndex, List<Geometry> sourceGeometries, List<ArrayList<Double>> wj_sources, List<Integer> freq_lvl, int reflexionOrder, int diffractionOrder, double maxSrcDist, double maxRefDist, double minRecDist, double wallAlpha, int cellId, DataSourceFactory dsf, ProgressionProcess cellProg) {
        this.vertices = vertices;
        this.receiverRowId = receiverRowId;
        this.triangles = triangles;
        this.freeFieldFinder = freeFieldFinder;
        this.sourcesIndex = sourcesIndex;
        this.sourceGeometries = sourceGeometries;
        this.wj_sources = wj_sources;
        this.freq_lvl = freq_lvl;
        this.reflexionOrder = reflexionOrder;
        this.diffractionOrder = diffractionOrder;
        this.maxSrcDist = maxSrcDist;
        this.maxRefDist = maxRefDist;
        this.minRecDist = minRecDist;
        this.wallAlpha = wallAlpha;
        this.cellId = cellId;
        this.dsf = dsf;
        this.cellProg = cellProg;
    }


	

}
