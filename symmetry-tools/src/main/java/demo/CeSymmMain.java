package demo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.vecmath.Point3d;
import javax.vecmath.Vector3d;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.biojava.nbio.structure.Atom;
import org.biojava.nbio.structure.Structure;
import org.biojava.nbio.structure.StructureException;
import org.biojava.nbio.structure.StructureIdentifier;
import org.biojava.nbio.structure.StructureTools;
import org.biojava.nbio.structure.align.ce.CeParameters.ScoringStrategy;
import org.biojava.nbio.structure.align.client.StructureName;
import org.biojava.nbio.structure.align.multiple.MultipleAlignment;
import org.biojava.nbio.structure.align.multiple.util.MultipleAlignmentScorer;
import org.biojava.nbio.structure.align.multiple.util.MultipleAlignmentWriter;
import org.biojava.nbio.structure.align.util.AtomCache;
import org.biojava.nbio.structure.align.util.CliTools;
import org.biojava.nbio.structure.align.util.RotationAxis;
import org.biojava.nbio.structure.align.util.UserConfiguration;
import org.biojava.nbio.structure.contact.Pair;
import org.biojava.nbio.structure.io.LocalPDBDirectory.ObsoleteBehavior;
import org.biojava.nbio.structure.io.util.FileDownloadUtils;
import org.biojava.nbio.structure.scop.ScopFactory;
import org.biojava.nbio.structure.symmetry.gui.SymmetryDisplay;
import org.biojava.nbio.structure.symmetry.gui.SymmetryGui;
import org.biojava.nbio.structure.symmetry.internal.CESymmParameters;
import org.biojava.nbio.structure.symmetry.internal.CESymmParameters.OrderDetectorMethod;
import org.biojava.nbio.structure.symmetry.internal.CESymmParameters.RefineMethod;
import org.biojava.nbio.structure.symmetry.internal.CESymmParameters.SymmetryType;
import org.biojava.nbio.structure.symmetry.internal.CeSymm;
import org.biojava.nbio.structure.symmetry.internal.CeSymmResult;
import org.biojava.nbio.structure.symmetry.internal.SymmetryAxes;
import org.biojava.nbio.structure.symmetry.internal.SymmetryAxes.Axis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main executable for running CE-Symm. Run with -h for usage help, or without
 * arguments for interactive mode using the {@link SymmetryGui}.
 * 
 * @author Spencer Bliven
 * @author Aleix Lafita
 *
 */
public class CeSymmMain {

	private static final Logger logger = LoggerFactory
			.getLogger(CeSymmMain.class);

	public static void main(String[] args) throws InterruptedException {
		// Begin argument parsing
		final String usage = "[OPTIONS] [structures...]";
		final String header = "Determine the order for each structure, which may "
				+ "be PDB IDs, SCOP domains, or file paths. If none are given, the "
				+ "user will be prompted at startup.";
		Options options = getOptions();
		CommandLineParser parser = new DefaultParser();
		HelpFormatter help = new HelpFormatter();
		help.setOptionComparator(null); // prevent option sorting

		final CommandLine cli;
		try {
			cli = parser.parse(options, args, false);
		} catch (ParseException e) {
			logger.error("Error: " + e.getMessage());
			help.printHelp(usage, header, options, "");
			System.exit(1);
			return;
		}

		args = cli.getArgs();

		// help
		if (cli.hasOption("help")) {
			help.printHelp(usage, header, options, "");
			System.exit(0);
			return;
		}
		// version
		if (cli.hasOption("version")) {
			String version = CeSymmMain.class.getPackage()
					.getImplementationVersion();
			if (version == null || version.isEmpty()) {
				version = "(custom version)";
			}
			System.out.println("CE-Symm " + version);
			System.exit(0);
			return;
		}

		// input structures
		List<String> names;
		if (cli.hasOption("input")) {
			// read from file
			try {
				names = parseInputStructures(cli.getOptionValue("input"));
			} catch (FileNotFoundException e) {
				logger.error("Error: File not found: "
						+ cli.getOptionValue("input"));
				System.exit(1);
				return;
			}
			// append cli arguments
			names.addAll(Arrays.asList(args));
		} else {
			if (args.length == 0) {
				// No structures given; prompt user with GUI
				SymmetryGui.getInstance();
				return;
			} else {
				// take names from the command line arguments
				names = Arrays.asList(args);
			}
		}

		// Show jmol?
		// Default to false with --input or with >=10 structures
		boolean displayAlignment = !cli.hasOption("input") && names.size() < 10;
		if (cli.hasOption("noshow3d")) {
			displayAlignment = false;
		}
		if (cli.hasOption("show3d")) {
			displayAlignment = true;
		}

		// AtomCache options
		String pdbFilePath = null;
		if (cli.hasOption("pdbfilepath")) {
			pdbFilePath = cli.getOptionValue("pdbfilepath");
			pdbFilePath = FileDownloadUtils.expandUserHome(pdbFilePath);
		}

		// SCOP version
		if (cli.hasOption("scopversion")) {
			String scopVersion = cli.getOptionValue("scopversion");
			ScopFactory.setScopDatabase(scopVersion);
		}

		// Logger control
		if(cli.hasOption("verbose") ) {
			// Note that this bypasses SLF4J abstractions
			LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
			Configuration config = ctx.getConfiguration();
			LoggerConfig loggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME); 
			loggerConfig.setLevel(Level.INFO);
			ctx.updateLoggers();  // This causes all Loggers to refetch information from their LoggerConfig.
		}

		// Output formats
		List<CeSymmWriter> writers = new ArrayList<CeSymmWriter>();


		if (cli.hasOption("simple")) {
			String filename = cli.getOptionValue("simple");
			if(filename == null || filename.isEmpty())
				filename = "-"; // standard out
			try {
				writers.add(new SimpleWriter(filename));
			} catch (IOException e) {
				logger.error("Error: Ignoring file " + filename + ".");
				logger.error(e.getMessage());
			}
		}

		if (cli.hasOption("stats")) {
			String filename = cli.getOptionValue("stats");
			if(filename == null || filename.isEmpty())
				filename = "-"; // standard out
			try {
				writers.add(new StatsWriter(filename));
			} catch (IOException e) {
				logger.error("Error: Ignoring file " + filename + ".");
				logger.error(e.getMessage());
			}
		}

		if (cli.hasOption("tsv")) {
			String filename = cli.getOptionValue("tsv");
			if(filename == null || filename.isEmpty())
				filename = "-"; // standard out
			try {
				writers.add(new TSVWriter(filename));
			} catch (IOException e) {
				logger.error("Error: Ignoring file " + filename + ".");
				logger.error(e.getMessage());
			}
		}

		if (cli.hasOption("xml")) {
			String filename = cli.getOptionValue("xml");
			if(filename == null || filename.isEmpty())
				filename = "-"; // standard out
			try {
				writers.add(new XMLWriter(filename));
			} catch (IOException e) {
				logger.error("Error: Ignoring file " + filename + ".");
				logger.error(e.getMessage());
			}
		}


		if (cli.hasOption("fatcat")) {
			String filename = cli.getOptionValue("fatcat");
			if(filename == null || filename.isEmpty())
				filename = "-"; // standard out
			try {
				writers.add(new FatcatWriter(filename));
			} catch (IOException e) {
				logger.error("Error: Ignoring file " + filename + ".");
				logger.error(e.getMessage());
			}
		}

		if (cli.hasOption("fasta")) {
			String filename = cli.getOptionValue("fasta");
			if(filename == null || filename.isEmpty())
				filename = "-"; // standard out
			try {
				writers.add(new FastaWriter(filename));
			} catch (IOException e) {
				logger.error("Error: Ignoring file " + filename + ".");
				logger.error(e.getMessage());
			}
		}
		
		if (cli.hasOption("axes")) {
			String filename = cli.getOptionValue("axes");
			if(filename == null || filename.isEmpty())
				filename = "-"; // standard out
			try {
				writers.add(new AxesWriter(filename));
			} catch (IOException e) {
				logger.error("Error: Ignoring file " + filename + ".");
				logger.error(e.getMessage());
			}
		}

		// Default to SimpleWriter
		if( writers.isEmpty() && !cli.hasOption("noverbose") ) {
			try {
				writers.add(new SimpleWriter("-"));
			} catch (IOException e) {
				logger.error(e.getMessage());
			}
		}


		// Multithreading

		Integer threads = Runtime.getRuntime().availableProcessors();
		if (cli.hasOption("threads")) {
			threads = new Integer(cli.getOptionValue("threads"));
			if (threads < 1) {
				threads = 1;
			}
		}

		CESymmParameters params = new CESymmParameters();

		if (cli.hasOption("maxgapsize")) {
			String gapStr = cli.getOptionValue("maxgapsize");
			try {
				int gap = Integer.parseInt(gapStr);
				if (gap < 1) {
					logger.error("Invalid maxgapsize: " + gap);
					System.exit(1);
				}
				params.setMaxGapSize(gap);
			} catch (NumberFormatException e) {
				logger.error("Invalid maxgapsize: " + gapStr);
				System.exit(1);
			}
		}
		if (cli.hasOption("scoringstrategy")) {
			String stratStr = cli.getOptionValue("scoringstrategy");
			ScoringStrategy strat;
			try {
				strat = ScoringStrategy.valueOf(stratStr.toUpperCase());
				params.setScoringStrategy(strat);
			} catch (IllegalArgumentException e) {
				// give up
				logger.error("Illegal scoringstrategy. Requires on of "
						+ CliTools.getEnumValuesAsString(ScoringStrategy.class));
				System.exit(1);
			}
		}
		if (cli.hasOption("winsize")) {
			String winStr = cli.getOptionValue("winsize");
			try {
				int win = Integer.parseInt(winStr);
				if (win < 1) {
					logger.error("Invalid winsize: " + winStr);
					System.exit(1);
				}
				params.setWinSize(win);
			} catch (NumberFormatException e) {
				logger.error("Invalid winsize: " + winStr);
				System.exit(1);
			}

		}
		if (cli.hasOption("maxrmsd")) {
			String strVal = cli.getOptionValue("maxrmsd");
			try {
				double val = Double.parseDouble(strVal);
				if (val < 0) {
					logger.error("Invalid maxrmsd: " + strVal);
					System.exit(1);
				}
				params.setMaxOptRMSD(val);
			} catch (NumberFormatException e) {
				logger.error("Invalid maxrmsd: " + strVal);
				System.exit(1);
			}

		}

		if (cli.hasOption("nointernalgaps")) {
			params.setGaps(false);
		}
		if (cli.hasOption("gapopen")) {
			String strVal = cli.getOptionValue("gapopen");
			try {
				double val = Double.parseDouble(strVal);
				if (val < 0) {
					logger.error("Invalid gapopen: " + strVal);
					System.exit(1);
				}
				params.setGapOpen(val);
			} catch (NumberFormatException e) {
				logger.error("Invalid gapopen: " + strVal);
				System.exit(1);
			}

		}
		if (cli.hasOption("gapextension")) {
			String strVal = cli.getOptionValue("gapextension");
			try {
				double val = Double.parseDouble(strVal);
				if (val < 0) {
					logger.error("Invalid gapextension: " + strVal);
					System.exit(1);
				}
				params.setGapExtension(val);
			} catch (NumberFormatException e) {
				logger.error("Invalid gapextension: " + strVal);
				System.exit(1);
			}
		}
		if (cli.hasOption("ordermethod")) {
			String strVal = cli.getOptionValue("ordermethod");
			OrderDetectorMethod val;
			try {
				val = OrderDetectorMethod.valueOf(strVal.toUpperCase());
				params.setOrderDetectorMethod(val);
			} catch (IllegalArgumentException e) {
				// give up
				logger.error("Illegal ordermethod. Requires on of {}",
						CliTools.getEnumValuesAsString(OrderDetectorMethod.class));
				System.exit(1);
			}
		}
		if (cli.hasOption("order")) {
			String strVal = cli.getOptionValue("order");
			try {
				int val = Integer.parseInt( strVal );
				params.setUserOrder(val);
				if(val>0) {
					params.setOrderDetectorMethod(OrderDetectorMethod.USER_INPUT);
				}
			} catch( NumberFormatException e) {
				logger.error("Invalid order: "+strVal);
			}
		}
		if (cli.hasOption("refinemethod")) {
			String strVal = cli.getOptionValue("refinemethod");
			RefineMethod val;
			try {
				val = RefineMethod.valueOf(strVal.toUpperCase());
				params.setRefineMethod(val);
			} catch (IllegalArgumentException e) {
				// give up
				logger.error("Illegal refinemethod. Requires on of "
						+ CliTools.getEnumValuesAsString(RefineMethod.class));
				System.exit(1);
			}
		}
		if (cli.hasOption("symmtype")) {
			String strVal = cli.getOptionValue("symmtype");
			SymmetryType val;
			try {
				val = SymmetryType.valueOf(strVal.toUpperCase());
				params.setSymmType(val);
			} catch (IllegalArgumentException e) {
				// give up
				logger.error("Illegal symmtype. Requires on of "
						+ CliTools.getEnumValuesAsString(RefineMethod.class));
				System.exit(1);
			}
		}
		if (cli.hasOption("maxorder")) {
			String strVal = cli.getOptionValue("maxorder");
			try {
				int val = Integer.parseInt(strVal);
				if (val < 0) {
					logger.error("Invalid maxorder: " + strVal);
					System.exit(1);
				}
				params.setMaxSymmOrder(val);
			} catch (NumberFormatException e) {
				logger.error("Invalid maxorder: " + strVal);
				System.exit(1);
			}
		}
		if (cli.hasOption("rndseed")) {
			String strVal = cli.getOptionValue("rndseed");
			try {
				int val = Integer.parseInt(strVal);
				if (val < 0) {
					logger.error("Invalid rndseed: " + strVal);
					System.exit(1);
				}
				params.setRndSeed(val);
			} catch (NumberFormatException e) {
				logger.error("Invalid rndseed: " + strVal);
				System.exit(1);
			}
		}
		boolean optimize = cli.hasOption("opt") || !cli.hasOption("noopt");
		params.setOptimization(optimize);
		if (cli.hasOption("symmlevels")) {
			String strVal = cli.getOptionValue("symmlevels");
			try {
				int val = Integer.parseInt(strVal);
				params.setSymmLevels(val);
			} catch (NumberFormatException e) {
				logger.error("Invalid symmlevels: " + strVal);
				System.exit(1);
			}
		}
		if (cli.hasOption("unrefinedscorethreshold")) {
			String strVal = cli.getOptionValue("unrefinedscorethreshold");
			try {
				double val = Double.parseDouble(strVal);
				if (val < 0) {
					logger.error("Invalid unrefinedscorethreshold: " + strVal);
					System.exit(1);
				}
				params.setUnrefinedScoreThreshold(val);
			} catch (NumberFormatException e) {
				logger.error("Invalid unrefinedscorethreshold: " + strVal);
				System.exit(1);
			}
		}
		if (cli.hasOption("refinedscorethreshold")) {
			String strVal = cli.getOptionValue("refinedscorethreshold");
			try {
				double val = Double.parseDouble(strVal);
				if (val < 0) {
					logger.error("Invalid refinedscorethreshold: " + strVal);
					System.exit(1);
				}
				params.setRefinedScoreThreshold(val);
			} catch (NumberFormatException e) {
				logger.error("Invalid refinedscorethreshold: " + strVal);
				System.exit(1);
			}
		}
		if (cli.hasOption("ssethreshold")) {
			String strVal = cli.getOptionValue("ssethreshold");
			try {
				int val = Integer.parseInt(strVal);
				if (val < 0) {
					logger.error("Invalid ssethreshold: " + strVal);
					System.exit(1);
				}
				params.setSSEThreshold(val);
			} catch (NumberFormatException e) {
				logger.error("Invalid ssethreshold: " + strVal);
				System.exit(1);
			}
		}
		if (cli.hasOption("dcutoff")) {
			String strVal = cli.getOptionValue("dcutoff");
			try {
				double val = Double.parseDouble(strVal);
				if (val < 0) {
					logger.error("Invalid dcutoff: " + strVal);
					System.exit(1);
				}
				params.setDistanceCutoff(val);
			} catch (NumberFormatException e) {
				logger.error("Invalid dcutoff: " + strVal);
				System.exit(1);
			}
		}
		if (cli.hasOption("minlen")) {
			String strVal = cli.getOptionValue("minlen");
			try {
				int val = Integer.parseInt(strVal);
				if (val < 0) {
					logger.error("Invalid minlen: " + strVal);
					System.exit(1);
				}
				params.setMinCoreLength(val);
			} catch (NumberFormatException e) {
				logger.error("Invalid minlen: " + strVal);
				System.exit(1);
			}
		}

		verifyParams(params);

		// Done parsing arguments

		// Configure atomcache
		UserConfiguration cacheConfig = new UserConfiguration();
		if (pdbFilePath != null && !pdbFilePath.isEmpty()) {
			cacheConfig.setPdbFilePath(pdbFilePath);
			cacheConfig.setCacheFilePath(pdbFilePath);
		}
		AtomCache cache = new AtomCache(cacheConfig);
		cache.setObsoleteBehavior(ObsoleteBehavior.FETCH_OBSOLETE);

		// Write the headers of the files
		for (CeSymmWriter writer : writers) {
			try {
				writer.writeHeader();
			} catch (IOException e) {
				logger.error("Could not write header to file.", e);
			}
		}
		long startTime = System.nanoTime();

		// Start the workers in a fixed threaded pool
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		for (String name : names) {
			StructureIdentifier id = new StructureName(name);
			Runnable worker = new CeSymmWorker(id, params, cache, writers,
					displayAlignment);
			executor.execute(worker);
		}
		executor.shutdown();
		while (!executor.isTerminated())
			Thread.sleep(100); // sleep .1 seconds

		long elapsed = (System.nanoTime() - startTime) / 1000000;
		long meanRT = (long) (elapsed / (float) names.size());
		logger.info("Total runtime: " + elapsed + ", mean runtime: " + meanRT);

		// Close any writers of output
		for (CeSymmWriter writer : writers)
			writer.close();
	}

	/**
	 * Check dependencies between parameters
	 * @param params
	 */
	private static void verifyParams(CESymmParameters params) {
		int order = params.getUserOrder();
		OrderDetectorMethod orderdetector = params.getOrderDetectorMethod();
		if( order > 0 && orderdetector != OrderDetectorMethod.USER_INPUT) {
			logger.info("--order={} is incompatible with --orderdetector={}",order,orderdetector);
			System.exit(1);
		}
		if( order < 1 && orderdetector == OrderDetectorMethod.USER_INPUT) {
			logger.info("USER_INPUT detector requires --order",orderdetector);
			System.exit(1);
		}
	}

	/**
	 * Creates the options
	 * 
	 * @param optionOrder
	 *            An empty map, which will be filled in with the option order
	 * @return all Options
	 */
	private static Options getOptions() {

		OptionGroup grp; // For mutually exclusive options
		Option opt;
		// Note: When adding an option, also add its long name to the

		Options options = new Options();
		options.addOption("h", "help", false, "Print usage information");
		options.addOption(Option.builder().longOpt("version").hasArg(false)
				.desc("Print CE-Symm version").build());

		// Input file
		options.addOption(Option.builder("i")
				.longOpt("input")
				.hasArg(true)
				.argName("file")
				.desc(
						"File listing whitespace-delimited query structures")
				.build());
		
		// Logger control
		grp = new OptionGroup();
		opt = Option.builder("v")
				.longOpt("verbose")
				//.hasArg(true)
				//.argName("file")
				.desc("Output verbose logging information.")
				.build();
		grp.addOption(opt);
		opt = Option.builder("q")
				.longOpt("noverbose")
				.hasArg(false)
				.desc("Disable verbose logging information, as well as the default (--simple) output.")
				.build();
		grp.addOption(opt);
		options.addOptionGroup(grp);

		// Output formats
		options.addOption(Option.builder("o")
				.longOpt("simple")
				.hasArg()
				.optionalArg(true)
				.argName("file")
				.desc( "Output result in a simple format. Use --simple=- for standard out (default)")
				.build());
		options.addOption(Option.builder()
				.longOpt("stats")
				.hasArg()
				.optionalArg(true)
				.argName("file")
				.desc( "Output a tsv file with detailed symmetry info")
				.build());
		options.addOption(Option.builder()
				.longOpt("tsv")
				.hasArg()
				.optionalArg(true)
				.argName("file")
				.desc( "Output alignment as a tsv-formated list of aligned residues")
				.build());
		options.addOption(Option.builder()
				.longOpt("xml")
				.hasArg()
				.optionalArg(true)
				.argName("file")
				.desc( "Output alignment as XML")
				.build());
		options.addOption(Option.builder()
				.longOpt("fatcat")
				.hasArg()
				.optionalArg(true)
				.argName("file")
				.desc("Output alignment as FATCAT output")
				.build());
		options.addOption(Option.builder()
				.longOpt("fasta")
				.hasArg()
				.optionalArg(true)
				.argName("file")
				.desc("Output alignment as FASTA alignment output")
				.build());
		options.addOption(Option.builder()
				.longOpt("axes")
				.hasArg()
				.optionalArg(true)
				.argName("file")
				.desc("Output information about rotation axes")
				.build());

		// jmol
		grp = new OptionGroup();
		opt = Option.builder("j")
				.longOpt("show3d")
				.hasArg(false)
				.desc( "Force jMol display for each structure "
						+ "[default for <10 structures when specified on command"
						+ " line]")
				.build();
		grp.addOption(opt);
		opt = Option.builder("J")
				.longOpt("noshow3d")
				.hasArg(false)
				.desc( "Disable jMol display [default with --input "
						+ "or for >=10 structures]")
				.build();
		grp.addOption(opt);
		options.addOptionGroup(grp);

		// enums
		options.addOption(Option.builder()
				.longOpt("ordermethod")
				.hasArg(true)
				.argName("str")
				.desc( "Order detection method: "+
						CliTools.getEnumValuesAsString(OrderDetectorMethod.class))
				.build());
		options.addOption(Option.builder()
				.longOpt("order")
				.hasArg(true)
				.argName("int")
				.desc( "Force a particular order. If positive, implies --ordermethod=USER_INPUT.")
				.build());

		options.addOption(Option.builder()
				.longOpt("refinemethod")
				.hasArg(true)
				.argName("str")
				.desc( "Refiner method: " +
						CliTools.getEnumValuesAsString(RefineMethod.class) )
				.build());

		options.addOption(Option.builder()
				.longOpt("symmtype")
				.hasArg(true)
				.argName("str")
				.desc( "Symmetry Type: " +
						CliTools.getEnumValuesAsString(SymmetryType.class))
				.build());

		// PDB_DIR
		options.addOption(Option.builder()
				.longOpt("pdbfilepath")
				.hasArg(true)
				.argName("dir")
				.desc( "Download directory for new structures [default tmp folder]. "
						+ "Can also be set with the PDB_DIR environmental variable.")
				.build());

		options.addOption(Option.builder().longOpt("threads").hasArg(true)
				.desc("Number of threads [default cores-1]")
				.build());

		// Parameters
		options.addOption(Option.builder()
				.longOpt("maxgapsize")
				.hasArg(true)
				.argName("int")
				.desc(
						"This parameter configures the maximum gap size "
								+ "G, that is applied during the AFP extension. The "
								+ "larger the value, the longer the calculation time "
								+ "can become, Default value is 30. Set to 0 for no limit.")
				.build());

		options.addOption(Option.builder()
				.longOpt("scoringstrategy")
				.hasArg(true)
				.argName("str")
				.desc(
						"Which scoring function to use: "
								+ CliTools
										.getEnumValuesAsString(ScoringStrategy.class))
				.build());

		options.addOption(Option.builder()
				.longOpt("winsize")
				.hasArg(true)
				.argName("int")
				.desc(
						"This configures the fragment size m of Aligned Fragment Pairs (AFPs).")
				.build());

		options.addOption(Option.builder()
				.longOpt("maxrmsd")
				.hasArg(true)
				.argName("float")
				.desc(
						"The maximum RMSD at which to stop alignment "
								+ "optimization. (default: unlimited=99)")
				.build());

		options.addOption(Option.builder()
				.longOpt("nointernalgaps")
				.desc( "Force alignment to include a residue from all repeats. "
						+ "(By default only 50% of repeats must be aligned in each column.)")
				.build());

		options.addOption(Option.builder()
				.longOpt("gapopen")
				.hasArg(true)
				.argName("float")
				.desc(
						"Gap opening penalty during alignment optimization [default: 5.0].")
				.build());

		options.addOption(Option.builder()
				.longOpt("gapextension")
				.hasArg(true)
				.argName("float")
				.desc(
						"Gap extension penalty during alignment optimization [default: 0.5].")
				.build());

		options.addOption(Option.builder()
				.longOpt("symmlevels")
				.hasArg(true)
				.argName("int")
				.desc(
						"Run iteratively the algorithm to find multiple symmetry levels. "
								+ "This specifies the maximum number of symmetry levels. 0 means unbounded"
								+ " [default: 0].").build());

		options.addOption(Option.builder()
				.longOpt("noopt")
				.hasArg(false)
				.desc(
						"Disable optimization of the resulting symmetry alignment.")
				.build());

		options.addOption(Option.builder()
				.longOpt("unrefinedscorethreshold")
				.hasArg(true)
				.argName("float")
				.desc(
						"The unrefined score threshold. TM-scores above this "
								+ "value in the optimal self-alignment of the "
								+ "structure (before refinement) "
								+ "will be considered significant results "
								+ "[default: 0.4, interval [0.0,1.0]].")
				.build());
		
		options.addOption(Option.builder()
				.longOpt("refinedscorethreshold")
				.hasArg(true)
				.argName("float")
				.desc(
						"The refined score threshold. TM-scores above this "
								+ "value in the refined multiple alignment "
								+ "of repeats (after refinement) "
								+ "will be considered significant results "
								+ "[default: 0.36, interval [0.0,1.0]].")
				.build());

		options.addOption(Option.builder()
				.longOpt("ssethreshold")
				.hasArg(true)
				.argName("int")
				.desc(
						"The SSE threshold. Number of secondary structure "
								+ "elements for repeat below this value will be considered "
								+ "asymmetric results. 0 means unbounded."
								+ "[default: 0].").build());

		options.addOption(Option.builder()
				.longOpt("maxorder")
				.hasArg(true)
				.argName("int")
				.desc(
						"The maximum number of symmetric repeats [default: 8].")
				.build());

		options.addOption(Option.builder()
				.longOpt("rndseed")
				.hasArg(true)
				.argName("int")
				.desc(
						"The random seed used in optimization, for reproducibility "
								+ "of the results [default: 0].").build());

		options.addOption(Option.builder()
				.longOpt("minlen")
				.hasArg(true)
				.argName("int")
				.desc(
						"The minimum length, expressed in number of core "
								+ "aligned residues, of a symmetric repeat [default: 15].")
				.build());

		options.addOption(Option.builder()
				.longOpt("dcutoff")
				.hasArg(true)
				.argName("float")
				.desc(
						"The maximum distance, in A, allowed between any two aligned "
								+ "residue positions [default: 7.0].")
				.build());

		options.addOption(Option.builder()
				.longOpt("scopversion")
				.hasArg(true)
				.argName("str")
				.desc(
						"Version of SCOP or SCOPe to use "
								+ "when resolving SCOP identifiers [defaults to latest SCOPe]")
				.build());

		return options;
	}

	/**
	 * Parse a whitespace-delimited file containing structure names
	 * 
	 * @throws FileNotFoundException
	 */
	public static List<String> parseInputStructures(String filename)
			throws FileNotFoundException {
		File file = new File(filename);
		Scanner s = new Scanner(file);

		List<String> structures = new ArrayList<String>();
		while (s.hasNext()) {
			String name = s.next();
			if (name.startsWith("#")) {
				// comment
				s.nextLine();
			} else {
				structures.add(name);
			}
		}
		s.close();
		return structures;
	}

	// Output formats

	/**
	 * Parent class for all output formats All methods are empty stubs, which
	 * should be overridden to write data to the writer.
	 */
	private static abstract class CeSymmWriter {
		protected PrintWriter writer;

		boolean shouldClose = true;

		public CeSymmWriter(String filename) throws IOException {
			if(filename.equals("-")) {
				this.shouldClose = false;
				writer = new PrintWriter(System.out,true);
			} else {
				this.shouldClose = true;
				writer = new PrintWriter(new BufferedWriter(new FileWriter(filename)));
			}
		}

		abstract public void writeHeader() throws IOException;

		abstract public void writeResult(CeSymmResult result)
				throws IOException;

		public void close() {
			if (writer != null) {
				writer.flush();
				if(shouldClose)
					writer.close();
			}
		}
	}

	private static class XMLWriter extends CeSymmWriter {
		public XMLWriter(String filename) throws IOException {
			super(filename);
		}

		@Override
		public void writeResult(CeSymmResult result) throws IOException {
			if (result != null && result.getMultipleAlignment() != null) {
				writer.append(MultipleAlignmentWriter.toXML(result
						.getMultipleAlignment().getEnsemble()));
				writer.flush();
			}
		}

		@Override
		public void writeHeader() throws IOException {
			// No header for XML file
		}
	}

	private static class FatcatWriter extends CeSymmWriter {
		public FatcatWriter(String filename) throws IOException {
			super(filename);
		}

		@Override
		public void writeResult(CeSymmResult result) {
			if (result != null ) {
				MultipleAlignment alignment = result.getMultipleAlignment();
				if(alignment != null) {
					writer.write(MultipleAlignmentWriter.toFatCat(alignment));
				} else {
					writer.format("Structures:[%s]%n",result.getStructureId());
					writer.format("Insignificant Alignment%n");
				}
			}
			writer.println("//");
			writer.flush();
		}

		@Override
		public void writeHeader() throws IOException {
			// No header for FatCat file
		}
	}

	private static class FastaWriter extends CeSymmWriter {
		public FastaWriter(String filename) throws IOException {
			super(filename);
		}

		@Override
		public void writeResult(CeSymmResult result) {
			if (result != null ) {
				MultipleAlignment alignment = result.getMultipleAlignment();
				if(alignment != null) {
					writer.write(MultipleAlignmentWriter.toFASTA(alignment));
				}
			}
			writer.println("//");
			writer.flush();
		}

		@Override
		public void writeHeader() throws IOException {
			// No header for Fasta files
		}
	}

	private static class StatsWriter extends CeSymmWriter {

		public StatsWriter(String filename) throws IOException {
			super(filename);
		}

		@Override
		public void writeHeader() {
			writer.println("Structure\t" + "NumRepeats\t" + "SymmGroup\t" + "Refined\t"
					+ "SymmLevels\t" + "SymmType\t" + "RotationAngle\t"
					+ "ScrewTranslation\t" + "UnrefinedTMscore\t" + "UnrefinedRMSD\t"
					+ "SymmTMscore\t" + "SymmRMSD\t" + "RepeatLength\t"
					+ "CoreLength\t" + "Length\t" + "Coverage");
			writer.flush();
		}

		@Override
		public void writeResult(CeSymmResult result) throws IOException {
			String id = null;
			if( result == null) {
				writeEmptyRow(id);
				writer.flush();
				return;
			}
			try {
				id = result.getStructureId().getIdentifier();
				int repeatLen = 0;
				int coreLen = result.getSelfAlignment().getOptLength();
				double coverage = result.getSelfAlignment().getCoverage1() / 100;
				String group = result.getSymmGroup();
				int order = result.getSymmOrder();
				double symmrmsd = 0.0;
				double symmscore = 0.0;

				RotationAxis rot = new RotationAxis(result.getSelfAlignment()
						.getBlockRotationMatrix()[0], result.getSelfAlignment()
						.getBlockShiftVector()[0]);
				double rotation_angle = Math.toDegrees(rot.getAngle());
				double screw_translation = new Vector3d(rot
						.getScrewTranslation().getCoords()).length();

				int structureLen = result.getAtoms().length;

				// If there is refinement alignment
				if (result.isRefined()) {
					MultipleAlignment msa = result.getMultipleAlignment();
					symmrmsd = msa.getScore(MultipleAlignmentScorer.RMSD);
					symmscore = msa
							.getScore(MultipleAlignmentScorer.AVGTM_SCORE);

					repeatLen = msa.length();
					coreLen = msa.getCoreLength() * msa.size();
					
					// Calculate coverage
					coverage = 0;
					for (int s = 0; s < msa.size(); s++)
						coverage += msa.getCoverages().get(s);

					rot = new RotationAxis(result.getAxes().getElementaryAxes()
							.get(0));
					rotation_angle = Math.toDegrees(rot.getAngle());
					screw_translation = new Vector3d(rot.getScrewTranslation()
							.getCoords()).length();
				}

				writer.format( "%s\t%d\t%s\t%b\t%d\t%s\t%.2f\t%.2f\t%.2f\t"
						+ "%.2f\t%.2f\t%.2f\t%d\t%d\t%d\t%.2f%n",
						id, order, group,
						result.isRefined(), result.getSymmLevels(),
						result.getType(), rotation_angle, screw_translation,
						result.getSelfAlignment().getTMScore(),
						result.getSelfAlignment().getTotalRmsdOpt(),
						symmscore, symmrmsd, repeatLen, coreLen, structureLen,
						coverage);
			} catch (Exception e) {
				// If any exception occurs when writing the results store empty
				// better
				logger.warn("Could not write result... storing empty row.", e);
				writeEmptyRow(id);
			}

			writer.flush();
		}

		private void writeEmptyRow(String id) {
			writer.format(
					"%s\t%d\t%s\t%b\t%d\t%s\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t"
							+ "%.2f\t%d\t%d\t%d\t%.2f%n", id, 1, "C1", false,
					0, SymmetryType.DEFAULT, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0,
					0, 0, 0.0);
		}
	}

	private static class SimpleWriter extends CeSymmWriter {

		public SimpleWriter(String filename) throws IOException {
			super(filename);
		}

		@Override
		public void writeHeader() {
			writer.println("Structure\tNumRepeats\tSymmGroup\tReason");
			writer.flush();
		}
		private void writeEmptyRow(String id) {
			writer.format("%s\t%d\t%s\t%s%n", id, 1, "C1", "Error");
		}
		@Override
		public void writeResult(CeSymmResult result) throws IOException {
			String id = null;
			if( result == null) {
				writeEmptyRow(id);
				writer.flush();
				return;
			}
			try {
				id = result.getStructureId().getIdentifier();
				writer.append(id);
				writer.append("\t");
				writer.append(Integer.toString(result.getSymmOrder()));
				writer.append("\t");
				writer.append(result.getSymmGroup());
				writer.append("\t");
				writer.append(result.getReason());
				writer.println();
				writer.flush();
			} catch( Exception e ) {
				logger.warn("Could not write result... storing empty row.", e);
				writeEmptyRow(id);
			}
			writer.flush();
		}
	}
	
	private static class AxesWriter extends CeSymmWriter {

		public AxesWriter(String filename) throws IOException {
			super(filename);
		}

		@Override
		public void writeHeader() {

			writer.println("Structure\t" + "SymmLevel\t" + "SymmType\t" + "SymmGroup\t"
					+ "RotationAngle\t" + "ScrewTranslation\t" + "Point1\t" + "Point2\t"
					+ "AlignedRepeats");
			writer.flush();
		}
		private void writeEmptyRow(String id) {
			writer.format("%s\t%d\t%s\t%s"
					+ "%.2f\t%.2f\t%.3f,%.3f,%.3f\t%.3f,%.3f,%.3f\t%s%n",
					id, -1, SymmetryType.DEFAULT, "C1",
					0., 0., 0.,0.,0., 0.,0.,0.,
					"");
		}
		@Override
		public void writeResult(CeSymmResult result) throws IOException {
			String id = null;
			if( result == null) {
				writeEmptyRow(id);
				writer.flush();
				return;
			}
			try {
				id = result.getStructureId().getIdentifier();
				
				SymmetryAxes axes = result.getAxes();
				Atom[] atoms = result.getAtoms();
				//TODO is this correct for hierarchical cases?
				String symmGroup = result.getSymmGroup();
				
				for(Axis axis : axes.getSymmetryAxes() ) {
					RotationAxis rot = axis.getRotationAxis();
//					Set<Integer> repIndex = new TreeSet<Integer>(axes
//							.getRepeatRelation(a).get(0));
					List<List<Integer>> repeatsCyclicForm = axes.getRepeatsCyclicForm(axis);
					String cyclicForm = SymmetryAxes.getRepeatsCyclicForm(repeatsCyclicForm, result.getRepeatsID());
					
					// Get atoms aligned by this axis
					List<Atom> axisAtoms = new ArrayList<Atom>();
					for(List<Integer> cycle : repeatsCyclicForm) {
						for(Integer repeat : cycle) {
							axisAtoms.addAll(Arrays.asList(atoms[repeat]));
						}
					}
					Pair<Atom> bounds = rot.getAxisEnds(axisAtoms.toArray(new Atom[axisAtoms.size()]));
							
					Atom start = bounds.getFirst();
					Atom end = bounds.getSecond();

					writer.format("%s\t%d\t%s\t%s\t"
							+ "%.2f\t%.2f\t%.3f,%.3f,%.3f\t%.3f,%.3f,%.3f\t%s%n",
							id, axis.getLevel()+1, axis.getSymmType(), symmGroup,
							Math.toDegrees(rot.getAngle()), rot.getTranslation(),
							start.getX(),start.getY(),start.getZ(),
							end.getX(),end.getY(),end.getZ(),
							cyclicForm);
				}
			} catch (Exception e) {
				// If any exception occurs when writing the results store empty
				// better
				logger.warn("Could not write result... storing empty row.", e);
				writeEmptyRow(id);
			}

			writer.flush();
		}
	}

	private static class TSVWriter extends CeSymmWriter {
		public TSVWriter(String filename) throws IOException {
			super(filename);
		}
		@Override
		public void writeHeader() throws IOException {
			// no header
		}
		@Override
		public void writeResult(CeSymmResult result) throws IOException {
			if( result != null) {
				MultipleAlignment alignment = result.getMultipleAlignment();
				if(alignment != null)
					writer.write(MultipleAlignmentWriter.toAlignedResidues(alignment));
				else {
					// No alignment; just write header
					writer.format("#Struct1:\t%s%n",result.getStructureId());
					writer.format("#Insignificant Alignment%n");
				}
			}
			writer.println("//");
			writer.flush();
		}
	}

	/**
	 * This Runnable implementation runs CeSymm on the input structure and with
	 * the input parameters and writes the results to the output writers. If the
	 * 3D visualization is turned on, it creates a new thread with the Jmol
	 * frame.
	 * 
	 * @author Aleix Lafita
	 *
	 */
	private static class CeSymmWorker implements Runnable {

		private StructureIdentifier id;
		private CESymmParameters params;
		private AtomCache cache;
		private List<CeSymmWriter> writers;
		private boolean show3d;

		public CeSymmWorker(StructureIdentifier id, CESymmParameters params,
				AtomCache cache, List<CeSymmWriter> writers, boolean show3d) {
			this.id = id;
			this.cache = cache;
			this.writers = writers;
			this.show3d = show3d;
			this.params = params;
		}

		@Override
		public void run() {

			try {
				// Obtain the structure representation
				Atom[] atoms;
				try {
					Structure s = cache.getStructure(id);
					atoms = StructureTools.getRepresentativeAtomArray(s);
				} catch (IOException | StructureException e) {
					logger.error("Could not load Structure " + id.getIdentifier());
					return;
				}
				// Run the symmetry analysis
				CeSymmResult result = CeSymm.analyze(atoms, params);

				// Write into the output files
				for (CeSymmWriter writer : writers) {
					try {
						synchronized (writer) {
							writer.writeResult(result);
						}
					} catch (Exception e) {
						logger.error(
								"Could not save results for "
										+ id.getIdentifier(), e);
					}
				}

				// Display alignment in 3D Jmol
				if (show3d) {
					SymmetryDisplay.display(result);
				}
			} catch (Exception e) {
				logger.error("Could not complete job: " + id.getIdentifier(), e);
			} finally {
				logger.info("Finished job: " + id);
			}
		}
	}

}
