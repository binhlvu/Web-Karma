package edu.isi.karma.modeling.cli;

import edu.isi.karma.modeling.ontology.OntologyManager;
import edu.isi.karma.rep.alignment.Label;

public class IdiotOntologyManager extends OntologyManager {
    public IdiotOntologyManager(String contextId) {
        super(contextId);
    }

    @Override
    public Label getUriLabel(String uri) {
        return new Label(uri);
    }
}
