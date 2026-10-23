package dev.iyanz.sourbycraft.abysslink;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static dev.iyanz.sourbycraft.abysslink.AbyssLinkTlv.*;

/** Bounded process client. No Bukkit/player references; all snapshots are immutable. */
public final class AbyssLinkClient implements AutoCloseable {
    public record Sample(UUID player, int latencyMillis, long sampledAtMillis,long generation) {
        public Sample(UUID player,int latencyMillis,long sampledAtMillis){this(player,latencyMillis,sampledAtMillis,0);}
    }
    private record Cached(Optional<Sample> sample, long expiresNanos) {}
    static final class Request {
        final UUID player;
        final AbyssLinkSession session;
        final CompletableFuture<Optional<Sample>> result = new CompletableFuture<>();
        int subscribers=1;
        io.netty.util.concurrent.ScheduledFuture<?> deadline;
        Request(UUID player, AbyssLinkSession session) { this.player=player; this.session=session; }
    }
    final AbyssLinkSettings settings;
    public final AbyssLinkMetrics metrics = new AbyssLinkMetrics();
    private final Map<Long,Request> pending = new HashMap<>();
    private final Map<UUID,Request> byPlayer = new HashMap<>();
    private final LinkedHashMap<UUID,Cached> cache = new LinkedHashMap<>();
    private volatile AbyssLinkSession session;
    private AbyssLinkConnectionManager manager;
    private volatile boolean closed;
    private volatile long generation;
    private long nextId = 1, queryWindowNanos;
    private int queryCount;

    public AbyssLinkClient(AbyssLinkSettings settings) { this.settings=settings; }
    public synchronized void start() {
        if (closed || manager != null || !settings.enabled()) return;
        manager = new AbyssLinkConnectionManager(this);
        manager.start();
    }
    synchronized long nextId() { if (nextId == Long.MAX_VALUE) nextId=1; return nextId++; }
    synchronized void authenticated(AbyssLinkSession value) {
        if (closed) { value.close(); return; }
        if (session != null && session != value) session.close();
        cache.clear(); generation++;session=value; metrics.connections.increment();
        if(manager!=null)manager.authenticated();
    }
    long generation(){return generation;}
    public boolean connected() { var current=session; return current != null && current.active(); }
    public AbyssLinkSettings settingsForDiagnostics() { return settings; }
    public Set<String> capabilities() { var current=session; return current == null ? Set.of() : current.capabilities(); }
    public boolean current(Sample sample) {
        return connected() && sample.generation()==generation && sample.sampledAtMillis()<=System.currentTimeMillis()+1000
            && sample.sampledAtMillis()>=System.currentTimeMillis()-settings.maxSampleAgeMillis();
    }
    public String status() { return connected() ? "authenticated" : closed ? "stopped" : settings.enabled() ? "disconnected" : "disabled"; }
    public synchronized int pendingCount() { return pending.size(); }

    /** Copies isolate caller cancellation; one wire request is shared by concurrent queries for a UUID. */
    public synchronized CompletableFuture<Optional<Sample>> resolve(UUID player) {
        Objects.requireNonNull(player);
        var current=session;
        if (closed || current == null || !current.active() || !current.capabilities().contains("ping")) return CompletableFuture.completedFuture(Optional.empty());
        long now=System.nanoTime();
        Cached cached=cache.get(player);
        if (cached != null && now-cached.expiresNanos < 0) {
            metrics.cacheHits.increment();return CompletableFuture.completedFuture(cached.sample.filter(this::current));
        }
        cache.remove(player);
        Request existing=byPlayer.get(player);
        if (existing != null) {
            if(existing.subscribers>=4){metrics.backpressure.increment();return CompletableFuture.completedFuture(Optional.empty());}
            existing.subscribers++;return existing.result.copy();
        }
        if (pending.size() >= settings.maxPendingRequests()) { metrics.backpressure.increment(); return CompletableFuture.completedFuture(Optional.empty()); }
        if(now-queryWindowNanos>=TimeUnit.SECONDS.toNanos(1)){queryWindowNanos=now;queryCount=0;}
        if(queryCount++>=64){metrics.backpressure.increment();return CompletableFuture.completedFuture(Optional.empty());}
        long id=nextId(); Request request=new Request(player,current);
        pending.put(id,request); byPlayer.put(player,request);
        try {
            current.execute(() -> {
                synchronized (AbyssLinkClient.this) {
                    if (pending.get(id)!=request || session!=current || !current.active()) { finish(id,request,Optional.empty(),false); return; }
                    request.deadline=current.context().executor().schedule(() -> {
                        metrics.timeouts.increment(); finish(id,request,Optional.empty(),true);
                    },settings.timeoutMillis(),TimeUnit.MILLISECONDS);
                    if (!current.send(new AbyssLinkFrame(4,id,encode(Map.of(1,value(player)))))) finish(id,request,Optional.empty(),false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) { finish(id,request,Optional.empty(),false); }
        return request.result.copy();
    }
    synchronized void result(AbyssLinkSession from, AbyssLinkFrame frame, Map<Integer,byte[]> fields) {
        UUID player=uuid(fields,1);
        int latency=i32(fields,2); long sampled=i64(fields,3); boolean found=bool(fields,4);
        Request request=pending.get(frame.requestId());
        // A well-formed late response after timeout cannot satisfy another request/session.
        if (request == null) return;
        if (request.session!=from || session!=from) throw new IllegalArgumentException("Stale session");
        if (!player.equals(request.player)) throw new IllegalArgumentException("Mismatched player");
        long now=System.currentTimeMillis();
        boolean fresh=sampled <= now+1000 && sampled >= now-settings.maxSampleAgeMillis();
        Optional<Sample> sample=found && latency>=0 && fresh ? Optional.of(new Sample(player,latency,sampled,generation)) : Optional.empty();
        finish(frame.requestId(),request,sample,true);
    }
    synchronized void error(AbyssLinkSession from,long id) {
        Request request=pending.get(id);
        if (request!=null && request.session==from) finish(id,request,Optional.empty(),true);
    }
    private synchronized void finish(long id,Request request,Optional<Sample> sample,boolean remember) {
        if (!pending.remove(id,request)) return;
        byPlayer.remove(request.player,request);
        if(request.deadline!=null) request.deadline.cancel(false);
        if (remember && session==request.session && settings.pingCacheMillis()>0) {
            if(cache.size()>=settings.maxPendingRequests()) cache.remove(cache.keySet().iterator().next());
            cache.put(request.player,new Cached(sample,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(settings.pingCacheMillis())));
        }
        request.result.complete(sample);
    }
    synchronized void lost(AbyssLinkSession from) {
        if(session==from) { session=null;generation++;cache.clear(); }
        for(var entry:new ArrayList<>(pending.entrySet())) if(entry.getValue().session==from) finish(entry.getKey(),entry.getValue(),Optional.empty(),false);
    }
    void reportBatch(Runnable batch) {
        var current=session;
        if(current==null || !current.active()) { batch.run(); return; }
        current.execute(batch);
    }
    boolean sendReport(int type,byte[] payload) {
        var current=session;
        return current!=null && current.active() && current.context().executor().inEventLoop() && current.send(new AbyssLinkFrame(type,0,payload));
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;
        if(manager!=null)manager.close();
        var current=session;session=null;cache.clear();
        if(current!=null)current.close();
        for(var entry:new ArrayList<>(pending.entrySet()))finish(entry.getKey(),entry.getValue(),Optional.empty(),false);
    }
}
