package edu.uta.flowsched;


import org.onlab.packet.MacAddress;
import org.onlab.util.DataRateUnit;
import org.onosproject.cfg.ConfigProperty;
import org.onosproject.net.*;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;


public class Util {
    static int POLL_FREQ = getPollFreq();
    static final long MODEL_SIZE = 20 * 1_000_000 * 8; // 20 Mega-bit
    static final MacAddress FL_SERVER_MAC = MacAddress.valueOf("00:00:00:00:00:AA");
    static final Host SERVER_HOST = Services.hostService.getHost(HostId.hostId(FL_SERVER_MAC));
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ClientInformationDatabase.class);

    final static ConcurrentHashMap<String, Logger> LOGGERS = new ConcurrentHashMap<>();


    private static int getPollFreq() {
        ConfigProperty pollFreq = Services.cfgService.getProperty("org.onosproject.provider.of.device.impl.OpenFlowDeviceProvider", "portStatsPollFrequency");
        if (pollFreq == null) {
            return 5;
        } else {
            return pollFreq.asInteger();
        }
    }


    public static long MbpsToBps(Number num) {
        return DataRateUnit.MBPS.toBitsPerSecond(num.longValue());
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


    public static long ageInMilliSeconds(long t) {
        return System.currentTimeMillis() - t;
    }

    public static int ageInSeconds(long t) {
        if (t == 0) {
            return Integer.MAX_VALUE;
        } else {
            return (int) (ageInMilliSeconds(t) / 1e3);
        }
    }

    public static double safeDivision(Number num1, Number num2) {
        if (num2.doubleValue() == 0) {
            num2 = 1;
        }
        return num1.doubleValue() / num2.doubleValue();
    }

    public static String pathFormat(Path path) {
        StringBuilder stringBuilder = new StringBuilder();
        for (Link link : path.links()) {
            ElementId id = link.src().elementId();
            if (id instanceof HostId) {
                if (((HostId) id).mac() == Util.FL_SERVER_MAC)
                    stringBuilder.append("FLServer");
                else
                    stringBuilder.append("FL#").append(ClientInformationDatabase.INSTANCE.getHostByHostID((HostId) id).getFlClientCID());
            }
            else {
                stringBuilder.append(id.toString().substring(15));
            }
            stringBuilder.append(" -> ");
        }
        stringBuilder.append("FL#").append(ClientInformationDatabase.INSTANCE.getHostByHostID(path.dst().hostId()).getFlClientCID());
        return stringBuilder.toString();
    }

    static void log(String loggerName, String message) {
        if (!LOGGERS.containsKey(loggerName)) {
            try {
                Logger logger = Logger.getLogger(loggerName);
                LOGGERS.put(loggerName, logger);
                FileHandler fh = new FileHandler(String.format("/home/osama/flow_sched_logs/%s.log", loggerName));
                logger.setUseParentHandlers(false);
                logger.addHandler(fh);
                fh.setFormatter(new Formatter() {
                    @Override
                    public String format(LogRecord logRecord) {
                        return logRecord.getMessage() + "\n";
                    }
                });
            } catch (Exception e) {
                LOGGER.error(e.getMessage());
            }
        }
        LOGGERS.get(loggerName).info(message);
    }
}