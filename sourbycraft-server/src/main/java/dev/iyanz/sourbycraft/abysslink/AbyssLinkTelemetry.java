package dev.iyanz.sourbycraft.abysslink;

import dev.iyanz.sourbycraft.core.AuroraRuntime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.iyanz.sourbycraft.abysslink.AbyssLinkTlv.*;

/** Global-owned immutable sampling, at most one queued control batch; no per-player scheduling. */
final class AbyssLinkTelemetry {
    private final AbyssLinkClient client;
    private final AbyssLinkSecurityBridge security=new AbyssLinkSecurityBridge();
    private final AtomicBoolean queued=new AtomicBoolean();
    private int previousStatus=-1,previousOnline=-1,cycles;
    private long lastGeneration=-1;
    AbyssLinkTelemetry(AbyssLinkClient client){this.client=client;}
    void sample(int online) {
        if(!client.connected())return;
        long currentGeneration=client.generation();
        if(currentGeneration!=lastGeneration){previousStatus=-1;previousOnline=-1;lastGeneration=currentGeneration;}
        var snapshot=dev.iyanz.sourbycraft.perf.MetricsRuntime.provider().snapshot();
        int status=health(snapshot,client.pendingCount()>=client.settings.maxPendingRequests(),AuroraRuntime.state()==AuroraRuntime.State.DEGRADED);
        if(status>0 && ++cycles%3!=0)return;
        if(!queued.compareAndSet(false,true)){client.metrics.backpressure.increment();return;}
        byte[] health=client.capabilities().contains("health") && (status!=previousStatus || online!=previousOnline)
            ?encode(Map.of(1,u8(status),2,value(online))):null;
        byte[] threat=client.capabilities().contains("threat")?security.sample(client.settings.threatsEnabled()):null;
        try {
            client.reportBatch(() -> {
                try {
                    if(client.generation()!=currentGeneration)return;
                    if(health!=null && client.sendReport(6,health)){previousStatus=status;previousOnline=online;}
                    if(threat!=null && client.sendReport(7,threat))client.metrics.threats.increment();
                }finally{queued.set(false);}
            });
        }catch(java.util.concurrent.RejectedExecutionException stopped){queued.set(false);}
    }
    /** Advisory mapping over the canonical immutable snapshot; unavailable is never healthy. */
    static int health(dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot snapshot,boolean saturated,boolean degraded) {
        if(saturated)return 2;
        if(snapshot==null || snapshot.freshness().state()!=dev.iyanz.sourbycraft.api.metrics.MetricState.AVAILABLE)return 1;
        var runtime=snapshot.runtime();
        if(runtime.processCpuPercent()>=95 || runtime.heapMaxBytes()>0 && runtime.heapUsedBytes()/(double)runtime.heapMaxBytes()>=0.95)return 2;
        return degraded?1:0;
    }

}
