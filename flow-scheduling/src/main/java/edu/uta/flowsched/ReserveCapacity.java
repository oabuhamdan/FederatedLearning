package edu.uta.flowsched;

import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.flow.*;
import org.onosproject.net.meter.*;

import java.util.Collections;

public class ReserveCapacity {
    public static void call(DeviceId deviceId) {

        Band dropBand = DefaultBand.builder()
                .ofType(Band.Type.DROP)
                .withRate(1000)
                .build();

        MeterRequest.Builder ops = DefaultMeterRequest.builder()
                .forDevice(deviceId)
                .withBands(Collections.singletonList(dropBand))
                .withUnit(Meter.Unit.KB_PER_SEC)
                .fromApp(Services.appId);

        Meter meter = Services.meterService.submit(ops.add());

//        for (Link link : Services.linkService.getDeviceEgressLinks(deviceId)) {
//            for (FlowEntry entry : LinkInformationDatabase.INSTANCE.getLinkInformation(link).getFlowsUsingLink()) {
//                TrafficTreatment trafficTreatment = DefaultTrafficTreatment.builder().meter(meter.id()).addTreatment(entry.treatment()).build();
//                TrafficSelector selector = entry.selector();
//                FlowRule flowRule = DefaultFlowEntry.builder().withTreatment(trafficTreatment)
//                        .withSelector(selector).withPriority(entry.priority() + 1)
//                        .makeTemporary(entry.timeout())
//                        .fromApp(Services.appId)
//                        .forDevice(entry.deviceId()).build();
//                Services.flowRuleService.applyFlowRules(flowRule);
//            }
//        }
    }
}
