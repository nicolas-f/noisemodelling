#! /bin/bash

# Run the get started tutorial
# https://noisemodelling.readthedocs.io/en/latest/Get_Started_Tutorial.html

# Step 4: Upload files to database
# create (or load existing) database and load a shape file into the database
./bin/scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Import_File.groovy -pathFile resources/scripts/ground_type.shp
./bin/scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Import_File.groovy -pathFile resources/scripts/wps/buildings.shp
./bin/scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Import_File.groovy -pathFile resources/scripts/receivers.shp
./bin/scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Import_File.groovy -pathFile resources/scripts/ROADS2.shp
./bin/scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Import_File.groovy -pathFile resources/scripts/dem.geojson


# Step 5: Run Calculation
./bin/scripts -w ./ -s noisemodelling/scripts/NoiseModelling/Noise_level_from_traffic.groovy -tableBuilding BUILDINGS -tableRoads ROADS2 -tableReceivers RECEIVERS -tableDEM DEM -tableGroundAbs GROUND_TYPE

# Step 6: Export (& see) the results
./bin/scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Export_Table.groovy -exportPath RECEIVERS_LEVEL.shp -tableToExport RECEIVERS_LEVEL