/*
 * Copyright (C) 2015 Stefan Zeller
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.docksnet.rgraph;

import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.docksnet.utils.IncrementableSet;
import ch.docksnet.utils.lcom.CalleesSubgraphAnalyzer;
import ch.docksnet.utils.lcom.CallersSubgraphAnalyzer;
import ch.docksnet.utils.lcom.ClusterAnalyzer;
import ch.docksnet.utils.lcom.LCOMAnalyzerData;
import ch.docksnet.utils.lcom.LCOMNode;
import com.intellij.diagram.DiagramCategory;
import com.intellij.diagram.DiagramDataModel;
import com.intellij.diagram.DiagramEdge;
import com.intellij.diagram.DiagramNode;
import com.intellij.diagram.DiagramProvider;
import com.intellij.diagram.DiagramRelationshipInfo;
import com.intellij.diagram.DiagramRelationshipInfoAdapter;
import com.intellij.diagram.presentation.DiagramLineType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static ch.docksnet.rgraph.PsiUtils.getFqn;

/**
 * @author Stefan Zeller
 */
public class ReferenceDiagramDataModel extends DiagramDataModel<PsiElement> {

    private final Map<String, SmartPsiElementPointer<PsiElement>> elementsAddedByUser = new HashMap();
    private final Map<String, SmartPsiElementPointer<PsiElement>> elementsRemovedByUser = new HashMap();

    private final Collection<DiagramNode<PsiElement>> myNodes = new HashSet<>();
    private final Map<PsiElement, DiagramNode<PsiElement>> myNodesPool = new HashMap<>();
    private final Collection<DiagramEdge<PsiElement>> myEdges = new HashSet<>();

    private final SmartPointerManager spManager;
    private SmartPsiElementPointer<PsiClass> myInitialElement;

    private long currentClusterCount = 0;

    public ReferenceDiagramDataModel(Project project, PsiClass psiClass) {
        super(project, ReferenceDiagramProvider.getInstance());
        spManager = SmartPointerManager.getInstance(getProject());
        init(psiClass);
    }

    /**
     * Populates elementsAddedByUser with members of given PsiClass
     */
    private void init(PsiClass psiClass) {
        myInitialElement = psiClass == null ? null : spManager.createSmartPsiElementPointer(psiClass);
        collectNodes(psiClass);
    }

    public void collectNodes(PsiClass psiClass) {
        for (PsiMethod psiMethod : psiClass.getMethods()) {
            elementsAddedByUser.put(getFqn(psiMethod), spManager.createSmartPsiElementPointer((PsiElement) psiMethod));
        }

        for (PsiField psiField : psiClass.getFields()) {
            elementsAddedByUser.put(getFqn(psiField), spManager.createSmartPsiElementPointer((PsiElement) psiField));
        }

        for (PsiClassInitializer psiClassInitializer : psiClass.getInitializers()) {
            elementsAddedByUser.put(getFqn(psiClassInitializer), spManager.createSmartPsiElementPointer((PsiElement) psiClassInitializer));
        }

        for (PsiClass innerClass : psiClass.getInnerClasses()) {
            elementsAddedByUser.put(getFqn(innerClass), spManager.createSmartPsiElementPointer((PsiElement) innerClass));
        }
    }

    @NotNull
    @Override
    public Collection<? extends DiagramNode<PsiElement>> getNodes() {
        if (myNodes == null) {
            throw new IllegalStateException("@NotNull method %s.%s must not return null");
        } else {
            return myNodes;
        }
    }

    @NotNull
    @Override
    public Collection<? extends DiagramEdge<PsiElement>> getEdges() {
        Collection var10000 = myEdges;
        if (myEdges == null) {
            throw new IllegalStateException(String.format("@NotNull method %s.%s must not return null",
                    new Object[]{"com/intellij/uml/java/JavaUmlDataModel", "getEdges"}));
        } else {
            return var10000;
        }
    }

    @NotNull
    @Override
    public String getNodeName(DiagramNode<PsiElement> diagramNode) {
        return PsiUtils.getPresentableName(diagramNode.getIdentifyingElement());
    }

    @Nullable
    public DiagramEdge<PsiElement> addEdge(final @NotNull DiagramNode<PsiElement> from, final @NotNull DiagramNode<PsiElement> to,
            Long value) {
        final DiagramRelationshipInfo relationship;
        if (from.getIdentifyingElement() instanceof PsiField) {
            relationship = createEdgeFromField();
        } else {
            relationship = createEdgeFromNonField(value == null ? 0 : value);
        }

        return new ReferenceEdge(from, to, relationship);
    }

    @Override
    public void removeNode(DiagramNode<PsiElement> node) {
        removeElement((PsiElement) node.getIdentifyingElement());
        analyzeLcom4();
    }

    private void removeElement(PsiElement element) {
        DiagramNode node = findNode(element);
        if (node == null) {
            elementsAddedByUser.remove(PsiUtils.getFqn(element));
        } else {
            PsiElement toRemove = (PsiElement) node.getIdentifyingElement();
            myNodes.remove(node);
            elementsRemovedByUser.put(PsiUtils.getFqn(element), spManager.createSmartPsiElementPointer
                    (toRemove));
            elementsAddedByUser.remove(PsiUtils.getFqn(element));
            removeAllEdgesFromOrTo(node);
        }
    }

    private void removeAllEdgesFromOrTo(DiagramNode<PsiElement> node) {
        String removedNode = PsiUtils.getFqn(node.getIdentifyingElement());
        Set<DiagramEdge<PsiElement>> toRemove = new HashSet<>();
        for (DiagramEdge<PsiElement> myEdge : myEdges) {
            if (PsiUtils.getFqn(myEdge.getSource().getIdentifyingElement()).equals(removedNode)) {
                toRemove.add(myEdge);
            } else if (PsiUtils.getFqn(myEdge.getTarget().getIdentifyingElement()).equals(removedNode)) {
                toRemove.add(myEdge);
            }
        }
        myEdges.removeAll(toRemove);
    }

    @Override
    public void refreshDataModel() {
        clearAll();
        updateDataModel();
        analyzeLcom4();
    }

    private void analyzeLcom4() {
        LCOMConverter lcomConverter = new LCOMConverter();
        Collection<LCOMNode> lcom4Nodes = lcomConverter.convert(getNodes(), getEdges());
        LCOMAnalyzerData lcomAnalyzerData = new LCOMAnalyzerData(lcom4Nodes);
        ClusterAnalyzer clusterAnalyzer = new ClusterAnalyzer(lcomAnalyzerData);
        currentClusterCount = clusterAnalyzer.countCluster();
    }

    @NotNull
    public ModificationTracker getModificationTracker() {
        return PsiManager.getInstance(getProject()).getModificationTracker();
    }

    private void clearAll() {
        myNodes.clear();
        myEdges.clear();
    }

    public synchronized void updateDataModel() {
        DiagramProvider provider = getBuilder().getProvider();
        Set<PsiElement> elements = getElements();

        for (PsiElement element : elements) {
            if (isAllowedToShow(element)) {
                myNodes.add(getReferenceNode(provider, element));
            }
        }

        IncrementableSet<SourceTargetPair> relationships = resolveRelationships(getInitialElement());
        for (Map.Entry<SourceTargetPair, Long> sourceTargetPair : relationships.elements()) {
            SourceTargetPair key = sourceTargetPair.getKey();
            DiagramNode<PsiElement> source = findNode(key.getSource());
            DiagramNode<PsiElement> target = findNode(key.getTarget());
            if (source != null && target != null) {
                myEdges.add(addEdge(source, target, sourceTargetPair.getValue()));
            }
        }
    }

    @NotNull
    private ReferenceNode getReferenceNode(DiagramProvider provider, PsiElement element) {
        if (myNodesPool.containsKey(element)) {
            return (ReferenceNode) myNodesPool.get(element);
        }
        ReferenceNode node = new ReferenceNode(element, provider);
        myNodesPool.put(element, node);
        return node;
    }

    @Nullable
    public PsiClass getInitialElement() {
        if (myInitialElement == null) {
            return null;
        } else {
            PsiElement element = myInitialElement.getElement();
            if (element != null && element.isValid()) {
                return (PsiClass) element;
            } else {
                return null;
            }
        }
    }

    @NotNull
    public IncrementableSet<SourceTargetPair> resolveRelationships(PsiClass psiClass) {
        IncrementableSet<SourceTargetPair> incrementableSet = new IncrementableSet<>();

        for (DiagramNode<PsiElement> node : myNodes) {
            PsiElement callee = node.getIdentifyingElement();

            Collection<PsiReference> all = ReferencesSearch.search(callee, new LocalSearchScope
                    (psiClass)).findAll();

            for (PsiReference psiReference : all) {
                if (!(psiReference instanceof PsiReferenceExpression)) {
                    continue;
                }
                PsiReferenceExpression referenceExpression = (PsiReferenceExpression) psiReference;
                PsiElement caller = PsiUtils.getRootPsiElement(psiClass, referenceExpression);

                if (caller == null) {
                    continue;
                }

                incrementableSet.increment(new SourceTargetPair(caller, callee));
            }
        }
        return incrementableSet;
    }

    private Set<PsiElement> getElements() {
        Set<PsiElement> result = new HashSet<>();

        for (SmartPsiElementPointer<PsiElement> psiElementPointer : elementsAddedByUser.values()) {
            PsiElement element = psiElementPointer.getElement();
            result.add(element);
        }

        for (SmartPsiElementPointer<PsiElement> psiElementPointer : elementsRemovedByUser.values()) {
            PsiElement element = psiElementPointer.getElement();
            result.remove(element);
        }

        return result;
    }

    private boolean isAllowedToShow(PsiElement psiElement) {
        if (psiElement != null && psiElement.isValid()) {
            for (DiagramCategory enabledCategory : getBuilder().getPresentation().getEnabledCategories()) {
                if (getBuilder().getProvider().getNodeContentManager().isInCategory(psiElement, enabledCategory, getBuilder()
                        .getPresentation())) {
                    return true;
                }
            }
        }
        return false;
    }

    public void dispose() {
    }

    @Nullable
    @Override
    public DiagramNode<PsiElement> addElement(PsiElement psiElement) {
        return null;
    }

    public boolean hasElement(PsiElement element) {
        return findNode(element) != null;
    }

    /**
     * @param psiElement
     * @return {@code true} if {@code myNodes} contains {@code psiElement}.
     */
    @Nullable
    public DiagramNode<PsiElement> findNode(PsiElement psiElement) {
        Iterator ptr = (new ArrayList(myNodes)).iterator();

        while (ptr.hasNext()) {
            DiagramNode node = (DiagramNode) ptr.next();
            String fqn = PsiUtils.getFqn((PsiElement) node.getIdentifyingElement());
            if (fqn != null && fqn.equals(PsiUtils.getFqn(psiElement))) {
                return node;
            }
        }
        return null;
    }

    public boolean isPsiListener() {
        return true;
    }

    public void rebuild(PsiElement element) {
        super.rebuild(element);
        elementsRemovedByUser.clear();
        clearAll();
        init((PsiClass) element);
        refreshDataModel();
    }

    @NotNull
    private DiagramRelationshipInfo createEdgeFromNonField(final long count) {
        DiagramRelationshipInfo r;
        r = new DiagramRelationshipInfoAdapter(ReferenceEdge.Type.REFERENCE.name()) {
            @Override
            public Shape getStartArrow() {
                return STANDARD;
            }

            @Override
            public String getToLabel() {
                if (count == 1) {
                    return "";
                } else {
                    return Long.toString(count);
                }
            }

            @Override
            public boolean equals(Object obj) {
                if (!(obj instanceof DiagramRelationshipInfoAdapter)) {
                    return false;
                }
                return toString().equals(obj.toString()) && getToLabel().equals(((DiagramRelationshipInfoAdapter) obj).getToLabel());
            }
        };
        return r;
    }

    @NotNull
    private DiagramRelationshipInfo createEdgeFromField() {
        DiagramRelationshipInfo r;
        r = new DiagramRelationshipInfoAdapter(ReferenceEdge.Type.FIELD_TO_METHOD.name()) {
            @Override
            public Shape getStartArrow() {
                return DELTA_SMALL;
            }

            @Override
            public DiagramLineType getLineType() {
                return DiagramLineType.DASHED;
            }

            @Override
            public boolean equals(Object obj) {
                if (!(obj instanceof DiagramRelationshipInfoAdapter)) {
                    return false;
                }
                return toString().equals(obj.toString());
            }
        };
        return r;
    }

    public long getCurrentClusterCount() {
        return currentClusterCount;
    }

    public void removeMarkedNodes() {
        List<ReferenceNode> toRemove = new ArrayList<>();
        for (DiagramNode<PsiElement> myNode : myNodes) {
            if (myNode instanceof ReferenceNode) {
                if (((ReferenceNode) myNode).isMarked()) {
                    toRemove.add((ReferenceNode) myNode);
                    ((ReferenceNode) myNode).switchMarked();
                }
            }
        }
        Iterator<ReferenceNode> iterator = toRemove.iterator();
        while (iterator.hasNext()) {
            ReferenceNode next = iterator.next();
            removeElement((PsiElement) next.getIdentifyingElement());
        }
        analyzeLcom4();
    }

    public void isolateMarkedNodes() {
        List<ReferenceNode> toRemove = new ArrayList<>();
        for (DiagramNode<PsiElement> myNode : myNodes) {
            if (myNode instanceof ReferenceNode) {
                if (!((ReferenceNode) myNode).isMarked()) {
                    toRemove.add((ReferenceNode) myNode);
                } else {
                    ((ReferenceNode) myNode).switchMarked();
                }
            }
        }
        Iterator<ReferenceNode> iterator = toRemove.iterator();
        while (iterator.hasNext()) {
            ReferenceNode next = iterator.next();
            removeElement((PsiElement) next.getIdentifyingElement());
        }
        analyzeLcom4();
    }

    public void unmarkAllNodes() {
        for (DiagramNode<PsiElement> myNode : myNodes) {
            if (myNode instanceof ReferenceNode) {
                ((ReferenceNode) myNode).unsetMarked();
            }
        }
    }

    public void markCallees(List<DiagramNode> roots) {
        LCOMConverter lcomConverter = new LCOMConverter();
        Collection<LCOMNode> lcom4Nodes = lcomConverter.convert(getNodes(), getEdges());
        for (DiagramNode root : roots) {
            markCallees(root, lcom4Nodes);
        }
    }

    private void markCallees(DiagramNode root, Collection<LCOMNode> lcom4Nodes) {
        LCOMNode lcomRoot = searchRoot(root, lcom4Nodes);
        lcomRoot.getIdentifyingElement().setMarked();
        List<LCOMNode> callees = getCalleesTransitiv(lcomRoot, lcom4Nodes);
        for (LCOMNode callee : callees) {
            callee.getIdentifyingElement().setMarked();
        }
    }

    private LCOMNode searchRoot(DiagramNode diagramNode, Collection<LCOMNode> lcom4Nodes) {
        for (LCOMNode lcom4Node : lcom4Nodes) {
            if (lcom4Node.getIdentifyingElement().equals(diagramNode)) {
                return lcom4Node;
            }
        }
        throw new IllegalStateException("DiagramNode not found");
    }

    private List<LCOMNode> getCalleesTransitiv(LCOMNode lcomRoot, Collection<LCOMNode> lcom4Nodes) {
        final LCOMAnalyzerData data = new LCOMAnalyzerData(lcom4Nodes);
        CalleesSubgraphAnalyzer analyzer = new CalleesSubgraphAnalyzer(data);
        List<LCOMNode> result = analyzer.getCallees(lcomRoot);
        return result;
    }

    public void markCallers(List<DiagramNode> roots) {
        LCOMConverter lcomConverter = new LCOMConverter();
        Collection<LCOMNode> lcom4Nodes = lcomConverter.convert(getNodes(), getEdges());
        for (DiagramNode root : roots) {
            markCallers(root, lcom4Nodes);
        }
    }

    private void markCallers(DiagramNode root, Collection<LCOMNode> lcom4Nodes) {
        LCOMNode lcomRoot = searchRoot(root, lcom4Nodes);
        lcomRoot.getIdentifyingElement().setMarked();
        List<LCOMNode> callers = getCallersTransitiv(lcomRoot, lcom4Nodes);
        for (LCOMNode caller : callers) {
            caller.getIdentifyingElement().setMarked();
        }
    }

    private List<LCOMNode> getCallersTransitiv(LCOMNode lcomRoot, Collection<LCOMNode> lcom4Nodes) {
        final LCOMAnalyzerData data = new LCOMAnalyzerData(lcom4Nodes);
        CallersSubgraphAnalyzer analyzer = new CallersSubgraphAnalyzer(data);
        List<LCOMNode> result = analyzer.getCallees(lcomRoot);
        return result;
    }
}
