package edu.uta.flowsched;


import org.onlab.packet.MacAddress;
import org.onlab.util.DataRateUnit;
import org.onosproject.cfg.ConfigProperty;
import org.onosproject.net.*;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;


public class Util {
    static int POLL_FREQ = getPollFreq();
    static final long MODEL_SIZE = 20 * 1_000_000 * 8; // 20 Mega-bit
    static final MacAddress FL_SERVER_MAC = MacAddress.valueOf("00:00:00:00:00:FA");
    static final Host SERVER_HOST = Services.hostService.getHost(HostId.hostId(FL_SERVER_MAC));
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(Util.class);
    final static ConcurrentHashMap<String, BufferedWriter> LOGGERS = new ConcurrentHashMap<>();


    private static int getPollFreq() {
        ConfigProperty pollFreq = Services.cfgService.getProperty("org.onosproject.provider.of.device.impl.OpenFlowDeviceProvider", "portStatsPollFrequency");
        if (pollFreq == null) {
            return 5;
        } else {
            return pollFreq.asInteger();
        }
    }


    public static long bitToMbit(Number num) {
        return num.longValue() / 1_000_000;
    }


    public static String formatLink(Link link) {
        if (link == null) {
            return "";
        }
        final String LINK_STRING_FORMAT = "%s -> %s";
        String src = link.src().elementId().toString().substring(15);
        String dst = link.dst().elementId().toString().substring(15);
        return String.format(LINK_STRING_FORMAT, src, dst);
    }

    static void log(String loggerNames, String message) {
        String[] loggers = loggerNames.split(",");
        for (String loggerName : loggers) {
            try {
                BufferedWriter logger = LOGGERS.computeIfAbsent(loggerName, name -> {
                    try {
                        return new BufferedWriter(new FileWriter(String.format("/home/osama/flow_sched_logs/%s.log", name)));
                    } catch (IOException e) {
                        LOGGER.error("Error while creating file writer for logger: " + name, e);
                        return null;
                    }
                });

                if (logger == null) {
                    continue; // If we failed to create the logger, skip this logger
                }
                logger.write(message + "\n");
            } catch (Exception e) {
                LOGGER.error("Error While Logging: " + loggerName + " => " + e.getMessage(), e);
            }
        }
    }

    static void flushWriters() {
        LOGGERS.forEach((s, bufferedWriter) -> {
            try {
                bufferedWriter.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    static void closeWriters() {
        LOGGERS.forEach((s, bufferedWriter) -> {
            try {
                bufferedWriter.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}