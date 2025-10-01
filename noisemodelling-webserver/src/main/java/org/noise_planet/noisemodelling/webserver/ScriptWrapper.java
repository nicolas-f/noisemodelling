package org.noise_planet.noisemodelling.webserver;

import java.io.File;
import java.util.*;

public class ScriptWrapper {
    public  String BASE_PACKAGE; //= "org.noise_planet.noisemodelling.scripts";

    public  String SCRIPTS_ROOT;// ="./noisemodelling-scripts/src/main/groovy/"+ BASE_PACKAGE.replace('.', '/');

    public ScriptWrapper(String basePackage,String scriptsRoot) {
        this.BASE_PACKAGE = basePackage;
        this.SCRIPTS_ROOT = scriptsRoot+this.BASE_PACKAGE.replace('.', '/');
    }

    public  Map<String, List<String>> scanScriptsGrouped() {
        //System.out.println("Scan des scripts a partir de : " + SCRIPTS_ROOT);
        Map<String, List<String>> grouped = new TreeMap<>();
        File baseDir = new File(this.SCRIPTS_ROOT);
        if (!baseDir.exists()) return grouped;

        scanRecursive(baseDir, this.BASE_PACKAGE, grouped);
        return grouped;
    }

    private void scanRecursive(File dir, String currentPackage, Map<String, List<String>> grouped) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            System.out.println("file name :" +f.getName());
            if (f.isDirectory()) {
                scanRecursive(f, currentPackage + "." + f.getName(), grouped);
            } else if (f.getName().endsWith(".groovy")) {
                String scriptName = f.getName().replace(".groovy", "");
                String groupName = currentPackage.substring(this.BASE_PACKAGE.length() + 1);
                if (groupName.isEmpty()) groupName = "Root";
                grouped.computeIfAbsent(groupName, k -> new ArrayList<>()).add(scriptName);
            }
        }
    }

}
