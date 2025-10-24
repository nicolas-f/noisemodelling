#! /bin/bash

# Run the get started tutorial
# https://noisemodelling.readthedocs.io/en/latest/Get_Started_Tutorial.html

# Step 4: Upload files to database
# create (or load existing) database and load a shape file into the database
./bin/Scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Import_File.groovy -pathFile resources/org/noise_planet/noisemodelling/scripts/ground_type.shp
./bin/Scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Import_File.groovy -pathFile resources/org/noise_planet/noisemodelling/scripts/wps/buildings.shp
./bin/Scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Import_File.groovy -pathFile resources/org/noise_planet/noisemodelling/scripts/receivers.shp
./bin/Scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Import_File.groovy -pathFile resources/org/noise_planet/noisemodelling/scripts/ROADS2.shp
./bin/Scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Import_File.groovy -pathFile resources/org/noise_planet/noisemodelling/scripts/dem.geojson


# Step 5: Run Calculation
./bin/Scripts -w ./ -s noisemodelling/scripts/NoiseModelling/Noise_level_from_traffic.groovy -tableBuilding BUILDINGS -tableRoads ROADS2 -tableReceivers RECEIVERS -tableDEM DEM -tableGroundAbs GROUND_TYPE

# Step 6: Export (& see) the results
./bin/Scripts -w ./ -s noisemodelling/scripts/Import_and_Export/Export_Table.groovy -exportPath RECEIVERS_LEVEL.shp -tableToExport RECEIVERS_LEVEL