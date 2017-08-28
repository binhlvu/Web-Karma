package edu.isi.karma.modeling.alignment;

import edu.isi.karma.modeling.ontology.OntologyManager;
import edu.isi.karma.rep.alignment.*;
import edu.isi.karma.util.EncodingDetector;
import edu.isi.karma.webserver.ContextParametersRegistry;
import edu.isi.karma.webserver.ServletContextParameterMap;
import org.codehaus.jackson.map.ObjectMapper;
import org.jgrapht.graph.DirectedWeightedMultigraph;


import java.io.File;
import java.io.IOException;
import java.util.*;


class InputNode {
    public String id;
    public String label;
}

class InputEdge {
    public String id;
    public String source;
    public String target;
    public String label;
    public Double weight;
}


class InputGraph {
    public List<InputNode> nodes;
    public List<InputEdge> edges;
    public List<String> steinerNodes;

    public InputNode getNode(String id) {
        for (InputNode n: nodes) {
            if (n.id.equals(id)) {
                return n;
            }
        }
        throw new RuntimeException("Cannot find node with id: " + id);
    }

    public InputEdge getEdge(String id) {
        for (InputEdge e: edges) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        throw new RuntimeException("Cannot find edge with id: " + id);
    }
}


//class IdiotOntologyManager extends OntologyManager {
//    public IdiotOntologyManager() {
//        super("");
//    }
//}


public class CLITopKSteinerTree {

    private static InputGraph getInput(String inputFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(new File(inputFile), InputGraph.class);
    }

    private static void writeOutput(InputGraph inputGraph, List<DirectedWeightedMultigraph<Node, LabeledLink>> trees, String outputFile) throws IOException {
        List<InputGraph> outputGraphs = new ArrayList<>();

        for (DirectedWeightedMultigraph<Node, LabeledLink> tree : trees) {
            InputGraph graph = new InputGraph();
            graph.nodes = new ArrayList<>();
            graph.edges = new ArrayList<>();
            for (Node n: tree.vertexSet()) {
                graph.nodes.add(inputGraph.getNode(n.getLocalId()));
            }
            for (LabeledLink e: tree.edgeSet()) {
                String eID = e.getId().split("---")[1];
                graph.edges.add(inputGraph.getEdge(eID));
            }

            outputGraphs.add(graph);
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(new File(outputFile), outputGraphs);
    }

    @SuppressWarnings("Duplicates")
    public static void main(String[] args) throws Exception {
        String karmaHome = args[0];
        String ontologiesPath = args[1];
        Integer topK = Integer.parseInt(args[2]);
        String inputFile = args[3];
        String outputFile = args[4];

        // Load ontologies
        ServletContextParameterMap contextParameters = ContextParametersRegistry.getInstance().getContextParameters(karmaHome);
        contextParameters.setParameterValue(ServletContextParameterMap.ContextParameter.USER_CONFIG_DIRECTORY, karmaHome + "/config");

        File ontDir = new File(ontologiesPath);
        if (!ontDir.exists()) {
            throw new RuntimeException("Path to ontologies does not exist");
        }

        File[] ontologies = ontDir.listFiles();
        OntologyManager mgr = new OntologyManager(contextParameters.getId());
        for (File ontology: ontologies) {
            System.out.println(ontology.getName());
            if (ontology.getName().endsWith(".owl") ||
                    ontology.getName().endsWith(".rdf") ||
                    ontology.getName().endsWith(".n3") ||
                    ontology.getName().endsWith(".ttl") ||
                    ontology.getName().endsWith(".xml")) {
                System.out.println("Loading ontology file: " + ontology.getAbsolutePath());
                try {
                    String encoding = EncodingDetector.detect(ontology);
                    mgr.doImport(ontology, encoding);
                } catch (Exception t) {
                    System.err.println("Error loading ontology: " + ontology.getAbsolutePath());
                    throw new RuntimeException(t);
                }
            } else {
                System.err.println("the file: " + ontology.getAbsolutePath() + " does not have proper format: xml/rdf/n3/ttl/owl");
            }
        }

        // update the cache at the end when all files are added to the model
        mgr.updateCache();

        // Load graph
        GraphBuilderTopK gbtk = new GraphBuilderTopK(mgr, false);

        Map<String, Node> nodeMap = new HashMap<>();
        Set<Node> steinerNodes = new HashSet<>();
        InputGraph input = getInput(inputFile);

        for (InputNode n: input.nodes) {
            Node node = new InternalNode(n.id, new Label(n.label));
            gbtk.addNode(node);
            nodeMap.put(node.getId(), node);
        }

        for (InputEdge e: input.edges) {
            ObjectPropertyLink link = new ObjectPropertyLink(LinkIdFactory.getLinkId(e.id, e.source, e.target),
                        new Label(e.label), ObjectPropertyType.Direct);
            gbtk.addLink(nodeMap.get(e.source), nodeMap.get(e.target), link, e.weight);
        }

        for (String nid: input.steinerNodes) {
            steinerNodes.add(nodeMap.get(nid));
        }

        List<DirectedWeightedMultigraph<Node, LabeledLink>> trees = gbtk.getTopKSteinerTrees(steinerNodes, topK, null, null, false);
        writeOutput(input, trees, outputFile);
    }
}
