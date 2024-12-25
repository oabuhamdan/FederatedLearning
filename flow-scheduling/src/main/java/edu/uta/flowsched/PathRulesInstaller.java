package edu.uta.flowsched;

import org.onlab.packet.DscpClass;
import org.onlab.packet.IPv4;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onosproject.net.*;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;

import java.util.LinkedList;
import java.util.List;

import static org.onlab.packet.Ethernet.TYPE_IPV4;

public class PathRulesInstaller {
    public static final PathRulesInstaller INSTANCE = new PathRulesInstaller();
    private static int PRIORITY = 20;


    enum Direction {
        SERVER_TO_CLIENT,
        CLIENT_TO_SERVER,
    }

    public void increasePriority() {
        PRIORITY++;
    }

    public void installPathRules(MacAddress hostMac, Path path) {
        List<FlowRule> rules = new LinkedList<>();
        List<Link> links = path.links();
        PortNumber outPort;
        DeviceId deviceId;
        PortNumber inPort = links.get(0).dst().port();
        for (int i = 1; i < links.size(); i++) {
            outPort = links.get(i).src().port();
            deviceId = links.get(i).src().deviceId();
            rules.add(getFlowEntry(deviceId, hostMac, outPort, inPort, path));
            inPort = links.get(i).dst().port();
        }
        Services.flowRuleService.applyFlowRules(rules.toArray(new FlowRule[0]));
    }


    private FlowRule getFlowEntry(DeviceId dId, MacAddress hostMac, PortNumber outPort, PortNumber inPort, Path path) {

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
//                .setIpDscp((byte) DscpClass.EF.getValue())
                .setQueue(1)
                .setOutput(outPort)
                .build();

        TrafficSelector.Builder selector = DefaultTrafficSelector.builder()
                .matchInPort(inPort)
                .matchEthDst(path.dst().hostId().mac())
                .matchEthSrc(path.src().hostId().mac());


        return DefaultFlowRule.builder()
                .withTreatment(treatment)
                .withSelector(selector.build())
                .forDevice(dId)
                .withPriority(PRIORITY)
                .fromApp(Services.appId)
                .makeTemporary(10)
                .build();
    }
}