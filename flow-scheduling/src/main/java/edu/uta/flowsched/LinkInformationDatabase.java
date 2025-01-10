package edu.uta.flowsched;


import org.onlab.util.KryoNamespace;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleEvent;
import org.onosproject.net.flow.FlowRuleListener;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.WallClockTimestamp;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.onosproject.net.device.DeviceEvent.Type.PORT_STATS_UPDATED;

public class LinkInformationDatabase {

    public static final LinkInformationDatabase INSTANCE = new LinkInformationDatabase();
    private EventuallyConsistentMap<Link, MyLink> LINK_INFORMATION_MAP;
    private LinkThroughputWatcher linkThroughputWatcher;
    private InternalFlowRuleListener internalFlowRuleListener;

    protected void activate() {
        linkThroughputWatcher = new LinkThroughputWatcher();
        internalFlowRuleListener = new InternalFlowRuleListener();

        Services.deviceService.addListener(linkThroughputWatcher);
        Services.flowRuleService.addListener(internalFlowRuleListener);

        KryoNamespace.Builder mySerializer = KryoNamespace.newBuilder().register(KryoNamespaces.API)
                .register(Link.class)
                .register(ConnectPoint.class)
                .register(MyPath.class)
                .register(MyLink.class);

        LINK_INFORMATION_MAP = Services.storageService.<Link, MyLink>eventuallyConsistentMapBuilder()
                .withName("mylink_info_map")
                .withTimestampProvider((k, v) -> new WallClockTimestamp())
                .withSerializer(mySerializer).build();

        for (Link link : Services.linkService.getLinks()) {
            LINK_INFORMATION_MAP.put(link, new MyLink(link));
        }

    }

    protected void deactivate() {
        Services.deviceService.removeListener(linkThroughputWatcher);
        Services.flowRuleService.removeListener(internalFlowRuleListener);
        LINK_INFORMATION_MAP.clear();
    }

    public void refreshComponent() {
        deactivate();
        activate();
    }

    public void updateLinksUtilization(Set<Link> links, long rate) {
        links.forEach(link -> {
            if (LINK_INFORMATION_MAP.containsKey(link))
                LINK_INFORMATION_MAP.get(link).setCurrentThroughput(rate);
        });
    }

    public MyLink getLinkInformation(Link link) {
        MyLink myLink = LINK_INFORMATION_MAP.get(link);
        if (myLink == null) {
            myLink = new MyLink(link);
            LINK_INFORMATION_MAP.put(link, myLink);
        }
        return myLink;
    }

    public Collection<MyLink> getAllLinkInformation() {
        return LINK_INFORMATION_MAP.values();
    }
    private class InternalFlowRuleListener implements FlowRuleListener {
        @Override
        public void event(FlowRuleEvent event) {
            if (event.type().equals(FlowRuleEvent.Type.RULE_REMOVED)) {
                FlowRule rule = event.subject();
                Optional<Instructions.OutputInstruction> outputInstruction = rule.treatment().allInstructions().stream()
                        .filter(instruction -> instruction.type() == Instruction.Type.OUTPUT)
                        .map(instruction -> (Instructions.OutputInstruction) instruction)
                        .findAny();
                if (outputInstruction.isPresent()) {
                    Set<Link> links = Services.linkService.getEgressLinks(new ConnectPoint(rule.deviceId(), outputInstruction.get().port()));
                    links.forEach(link -> getLinkInformation(link).decreaseActiveFlows());
                }
            }
        }
    }

    private class LinkThroughputWatcher implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
            DeviceEvent.Type type = event.type();
            DeviceId deviceId = event.subject().id();
            if (type == PORT_STATS_UPDATED) {
                List<PortStatistics> portStatisticsList = Services.deviceService.getPortDeltaStatistics(deviceId);
                portStatisticsList.forEach(portStatistics -> {
                    long receivedRate = (portStatistics.bytesReceived() * 8) / Util.POLL_FREQ;
                    long sentRate = (portStatistics.bytesSent() * 8) / Util.POLL_FREQ;
                    Set<Link> ingressLinks = Services.linkService.getIngressLinks(new ConnectPoint(deviceId, portStatistics.portNumber()));
                    updateLinksUtilization(ingressLinks, receivedRate);
                    Set<Link> egressLinks = Services.linkService.getEgressLinks(new ConnectPoint(deviceId, portStatistics.portNumber()));
                    updateLinksUtilization(egressLinks, sentRate);
                });
            }
        }
    }
}