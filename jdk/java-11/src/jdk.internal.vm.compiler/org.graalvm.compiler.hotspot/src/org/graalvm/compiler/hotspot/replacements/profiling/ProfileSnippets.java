/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */


package org.graalvm.compiler.hotspot.replacements.profiling;

import static org.graalvm.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static org.graalvm.compiler.hotspot.replacements.HotSpotReplacementsUtil.config;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.probability;
import static org.graalvm.compiler.replacements.SnippetTemplate.DEFAULT_REPLACER;

import org.graalvm.compiler.api.replacements.Snippet;
import org.graalvm.compiler.api.replacements.Snippet.ConstantParameter;
import org.graalvm.compiler.core.common.spi.ForeignCallDescriptor;
import org.graalvm.compiler.debug.DebugHandlersFactory;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.Node.ConstantNodeParameter;
import org.graalvm.compiler.graph.Node.NodeIntrinsic;
import org.graalvm.compiler.hotspot.HotSpotBackend;
import org.graalvm.compiler.hotspot.meta.HotSpotProviders;
import org.graalvm.compiler.hotspot.nodes.aot.LoadMethodCountersNode;
import org.graalvm.compiler.hotspot.nodes.profiling.ProfileBranchNode;
import org.graalvm.compiler.hotspot.nodes.profiling.ProfileInvokeNode;
import org.graalvm.compiler.hotspot.nodes.profiling.ProfileNode;
import org.graalvm.compiler.hotspot.word.MethodCountersPointer;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.extended.ForeignCallNode;
import org.graalvm.compiler.nodes.spi.LoweringTool;
import org.graalvm.compiler.nodes.util.GraphUtil;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.replacements.SnippetTemplate;
import org.graalvm.compiler.replacements.SnippetTemplate.AbstractTemplates;
import org.graalvm.compiler.replacements.SnippetTemplate.Arguments;
import org.graalvm.compiler.replacements.SnippetTemplate.SnippetInfo;
import org.graalvm.compiler.replacements.Snippets;

import jdk.vm.ci.code.CodeUtil;
import jdk.vm.ci.code.TargetDescription;

public class ProfileSnippets implements Snippets {
    @NodeIntrinsic(ForeignCallNode.class)
    public static native void methodInvocationEvent(@ConstantNodeParameter ForeignCallDescriptor descriptor, MethodCountersPointer counters);

    @Snippet
    protected static int notificationMask(int freqLog, int stepLog) {
        int stepMask = (1 << stepLog) - 1;
        int frequencyMask = (1 << freqLog) - 1;
        return frequencyMask & ~stepMask;
    }

    @Snippet
    public static void profileMethodEntry(MethodCountersPointer counters, int step, int stepLog, @ConstantParameter int freqLog) {
        int counterValue = counters.readInt(config(INJECTED_VMCONFIG).invocationCounterOffset) + config(INJECTED_VMCONFIG).invocationCounterIncrement * step;
        counters.writeInt(config(INJECTED_VMCONFIG).invocationCounterOffset, counterValue);
        if (freqLog >= 0) {
            final int mask = notificationMask(freqLog, stepLog);
            if (probability(SLOW_PATH_PROBABILITY, (counterValue & (mask << config(INJECTED_VMCONFIG).invocationCounterShift)) == 0)) {
                methodInvocationEvent(HotSpotBackend.INVOCATION_EVENT, counters);
            }
        }
    }

    @NodeIntrinsic(ForeignCallNode.class)
    public static native void methodBackedgeEvent(@ConstantNodeParameter ForeignCallDescriptor descriptor, MethodCountersPointer counters, int bci, int targetBci);

    @Snippet
    public static void profileBackedge(MethodCountersPointer counters, int step, int stepLog, @ConstantParameter int freqLog, int bci, int targetBci) {
        int counterValue = counters.readInt(config(INJECTED_VMCONFIG).backedgeCounterOffset) + config(INJECTED_VMCONFIG).invocationCounterIncrement * step;
        counters.writeInt(config(INJECTED_VMCONFIG).backedgeCounterOffset, counterValue);
        final int mask = notificationMask(freqLog, stepLog);
        if (probability(SLOW_PATH_PROBABILITY, (counterValue & (mask << config(INJECTED_VMCONFIG).invocationCounterShift)) == 0)) {
            methodBackedgeEvent(HotSpotBackend.BACKEDGE_EVENT, counters, bci, targetBci);
        }
    }

    @Snippet
    public static void profileConditionalBackedge(MethodCountersPointer counters, int step, int stepLog, @ConstantParameter int freqLog, boolean branchCondition, int bci, int targetBci) {
        if (branchCondition) {
            profileBackedge(counters, step, stepLog, freqLog, bci, targetBci);
        }
    }

    public static class Templates extends AbstractTemplates {
        private final SnippetInfo profileMethodEntry = snippet(ProfileSnippets.class, "profileMethodEntry");
        private final SnippetInfo profileBackedge = snippet(ProfileSnippets.class, "profileBackedge");
        private final SnippetInfo profileConditionalBackedge = snippet(ProfileSnippets.class, "profileConditionalBackedge");

        public Templates(OptionValues options, Iterable<DebugHandlersFactory> factories, HotSpotProviders providers, TargetDescription target) {
            super(options, factories, providers, providers.getSnippetReflection(), target);
        }

        public void lower(ProfileNode profileNode, LoweringTool tool) {
            StructuredGraph graph = profileNode.graph();
            LoadMethodCountersNode counters = graph.unique(new LoadMethodCountersNode(profileNode.getProfiledMethod()));
            ConstantNode step = ConstantNode.forInt(profileNode.getStep(), graph);
            ConstantNode stepLog = ConstantNode.forInt(CodeUtil.log2(profileNode.getStep()), graph);

            if (profileNode instanceof ProfileBranchNode) {
                // Backedge event
                ProfileBranchNode profileBranchNode = (ProfileBranchNode) profileNode;
                SnippetInfo snippet = profileBranchNode.hasCondition() ? profileConditionalBackedge : profileBackedge;
                Arguments args = new Arguments(snippet, graph.getGuardsStage(), tool.getLoweringStage());
                ConstantNode bci = ConstantNode.forInt(profileBranchNode.bci(), graph);
                ConstantNode targetBci = ConstantNode.forInt(profileBranchNode.targetBci(), graph);
                args.add("counters", counters);
                args.add("step", step);
                args.add("stepLog", stepLog);
                args.addConst("freqLog", profileBranchNode.getNotificationFreqLog());
                if (profileBranchNode.hasCondition()) {
                    args.add("branchCondition", profileBranchNode.branchCondition());
                }
                args.add("bci", bci);
                args.add("targetBci", targetBci);

                SnippetTemplate template = template(profileNode, args);
                template.instantiate(providers.getMetaAccess(), profileNode, DEFAULT_REPLACER, args);
            } else if (profileNode instanceof ProfileInvokeNode) {
                ProfileInvokeNode profileInvokeNode = (ProfileInvokeNode) profileNode;
                // Method invocation event
                Arguments args = new Arguments(profileMethodEntry, graph.getGuardsStage(), tool.getLoweringStage());
                args.add("counters", counters);
                args.add("step", step);
                args.add("stepLog", stepLog);
                args.addConst("freqLog", profileInvokeNode.getNotificationFreqLog());
                SnippetTemplate template = template(profileNode, args);
                template.instantiate(providers.getMetaAccess(), profileNode, DEFAULT_REPLACER, args);
            } else {
                throw new GraalError("Unsupported profile node type: " + profileNode);
            }

            assert profileNode.hasNoUsages();
            if (!profileNode.isDeleted()) {
                GraphUtil.killWithUnusedFloatingInputs(profileNode);
            }
        }
    }
}
