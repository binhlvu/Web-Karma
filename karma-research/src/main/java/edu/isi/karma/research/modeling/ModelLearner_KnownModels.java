/*******************************************************************************
 * Copyright 2012 University of Southern California
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 	http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * This code was developed by the Information Integration Group as part 
 * of the Karma project at the Information Sciences Institute of the 
 * University of Southern California.  For more information, publications, 
 * and related projects, please see: http://www.isi.edu/integration
 ******************************************************************************/

package edu.isi.karma.research.modeling;

import java.io.*;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import com.google.gson.stream.JsonWriter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.jgrapht.graph.WeightedMultigraph;
import org.python.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.isi.karma.config.ModelingConfiguration;
import edu.isi.karma.config.ModelingConfigurationRegistry;
import edu.isi.karma.er.helper.PythonRepository;
import edu.isi.karma.er.helper.PythonRepositoryRegistry;
import edu.isi.karma.modeling.alignment.GraphBuilder;
import edu.isi.karma.modeling.alignment.GraphBuilderTopK;
import edu.isi.karma.modeling.alignment.GraphUtil;
import edu.isi.karma.modeling.alignment.LinkIdFactory;
import edu.isi.karma.modeling.alignment.ModelEvaluation;
import edu.isi.karma.modeling.alignment.NodeIdFactory;
import edu.isi.karma.modeling.alignment.SemanticModel;
import edu.isi.karma.modeling.alignment.SteinerTree;
import edu.isi.karma.modeling.alignment.TreePostProcess;
import edu.isi.karma.modeling.alignment.learner.CandidateSteinerSets;
import edu.isi.karma.modeling.alignment.learner.ModelLearningGraph;
import edu.isi.karma.modeling.alignment.learner.ModelLearningGraphCompact;
import edu.isi.karma.modeling.alignment.learner.ModelLearningGraphType;
import edu.isi.karma.modeling.alignment.learner.ModelReader;
import edu.isi.karma.modeling.alignment.learner.SemanticTypeMapping;
import edu.isi.karma.modeling.alignment.learner.SortableSemanticModel;
import edu.isi.karma.modeling.alignment.learner.SteinerNodes;
import edu.isi.karma.modeling.ontology.OntologyManager;
import edu.isi.karma.modeling.research.Params;
import edu.isi.karma.rep.alignment.ClassInstanceLink;
import edu.isi.karma.rep.alignment.ColumnNode;
import edu.isi.karma.rep.alignment.ColumnSemanticTypeStatus;
import edu.isi.karma.rep.alignment.DataPropertyLink;
import edu.isi.karma.rep.alignment.DefaultLink;
import edu.isi.karma.rep.alignment.InternalNode;
import edu.isi.karma.rep.alignment.Label;
import edu.isi.karma.rep.alignment.LabeledLink;
import edu.isi.karma.rep.alignment.Node;
import edu.isi.karma.rep.alignment.SemanticType;
import edu.isi.karma.rep.alignment.SemanticType.Origin;
import edu.isi.karma.util.RandomGUID;
import edu.isi.karma.webserver.ContextParametersRegistry;
import edu.isi.karma.webserver.ServletContextParameterMap;
import edu.isi.karma.webserver.ServletContextParameterMap.ContextParameter;

public class ModelLearner_KnownModels {

	private static Logger logger = LoggerFactory.getLogger(ModelLearner_KnownModels.class);
	private OntologyManager ontologyManager = null;
	private GraphBuilder graphBuilder = null;
	private NodeIdFactory nodeIdFactory = null; 
	private List<Node> steinerNodes = null;
	
	public ModelLearner_KnownModels(OntologyManager ontologyManager, 
			List<Node> steinerNodes) {
		if (ontologyManager == null || 
				steinerNodes == null || 
				steinerNodes.isEmpty()) {
			logger.error("cannot instanciate model learner!");
			return;
		}
		GraphBuilder gb = ModelLearningGraph.getInstance(ontologyManager, ModelLearningGraphType.Compact).getGraphBuilder();
		this.ontologyManager = ontologyManager;
		this.steinerNodes = steinerNodes;
//		if (this.steinerNodes != null) Collections.sort(this.steinerNodes);
		this.graphBuilder = cloneGraphBuilder(gb); // create a copy of the graph builder
		this.nodeIdFactory = this.graphBuilder.getNodeIdFactory();
	}

	public ModelLearner_KnownModels(GraphBuilder graphBuilder, 
			List<Node> steinerNodes) {
		if (graphBuilder == null || 
				steinerNodes == null || 
				steinerNodes.isEmpty()) {
			logger.error("cannot instanciate model learner!");
			return;
		}
		this.ontologyManager = graphBuilder.getOntologyManager();
		this.steinerNodes = steinerNodes;
//		if (this.steinerNodes != null) Collections.sort(this.steinerNodes);
		this.graphBuilder = cloneGraphBuilder(graphBuilder); // create a copy of the graph builder
		this.nodeIdFactory = this.graphBuilder.getNodeIdFactory();
	}

	private GraphBuilder cloneGraphBuilder(GraphBuilder graphBuilder) {

		GraphBuilder clonedGraphBuilder = null;
		if (graphBuilder == null || graphBuilder.getGraph() == null) {
			clonedGraphBuilder = new GraphBuilderTopK(this.ontologyManager, false);
		} else {
			clonedGraphBuilder = new GraphBuilderTopK(this.ontologyManager, graphBuilder.getGraph());
		}
		return clonedGraphBuilder;
	}

	public List<SortableSemanticModel> hypothesize(boolean useCorrectTypes, int numberOfCandidates) throws Exception {

		ModelingConfiguration modelingConfiguration = ModelingConfigurationRegistry.getInstance().getModelingConfiguration(ontologyManager.getContextId());
		List<SortableSemanticModel> sortableSemanticModels = new ArrayList<SortableSemanticModel>();
		Set<Node> addedNodes = new HashSet<Node>(); //They should be deleted from the graph after computing the semantic models

		List<ColumnNode> columnNodes = new LinkedList<ColumnNode>();
		for (Node n : steinerNodes)
			if (n instanceof ColumnNode)
				columnNodes.add((ColumnNode)n);
		
//		long start = System.currentTimeMillis();

		logger.info("finding candidate steiner sets ... ");
		CandidateSteinerSets candidateSteinerSets = getCandidateSteinerSets(steinerNodes, useCorrectTypes, numberOfCandidates, addedNodes);

//		long elapsedTimeMillis = System.currentTimeMillis() - start;
//		float elapsedTimeSec = elapsedTimeMillis/1000F;
//		System.out.println(elapsedTimeSec);

		if (candidateSteinerSets == null || 
				candidateSteinerSets.getSteinerSets() == null || 
				candidateSteinerSets.getSteinerSets().isEmpty()) {
			logger.error("there is no candidate set of steiner nodes.");
			
			DirectedWeightedMultigraph<Node, LabeledLink> tree = 
					new DirectedWeightedMultigraph<Node, LabeledLink>(LabeledLink.class);
			
			for (Node n : steinerNodes)
				tree.addVertex(n);
			
			SemanticModel sm = new SemanticModel(new RandomGUID().toString(), tree);
			SortableSemanticModel sortableSemanticModel = new SortableSemanticModel(sm, null);
			sortableSemanticModels.add(sortableSemanticModel);
			return sortableSemanticModels;
		}
		
		logger.info("graph nodes: " + this.graphBuilder.getGraph().vertexSet().size());
		logger.info("graph links: " + this.graphBuilder.getGraph().edgeSet().size());

		logger.info("number of steiner sets: " + candidateSteinerSets.numberOfCandidateSets());

//		logger.info("updating weights according to training data ...");
//		long start = System.currentTimeMillis();
//		this.updateWeights();
//		long updateWightsElapsedTimeMillis = System.currentTimeMillis() - start;
//		logger.info("time to update weights: " + (updateWightsElapsedTimeMillis/1000F));
		

		
		logger.info("computing steiner trees ...");
		int number = 0;
		for (SteinerNodes sn : candidateSteinerSets.getSteinerSets()) {
			if (sn == null) continue;
			logger.info("computing steiner tree for steiner nodes set " + number + " ...");
//			logger.info(sn.getScoreDetailsString());
//			for (Entry<ColumnNode, SemanticTypeMapping> n : sn.getColumnNodeInfo().entrySet()) {
//				System.out.println(sn.getMappingToSourceColumns().get(n.getKey()).getColumnName() + "---" +
//						n.getValue().getLink().getId());
//			}
			number++;
//			logger.info("START ...");
			
			List<DirectedWeightedMultigraph<Node, LabeledLink>> topKSteinerTrees;
			if (this.graphBuilder instanceof GraphBuilderTopK) {
				topKSteinerTrees =  ((GraphBuilderTopK)this.graphBuilder).getTopKSteinerTrees(sn, 
						modelingConfiguration.getTopKSteinerTree(), 
						5, 3, true);
			} 
			else 
			{
				topKSteinerTrees = new LinkedList<DirectedWeightedMultigraph<Node, LabeledLink>>();
				SteinerTree steinerTree = new SteinerTree(
						new AsUndirectedGraph<Node, DefaultLink>(this.graphBuilder.getGraph()), Lists.newLinkedList(sn.getNodes()));
				WeightedMultigraph<Node, DefaultLink> t = steinerTree.getDefaultSteinerTree();
				TreePostProcess treePostProcess = new TreePostProcess(this.graphBuilder, t);
				if (treePostProcess.getTree() != null)
					topKSteinerTrees.add(treePostProcess.getTree());
			}
			

			
//			System.out.println(GraphUtil.labeledGraphToString(treePostProcess.getTree()));
			
//			logger.info("END ...");

			for (DirectedWeightedMultigraph<Node, LabeledLink> tree: topKSteinerTrees) {
				if (tree != null) {
//					System.out.println();
					SemanticModel sm = new SemanticModel(new RandomGUID().toString(), 
							tree,
							columnNodes,
							sn.getMappingToSourceColumns()
							);
					SortableSemanticModel sortableSemanticModel = 
							new SortableSemanticModel(sm, sn);
					sortableSemanticModels.add(sortableSemanticModel);
					
//					System.out.println(GraphUtil.labeledGraphToString(sm.getGraph()));
//					System.out.println(sortableSemanticModel.getLinkCoherence().printCoherenceList());
				}
			}
			if (number >= modelingConfiguration.getNumCandidateMappings())
				break;

		}
		
		Collections.sort(sortableSemanticModels);
//		int count = Math.min(sortableSemanticModels.size(), modelingConfiguration.getNumCandidateMappings());

		logger.info("results are ready ...");
//		sortableSemanticModels.get(0).print();
//		return sortableSemanticModels.subList(0, count);

		List<SortableSemanticModel> uniqueModels = new ArrayList<SortableSemanticModel>();
		SortableSemanticModel current, previous;
		if (sortableSemanticModels != null) {
			if (sortableSemanticModels.size() > 0)
				uniqueModels.add(sortableSemanticModels.get(0));
			for (int i = 1; i < sortableSemanticModels.size(); i++) {
				current = sortableSemanticModels.get(i);
				previous = sortableSemanticModels.get(i - 1);
				if (current.getScore() == previous.getScore() && current.getCost() == previous.getCost())
					continue;
				uniqueModels.add(current);
			}
		}
		
		logger.info("results are ready ...");
		return uniqueModels;

	}

//	private DirectedWeightedMultigraph<Node, LabeledLink> computeSteinerTree(Set<Node> steinerNodes) {
//
//		if (steinerNodes == null || steinerNodes.size() == 0) {
//			logger.error("There is no steiner node.");
//			return null;
//		}
//
//		//		System.out.println(steinerNodes.size());
//		List<Node> steinerNodeList = new ArrayList<Node>(steinerNodes); 
//
//		long start = System.currentTimeMillis();
//		UndirectedGraph<Node, DefaultLink> undirectedGraph = new AsUndirectedGraph<Node, DefaultLink>(this.graphBuilder.getGraph());
//
//		logger.debug("computing steiner tree ...");
//		SteinerTree steinerTree = new SteinerTree(undirectedGraph, steinerNodeList);
//		DirectedWeightedMultigraph<Node, LabeledLink> tree = new TreePostProcess(this.graphBuilder, steinerTree.getDefaultSteinerTree(), null, false).getTree();
//		//(DirectedWeightedMultigraph<Node, LabeledLink>)GraphUtil.asDirectedGraph(steinerTree.getDefaultSteinerTree());
//
//		logger.debug(GraphUtil.labeledGraphToString(tree));
//
//		long steinerTreeElapsedTimeMillis = System.currentTimeMillis() - start;
//		logger.debug("total number of nodes in steiner tree: " + tree.vertexSet().size());
//		logger.debug("total number of edges in steiner tree: " + tree.edgeSet().size());
//		logger.debug("time to compute steiner tree: " + (steinerTreeElapsedTimeMillis/1000F));
//
//		return tree;
//
//		//		long finalTreeElapsedTimeMillis = System.currentTimeMillis() - steinerTreeElapsedTimeMillis;
//		//		DirectedWeightedMultigraph<Node, Link> finalTree = buildOutputTree(tree);
//		//		logger.info("time to build final tree: " + (finalTreeElapsedTimeMillis/1000F));
//
//		//		GraphUtil.printGraph(finalTree);
//		//		return finalTree; 
//
//	}

	private CandidateSteinerSets getCandidateSteinerSets(List<Node> steinerNodes, boolean useCorrectTypes, int numberOfCandidates, Set<Node> addedNodes) {

		if (steinerNodes == null || steinerNodes.isEmpty())
			return null;

		int maxNumberOfSteinerNodes = steinerNodes.size() * 2;
		CandidateSteinerSets candidateSteinerSets = new CandidateSteinerSets(maxNumberOfSteinerNodes,ontologyManager.getContextId());

		if (addedNodes == null) 
			addedNodes = new HashSet<Node>();

		Set<SemanticTypeMapping> tempSemanticTypeMappings;
		HashMap<ColumnNode, List<SemanticType>> columnSemanticTypes = new HashMap<ColumnNode, List<SemanticType>>();
		HashMap<String, Integer> semanticTypesCount = new HashMap<String, Integer>();
		List<SemanticType> candidateSemanticTypes = null;
		String domainUri = "", propertyUri = "";

		for (Node n : steinerNodes) {

			ColumnNode cn = null;
			if (n instanceof ColumnNode)
				cn = (ColumnNode)n;
			else
				continue;
			
			if (!useCorrectTypes) {
				candidateSemanticTypes = cn.getTopKLearnedSemanticTypes(numberOfCandidates);
			} else if (cn.getSemanticTypeStatus() == ColumnSemanticTypeStatus.UserAssigned) {
				candidateSemanticTypes = cn.getUserSemanticTypes();
			}

				
			if (candidateSemanticTypes == null) {
				logger.error("No candidate semantic type found for the column " + cn.getColumnName());
				return null;
			}
			
			columnSemanticTypes.put(cn, candidateSemanticTypes);

			for (SemanticType semanticType: candidateSemanticTypes) {

				if (semanticType == null || 
						semanticType.getDomain() == null ||
						semanticType.getType() == null) continue;

				domainUri = semanticType.getDomain().getUri();
				propertyUri = semanticType.getType().getUri();

				Integer count = semanticTypesCount.get(domainUri + propertyUri);
				if (count == null) semanticTypesCount.put(domainUri + propertyUri, 1);
				else semanticTypesCount.put(domainUri + propertyUri, count.intValue() + 1);
			}
		}

		long numOfMappings = 1;
		
		for (Node n : steinerNodes) {

			if (n instanceof InternalNode) 
				continue;
			
			ColumnNode cn = null;
			if (n instanceof ColumnNode)
				cn = (ColumnNode)n;
			else
				continue;
			
			candidateSemanticTypes = columnSemanticTypes.get(n);
			if (candidateSemanticTypes == null) continue;

			logger.info("===== Column: " + cn.getColumnName());

			Set<SemanticTypeMapping> semanticTypeMappings = null; 
			
//			if (cn.hasUserType()) {
//				HashMap<SemanticType, LabeledLink> domainLinks = 
//						GraphUtil.getDomainLinks(this.graphBuilder.getGraph(), cn, cn.getUserSemanticTypes());
//				if (domainLinks != null && !domainLinks.isEmpty()) {
//					for (SemanticType st : cn.getUserSemanticTypes()) {
//						semanticTypeMappings = new HashSet<SemanticTypeMapping>();
//						LabeledLink domainLink = domainLinks.get(st);
//						if (domainLink == null || domainLink.getSource() == null || !(domainLink.getSource() instanceof InternalNode))
//							continue;
//						SemanticTypeMapping mp = 
//								new SemanticTypeMapping(cn, st, (InternalNode)domainLink.getSource(), domainLink, cn);
//						semanticTypeMappings.add(mp);
//						candidateSteinerSets.updateSteinerSets(semanticTypeMappings);
//					}
//				}
//			} else 
			{
				semanticTypeMappings = new HashSet<SemanticTypeMapping>();
				for (SemanticType semanticType: candidateSemanticTypes) {
	
					logger.info("\t" + semanticType.getConfidenceScore() + " :" + semanticType.getModelLabelString());
	
					if (semanticType == null || 
							semanticType.getDomain() == null ||
							semanticType.getType() == null) continue;
	
					domainUri = semanticType.getDomain().getUri();
					
					propertyUri = semanticType.getType().getUri();
					Integer countOfSemanticType = semanticTypesCount.get(domainUri + propertyUri);
					logger.debug("count of semantic type: " +  countOfSemanticType);
	
					
					tempSemanticTypeMappings = findSemanticTypeInGraph(cn, semanticType, semanticTypesCount, addedNodes);
					logger.debug("number of matches for semantic type: " +  
						 + (tempSemanticTypeMappings == null ? 0 : tempSemanticTypeMappings.size()));
	
					if (tempSemanticTypeMappings != null) 
						semanticTypeMappings.addAll(tempSemanticTypeMappings);
	
					int countOfMatches = tempSemanticTypeMappings == null ? 0 : tempSemanticTypeMappings.size();
//					if (countOfMatches < countOfSemanticType) 
					if (countOfMatches == 0) // No struct in graph is matched with the semantic type, we add a new struct to the graph
					{
						SemanticTypeMapping mp = addSemanticTypeStruct(cn, semanticType, addedNodes);
						if (mp != null)
							semanticTypeMappings.add(mp);
					}
				}

				//			System.out.println("number of matches for column " + n.getColumnName() + 
				//					": " + (semanticTypeMappings == null ? 0 : semanticTypeMappings.size()));
				logger.debug("number of matches for column " + cn.getColumnName() + 
						": " + (semanticTypeMappings == null ? 0 : semanticTypeMappings.size()));
				numOfMappings *= (semanticTypeMappings == null || semanticTypeMappings.isEmpty() ? 1 : semanticTypeMappings.size());
	
				logger.debug("number of candidate steiner sets before update: " + candidateSteinerSets.getSteinerSets().size());
				candidateSteinerSets.updateSteinerSets(semanticTypeMappings);
				logger.debug("number of candidate steiner sets after update: " + candidateSteinerSets.getSteinerSets().size());
			}
		}

		for (Node n : steinerNodes) {
			if (n instanceof InternalNode) {
				candidateSteinerSets.updateSteinerSets((InternalNode)n);
			}
		}
		
		//		System.out.println("number of possible mappings: " + numOfMappings);
		logger.info("number of possible mappings: " + numOfMappings);

		return candidateSteinerSets;
	}

	private Set<SemanticTypeMapping> findSemanticTypeInGraph(ColumnNode sourceColumn, SemanticType semanticType, 
			HashMap<String, Integer> semanticTypesCount, Set<Node> addedNodes) {

		logger.debug("finding matches for semantic type in the graph ... ");
		ModelingConfiguration modelingConfiguration = ModelingConfigurationRegistry.getInstance().getModelingConfiguration(ontologyManager.getContextId());
		if (addedNodes == null)
			addedNodes = new HashSet<Node>();

		Set<SemanticTypeMapping> mappings = new HashSet<SemanticTypeMapping>();

		if (semanticType == null) {
			logger.error("semantic type is null.");
			return mappings;

		}
		if (semanticType.getDomain() == null) {
			logger.error("semantic type does not have any domain");
			return mappings;
		}

		if (semanticType.getType() == null) {
			logger.error("semantic type does not have any link");
			return mappings;
		}

		String domainUri = semanticType.getDomain().getUri();
		String propertyUri = semanticType.getType().getUri();
		Double confidence = semanticType.getConfidenceScore();
		Origin origin = semanticType.getOrigin();

		Integer countOfSemanticType = semanticTypesCount.get(domainUri + propertyUri);
		if (countOfSemanticType == null) {
			logger.error("count of semantic type should not be null or zero");
			return mappings;
		}

		if (domainUri == null || domainUri.isEmpty()) {
			logger.error("semantic type does not have any domain");
			return mappings;
		}

		if (propertyUri == null || propertyUri.isEmpty()) {
			logger.error("semantic type does not have any link");
			return mappings;
		}

		logger.debug("semantic type: " + domainUri + "|" + propertyUri + "|" + confidence + "|" + origin);

		// add dataproperty to existing classes if sl is a data node mapping
		//		Set<Node> foundInternalNodes = new HashSet<Node>();
		Set<SemanticTypeMapping> semanticTypeMatches = this.graphBuilder.getSemanticTypeMatches().get(domainUri + propertyUri);
		if (semanticTypeMatches != null) {
			for (SemanticTypeMapping stm : semanticTypeMatches) {

				SemanticTypeMapping mp = 
						new SemanticTypeMapping(sourceColumn, semanticType, stm.getSource(), stm.getLink(), stm.getTarget());
				mappings.add(mp);
				//				foundInternalNodes.add(stm.getSource());
			}
		}

		logger.debug("adding data property to the found internal nodes ...");

		Integer count;
		boolean allowMultipleSamePropertiesPerNode = modelingConfiguration.isMultipleSamePropertyPerNode();
		Set<Node> nodesWithSameUriOfDomain = this.graphBuilder.getUriToNodesMap().get(domainUri);
		if (nodesWithSameUriOfDomain != null) { 
			for (Node source : nodesWithSameUriOfDomain) {
				count = this.graphBuilder.getNodeDataPropertyCount().get(source.getId() + propertyUri);

				if (count != null) {
					if (allowMultipleSamePropertiesPerNode) {
						if (count >= countOfSemanticType.intValue())
							continue;
					} else {
						if (count >= 1) 
							continue;
					}
				}


				String nodeId = new RandomGUID().toString();
				ColumnNode target = new ColumnNode(nodeId, nodeId, sourceColumn.getColumnName(), null);
				if (!this.graphBuilder.addNode(target)) continue;;
				addedNodes.add(target);

				String linkId = LinkIdFactory.getLinkId(propertyUri, source.getId(), target.getId());	
				LabeledLink link = new DataPropertyLink(linkId, new Label(propertyUri));
				if (!this.graphBuilder.addLink(source, target, link)) continue;;

				SemanticTypeMapping mp = new SemanticTypeMapping(sourceColumn, semanticType, (InternalNode)source, link, target);
				mappings.add(mp);
			}
		}

		return mappings;
	}

	private SemanticTypeMapping addSemanticTypeStruct(ColumnNode sourceColumn, SemanticType semanticType, Set<Node> addedNodes) {

		logger.debug("adding semantic type to the graph ... ");

		if (addedNodes == null) 
			addedNodes = new HashSet<Node>();

		if (semanticType == null) {
			logger.error("semantic type is null.");
			return null;

		}
		if (semanticType.getDomain() == null) {
			logger.error("semantic type does not have any domain");
			return null;
		}

		if (semanticType.getType() == null) {
			logger.error("semantic type does not have any link");
			return null;
		}

		String domainUri = semanticType.getDomain().getUri();
		String propertyUri = semanticType.getType().getUri();
		Double confidence = semanticType.getConfidenceScore();
		Origin origin = semanticType.getOrigin();

		if (domainUri == null || domainUri.isEmpty()) {
			logger.error("semantic type does not have any domain");
			return null;
		}

		if (propertyUri == null || propertyUri.isEmpty()) {
			logger.error("semantic type does not have any link");
			return null;
		}

		logger.debug("semantic type: " + domainUri + "|" + propertyUri + "|" + confidence + "|" + origin);

		InternalNode source = null;
		String nodeId;

		nodeId = nodeIdFactory.getNodeId(domainUri);
		source = new InternalNode(nodeId, new Label(domainUri));
		if (!this.graphBuilder.addNodeAndUpdate(source, addedNodes)) return null;

		nodeId = new RandomGUID().toString();
		ColumnNode target = new ColumnNode(nodeId, nodeId, sourceColumn.getColumnName(), null);
		if (!this.graphBuilder.addNode(target)) return null;
		addedNodes.add(target);

		String linkId = LinkIdFactory.getLinkId(propertyUri, source.getId(), target.getId());	
		LabeledLink link;
		if (propertyUri.equalsIgnoreCase(ClassInstanceLink.getFixedLabel().getUri()))
			link = new ClassInstanceLink(linkId);
		else {
			Label label = this.ontologyManager.getUriLabel(propertyUri);
			link = new DataPropertyLink(linkId, label);
		}
		if (!this.graphBuilder.addLink(source, target, link)) return null;

		SemanticTypeMapping mappingStruct = new SemanticTypeMapping(sourceColumn, semanticType, source, link, target);

		return mappingStruct;
	}

//	private void updateWeights() {
//
//		List<DefaultLink> oldLinks = new ArrayList<DefaultLink>();
//
//		List<Node> sources = new ArrayList<Node>();
//		List<Node> targets = new ArrayList<Node>();
//		List<LabeledLink> newLinks = new ArrayList<LabeledLink>();
//		List<Double> weights = new ArrayList<Double>();
//
//		HashMap<String, LinkFrequency> sourceTargetLinkFrequency = 
//				new HashMap<String, LinkFrequency>();
//
//		LinkFrequency lf1, lf2;
//
//		String key, key1, key2;
//		String linkUri;
//		for (DefaultLink link : this.graphBuilder.getGraph().edgeSet()) {
//			linkUri = link.getUri();
//			if (!linkUri.equalsIgnoreCase(Uris.DEFAULT_LINK_URI)) {
//				if (link.getTarget() instanceof InternalNode && !linkUri.equalsIgnoreCase(Uris.RDFS_SUBCLASS_URI)) {
//					key = "domain:" + link.getSource().getLabel().getUri() + ",link:" + linkUri + ",range:" + link.getTarget().getLabel().getUri();
//					Integer count = this.graphBuilder.getLinkCountMap().get(key);
//					if (count != null)
//						this.graphBuilder.changeLinkWeight(link, ModelingParams.PATTERN_LINK_WEIGHT - ((double)count / (double)this.graphBuilder.getNumberOfModelLinks()) );
//				}
//				continue;
//			}
//
//			key1 = link.getSource().getLabel().getUri() + 
//					link.getTarget().getLabel().getUri();
//			key2 = link.getTarget().getLabel().getUri() + 
//					link.getSource().getLabel().getUri();
//
//			lf1 = sourceTargetLinkFrequency.get(key1);
//			if (lf1 == null) {
//				lf1 = this.graphBuilder.getMoreFrequentLinkBetweenNodes(link.getSource().getLabel().getUri(), link.getTarget().getLabel().getUri());
//				sourceTargetLinkFrequency.put(key1, lf1);
//			}
//
//			lf2 = sourceTargetLinkFrequency.get(key2);
//			if (lf2 == null) {
//				lf2 = this.graphBuilder.getMoreFrequentLinkBetweenNodes(link.getTarget().getLabel().getUri(), link.getSource().getLabel().getUri());
//				sourceTargetLinkFrequency.put(key2, lf2);
//			}
//
//			int c = lf1.compareTo(lf2);
//			String id = null;
//			if (c > 0) {
//				sources.add(link.getSource());
//				targets.add(link.getTarget());
//
//				id = LinkIdFactory.getLinkId(lf1.getLinkUri(), link.getSource().getId(), link.getTarget().getId());
//				if (link instanceof ObjectPropertyLink)
//					newLinks.add(new ObjectPropertyLink(id, new Label(lf1.getLinkUri()), ((ObjectPropertyLink) link).getObjectPropertyType()));
//				else if (link instanceof SubClassLink)
//					newLinks.add(new SubClassLink(id));
//
//				weights.add(lf1.getWeight());
//			} else if (c < 0) {
//				sources.add(link.getTarget());
//				targets.add(link.getSource());
//
//				id = LinkIdFactory.getLinkId(lf2.getLinkUri(), link.getSource().getId(), link.getTarget().getId());
//				if (link instanceof ObjectPropertyLink)
//					newLinks.add(new ObjectPropertyLink(id, new Label(lf2.getLinkUri()), ((ObjectPropertyLink) link).getObjectPropertyType()));
//				else if (link instanceof SubClassLink)
//					newLinks.add(new SubClassLink(id));
//
//				weights.add(lf2.getWeight());
//			} else
//				continue;
//
//			oldLinks.add(link);
//		}
//
//		for (DefaultLink link : oldLinks)
//			this.graphBuilder.getGraph().removeEdge(link);
//
//		LabeledLink newLink;
//		for (int i = 0; i < newLinks.size(); i++) {
//			newLink = newLinks.get(i);
//			this.graphBuilder.addLink(sources.get(i), targets.get(i), newLink);
//			this.graphBuilder.changeLinkWeight(newLink, weights.get(i));
//		}
//	}

	private static double roundDecimals(double d, int k) {
		String format = "";
		for (int i = 0; i < k; i++) format += "#";
        DecimalFormat DForm = new DecimalFormat("#." + format);
        return Double.valueOf(DForm.format(d));
	}

	private static void getStatistics(List<SemanticModel> semanticModels) {
		for (int i = 0; i < semanticModels.size(); i++) {
			SemanticModel source = semanticModels.get(i);
			int attributeCount = source.getColumnNodes().size();
			int nodeCount = source.getGraph().vertexSet().size();
			int linkCount = source.getGraph().edgeSet().size();
			int datanodeCount = 0;
			int classNodeCount = 0;
			for (Node n : source.getGraph().vertexSet()) {
				if (n instanceof InternalNode) classNodeCount++;
				if (n instanceof ColumnNode) datanodeCount++;
			}
//			System.out.println(attributeCount + "\t" + nodeCount + "\t" + linkCount + "\t" + classNodeCount + "\t" + datanodeCount);
			
			List<ColumnNode> columnNodes = source.getColumnNodes();

			if (columnNodes == null)
				return;
			
			
			int numberOfAttributesWhoseTypeIsFirstCRFType = 0;
			int numberOfAttributesWhoseTypeIsInCRFTypes = 0;
			for (ColumnNode cn : columnNodes) {
				List<SemanticType> userSemanticTypes = cn.getUserSemanticTypes();
				List<SemanticType> top4Suggestions = cn.getTopKLearnedSemanticTypes(4);

				for (int j = 0; j < top4Suggestions.size(); j++) {
					SemanticType st = top4Suggestions.get(j);
					if (userSemanticTypes != null) {
						for (SemanticType t : userSemanticTypes) {
							if (st.getModelLabelString().equalsIgnoreCase(t.getModelLabelString())) {
								if (j == 0) numberOfAttributesWhoseTypeIsFirstCRFType ++;
								numberOfAttributesWhoseTypeIsInCRFTypes ++;
								j = top4Suggestions.size();
								break;
							}
						}
					} 
				}

			}

//			System.out.println(numberOfAttributesWhoseTypeIsInCRFTypes + "\t" + numberOfAttributesWhoseTypeIsFirstCRFType);
			
			System.out.println(
					attributeCount + "\t" + 
					nodeCount + "\t" + 
					linkCount + "\t" + 
					(linkCount - attributeCount) + "\t" +
					classNodeCount + "\t" + 
					datanodeCount + "\t" + 
					numberOfAttributesWhoseTypeIsInCRFTypes + "\t" + 
					numberOfAttributesWhoseTypeIsFirstCRFType);

		}
	}

	public static String toJSONString(SemanticModel sm) throws Exception {
		StringWriter out = new StringWriter();
		JsonWriter writer = new JsonWriter(out);

		sm.writeModel(writer);
		return out.toString();
	}

	public static void write2File(String fileName, List<String> lines) throws Exception {
		PrintWriter writer = new PrintWriter(fileName, "UTF-8");
		for (String line: lines) {
			writer.println(line);
		}
		writer.close();
	}
	
	
	public static void main(String[] args) throws Exception {
		// BINH: Add code to handle cli arg
		boolean useCorrectType = true;
		int numberOfCandidates = 4;
		int cutoff = 10; // this is to trim off the candidate models, (after combining all topKSteinerTree)

//		args = new String[]{
//				"-karma_home", "/Users/rook/workspace/DataIntegration/SourceModeling/debug/american_art_small/mohsen_jws2015/",
//				"-dataset_name", "american_art_small", "-use_correct_type", "false",
//				"-num_candidate_semantic_type", "4", "-multiple_same_property_per_node",
//				"true", "-coefficient_coherence", "1.0", "-coefficient_confidence", "1.0",
//				"-coefficient_size", "0.5", "-num_candidate_mappings", "50",
//				"-mapping_branching_factor", "50", "-topk_steiner_tree", "10",
//				"-cutoff", "1000000",
//				"-train_source_names", "s00---acm---acm-artist,s01---acm---acm-objects,s02---acm---acm-media,s03---autry---AutryCultureMade,s04---autry---AutryDated,s05---autry---AutryMakers,s06---autry---AutryMedia,s07---autry---AutryObjects,s08---autry---AutryPubDesc,s11---cbm---CBMAA_OtherTitles,s12---cbm---CBMAA_Titles,s14---cbm---CBMAA_URLs",
//				"-test_source_names", "s15---cbm---CBMAA_Roles,s16---cbm---PG_Constituents,s17---cbm---PG_Objects,s18---cbm---PG_OtherTitles,s19---cbm---PG_Titles,s20---cbm---PG_UnknownTitles,s21---cbm---PG_URLs,s22---cbm---PG_Roles,s23---ccma---ccma_artists,s24---ccma---ccma_objects,s25---DMA---Constituents,s26---DMA---Objects"};

		CliArg cliArg = new CliArg(args);
		Params.ROOT_DIR = cliArg.karmaHome.endsWith("/") ? cliArg.karmaHome : cliArg.karmaHome + "/";
		Params.DATASET_NAME = cliArg.datasetName;

		useCorrectType = cliArg.useCorrectType;
		numberOfCandidates = cliArg.numCandidateSemanticType;
		cutoff = cliArg.cutoff;

		// reset Params according to CLI Args
		Params.MODEL_MAIN_FILE_EXT = "model.json";
		Params.MODEL_DIR = Params.ROOT_DIR + "models-json/";
		Params.ONTOLOGY_DIR = Params.ROOT_DIR + "preloaded-ontologies/";
		Params.OUTPUT_DIR = Params.ROOT_DIR + "output/";

		Params.GRAPHS_DIR = Params.ROOT_DIR + "alignment-graph/";
		Params.GRAPHVIS_DIR = Params.ROOT_DIR + "models-graphviz/";
		Params.SOURCE_DIR = Params.ROOT_DIR + "sources/";
		Params.R2RML_DIR = Params.ROOT_DIR + "models-r2rml/";
		Params.RESULTS_DIR = Params.ROOT_DIR + "results/";

		Params.LOD_DIR = Params.ROOT_DIR + "lod/";
		Params.PATTERNS_DIR = Params.LOD_DIR + "patterns/";
		Params.LOD_OBJECT_PROPERIES_FILE = Params.LOD_DIR + "objectproperties.csv";
		Params.LOD_DATA_PROPERIES_FILE = Params.LOD_DIR + "dataproperties.csv";

		///////////////////////////////////////////////////////////////////////////

		/***
		 * When running with k=1, change the flag "multiple.same.property.per.node" to true so all attributes have at least one semantic types
		 */
		ServletContextParameterMap contextParameters = ContextParametersRegistry.getInstance().registerByKarmaHome(Params.ROOT_DIR);
		contextParameters.setParameterValue(ContextParameter.USER_DIRECTORY_PATH, Params.ROOT_DIR);
		contextParameters.setParameterValue(ContextParameter.USER_CONFIG_DIRECTORY, Params.ROOT_DIR + "config");
		contextParameters.setParameterValue(ContextParameter.TRAINING_EXAMPLE_MAX_COUNT, "1000000");
		contextParameters.setParameterValue(ContextParameter.SEMTYPE_MODEL_DIRECTORY, Params.ROOT_DIR + "semantic-type-files/");
		contextParameters.setParameterValue(ContextParameter.JSON_MODELS_DIR, Params.MODEL_DIR);
		contextParameters.setParameterValue(ContextParameter.GRAPHVIZ_MODELS_DIR, Params.GRAPHVIS_DIR);
		contextParameters.setParameterValue(ContextParameter.USER_PYTHON_SCRIPTS_DIRECTORY, Params.ROOT_DIR + "python/");
		contextParameters.setParameterValue(ContextParameter.EVALUATE_MRR, Params.ROOT_DIR + "evaluate-mrr/");
		PythonRepository pythonRepository = new PythonRepository(true, contextParameters.getParameterValue(ContextParameter.USER_PYTHON_SCRIPTS_DIRECTORY));
		PythonRepositoryRegistry.getInstance().register(pythonRepository);

		// BINH: Code for CLI
		if (cliArg != null) {
			ModelingConfiguration modelingConfiguration = ModelingConfigurationRegistry.getInstance().getModelingConfiguration(contextParameters.getId());
			cliArg.updateModelingConfiguration(modelingConfiguration);

			DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
			FileUtils.cleanDirectory(new File(Params.OUTPUT_DIR));
			Date date = new Date();
			write2File(Params.OUTPUT_DIR + ".meta", Arrays.asList(
					String.format("created: %s", dateFormat.format(date)),
					String.format("cli params: %s", StringUtils.join(args, " ")),
					"===================================================",
					"Controlling parameters:",
					String.format("\t KarmaHome: %s", Params.ROOT_DIR),
					String.format("\t Dataset name: %s", Params.DATASET_NAME),
					String.format("\t Output dir: %s", Params.OUTPUT_DIR),
					String.format("\t useCorrectType: %s", useCorrectType),
					String.format("\t numberOfCandidates: %s", numberOfCandidates),
					String.format("\t cutoff: %s", cutoff),
					String.format("\t train_source_names: %s", Arrays.toString(cliArg.trainSourceNames)),
					String.format("\t test_source_names: %s", Arrays.toString(cliArg.testSourceNames)),
					"",
					String.format("\t modelingConfiguration.numCandidateMappings: %s", modelingConfiguration.getNumCandidateMappings()),
					String.format("\t modelingConfiguration.mappingBranchingFactor: %s", modelingConfiguration.getMappingBranchingFactor()),
					String.format("\t modelingConfiguration.multipleSamePropertyPerNode: %s", modelingConfiguration.isMultipleSamePropertyPerNode()),
					String.format("\t modelingConfiguration.topKSteinerTree: %s", modelingConfiguration.getTopKSteinerTree()),
					"",
					String.format("\t modelingConfiguration.scoringCoherenceSCoefficient: %s", modelingConfiguration.getScoringCoherenceSCoefficient()),
					String.format("\t modelingConfiguration.scoringConfidenceCoefficient: %s", modelingConfiguration.getScoringConfidenceCoefficient()),
					String.format("\t modelingConfiguration.scoringSizeCoefficient: %s", modelingConfiguration.getScoringSizeCoefficient())
			));
		}
		////////////////////////////////////////////////////////////////////

		//		String inputPath = Params.INPUT_DIR;
		String graphPath = Params.GRAPHS_DIR;
		File semFilesFolder = new File(contextParameters.getParameterValue(ContextParameter.SEMTYPE_MODEL_DIRECTORY));

		//		List<SemanticModel> semanticModels = ModelReader.importSemanticModels(inputPath);
		List<SemanticModel> semanticModels = 
				ModelReader.importSemanticModelsFromJsonFiles(Params.MODEL_DIR, Params.MODEL_MAIN_FILE_EXT);

		File[] sources = new File(Params.SOURCE_DIR).listFiles();
		File[] r2rmlModels = new File(Params.R2RML_DIR).listFiles();

		Arrays.sort(sources);
		Arrays.sort(r2rmlModels);
		if (sources.length > 0 && sources[0].getName().startsWith(".")) 
			sources = (File[]) ArrayUtils.removeElement(sources, sources[0]);
		if (r2rmlModels.length > 0 && r2rmlModels[0].getName().startsWith(".")) 
			r2rmlModels = (File[]) ArrayUtils.removeElement(r2rmlModels, r2rmlModels[0]);


		List<SemanticModel> trainingData = new ArrayList<SemanticModel>();

		OntologyManager ontologyManager = new OntologyManager(contextParameters.getId());
		File ff = new File(Params.ONTOLOGY_DIR);
		File[] files = ff.listFiles();
		for (File f : files) {
			if(f.getName().startsWith(".") || f.isDirectory()) {
				continue; //Ignore . files
			}
			ontologyManager.doImport(f, "UTF-8");
		}
		ontologyManager.updateCache();  

		ModelLearningGraph modelLearningGraph = null;
		
		ModelLearner_KnownModels modelLearner;
		
		boolean iterativeEvaluation = false;
		boolean zeroKnownModel = false;

		String filePath = Params.RESULTS_DIR + "temp/";
		String filename = ""; 
		filename += "results";
		filename += useCorrectType ? "-correct":"-k=" + numberOfCandidates;
		filename += zeroKnownModel ? "-ontology":"";
		filename += iterativeEvaluation ? "-iterative":"";
		filename += ".csv"; 
		
		PrintWriter resultFileIterative = null;
		PrintWriter resultFile = null;
		StringBuffer[] resultsArray = null;
		
		if (iterativeEvaluation) {
			resultFileIterative = new PrintWriter(new File(filePath + filename));
			resultsArray = new StringBuffer[semanticModels.size() + 2];
			for (int i = 0; i < resultsArray.length; i++) {
				resultsArray[i] = new StringBuffer();
			}
		} else {
			resultFile = new PrintWriter(new File(filePath + filename));
			resultFile.println("source \t p \t r \t t \t a \t m \n");
		}

		// BINH: setup training data
		// clean semantic files folder in karma home
		FileUtils.cleanDirectory(semFilesFolder);
		trainingData.clear();
		int count = 0;
		for (int i = 0; i < semanticModels.size(); i++) {
			SemanticModel sm = semanticModels.get(i);
			for (String sourceName: cliArg.trainSourceNames) {
				if (sm.getId().startsWith(sourceName)) {
					// using startsWith so that we can use prefix to test faster
					trainingData.add(sm);
					count++;
				}
			}
		}
		if (count != cliArg.trainSourceNames.length) {
			throw new Exception("Invalid train source names");
		}

		// BINH: testing later
		for (String sourceName: cliArg.testSourceNames) {
			SemanticModel newSource = null;
			for (SemanticModel sm: semanticModels) {
				if (sm.getId().startsWith(sourceName)) {
					if (newSource != null) {
						throw new Exception("Source name is not unique!");
					} else {
						newSource = sm;
					}
				}
			}

			logger.info("======================================================");
			logger.info(newSource.getName() + "(#attributes:" + newSource.getColumnNodes().size() + ")");
			logger.info("======================================================");

			SemanticModel correctModel;
			if (useCorrectType) {
				correctModel = newSource;
				correctModel.setAccuracy(1.0);
				correctModel.setMrr(1.0);
			} else {
				// BINH: set correctModel to be a GoldModel, so that
				// it will use learned semantic types of that
				correctModel = newSource;
			}
			List<ColumnNode> columnNodes = correctModel.getColumnNodes();

			List<Node> steinerNodes = new LinkedList<Node>(columnNodes);

			modelLearningGraph = (ModelLearningGraphCompact)ModelLearningGraph.getEmptyInstance(ontologyManager, ModelLearningGraphType.Compact);
			logger.info("building the graph ...");
			long start = System.currentTimeMillis();
			for (SemanticModel sm : trainingData)
				modelLearningGraph.addModelAndUpdate(sm, false);
			modelLearner = new ModelLearner_KnownModels(modelLearningGraph.getGraphBuilder(), steinerNodes);

			List<SortableSemanticModel> hypothesisList = modelLearner.hypothesize(useCorrectType, numberOfCandidates);

			long elapsedTimeMillis = System.currentTimeMillis() - start;
			float elapsedTimeSec = elapsedTimeMillis/1000F;

			List<SortableSemanticModel> topHypotheses = null;
			if (hypothesisList != null) {
				topHypotheses = hypothesisList.size() > cutoff ?
						hypothesisList.subList(0, cutoff) :
						hypothesisList;
			}
			List<String> serializedTopHypotheses = new ArrayList<>();

			Map<String, SemanticModel> models =
					new TreeMap<String, SemanticModel>();

			ModelEvaluation me;
			models.put("1-correct model", correctModel);
			if (topHypotheses != null) {
				for (int k = 0; k < topHypotheses.size(); k++) {
					SortableSemanticModel m = topHypotheses.get(k);
					serializedTopHypotheses.add(toJSONString(m));
					// BINH: UNCOMMENT TO SEE THE EVALUATION (IT MAY RUNS SLOW)
					System.out.println("===========================================================");
					System.out.println("newSource=" + newSource.getName());
					me = m.evaluate(correctModel, false, false);

					String label = "candidate " + k + "\n" +
//								(m.getSteinerNodes() == null ? "" : m.getSteinerNodes().getScoreDetailsString()) +
							"link coherence:" + (m.getLinkCoherence() == null ? "" : m.getLinkCoherence().getCoherenceValue()) + "\n";
					label += (m.getSteinerNodes() == null || m.getSteinerNodes().getCoherence() == null) ?
							"" : "node coherence:" + m.getSteinerNodes().getCoherence().getCoherenceValue() + "\n";
					label += "confidence:" + m.getConfidenceScore() + "\n";
					label += m.getSteinerNodes() == null ? "" : "mapping score:" + m.getSteinerNodes().getScore() + "\n";
					label +=
							"cost:" + roundDecimals(m.getCost(), 6) + "\n" +
									//								"-distance:" + me.getDistance() +
									"-precision:" + me.getPrecision() +
									"-recall:" + me.getRecall();

					models.put(label, m);

					if (k == 0) { // first rank model
						System.out.println("newSource=" + newSource.getName() + ", number of known models: " + cliArg.trainSourceNames.length +
								", precision: " + me.getPrecision() +
								", recall: " + me.getRecall() +
								", time: " + elapsedTimeSec +
								", accuracy: " + correctModel.getAccuracy() +
								", mrr: " + correctModel.getMrr());
						logger.info("number of known models: " + cliArg.trainSourceNames.length +
								", precision: " + me.getPrecision() +
								", recall: " + me.getRecall() +
								", time: " + elapsedTimeSec +
								", accuracy: " + correctModel.getAccuracy() +
								", mrr: " + correctModel.getMrr());
						String s = newSource.getName() + "\t" +
								me.getPrecision() + "\t" +
								me.getRecall() + "\t" +
								elapsedTimeSec + "\t" +
								correctModel.getAccuracy() + "\t" +
								correctModel.getMrr();
						resultFile.println(s);
					}
				}
			}

			// BINH: write the sm candidate generation result to file in folder: output/
			write2File(Params.OUTPUT_DIR + String.format("source--%s.json", newSource.getName()), serializedTopHypotheses);

			// BINH: comment out the export semantic models to graphviz below
//				String outputPath = Params.OUTPUT_DIR;
//				String outName = !iterativeEvaluation?
//						outputPath + semanticModels.get(newSourceIndex).getName() + Params.GRAPHVIS_OUT_DETAILS_FILE_EXT :
//							outputPath + semanticModels.get(newSourceIndex).getName() + ".knownModels=" + numberOfKnownModels + Params.GRAPHVIS_OUT_DETAILS_FILE_EXT;
//				GraphVizUtil.exportSemanticModelsToGraphviz(
//						models,
//						newSource.getName(),
//						outName,
//						GraphVizLabelType.LocalId,
//						GraphVizLabelType.LocalUri,
//						true,
//						true);
		}

		resultFile.close();
	}
}
