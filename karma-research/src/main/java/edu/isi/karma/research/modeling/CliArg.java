package edu.isi.karma.research.modeling;

import java.util.logging.Logger;

import edu.isi.karma.config.ModelingConfiguration;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class CliArg {
    private static final Logger log = Logger.getLogger(CliArg.class.getName());
    private String[] args = null;
    private Options options = new Options();
    private CommandLine cmd;

    public String datasetName;
    public String karmaHome;
    public boolean useCorrectType;
    public int numCandidateSemanticType;
    public int cutoff;
    public String[] trainSourceNames;
    public String[] testSourceNames;
    public int trainSizeMin;
    public int trainSizeMax;
    public int testSourceIndexBegin;
    public int testSourceIndexEnd;

    public CliArg(String[] args) {

        this.args = args;

        options.addOption("h", "help", false, "show help.");

        options.addOption("karma_home", true, "Karma Home");
        options.addOption("dataset_name", true, "Dataset's name");
        options.addOption("use_correct_type", true, "Whether use correct type to predict. Set to true will suspend semantic labeling params");

        options.addOption("num_candidate_semantic_type", true, "Related to semantic labeling");
        options.addOption("multiple_same_property_per_node", true, "Related to semantic labeling, When running with k=1, change the flag to true so all attributes have at least one semantic types");

        options.addOption("mapping_branching_factor", true, "Source attribute mapping params");
        options.addOption("num_candidate_mappings", true, "Source attribute mapping params");
        options.addOption("topk_steiner_tree", true, "Candidate generation params");
        options.addOption("cutoff", true, "Candidate generation params");

        options.addOption("coefficient_confidence", true, "");
        options.addOption("coefficient_coherence", true, "");
        options.addOption("coefficient_size", true, "");

        options.addOption("train_source_names", true, "List of known semantic models (comma separated)");
        options.addOption("test_source_names", true, "List of sources want to test (comma separated)");

        parse();
    }

    public void parse() {
        CommandLineParser parser = new BasicParser();

        try {
            cmd = parser.parse(options, args);

            if (cmd.hasOption("h"))
                help();

            if (cmd.hasOption("karma_home")) {
                karmaHome = cmd.getOptionValue("karma_home");
            } else {
                System.err.println("Missing required arg: karma_home");
                help();
            }

            if (cmd.hasOption("dataset_name")) {
                datasetName = cmd.getOptionValue("dataset_name");
            } else {
                System.err.println("Missing required arg: dataset_name");
                help();
            }

            if (cmd.hasOption("use_correct_type")) {
                useCorrectType = Boolean.parseBoolean(cmd.getOptionValue("use_correct_type"));
            } else {
                System.err.println("Missing required arg: use_correct_type");
                help();
            }

            if (cmd.hasOption("num_candidate_semantic_type")) {
                numCandidateSemanticType = Integer.parseInt(cmd.getOptionValue("num_candidate_semantic_type"));
            } else {
                System.err.println("Missing required arg: num_candidate_semantic_type");
                help();
            }

            if (cmd.hasOption("cutoff")) {
                cutoff = Integer.parseInt(cmd.getOptionValue("cutoff"));
            } else {
                System.err.println("Missing required arg: cutoff");
                help();
            }

            if (cmd.hasOption("train_source_names")) {
                trainSourceNames = cmd.getOptionValue("train_source_names").split(",");
            } else {
                System.err.println("Missing required arg: train_source_names");
                help();
            }

            if (cmd.hasOption("test_source_names")) {
                testSourceNames = cmd.getOptionValue("test_source_names").split(",");
            } else {
                System.err.println("Missing required arg: test_source_names");
                help();
            }

            String[] requiredArgs = new String[]{"mapping_branching_factor","multiple_same_property_per_node","num_candidate_mappings","topk_steiner_tree","coefficient_confidence","coefficient_coherence","coefficient_size"};
            for (String requiredArg: requiredArgs) {
                if (!cmd.hasOption(requiredArg)) {
                    System.err.println("Missing required arg: " + requiredArg);
                    help();
                }
            }
        } catch (ParseException e) {
            System.err.println("Parsing error:" + e.getMessage());
            help();
        }
    }

    public void updateModelingConfiguration(ModelingConfiguration modelingConfiguration) {
        modelingConfiguration.setMappingBranchingFactor(Integer.parseInt(cmd.getOptionValue("mapping_branching_factor")));
        modelingConfiguration.setMultipleSamePropertyPerNode(Boolean.parseBoolean(cmd.getOptionValue("multiple_same_property_per_node")));
        modelingConfiguration.setNumCandidateMappings(Integer.parseInt(cmd.getOptionValue("num_candidate_mappings")));
        modelingConfiguration.setTopKSteinerTree(Integer.parseInt(cmd.getOptionValue("topk_steiner_tree")));

        modelingConfiguration.setScoringConfidenceCoefficient(Double.parseDouble(cmd.getOptionValue("coefficient_confidence")));
        modelingConfiguration.setScoringCoherenceSCoefficient(Double.parseDouble(cmd.getOptionValue("coefficient_coherence")));
        modelingConfiguration.setScoringSizeCoefficient(Double.parseDouble(cmd.getOptionValue("coefficient_size")));
    }

    private void help() {
        // This prints out some help
        HelpFormatter formater = new HelpFormatter();

        formater.printHelp("java -jar <jar_file>", options);
        System.exit(0);
    }
}