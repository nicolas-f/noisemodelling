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

import com.vividsolutions.jts.geom.*;
import java.util.*;
import java.util.Map.Entry;
import org.apache.log4j.Logger;
import org.gdms.data.DataSourceFactory;
import org.gdms.data.schema.DefaultMetadata;
import org.gdms.data.schema.Metadata;
import org.gdms.data.schema.MetadataUtilities;
import org.gdms.data.types.Type;
import org.gdms.data.types.TypeFactory;
import org.gdms.data.values.Value;
import org.gdms.data.values.ValueFactory;
import org.gdms.driver.DataSet;
import org.gdms.driver.DiskBufferDriver;
import org.gdms.driver.DriverException;
import org.gdms.sql.function.FunctionException;
import org.gdms.sql.function.FunctionSignature;
import org.gdms.sql.function.table.AbstractTableFunction;
import org.gdms.sql.function.table.TableArgument;
import org.gdms.sql.function.table.TableDefinition;
import org.gdms.sql.function.table.TableFunctionSignature;
import org.orbisgis.progress.ProgressMonitor;

/**
 * 
 * @author Nicolas Fortin
 */
class GroupKey {
    private int cellId;
    private short idIso;

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 13 * hash + this.cellId;
        hash = 13 * hash + this.idIso;
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final GroupKey other = (GroupKey) obj;
        if (this.cellId != other.getCellId()) {
            return false;
        }
        if (this.idIso != other.getIdIso()) {
            return false;
        }
        return true;
    }

    public int getCellId() {
        return cellId;
    }

    public void setCellId(int cellId) {
        this.cellId = cellId;
    }

    public short getIdIso() {
        return idIso;
    }

    public void setIdIso(short idIso) {
        this.idIso = idIso;
    }

    public GroupKey(int cellId, short idIso) {
        this.cellId = cellId;
        this.idIso = idIso;
    }
}

/**
 * This function is the post process of ST_TriangleContouring function. It will merge geometry of the same category.
 */
public class ST_TableGeometryUnion extends AbstractTableFunction {
    private Logger logger = Logger.getLogger(ST_TableGeometryUnion.class);
    @Override
    public DataSet evaluate(DataSourceFactory sqldsf, DataSet[] dss, Value[] values, ProgressMonitor pm) throws FunctionException {
        //First pass
        //Aggregation of row line number corresponding to groups.
        ProgressionOrbisGisManager pmManager=null;
        try {

            pm.startTask("Grouping and Fusion of geometries", 100);
            int spatialFieldIndex;
            final DataSet sds = dss[0];
            int cellidFieldIndex=sds.getMetadata().getFieldIndex("cellid");
            int idisoFieldIndex=sds.getMetadata().getFieldIndex("idiso");
            if (1 == values.length) {
                    // if no spatial's field's name is provided, the default (first)
                    // one is arbitrarily chosen.
                   spatialFieldIndex = sds.getMetadata().getFieldIndex(values[0].toString());
            } else {
                   spatialFieldIndex = MetadataUtilities.getSpatialFieldIndex(sds.getMetadata());
            }
            final DiskBufferDriver driver = new DiskBufferDriver(sqldsf,
					getMetadata(null));


            //Declaration of the HashMap that will keep the lines number for each geometries of the same category.
            HashMap<GroupKey,RowsUnionClassification> groups=new HashMap<GroupKey,RowsUnionClassification>();
            long rowCount = sds.getRowCount();
            //Instanciate the progression manager
            pmManager = new ProgressionOrbisGisManager(
					2, pm);
            ProgressionProcess progressionInfo=pmManager.nextSubProcess(rowCount);
            pmManager.start();
            // Fill the HashMap with rowCount line numbers
            for (long i = 0; i < rowCount; i++) {
                if(pm.isCancelled()) {
                    throw new FunctionException("Canceled by user");
                }
                int cellid=sds.getFieldValue(i,cellidFieldIndex).getAsInt();
                short idiso=sds.getFieldValue(i,idisoFieldIndex).getAsShort();
                GroupKey thekey=new GroupKey(cellid,idiso);
                RowsUnionClassification res=groups.get(thekey);
                if(res==null) {
                    groups.put(thekey, new RowsUnionClassification((int) i));
                } else {
                    res.addRow((int) i);
                }
                progressionInfo.nextSubProcessEnd();
            }


            //Step 2, Union of geometries
            ProgressionProcess progressionInfoUnion=pmManager.nextSubProcess(groups.size());
            Iterator<Entry<GroupKey,RowsUnionClassification>> it = groups.entrySet().iterator();
            //For each distinct group
            GeometryFactory geometryFactory = new GeometryFactory();
            for(Map.Entry<GroupKey,RowsUnionClassification> pairs : groups.entrySet()) {
                if(pm.isCancelled()) {
                    throw new FunctionException("Canceled by user");
                }
                RowsUnionClassification curClassification = pairs.getValue();
                int sizeof=0;
                for(RowInterval interval : curClassification) {
                    sizeof+=interval.getEnd()-interval.getBegin();
                }
                List<Polygon> toUnite=new ArrayList<Polygon>(sizeof);
                for(RowInterval interval : curClassification) {
                    for(int rowid=interval.getBegin();rowid<interval.getEnd();rowid++) {
                        if(pm.isCancelled()) {
                            throw new FunctionException("Canceled by user");
                        }
                        Geometry unknownGeo=sds.getFieldValue(rowid,spatialFieldIndex).getAsGeometry();
                        if(unknownGeo instanceof Polygon) {
                            toUnite.add((Polygon)unknownGeo);
                        } else {
                            throw new FunctionException("Only polygons are accepted");
                        }
                    }
                }
                //Merge geometries
                Polygon[] allTri = new Polygon[toUnite.size()];
                MultiPolygon polygonCollection = geometryFactory.createMultiPolygon(toUnite.toArray(allTri));
                Geometry mergedGeom;
                try {
                    mergedGeom=polygonCollection.union();
                } catch (IllegalArgumentException e) {
                    //Union fails
                    throw new FunctionException("Union fails with geometry argument "+polygonCollection.toText(), e);
                }
                //Save the merged geometries into the driver
                Value[] row = new Value[3];
                row[0] = ValueFactory.createValue(mergedGeom);
                row[1] = ValueFactory.createValue(pairs.getKey().getCellId());
                row[2] = ValueFactory.createValue(pairs.getKey().getIdIso());
                driver.addValues(row);
                progressionInfoUnion.nextSubProcessEnd();
            }
            
            //Close all threads & files
            pmManager.stop();
            pm.endTask();
            driver.writingFinished();
            driver.open();
            return driver.getTable("main");
            } catch (DriverException e) {
                    throw new FunctionException(e);
            } finally {
                if(pmManager!=null) {
                    pmManager.stop();
                }
            }
    }

    @Override
    public Metadata getMetadata(Metadata[] mtdts) throws DriverException {
		return new DefaultMetadata(new Type[] {
				TypeFactory.createType(Type.GEOMETRY),
				TypeFactory.createType(Type.INT),
                                TypeFactory.createType(Type.SHORT)},
                                new String[] { "the_geom",
				"cellid",
                                "idiso"});
    }

    @Override
    public String getDescription() {
        return "This function is not generic,it post process the ST_TriangleContouring function. It will merge geometry of the same category (cellid and idiso).";
    }

    @Override
    public FunctionSignature[] getFunctionSignatures() {
        return new FunctionSignature[]{
                new TableFunctionSignature(TableDefinition.GEOMETRY,
                new TableArgument(TableDefinition.GEOMETRY)),
                new TableFunctionSignature(TableDefinition.GEOMETRY)
        };
    }

    @Override
    public String getName() {
        return "ST_TableGeometryUnion";
    }

    @Override
    public String getSqlOrder() {
        return "select * from ST_TableGeometryUnion(contouring_table);";
    }

}
