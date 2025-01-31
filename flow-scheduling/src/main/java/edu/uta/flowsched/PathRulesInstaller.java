package edu.uta.flowsched;

import org.onlab.packet.*;
import org.onosproject.net.*;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;

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


    enum Direction {
        SERVER_TO_CLIENT,
        CLIENT_TO_SERVER,
    }

    public void increasePriority() {
        PRIORITY++;
    }

    public void installPathRules(FLHost flHost, Path path) {
        List<FlowRule> rules = new LinkedList<>();
        List<Link> links = path.links();
        PortNumber outPort;
        DeviceId deviceId;
        PortNumber inPort = links.get(0).dst().port();
        for (int i = 1; i < links.size(); i++) {
            outPort = links.get(i).src().port();
            deviceId = links.get(i).src().deviceId();
            rules.add(getFlowEntry(deviceId, flHost.mac(), outPort, inPort, path));
            inPort = links.get(i).dst().port();
        }
        Services.flowRuleService.applyFlowRules(rules.toArray(new FlowRule[0]));
        scheduledExecutorService.schedule(() -> debugRules(flHost, rules, path), 10, TimeUnit.SECONDS);
    }

    private void debugRules(FLHost flHost, List<FlowRule> rules, Path path) {
        String dir = path.dst().hostId().mac().equals(Util.FL_SERVER_MAC)? "C2S": "S2C";
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(String.format("\t******* Log %s for Client %s  *******\n", dir, flHost.getFlClientCID()));
        StreamSupport.stream(Services.flowRuleService.getFlowEntriesById(Services.appId).spliterator(), false)
                .filter(rules::contains).forEach(flowRule -> stringBuilder
                .append("\t\tDev:")
                .append(flowRule.deviceId().toString().substring(15))
                .append(" Bytes: ")
                .append(flowRule.bytes())
                .append("\n"));
        Util.log("flow_debug", stringBuilder.toString());
    }


    private FlowRule getFlowEntry(DeviceId dId, MacAddress hostMac, PortNumber outPort, PortNumber inPort, Path path) {
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

        return DefaultFlowRule.builder()
                .withTreatment(treatment)
                .withSelector(selector.build())
                .forDevice(dId)
                .withPriority(PRIORITY)
                .fromApp(Services.appId)
                .makeTemporary(5)
                .build();
    }
}