/*
 * Copyright 2024-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uta.flowsched;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.net.DeviceId;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Sample Apache Karaf CLI command.
 */
@Service
@Command(scope = "onos", name = "flowsched")
public class AppCommand extends AbstractShellCommand {
    @Argument(name = "type", description = "type of stats to print", required = true)
    String type = "";
    @Argument(name = "deviceid", description = "type of stats to print", required = false, index = 1)
    String deviceID = "";

    @Override
    protected void doExecute() {
        switch (type) {
            case "link-info":
                linkInfo();
                break;
            case "meter":
                addMeter(DeviceId.deviceId(deviceID));
                break;
            case "refresh-links":
                LinkInformationDatabase.INSTANCE.refreshComponent();
                break;
        }
    }

    private void addMeter(DeviceId deviceId) {
//        ReserveCapacity.call(deviceId);
        print("Reserved Capacity Successfully");
    }

    void linkInfo() {
        for (MyLink link : LinkInformationDatabase.INSTANCE.getAllLinkInformation()) {
            if (link.src().deviceId().toString().matches("of:000000000000010\\d") || link.dst().deviceId().toString().matches("of:000000000000010\\d"))
                print("%s,%s,%s", Util.formatLink(link), link.getEstimatedFreeCapacity(), link.getCurrentThroughput());
        }
        print(",,");
    }

}
