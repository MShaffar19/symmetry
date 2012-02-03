
package org.biojava3.structure.align.symm.quarternary;

import java.util.ArrayList;
import java.util.List;
import javax.vecmath.Matrix4d;
import javax.vecmath.Point3d;
import javax.vecmath.Vector3d;

/**
 *
 * @author Peter
 */
public class QuatSuperpositionScorer {
    private Subunits subunits = null;
    private List<DistanceBox<Integer>> distanceBoxes = new ArrayList<DistanceBox<Integer>>();

    public QuatSuperpositionScorer(Subunits subunits) {
        this.subunits = subunits;
        setupDistanceBoxes();
    }
    
    public double calcCalphaRMSD(Matrix4d transformation, List<Integer> permutation) {
    	// rmsd can only be calculated if the permuted subunits
    	// are identical in sequence
 //   	System.out.println("transformation: " + transformation);
    	if (! hasEquivalentSubunits(permutation)) {
    		return -1.0;
    	}
    	
        int len = 0;
        int cbLen = 0;
        double distanceSq = 0;
        double cbDistanceSq = 0;
        Point3d t = new Point3d();
        List<Point3d[]> traces = subunits.getTraces();
        List<Point3d[]> cbCoords = subunits.getCBCoords();
        int reverseAlignments = 0;
        
        for (int i = 0; i < traces.size(); i++) {
            Point3d[] orig = traces.get(i);
            len += orig.length;
                    
   //         System.out.println("compare: " + i + " - " + permutation.get(i));

            // transform each CA trace
            Point3d[] perm = traces.get(permutation.get(i));
            for (int j = 0; j < perm.length; j++) {
                t.set(perm[j]);
                transformation.transform(t);
                distanceSq += orig[j].distanceSquared(t);
//                if (distanceSq > 16) {
 //                   System.out.println(orig[j] + " - " + t);
 //               }
            }
            
            // calculate CB RMSD
            Point3d[] cbOrig = cbCoords.get(i);
            cbLen += cbOrig.length;
                    
   //         System.out.println("compare: " + i + " - " + permutation.get(i));

            // transform each CB trace
            Point3d[] cbPerm = cbCoords.get(permutation.get(i));
            for (int j = 0; j < cbPerm.length; j++) {
                t.set(cbPerm[j]);
                transformation.transform(t);
                cbDistanceSq += cbOrig[j].distanceSquared(t);
//                if (distanceSq > 16) {
 //                   System.out.println(orig[j] + " - " + t);
 //               }
            }
         // N -> Cterminal vector for original coordinates
            
   //         int first = 4; 
            int first = 10; 
  //          int last = orig.length - 5;
            int last = orig.length - 11;
            if (first+10 >= orig.length) {
            	first = 0;
            	last = orig.length-1;
            }
            Vector3d vorig = new Vector3d(orig[first]);
            vorig.sub(orig[last]);
            vorig.normalize();
            // N -> Cterminal vector for transformed coordinates
            t.set(perm[first]);
            transformation.transform(t);
            Vector3d vtrans = new Vector3d(t);
            t.set(perm[last]);
            transformation.transform(t);
            vtrans.sub(t);
            vtrans.normalize();
           
     //       System.out.println("Reverse terminal check: " +  vorig.dot(vtrans) + "dist: " + distanceFirst);
      //      if (vorig.dot(vtrans) < 0.5) {
            // TODO is 0.8 too strict?
            if (vorig.dot(vtrans) < 0.5) {
 //           	System.out.println("Found reverse alignment: N->C to C->N terminal: " + vorig.dot(vtrans) + " dist: " + distanceFirst);
            	reverseAlignments++;
            }
        }
        
        double caRmsd = Math.sqrt(distanceSq/len);
        double cbRmsd = 0.0f;
        if (cbLen > 0) {
            cbRmsd = Math.sqrt(cbDistanceSq/cbLen);
        }
   //     System.out.println("caRMSD: " + caRmsd + " cbRMSD: " + cbRmsd);
        boolean reverse = false;
        if (reverseAlignments > 0) {
        	reverse = true;
   //     	System.out.println("# reverse alignments: " + reverseAlignments + " total: " + traces.size());
        }
        if (cbRmsd > caRmsd + 1.0f) {
        	reverse = true;
        	System.out.println("# reverse alignment: CA RMSD: " + caRmsd + " CB RMSD: " + cbRmsd);
        }
        if (reverse) {
        	caRmsd = -caRmsd;
        }
        return caRmsd;
    }
    
    /**
     * Returns true if the specified permutation permutes 
     * equivalent subunits. This requires 100% sequence identity,
     * which is checked by comparing the sequence clusters.
     * 
     * @param permutation
     * @return
     */
    private boolean hasEquivalentSubunits(List<Integer> permutation) {
    	List<Integer> sequenceClusterIds = subunits.getSequenceClusterIds();
  //  	System.out.println("seq ids: " + sequenceClusterIds);
    	for (int i = 0; i < sequenceClusterIds.size(); i++) {
    		int j = permutation.get(i);
    		if (sequenceClusterIds.get(i) != sequenceClusterIds.get(j)) {
    			return false;
    		}
    	}
    	return true;
    }

    /**
     * Calculates a variant of the GDT-TS (Global Distance Test Total Score)
     * describing the percentage of residues percent of residues that can fit
     * under 1, 2, 4, and 8 Angstrom distance cutoffs. Distances are calculated
     * between the two closest pairs of points, therefore the 'Min' in the name.
     *
     * Zemla, A. (2003) LGA: a method for finding 3D similarities in protein
     * structures. Nucleic Acids Research 31: 3370. doi:10.1093/nar/gkg571
     * http://www2.predictioncenter.org/calculations/lga.htm
     *
     * @param solution
     * @return
     */
    public double calcGtsMinScore(Matrix4d transformation, List<Integer> permutation) {
        int len = 0;
        int contacts = 0;
        List<Point3d[]> traces = subunits.getTraces();
        for (int i = 0; i < traces.size(); i++) {
            Point3d[] orig = traces.get(i);
            len += orig.length;

            // transform each CA trace
            Point3d[] perm = traces.get(permutation.get(i));
            Point3d[] transformed = new Point3d[perm.length];

            for (int j = 0; j < perm.length; j++) {
                Point3d t = new Point3d(perm[j]);
                transformation.transform(t, t);
                transformed[j] = t;
            }

            // sum up GTS contacts for each CA trace
            contacts += calcGtsMinContacts(i, transformed);
        }
        // calculate the average percentage of residues within
        // the 4 distance thresholds
        return contacts * 100 / 4 / len;
    }
 
    /**
     * Calculates the number of contacts within 1, 2, 4, and 8 Angstroms
     * between the original and the transformed coordinates. Distances
     * are calculated between the two closest ('Min') pairs of points.
     *
     * @param x original coordinates
     * @param y transformed coordinates
     */
    public int calcGtsMinContacts(int i, Point3d[] x) {
        DistanceBox<Integer> box = distanceBoxes.get(i);
        List<Point3d[]> traces = subunits.getTraces();
        Point3d[] originalCoords = traces.get(i);
        int contacts = 0;

        for (Point3d px : x) {
            double minDist = Double.MAX_VALUE;
            List<Integer> neighbors = box.getNeighborsWithCache(px);

            for (int j : neighbors) {
                minDist = Math.min(minDist, px.distanceSquared(originalCoords[j]));
            }

            // # contacts under <= 8 Angstroms
            if (minDist > 64) {
                continue;
            }
            contacts++;

            // # contacts under <= 4 Angstroms
            if (minDist > 16) {
                continue;
            }
            contacts++;

            // # contacts under <= 2 Angstroms
            if (minDist > 4) {
                continue;
            }
            contacts++;

            // # contacts under <= 1 Angstroms
            if (minDist > 1) {
                continue;
            }
            contacts++;
        }
        return contacts;
    }

    private void setupDistanceBoxes() {
        for (Point3d[] trace: subunits.getTraces()) {
            DistanceBox<Integer> box = new DistanceBox<Integer>(8); // set threshold to 8 Angstroms
            for (int i = 0; i < trace.length; i++) {
                box.addPoint(trace[i], i);
            }
            distanceBoxes.add(box);
        }
    }
}