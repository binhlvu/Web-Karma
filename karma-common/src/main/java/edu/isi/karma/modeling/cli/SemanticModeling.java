package edu.isi.karma.modeling.cli;

import edu.isi.karma.config.ModelingConfiguration;
import edu.isi.karma.config.ModelingConfigurationRegistry;
import edu.isi.karma.modeling.alignment.GraphBuilder;
import edu.isi.karma.modeling.alignment.SemanticModel;
import edu.isi.karma.modeling.alignment.learner.ModelLearner;
import edu.isi.karma.modeling.alignment.learner.ModelLearningGraph;
import edu.isi.karma.modeling.alignment.learner.ModelLearningGraphType;
import edu.isi.karma.modeling.alignment.learner.SortableSemanticModel;
import edu.isi.karma.modeling.ontology.OntologyManager;
import edu.isi.karma.rep.alignment.*;
import edu.isi.karma.util.EncodingDetector;
import edu.isi.karma.webserver.ContextParametersRegistry;
import edu.isi.karma.webserver.ServletContextParameterMap;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

class Input {
    public String sourceName;
    public int topKSteinerTree;
    public int numCandidateMappings;
    public int mappingBranchingFactor;

    public List<InputSteinerNode> steinerNodes;

    public List<Node> getSteinerNodes() {
        List<Node> steinerNodes = new ArrayList<>();
        for (InputSteinerNode n: this.steinerNodes) {
            steinerNodes.add(n.toSteinerNode());
        }
        return steinerNodes;
    }
}


class Output {
    public IOGraph semanticModel;
    public List<WeightedIOGraph> steinerTrees;

    public Output(IOGraph semanticModel, List<WeightedIOGraph> steinerTrees) {
        this.semanticModel = semanticModel;
        this.steinerTrees = steinerTrees;
    }
}

public class SemanticModeling {

    private final int topKSteinerTree;
    private final int numCandidateMappings;
    private final int mappingBranchingFactor;
    private final int numSemanticTypes;

    private ServletContextParameterMap contextParameters;
    private OntologyManager ontologyManager;
    private String karmaHome;

    public SemanticModeling(String karmaHome, int topKSteinerTree, int numCandidateMappings, int mappingBranchingFactor) throws NoSuchFieldException, IllegalAccessException {
        this.karmaHome = karmaHome;
        this.topKSteinerTree = topKSteinerTree;
        this.numCandidateMappings = numCandidateMappings;
        this.mappingBranchingFactor = mappingBranchingFactor;

        Field f1 = ModelLearner.class.getDeclaredField("NUM_SEMANTIC_TYPES");
        f1.setAccessible(true);
        this.numSemanticTypes = (int) f1.get(null);

        contextParameters = ContextParametersRegistry.getInstance().getContextParameters(this.karmaHome);
        contextParameters.setParameterValue(ServletContextParameterMap.ContextParameter.USER_CONFIG_DIRECTORY, karmaHome + "/config");
        contextParameters.setParameterValue(ServletContextParameterMap.ContextParameter.JSON_MODELS_DIR, karmaHome + "/models-json");
        contextParameters.setParameterValue(ServletContextParameterMap.ContextParameter.ALIGNMENT_GRAPH_DIRECTORY, karmaHome + "/");
        setupEnvironment();

        ontologyManager = this.loadOntologyManager();
    }

    protected void setupEnvironment() throws NoSuchFieldException, IllegalAccessException {
        File file = new File(contextParameters.getParameterValue(ServletContextParameterMap.ContextParameter.ALIGNMENT_GRAPH_DIRECTORY) + "graph.json");
        if (file.exists()) {
            if (!file.delete()) {
                throw new RuntimeException("Alignment graph must not be existed!");
            }
        }

        ModelingConfiguration modelingConfiguration = ModelingConfigurationRegistry.getInstance().getModelingConfiguration(contextParameters.getId());
        Field topKSteinerTree = ModelingConfiguration.class.getDeclaredField("topKSteinerTree");
        Field numCandidateMappings = ModelingConfiguration.class.getDeclaredField("numCandidateMappings");
        Field mappingBranchingFactor = ModelingConfiguration.class.getDeclaredField("mappingBranchingFactor");

        topKSteinerTree.setAccessible(true);
        topKSteinerTree.set(modelingConfiguration, this.topKSteinerTree);
        numCandidateMappings.setAccessible(true);
        numCandidateMappings.set(modelingConfiguration, this.numCandidateMappings);
        mappingBranchingFactor.setAccessible(true);
        mappingBranchingFactor.set(modelingConfiguration, this.mappingBranchingFactor);
    }

    protected OntologyManager loadOntologyManager() {
        File ontDir = new File(this.karmaHome + "/preloaded-ontologies");
        if (!ontDir.exists()) {
            throw new RuntimeException("No directory for preloading ontologies exists.");
        }
        File[] ontologies = ontDir.listFiles();
        OntologyManager mgr = new OntologyManager(contextParameters.getId());
        for (File ontology: ontologies) {
            if (ontology.getName().endsWith(".owl") ||
                    ontology.getName().endsWith(".rdf") ||
                    ontology.getName().endsWith(".n3") ||
                    ontology.getName().endsWith(".ttl") ||
                    ontology.getName().endsWith(".xml")) {
                try {
                    String encoding = EncodingDetector.detect(ontology);
                    mgr.doImport(ontology, encoding);
                } catch (Exception t) {
                    throw new RuntimeException("Error loading ontology: " + ontology.getAbsolutePath(), t);
                }
            } else {
                throw new RuntimeException("the file: " + ontology.getAbsolutePath() + " does not have proper format: xml/rdf/n3/ttl/owl");
            }
        }
        mgr.updateCache();
        return mgr;
    }

    public Output runSemanticModeling(Input input) throws Exception {
        List<Node> steinerNodes = input.getSteinerNodes();
        GraphBuilder gb = ModelLearningGraph.getInstance(ontologyManager, ModelLearningGraphType.Compact).getGraphBuilder();
        Map<String, String> domainID2NodeID = new HashMap<>();
        Map<String, String> nodeID2domainID = new HashMap<>();

        for (Node n: steinerNodes) {
            gb.addNode(n);

            ColumnNode cn = (ColumnNode) n;
            if (cn.getSemanticTypeStatus().equals(ColumnSemanticTypeStatus.UserAssigned)) {
                // if user assigned semantic types, leveraging it to pick exactly one node for the column
                SemanticType userSemanticType = cn.getUserSemanticTypes().get(0);
                Node source = null;
                Set<Node> domains = gb.getUriToNodesMap().get(userSemanticType.getDomain().getUri());

                if (domains == null) {
                    gb.addNode(new InternalNode(gb.getNodeIdFactory().getNodeId(userSemanticType.getDomain().getUri()), userSemanticType.getDomain()));
                    domains = gb.getUriToNodesMap().get(userSemanticType.getDomain().getUri());
                }

                if (domainID2NodeID.containsKey(userSemanticType.getDomainId())) {
                    // same domainID is already mapped before
                    String nodeId = domainID2NodeID.get(userSemanticType.getDomainId());
                    for (Node domain: domains) {
                        if (domain.getId().equals(nodeId)) {
                            source = domain;
                            break;
                        }
                    }
                } else {
                    // node has not mapped before
                    for (Node domain: domains) {
                        if (nodeID2domainID.containsKey(domain.getId())) {
                            // this node was used in another mapping;
                            continue;
                        }
                        if (source == null) {
                            source = domain;
                        }
                        if (domain.getModelIds().size() > source.getModelIds().size()) {
                            source = domain;
                        }
                    }

                    if (source == null) {
                        // run out of nodes
                        source = new InternalNode(gb.getNodeIdFactory().getNodeId(userSemanticType.getDomain().getUri()), userSemanticType.getDomain());
                        gb.addNode(source);
                    } else {
                        // have to update nodeIdFactory otherwise, when we run out of node, we will generate incorrect id
                        gb.getNodeIdFactory().addNodeId(source.getId(), userSemanticType.getDomain().getUri());
                    }
                    domainID2NodeID.put(userSemanticType.getDomainId(), source.getId());
                    nodeID2domainID.put(source.getId(), userSemanticType.getDomainId());
                }

                String id = String.format("%s---%s---%s", source.getId(), userSemanticType.getType().getUri(), n.getId());
                gb.addLink(source, n, new DataPropertyLink(id, userSemanticType.getType()));
            }
        }

        // double check setting userSemanticTypes
        if (domainID2NodeID.size() != nodeID2domainID.size()) {
            throw new RuntimeException("Bug in setting userSemanticTypes");
        }

        ModelLearner modelLearner = new ModelLearner(gb, steinerNodes);
        List<SortableSemanticModel> hypothesisList = modelLearner.hypothesize(true, this.numSemanticTypes);

        if (hypothesisList == null || hypothesisList.isEmpty()) {
            return new Output(null, null);
        }

        SemanticModel model = new SemanticModel(hypothesisList.get(0));
        List<WeightedIOGraph> weightedIOGraphs = new ArrayList<>();
        for (SortableSemanticModel sm: hypothesisList) {
            weightedIOGraphs.add((WeightedIOGraph) updateIOGraph(new WeightedIOGraph(sm.getCost()), sm));
        }
        return new Output(updateIOGraph(new IOGraph(), model), weightedIOGraphs);
    }

    protected IOGraph updateIOGraph(IOGraph graph, SemanticModel sm) {
        for (Node n: sm.getGraph().vertexSet()) {
            if (sm.getMappingToSourceColumns().containsKey(n)) {
                n = sm.getMappingToSourceColumns().get(n);
                // data node
                graph.addNode(new IONode(n.getId(), ((ColumnNode) n).getColumnName()));
            } else {
                graph.addNode(new IONode(n.getId(), n.getLabel().getUri()));
            }
        }

        for (LabeledLink e: sm.getGraph().edgeSet()) {
            String targetID = e.getTarget().getId();
            if (sm.getMappingToSourceColumns().containsKey(e.getTarget())) {
                targetID = sm.getMappingToSourceColumns().get(e.getTarget()).getId();
            }
            graph.addEdge(new IOEdge(e.getId(), e.getLabel().getUri(), e.getSource().getId(), targetID));
        }
        return graph;
    }

    protected String sm2string(SemanticModel sm) {
        StringBuilder s = new StringBuilder();
        for (Node vertex: sm.getGraph().vertexSet()) {
            s.append(String.format("Node: id=%s, label=%s\n", vertex.getId(), vertex.getLabel().getDisplayName()));
        }

        for (LabeledLink edge: sm.getGraph().edgeSet()) {
            s.append(String.format("Edge: source=%s, target=%s, label=%s\n", edge.getSource().getId(), edge.getTarget().getId(), edge.getLabel().getDisplayName()));
        }
        return s.toString();
    }

    private static Input readInput(String inputFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(new File(inputFile), Input.class);
    }

    private static void writeOutput(Output output, String outputFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputFile), output);
    }

    public static void main(String[] args) throws Exception {
        String karmaHome = args[0];
        String inputFile = args[1];
        String outputFile = args[2];

        Input input = readInput(inputFile);
        SemanticModeling cli = new SemanticModeling(karmaHome, input.topKSteinerTree, input.numCandidateMappings, input.mappingBranchingFactor);

        Output output = cli.runSemanticModeling(input);
        writeOutput(output, outputFile);
    }
}
