package writers;

import java.io.IOException;
import java.util.stream.Collectors;

import org.biojava.nbio.structure.symmetry.core.QuatSymmetryResults;

/**
 * Writes a stats summary of a Quaternary Symmetry result in TSV format
 * 
 * @author Aleix Lafita
 * 
 */
public class QuatSymmStatsWriter extends QuatSymmWriter {

	public QuatSymmStatsWriter(String filename) throws IOException {
		super(filename);
	}

	@Override
	public synchronized void writeResult(String identifier,
			QuatSymmetryResults result) throws IOException {

		if (result == null) {
			writeEmptyResult(identifier);
			return;
		}

		writer.println(String.format(
				"%s\t%d\t%s\t%s\t%b\t%s\t%b\t%s\t%.2f\t%.2f",
				identifier,
				result.getSubunitCount(),
				result.getSubunits().stream().map(s -> s.getName())
						.collect(Collectors.toList()).toString(),
				result.getStoichiometry(), result.isPseudoStoichiometric(),
				result.getSymmetry(), result.isLocal(), result.getMethod(),
				result.getScores().getRmsd(), result.getScores().getTm()));
		writer.flush();
	}

	private void writeEmptyResult(String identifier) {
		writer.println(identifier + "\t0\t\t\t\t\t\t0\t0");
	}

	@Override
	public synchronized void writeHeader() throws IOException {
		writer.println("Name\t" + "Size\t" + "Subunits\t" + "Stoichiometry\t"
				+ "Pseudostoichiometry\t" + "Symmetry\t" + "Local\t"
				+ "Method\t" + "SymmRMSD\t" + "SymmTMscore");
		writer.flush();
	}

}