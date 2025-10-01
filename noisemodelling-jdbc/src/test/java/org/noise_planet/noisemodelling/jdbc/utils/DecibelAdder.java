package org.noise_planet.noisemodelling.jdbc.utils;

public class DecibelAdder {

    public static void main(String[] args) {
        double[] array1 = {-55.737259848347385, -55.79354914158758, -55.91661560109041, -56.088735634738775, -56.42535417569815, -57.59415450713864, -62.09054957262068, -78.4584792469193};
        double[] array2 = new double[]{93, 93, 93, 93, 93, 93, 93, 93};
        double[] result = sumDbArray(array1, array2);

        // Afficher les résultats
        for (int i = 0; i < result.length; i++) {
            System.out.println(" dB est " + result[i] + " dB");
        }
    }

    public static double[] sumDbArray(double[] array1, double[] array2) {
        if (array1.length != array2.length) {
            throw new IllegalArgumentException("Not same size array");
        }
        double[] sum = new double[array1.length];
        for (int i = 0; i < array1.length; i++) {
            //sum[i] = wToDba(dbaToW(array1[i]) + dbaToW(array2[i]));
            sum[i] = 10 * Math.log10(Math.pow(10, array1[i] / 10) + Math.pow(10, array2[i] / 10));
        }
        return sum;
    }

}
