package org.noise_planet.noisemodelling.emission;


public class SourceDirectivity {


    //Ref. 2.3.15 2.3.16

    public enum TrainNoiseSource {
        ROLLING,
        IMPACT,
        SQUEAL,
        BRAKING,
        FANS,
        AERODYNAMIC,
        BRIDGE
    }

    public static Double GetDirectionAttenuation(TrainNoiseSource noiseSource, int height_index, double phi, double theta, double frequency) {
        if(noiseSource == TrainNoiseSource.BRIDGE) {
            return 0.0;
        }
        double attHorizontal = 10 * Math.log10(0.01 + 0.99 * Math.pow(Math.sin(phi), 2));
        double attVertical = 0;
        if(height_index == 1) {
            if(theta > 0 && theta < Math.PI / 2.0) {
                attVertical = (40.0 / 3.0)
                        * (2.0 / 3.0 * Math.sin(2 * theta) - Math.sin(theta))
                        * Math.log10((frequency + 600.0) / 200.0);
            }
        } else if(height_index == 2){
            if(theta < 0) { // for aerodynamic effect only
                attVertical = 10 * Math.log10(Math.pow(Math.sin(theta), 2));
            }
        }
        return attHorizontal + attVertical;
    }
}
