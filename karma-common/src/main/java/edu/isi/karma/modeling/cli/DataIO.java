package edu.isi.karma.modeling.cli;


import edu.isi.karma.rep.alignment.ColumnNode;
import edu.isi.karma.rep.alignment.Label;
import edu.isi.karma.rep.alignment.SemanticType;

import java.util.ArrayList;
import java.util.List;

class IONode {
    public String id;
    public String label;

    public IONode() {}

    public IONode(String id, String label) {
        this.id = id;
        this.label = label;
    }
}

class IOEdge {
    public String id;
    public String label;
    public String sourceID;
    public String targetID;

    public IOEdge() {}

    public IOEdge(String id, String label, String sourceID, String targetID) {
        this.id = id;
        this.label = label;
        this.sourceID = sourceID;
        this.targetID = targetID;
    }
}


class InputSemanticType {
    public String domain;
    public String type;
    public String origin;
    public double confidenceScore;
}

class InputUserSemanticType extends InputSemanticType {
    public String domainId;
}

class InputSteinerNode extends IONode {
    // input for ColumnNode
    public InputUserSemanticType userSemanticType;
    public InputSemanticType[] semanticTypes;

    public ColumnNode toSteinerNode() {
        //
        ColumnNode n = new ColumnNode(id, id+":"+label, label, null, null);
        if (userSemanticType != null) {
            n.assignUserType(this.toSemanticType(userSemanticType));
        } else {
            List<SemanticType> learnedST = new ArrayList<>();
            for (InputSemanticType st : semanticTypes) {
                learnedST.add(this.toSemanticType(st));
            }
            n.setLearnedSemanticTypes(learnedST);
        }
        return n;
    }

    private SemanticType toSemanticType(InputSemanticType st) {
        String domainId = st.domain;
        if (st instanceof InputUserSemanticType) {
            domainId = ((InputUserSemanticType) st).domainId;
        }
        return new SemanticType(id, new Label(st.type), new Label(st.domain), domainId, true, SemanticType.Origin.valueOf(st.origin), st.confidenceScore);
    }
}

class IOGraph {
    public List<IONode> nodes;
    public List<IOEdge> edges;

    public IOGraph() {
        nodes = new ArrayList<>();
        edges = new ArrayList<>();
    }

    public IONode getNode(String id) {
        for (IONode n: nodes) {
            if (n.id.equals(id)) {
                return n;
            }
        }
        return null;
    }

    public IOEdge getEdge(String id) {
        for (IOEdge e: edges) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        return null;
    }

    public void addNode(IONode node) {
        if (getNode(node.id) != null) {
            throw new RuntimeException("Duplicate node");
        }
        nodes.add(node);
    }

    public void addEdge(IOEdge edge) {
        if (getEdge(edge.id) != null) {
            throw new RuntimeException("Duplicate edge");
        }
        edges.add(edge);
    }
}

class WeightedIOGraph extends IOGraph {
    public double weighted;

    public WeightedIOGraph(double weighted) {
        super();
        this.weighted = weighted;
    }
}