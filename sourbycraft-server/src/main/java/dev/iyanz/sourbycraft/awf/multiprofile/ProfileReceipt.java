package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The permanent result of one operation: operation id, request digest, outcome and a bounded result.
 *
 * <p>Receipts are stored in paged records per resource ({@link Page}, format-2 record type 13 subtype
 * receipt) and are never expired: a retry after any delay returns the same result instead of running
 * again. Lookup is point-indexed by operation id. A resource holds at most
 * {@value Page#MAX_RECEIPTS_PER_RESOURCE} receipts; beyond that new financial and delivery operations
 * are refused until an offline archival policy exists (fail closed, never silent deletion).</p>
 *
 * @param operationId the operation
 * @param requestDigest the request digest the result belongs to
 * @param kind the operation kind
 * @param outcome committed or aborted
 * @param result bounded durable result bytes (operation-specific, may be empty)
 * @param decidedAtMillis commit or abort time
 */
public record ProfileReceipt(UUID operationId, byte[] requestDigest, CoopOperation.Kind kind, Outcome outcome, byte[] result,
                             long decidedAtMillis) {

    /**
     * Receipts kept per resource (Build 51; COOP-DESIGN §9 bounds). Both repositories drop the oldest by decision
     * time once a resource holds more: a receipt that old can no longer replay or refuse its operation id (a
     * reconcile of a lost reply runs right after the write and always finds its receipt). Save and login commits
     * keep no receipt at all ({@link CoopOperation.Kind#permanentReceipt}).
     */
    public static final int RETAINED_PER_RESOURCE = 256;

    public static final int MAX_RESULT_BYTES = 4096;

    public ProfileReceipt {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(requestDigest, "requestDigest");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(outcome, "outcome");
        if (requestDigest.length != CoopOperation.DIGEST_BYTES) throw new IllegalArgumentException("request digest length");
        requestDigest = requestDigest.clone();
        result = result == null ? new byte[0] : result.clone();
        if (result.length > MAX_RESULT_BYTES) throw new IllegalArgumentException("receipt result too large");
        if (decidedAtMillis < 0) throw new IllegalArgumentException("decidedAtMillis");
    }

    @Override
    public byte[] requestDigest() {
        return this.requestDigest.clone();
    }

    @Override
    public byte[] result() {
        return this.result.clone();
    }

    public boolean sameRequest(final byte[] digest) {
        return digest != null && MessageDigest.isEqual(this.requestDigest, digest);
    }

    /** Receipt outcomes with stable numeric tags. */
    public enum Outcome {
        COMMITTED(1),
        ABORTED(2);

        private final int tag;

        Outcome(final int tag) {
            this.tag = tag;
        }

        public int tag() {
            return this.tag;
        }

        public static Outcome fromTag(final int tag, final String where) throws IOException {
            for (final Outcome outcome : values()) {
                if (outcome.tag == tag) return outcome;
            }
            throw new IOException("receipt outcome tag " + tag + " in " + where + " is not known to this build");
        }
    }

    /**
     * One page of a resource's receipts (record type 13, subtype receipt).
     *
     * @param resource the account or profile the operations acted on
     * @param page page number from 0
     * @param revision the page's own revision
     * @param receipts receipts in decision order
     */
    public record Page(UUID resource, int page, long revision, List<ProfileReceipt> receipts) {

        public static final int MAX_PER_PAGE = 128;
        /** Permanent receipts per resource before new financial/delivery operations are refused. */
        public static final long MAX_RECEIPTS_PER_RESOURCE = 1_000_000L;

        public Page {
            Objects.requireNonNull(resource, "resource");
            if (page < 0) throw new IllegalArgumentException("page " + page);
            CoopWriteGuard.counter(revision, "receipt page revision");
            receipts = List.copyOf(receipts == null ? List.of() : receipts);
            if (receipts.size() > MAX_PER_PAGE) throw new IllegalArgumentException("receipt page too long");
            for (int i = 0; i < receipts.size(); i++) {
                for (int j = i + 1; j < receipts.size(); j++) {
                    if (receipts.get(i).operationId().equals(receipts.get(j).operationId())) {
                        throw new IllegalArgumentException("operation " + receipts.get(i).operationId() + " twice in one page");
                    }
                }
            }
        }

        public boolean full() {
            return this.receipts.size() >= MAX_PER_PAGE;
        }

        public ProfileReceipt find(final UUID operationId) {
            for (final ProfileReceipt receipt : this.receipts) {
                if (receipt.operationId().equals(operationId)) return receipt;
            }
            return null;
        }

        /** The page with {@code receipt} appended, at revision + 1. */
        public Page with(final ProfileReceipt receipt) {
            if (full()) throw new IllegalStateException("receipt page " + this.resource + "/" + this.page + " is full");
            final List<ProfileReceipt> next = new java.util.ArrayList<>(this.receipts);
            next.add(receipt);
            return new Page(this.resource, this.page, CoopWriteGuard.next(this.revision, "receipt page"), next);
        }
    }
}
