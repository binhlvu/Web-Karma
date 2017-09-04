package edu.isi.karma.modeling.cli;

import edu.isi.karma.modeling.alignment.GraphBuilderTopK;
import edu.isi.karma.modeling.alignment.LinkIdFactory;
import edu.isi.karma.modeling.ontology.OntologyManager;
import edu.isi.karma.rep.alignment.*;
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


public class TopKSteinerTree {

    private static InputGraph[] getInput(String inputFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(new File(inputFile), InputGraph[].class);
    }

    private static List<InputGraph> getOutput(InputGraph inputGraph, List<DirectedWeightedMultigraph<Node, LabeledLink>> trees) throws IOException {
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

        return outputGraphs;
    }

    private static void writeOutput(List<List<InputGraph>> outputGraphs, String outputFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputFile), outputGraphs);
    }

    @SuppressWarnings("Duplicates")
    public static void main(String[] args) throws Exception {
        String karmaHome = args[0];
        Integer topK = Integer.parseInt(args[1]);
        String inputFile = args[2];
        String outputFile = args[3];

        // Load ontologies
        ServletContextParameterMap contextParameters = ContextParametersRegistry.getInstance().getContextParameters(karmaHome);
        contextParameters.setParameterValue(ServletContextParameterMap.ContextParameter.USER_CONFIG_DIRECTORY, karmaHome + "/config");
        OntologyManager mgr = new IdiotOntologyManager(contextParameters.getId());

        // Load graph
        GraphBuilderTopK gbtk = new GraphBuilderTopK(mgr, false);

        Map<String, Node> nodeMap = new HashMap<>();
        Set<Node> steinerNodes = new HashSet<>();
        InputGraph[] inputs = getInput(inputFile);
        List<List<InputGraph>> outputs = new ArrayList<>();

        for (InputGraph input: inputs) {
            for (InputNode n : input.nodes) {
                Node node = new InternalNode(n.id, new Label(n.label));
                gbtk.addNode(node);
                nodeMap.put(node.getId(), node);
            }

            for (InputEdge e : input.edges) {
                ObjectPropertyLink link = new ObjectPropertyLink(LinkIdFactory.getLinkId(e.id, e.source, e.target),
                        new Label(e.label), ObjectPropertyType.Direct);
                gbtk.addLink(nodeMap.get(e.source), nodeMap.get(e.target), link, e.weight);
            }

            for (String nid : input.steinerNodes) {
                steinerNodes.add(nodeMap.get(nid));
            }

            List<DirectedWeightedMultigraph<Node, LabeledLink>> trees = gbtk.getTopKSteinerTrees(steinerNodes, topK, null, null, true);
            outputs.add(getOutput(input, trees));
        }

        writeOutput(outputs, outputFile);
    }
}
