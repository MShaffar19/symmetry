/*
 * BioJava development code
 * 
 * This code may be freely distributed and modified under the terms of the GNU Lesser General Public Licence. This
 * should be distributed with the code. If you do not have a copy, see:
 * 
 * http://www.gnu.org/copyleft/lesser.html
 * 
 * Copyright for this code is held jointly by the individual authors. These should be listed in @author doc comments.
 * 
 * For more information on the BioJava project and its aims, or to join the biojava-l mailing list, visit the home page
 * at:
 * 
 * http://www.biojava.org/
 * 
 * Created on 2013-02-18
 */
package org.biojava3.structure.align.symm.census2;

import java.io.IOException;
import java.util.concurrent.Callable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.biojava.bio.structure.Atom;
import org.biojava.bio.structure.Structure;
import org.biojava.bio.structure.StructureException;
import org.biojava.bio.structure.StructureTools;
import org.biojava.bio.structure.align.model.AFPChain;
import org.biojava.bio.structure.align.util.AFPChainScorer;
import org.biojava.bio.structure.align.util.AtomCache;
import org.biojava.bio.structure.align.util.RotationAxis;
import org.biojava.bio.structure.jama.Matrix;
import org.biojava.bio.structure.scop.ScopDatabase;
import org.biojava.bio.structure.scop.ScopDescription;
import org.biojava.bio.structure.scop.ScopDomain;
import org.biojava.bio.structure.scop.ScopFactory;
import org.biojava.bio.structure.secstruc.SecStruc;
import org.biojava.bio.structure.secstruc.SecStrucGroup;
import org.biojava.bio.structure.secstruc.SecStrucState;
import org.biojava3.structure.align.symm.CeSymm;
import org.biojava3.structure.align.symm.census2.Census.AlgorithmGiver;
import org.biojava3.structure.align.symm.protodomain.Protodomain;

/**
 * One run of CE-Symm in the census.
 * 
 * @author dmyerstu
 */
public class CensusJob implements Callable<Result> {

	public static class FullInfo {
		private AFPChain afpChain;
		private Result result;

		public FullInfo(AFPChain afpChain, Result result) {
			super();
			this.afpChain = afpChain;
			this.result = result;
		}

		public AFPChain getAfpChain() {
			return afpChain;
		}

		public Result getResult() {
			return result;
		}

		public void setAfpChain(AFPChain afpChain) {
			this.afpChain = afpChain;
		}

		public void setResult(Result result) {
			this.result = result;
		}
	}

	private static final Logger logger = LogManager.getLogger(CensusJob.class.getPackage().getName());

	private AlgorithmGiver algorithm;
	private Significance significance;

	private String name;
	private Integer count;
	private boolean isScopDomain;
	
	private AtomCache cache;
	private ScopDatabase scop;

	private boolean calcFractionHelical;
	private boolean storeAfpChain;
	private AFPChain afpChain;

	private Long timeTaken;

	public static CensusJob forScopId(AlgorithmGiver algorithm, Significance significance, String name, int count,
			AtomCache cache, ScopDatabase scop) {
		CensusJob job = new CensusJob(algorithm, significance);
		job.setCache(cache);
		job.setScop(scop);
		job.setName(name);
		job.setIsScopDomain(true);
		return job;
	}

	/**
	 * Preferred method for web-based calls.
	 */
	public static FullInfo runOn(String name, AlgorithmGiver algorithm, Significance sig, AtomCache cache,
			ScopDatabase scop) {
		CensusJob job = new CensusJob(algorithm, sig);
		job.setScop(scop);
		job.setCache(cache);
		job.setStoreAfpChain(true);
		job.setName(name);
		job.setCount(0);
		Result r = job.call();
		return new FullInfo(job.getAfpChain(), r);
	}

	private static boolean sanityCheckPreAlign(Atom[] ca1, Atom[] ca2) {
		if (ca1 == ca2) return false;
		if (ca1[0].getGroup().getChain().getParent() == ca2[0].getGroup().getChain().getParent()) return false;
		return true;
	}

	public CensusJob(AlgorithmGiver algorithm, Significance significance) {
		this.algorithm = algorithm;
		this.significance = significance;
	}

	@Override
	public Result call() {

		if (name == null || count == null) throw new IllegalStateException("Must set name and count first.");

		// first, get the atoms
		Atom[] ca1, ca2;
		Structure structure;
		logger.debug("Getting atoms for " + name + " (job #" + count + ")");
		try {
			if (cache == null) cache = new AtomCache();
			if (isScopDomain) {
				if (scop == null) scop = ScopFactory.getSCOP();
				structure = cache.getStructureForDomain(name, scop);
			} else {
				structure = cache.getStructure(name);
			}
			// ca1 = cache.getAtoms(name);
			ca1 = StructureTools.getAtomCAArray(structure);
			ca2 = StructureTools.cloneCAArray(ca1);
		} catch (Exception e) {
			logger.error("Could not create the atom arrays for " + name + ": " + e.getMessage(), e);
			return null;
		}
		logger.debug("Got " + ca1.length + " atoms (job #" + count + ")");

		// run the alignment
		AFPChain afpChain = null;
		logger.debug("Running CE-Symm (job #" + count + ")");
		try {
			afpChain = findSymmetry(name, ca1, ca2);
		} catch (Exception e) {
			logger.error("Failed running CE-Symm on " + name + ": " + e.getMessage(), e);
			return convertResult(null, null, null, null, name, null, null);
		}
		if (afpChain == null || afpChain.getOptAln() == null) {
			logger.debug("CE-Symm returned null (job #" + count + ")");
			return convertResult(null, null, null, null, name, null, null);
		}

		// there are two cases in which we know there is no symmetry
		if (afpChain.getBlockNum() != 2) {
			logger.debug("CE-Symm returned a result with " + afpChain.getBlockNum() + " block(s) (job #" + count + ")");
			return convertResult(afpChain, false, null, null, name, null, null);
		}
		if (afpChain.getAlnLength() < 1) {
			logger.debug("CE-Symm returned an empty alignment (job #" + count + ")");
			return convertResult(afpChain, false, null, null, name, null, null);
		}

		if (significance.isPossiblySignificant(afpChain)) {

			logger.debug("Result is significant (job #" + count + ")");

			// initializes these to null, then try setting them separately
			Boolean isSignificant = null;
			Integer order = null;
			Float angle = null;
			Protodomain protodomain = null;

			// first try to find the protodomain
			logger.debug("Finding protodomain (job #" + count + ")");
			try {
				protodomain = Protodomain.fromSymmetryAlignment(afpChain, ca2, 1, cache);
				logger.debug("Protodomain is " + protodomain + " (job #" + count + ")");
			} catch (Exception e) {
				logger.warn("Could not create protodomain because " + e.getMessage(), e);
			}

			// now try to find the order
			logger.debug("Finding order (job #" + count + ")");
			try {
				order = CeSymm.getSymmetryOrder(afpChain);
				logger.debug("Order is " + order + " (job #" + count + ")");
			} catch (Exception e) {
				logger.error("Failed to determine the order of symmetry on " + name + ": " + e.getMessage(), e);
			}

			// now try to find the angle
			logger.debug("Finding angle (job #" + count + ")");
			try {
				angle = (float) getAngle(afpChain, ca1, ca2);
				logger.debug("Angle is " + angle + " (job #" + count + ")");
			} catch (Exception e) {
				logger.error("Failed to determine the angle on " + name + ": " + e.getMessage(), e);
			}

			// now determine whether it's significant
			logger.debug("Determining significance (job #" + count + ")");
			try {
				isSignificant = significance.isSignificant(protodomain, order, angle, afpChain);
			} catch (RuntimeException e) {
				logger.error("Failed to determine the signifcance of " + name + ": " + e.getMessage(), e);
			}

			// now find fraction helical
			Float fractionHelical = null;
			if (calcFractionHelical) {
				try {
					fractionHelical = getFractionHelical(structure);
				} catch (Exception e) {
					logger.error("Could not assign secondary structure to " + name + " (job #" + count + ")", e);
				}
			}

			return convertResult(afpChain, isSignificant, order, protodomain.toString(), name, angle, fractionHelical);

		} else { // trying this would take too long
			logger.debug("Result is not significant (job #" + count + ")");
			return convertResult(afpChain, false, null, null, name, null, null);
		}
	}

	public AFPChain getAfpChain() {
		return afpChain;
	}

	public Long getTimeTaken() {
		return timeTaken;
	}

	/**
	 * Discards the stored AFPChain to free heap memory.
	 */
	public void nullifyAfpChain() {
		afpChain = null;
	}

	public void setCache(AtomCache cache) {
		this.cache = cache;
	}

	public void setCalcFractionHelical(boolean calcFractionHelical) {
		this.calcFractionHelical = calcFractionHelical;
	}

	public void setCount(int count) {
		this.count = count;
	}

	public void setIsScopDomain(boolean isScopDomain) {
		this.isScopDomain = isScopDomain;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setScop(ScopDatabase scop) {
		this.scop = scop;
	}

	/**
	 * Store the raw AFPChain in {@link #getAfpChain()}. Requires a lot of memory. Not recommended.
	 * 
	 * @param storeAfpChain
	 */
	public void setStoreAfpChain(boolean storeAfpChain) {
		this.storeAfpChain = storeAfpChain;
	}

	private Result convertResult(AFPChain afpChain, Boolean isSymmetric, Integer order, String protodomain,
			String name, Float angle, Float fractionHelical) {

		if (storeAfpChain) this.afpChain = afpChain;

		Result r = new Result();

		r.setRank(count);
		r.setScopId(name);
		if (isScopDomain) {
			ScopDomain domain = scop.getDomainByScopID(name);
			ScopDescription superfamily = scop.getScopDescriptionBySunid(domain.getSuperfamilyId());
			r.setClassification(superfamily.getClassificationId());
			r.setSunId(domain.getSunid());
			final String description = superfamily.getDescription();
			r.setDescription(description);
		}
		r.setProtodomain(protodomain);

		r.setAlignment(new Alignment(afpChain));
		r.setIsSignificant(isSymmetric);
		r.setFractionHelical(fractionHelical);
		try {
			if (afpChain.getAlnLength() > 0) r.setAxis(new Axis(new RotationAxis(afpChain)));

		} catch (RuntimeException e) {

			logger.error("Could not get rotation axis for " + name + "(job #" + count + ")", e);

		} catch (Exception e) {

			e.printStackTrace();

			logger.error("Alignment for " + name + " is empty (job #" + count + ")", e);

			if (angle != null) { // if the axis can't be found, at least we do have the angle
				Axis axis = new Axis();
				axis.setTheta((float) angle);
				r.setAxis(axis);
			}
		}
		r.setOrder(order);

		return r;
	}

	private AFPChain findSymmetry(String name, Atom[] ca1, Atom[] ca2) throws StructureException, IOException {
		if (!sanityCheckPreAlign(ca1, ca2)) throw new RuntimeException("Can't align using same structure.");
		long startTime = System.currentTimeMillis();
		AFPChain afpChain = algorithm.getAlgorithm().align(ca1, ca2);
		long endTime = System.currentTimeMillis();
		timeTaken = endTime - startTime;
		if (afpChain == null) return null;
		afpChain.setName1(name);
		afpChain.setName2(name);
		double realTmScore = AFPChainScorer.getTMScore(afpChain, ca1, ca2);
		afpChain.setTMScore(realTmScore);
		return afpChain;
	}

	/**
	 * Returns the <em>magnitude</em> of the angle between the first and second blocks of {@code afpChain}, measured in
	 * degrees. This is always a positive value (unsigned).
	 * 
	 * @param afpChain
	 * @param ca1
	 * @param ca2
	 * @return
	 */
	private double getAngle(AFPChain afpChain, Atom[] ca1, Atom[] ca2) {
		Matrix rotation = afpChain.getBlockRotationMatrix()[0];
		return Math.acos(rotation.trace() - 1) * 180 / Math.PI;
	}

	private float getFractionHelical(Structure structure) throws StructureException {
		SecStruc ss = new SecStruc();
		ss.assign(structure);
		SecStrucGroup[] ssgs = ss.getGroups();
		int nHelix = 0;
		for (SecStrucGroup ssg : ssgs) {
			SecStrucState state = (SecStrucState) ssg.getProperty("secstruc");
			if (state.getSecStruc().isHelixType()) {
				nHelix++;
			}
		}
		double fractionHelix = (double) nHelix / (double) ssgs.length;
		return (float) fractionHelix;
	}

}
