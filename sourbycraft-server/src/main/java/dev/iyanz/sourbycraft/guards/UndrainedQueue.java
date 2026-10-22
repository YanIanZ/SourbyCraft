package dev.iyanz.sourbycraft.guards;

import java.util.AbstractQueue;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The value of {@code MinecraftServer.processQueue} under region threading. In a single-threaded
 * server that queue is drained by the main loop; here its only poll sits inside a block that never
 * runs, so a task added to it would silently never execute (Spigot's {@code /restart}, Bukkit
 * conversations, sync chat and pre-login events all did exactly that in the base). Adding is
 * therefore refused: {@link #offer} throws {@link RegionRefusal#UNDRAINED_QUEUE} naming the caller
 * and logs it at ERROR once per distinct caller (bounded), so a plugin that swallows the exception
 * still leaves a trace. Reading the queue is harmless and reports it empty.
 */
public final class UndrainedQueue extends AbstractQueue<Runnable> {

    private static final Logger LOGGER = LoggerFactory.getLogger("SourbyCraft");
    private static final int MAX_REPORTED_CALLERS = 64;

    private final Consumer<String> errorSink;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /** The queue installed on the server: refusals are logged to the SourbyCraft logger. */
    public static java.util.Queue<Runnable> create() {
        return new UndrainedQueue(LOGGER::error);
    }

    /** For tests: refusals are logged to {@code errorSink} instead. */
    public UndrainedQueue(final Consumer<String> errorSink) {
        this.errorSink = errorSink;
    }

    @Override
    public boolean offer(final Runnable task) {
        final String caller = caller();
        final String message = RegionRefusal.UNDRAINED_QUEUE.message("added by " + caller);
        if (this.reported.size() < MAX_REPORTED_CALLERS && this.reported.add(caller)) {
            this.errorSink.accept(message);
        }
        throw new UnsupportedOperationException(message);
    }

    @Override
    public Runnable poll() {
        return null;
    }

    @Override
    public Runnable peek() {
        return null;
    }

    @Override
    public Iterator<Runnable> iterator() {
        return Collections.emptyIterator();
    }

    @Override
    public int size() {
        return 0;
    }

    /** The first frame that is not this class or the JDK's {@code AbstractQueue.add} wrapper. */
    private static String caller() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
            .filter(f -> f.getDeclaringClass() != UndrainedQueue.class && f.getDeclaringClass() != AbstractQueue.class)
            .findFirst()
            .map(f -> f.getClassName() + "#" + f.getMethodName() + ":" + f.getLineNumber())
            .orElse("unknown"));
    }
}
