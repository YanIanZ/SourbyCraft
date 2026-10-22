package dev.iyanz.sourbycraft.api.world;

import org.jspecify.annotations.NullMarked;

/** An AWF operation conflicts with work still using the same world, template or output file. */
@NullMarked
public final class WorldOperationBusyException extends IllegalStateException {
    private final String resource;
    private final String activeOperation;

    public WorldOperationBusyException(final String resource, final String activeOperation) {
        super(resource + " is busy with " + activeOperation + "; retry after that operation completes");
        this.resource = resource;
        this.activeOperation = activeOperation;
    }

    public String resource() {
        return this.resource;
    }

    public String activeOperation() {
        return this.activeOperation;
    }
}
