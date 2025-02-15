package edu.uta.flowsched;

import org.onlab.packet.*;
import org.onosproject.net.*;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.onlab.packet.Ethernet.TYPE_IPV4;

public class PathRulesInstaller {
    public static final PathRulesInstaller INSTANCE = new PathRulesInstaller();
    private static final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private static int PRIORITY = 20;

    public void increasePriority() {
        PRIORITY++;
    }

    public void installPathRules(FLHost flHost, Path path, boolean permanent) {
        List<FlowRule> rules = new LinkedList<>();
        List<Link> links = path.links();
        PortNumber outPort;
        DeviceId deviceId;
        PortNumber inPort = links.get(0).dst().port();
        for (int i = 1; i < links.size(); i++) {
            outPort = links.get(i).src().port();
            deviceId = links.get(i).src().deviceId();
            rules.add(getFlowEntry(deviceId, flHost.mac(), outPort, inPort, path, permanent));
            inPort = links.get(i).dst().port();
        }
        Services.flowRuleService.applyFlowRules(rules.toArray(new FlowRule[0]));
    }

    private void debugRules(FLHost flHost, List<FlowRule> rules, Path path, int delay) {
        String dir = path.dst().hostId().mac().equals(Util.FL_SERVER_MAC) ? "C2S" : "S2C";
        StringBuilder stringBuilder = new StringBuilder();
        int round = dir.equals("C2S") ? GreedyFlowScheduler.C2S_INSTANCE.getRound() : GreedyFlowScheduler.S2C_INSTANCE.getRound();

        stringBuilder.append(String.format("\t******* Log %s for Client %s For Round %s Delay %s *******\n", dir, flHost.getFlClientCID(), round, delay));
        rules.forEach(flowRule ->
                StreamSupport.stream(Services.flowRuleService.getFlowEntries(flowRule.deviceId()).spliterator(), false)
                        .filter(flowEntry -> flowRule.selector().getCriterion(Criterion.Type.ETH_SRC).equals(flowEntry.selector().getCriterion(Criterion.Type.ETH_SRC)))
                        .filter(flowEntry -> flowRule.selector().getCriterion(Criterion.Type.ETH_DST).equals(flowEntry.selector().getCriterion(Criterion.Type.ETH_DST)))
                        .forEach(flowEntry -> {
                            stringBuilder
                                    .append("\t\tDev:").append(flowEntry.deviceId().toString().substring(15))
                                    .append(" - Application: ").append(Services.coreService.getAppId(flowEntry.appId()).name())
                                    .append(" - Life: ").append(flowEntry.life())
                                    .append(" - State: ").append(flowEntry.state().name())
                                    .append(" - Bytes: ").append(flowEntry.bytes())
                                    .append(" - Priority: ").append(flowEntry.priority())
                                    .append(" - Output: ").append(flowEntry.treatment().allInstructions().get(0).toString())
                                    .append(" - isPermanent: ").append(flowEntry.isPermanent())
                                    .append("\n");
                        }));
        Util.log("debug_rules" + dir, stringBuilder.toString());
    }


    private FlowRule getFlowEntry(DeviceId dId, MacAddress hostMac, PortNumber outPort, PortNumber inPort, Path path, boolean permanent) {
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
//                .setIpDscp((byte) DscpClass.EF.getValue())
//                .setQueue(1)
                .setOutput(outPort)
                .build();

        TrafficSelector.Builder selector = DefaultTrafficSelector.builder()
                .matchInPort(inPort)
                .matchEthDst(path.dst().hostId().mac())
                .matchEthSrc(path.src().hostId().mac())
                .matchEthType(TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_TCP);


        FlowRule.Builder ruleBuilder = DefaultFlowRule.builder()
                .withTreatment(treatment)
                .withSelector(selector.build())
                .forDevice(dId)
                .withPriority(PRIORITY)
                .fromApp(Services.appId);

        if (permanent)
            ruleBuilder.makePermanent();
        else
            ruleBuilder.makeTemporary(5);

        return ruleBuilder.build();
    }
}