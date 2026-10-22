package dev.iyanz.aurora.command;

import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.network.chat.Component;

/**
 * Command failures the Aurora engine reports, owned by Aurora rather than borrowed upstream.
 *
 * <p>Region threading gives commands a failure mode vanilla has no vocabulary for: the work is
 * legal, the arguments are valid, and the thread running it simply does not own the data. Canvas
 * defines an equivalent, but a Minecraft patch reaching into the engine's plugin API would put a
 * direct engine call in a vanilla class, which is the coupling the independence policy exists to
 * prevent -- engine access belongs behind something SourbyCraft owns.</p>
 *
 * <p>The wording deliberately matches what an operator already sees from Canvas-era builds, so
 * the message is not a second vocabulary for the same condition.</p>
 */
public final class AuroraCommandErrors {

    private AuroraCommandErrors() {}

    /**
     * The executing thread does not own the region the command targets.
     *
     * <p>Applies where the answer is needed immediately and so cannot be handed to the owning
     * region: a conditional has to return before the rest of the chain runs, unlike
     * {@code setblock} or {@code fill}, which pass the position over and finish later.</p>
     */
    public static final DynamicCommandExceptionType CANNOT_DO_X_CROSS_REGION =
        new DynamicCommandExceptionType(action -> Component.literal("Cannot " + action + " cross-region"));
}
