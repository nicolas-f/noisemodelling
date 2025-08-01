/**
 * NoiseModelling is a library capable of producing noise maps. It can be freely used either for research and education, as well as by experts in a professional use.
 * <p>
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software.
 * <p>
 * Official webpage : http://noise-planet.org/noisemodelling.html
 * Contact: contact@noise-planet.org
 */

package org.noise_planet.noisemodelling.propagation.cnossos;


import org.noise_planet.noisemodelling.pathfinder.profilebuilder.CutProfile;

/**
 * All the datas Path of Cnossos
 */
public class CnossosPath extends Path {


    public boolean favorable; // if true, favorable meteorological condition path
    public  double[] aAtm = new double[0];
    public  double[] aDiv = new double[0];
    public  double[] aRef = new double[0];
    public  double[] double_aBoundary = new double[0];
    //public  double[] double_aBoundaryF = new double[0];
    public  double[] aRetroDiff = new double[0]; // Alpha Retro Diffraction homogenous
    //public  double[] aRetroDiffF = new double[0]; // Alpha Retro Diffraction favorable
    public  double[] aGlobal = new double[0];
    //public double[] aGlobalF = new double[0];
    public double[] aDif = new double[0];
    //public double[] aDifF = new double[0];
    public double[] aGlobalL = new double[0];
    public double[] aSource = new double[0]; // directivity attenuation
    public double delta = Double.MAX_VALUE;
    //public double deltaF= Double.MAX_VALUE;
    public double deltaPrime = Double.MAX_VALUE;
    //public double deltaPrimeF= Double.MAX_VALUE;
    public double deltaSPrimeR = Double.MAX_VALUE;
    public double deltaSRPrime = Double.MAX_VALUE;
    public ABoundary aBoundary = new ABoundary();
    //public ABoundary aBoundaryF = new ABoundary();
    public GroundAttenuation groundAttenuation = new GroundAttenuation();
    //public double deltaSPrimeRF= Double.MAX_VALUE;
    //public double deltaSRPrimeF= Double.MAX_VALUE;
    public double e =0;
    //public double deltaRetroH= Double.MAX_VALUE;
    //public double deltaRetroF= Double.MAX_VALUE;

    public void init(int size) {
        this.aAtm = new double[size];
        this.aDiv = new double[size];
        this.aRef = new double[size];
        this.double_aBoundary = new double[size];
        //this.double_aBoundaryF = new double[size];
        this.aGlobal = new double[size];
        //this.aGlobalF = new double[size];
        this.aDif = new double[size];
        //this.aDifF = new double[size];
        this.aGlobalL = new double[size];
        this.aSource = new double[size];
        this.aRetroDiff = new double[size];
        //this.aRetroDiffF = new double[size];
    }

    public CnossosPath() {
    }

    public CnossosPath(CutProfile cutProfile) {
        super(cutProfile);
    }

    public CnossosPath(CnossosPath other) {
        super(other);
        this.favorable = other.favorable;
        this.aAtm = other.aAtm;
        this.aDiv = other.aDiv;
        this.aRef = other.aRef;
        this.double_aBoundary = other.double_aBoundary;
        //this.double_aBoundaryF = other.double_aBoundaryF;
        this.aRetroDiff = other.aRetroDiff;
        //this.aRetroDiffF = other.aRetroDiffF;
        this.aGlobal = other.aGlobal;
        //this.aGlobalF = other.aGlobalF;
        this.aDif = other.aDif;
        //this.aDifF = other.aDifF;
        this.aGlobalL = other.aGlobalL;
        this.aSource = other.aSource;
        this.delta = other.delta;
        //this.deltaF = other.deltaF;
        this.deltaPrime = other.deltaPrime;
        //this.deltaPrimeF = other.deltaPrimeF;
        this.deltaSPrimeR = other.deltaSPrimeR;
        //this.deltaSRPrimeH = other.deltaSRPrimeH;
        this.aBoundary = other.aBoundary;
        //this.aBoundaryF = other.aBoundaryF;
        this.groundAttenuation = other.groundAttenuation;
        //this.deltaSPrimeRF = other.deltaSPrimeRF;
        //this.deltaSRPrimeF = other.deltaSRPrimeF;
        this.e = other.e;
        //this.deltaRetroH = other.deltaRetroH;
        //this.deltaRetroF = other.deltaRetroF;
    }
    public boolean isFavorable() {
        return favorable;
    }

    public void setFavorable(boolean favorable) {
        this.favorable = favorable;
    }

    public static class ABoundary {
        public double[] deltaDiffSR;
        public double[] aGroundSO;
        public double[] aGroundOR;
        public double[] deltaDiffSPrimeR;
        public double[] deltaDiffSRPrime;
        public double[] deltaGroundSO;
        public double[] deltaGroundOR;
        public double[] aDiff;

        private boolean init = false;

        public void init(int freqCount) {
            if(!init) {
                deltaDiffSR = new double[freqCount];
                aGroundSO = new double[freqCount];
                aGroundOR = new double[freqCount];
                deltaDiffSPrimeR = new double[freqCount];
                deltaDiffSRPrime = new double[freqCount];
                deltaGroundSO = new double[freqCount];
                deltaGroundOR = new double[freqCount];
                aDiff = new double[freqCount];
                init = true;
            }
        }
    }


    public static class GroundAttenuation {
        public double[] w;
        public double[] cf;
        public double[] aGround;
        //public double[] wF;
        //public double[] cfF;
        //public double[] aGroundF;

        public void init(int size) {
            w = new double[size];
            cf = new double[size];
            aGround = new double[size];
           // wF = new double[size];
            //cfF = new double[size];
            //aGroundF = new double[size];
        }

        public GroundAttenuation() {
        }

        public GroundAttenuation(GroundAttenuation other) {
            this.w = other.w;
            this.cf = other.cf;
            this.aGround = other.aGround;
            //this.wF = other.wF;
            //this.cfF = other.cfF;
            //this.aGroundF = other.aGroundF;
        }
    }
}