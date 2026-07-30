package dev.iyanz.sourbyclip.cherry.at;

import org.objectweb.asm.Opcodes;

/**
 * Cherry — a single access-modifier / finality change applied to a class, field or method by an
 * access transformer (AT) line.
 *
 * <p>Ported from CraftCanvasMC/Horizon's {@code io.canvasmc.horizon.transformer.widener
 * .TransformOperation} (the access-transformer capability Horizon has and the Leaves mixin loader
 * lacks), de-branded into Cherry and stripped of Horizon's jspecify dependency. The bit-twiddling
 * on the ASM access flags is preserved verbatim so Horizon-authored {@code .at} files behave
 * identically under Cherry.
 *
 * <p>The two enums ({@link Access}, {@link Finality}) each implement this interface purely to reuse
 * {@link #apply(int)}'s shape; a fully-formed change (carrying both an access level AND a finality
 * decision) is only ever represented by an {@link OpImpl}, built via {@link #builder()}. Calling
 * {@link #access()} on an {@link Access} or {@link Finality} constant is not meaningful and throws
 * {@link UnsupportedOperationException} — see {@link OpImpl#access()} for the real accessor.
 */
public interface AccessChange {

    /** @return a new {@link Builder}, defaulting to {@link Access#DEFAULT} and {@link Finality#NONE}. */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Apply this change to a set of ASM access flags (as found on {@code ClassNode.access},
     * {@code FieldNode.access} or {@code MethodNode.access}).
     *
     * @param flags the original access flags
     * @return the resulting access flags after this change is applied
     */
    int apply(int flags);

    /**
     * @return the access level this change carries. Only meaningful on an {@link OpImpl} built via
     * {@link #builder()}; the {@link Access} and {@link Finality} enum constants themselves throw
     * {@link UnsupportedOperationException} since they each represent only half of a change.
     */
    Access access();

    /** The four JVM visibility levels an AT line can set on a class, field or method. */
    enum Access implements AccessChange {
        /** Clears {@code private}/{@code protected} and sets {@code public}. */
        PUBLIC {
            public int apply(int f) {
                return (f & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            }
        },
        /** Sets {@code protected}, unless the member is already {@code public} (which is left untouched). */
        PROTECTED {
            public int apply(int f) {
                if ((f & Opcodes.ACC_PUBLIC) != 0) return f;
                return (f & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PROTECTED;
            }
        },
        /** Clears {@code public}/{@code protected}/{@code private}, leaving package-private (default) access. */
        DEFAULT {
            public int apply(int f) {
                return f & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
            }
        },
        /** Clears {@code public}/{@code protected} and sets {@code private}. */
        PRIVATE {
            public int apply(int f) {
                return (f & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;
            }
        };

        /**
         * @throws UnsupportedOperationException always — an {@link Access} constant only carries
         * half of a change (see the interface-level note); use {@link OpImpl#access()} instead.
         */
        @Override
        public Access access() {
            throw new UnsupportedOperationException("Access is carried by OpImpl, not the enum member");
        }
    }

    /** Whether an AT line additionally adds or removes the {@code final} modifier. */
    enum Finality implements AccessChange {
        /** Leaves the {@code final} modifier untouched. */
        NONE {
            public int apply(int f) {
                return f;
            }
        },
        /** Sets the {@code final} modifier ({@code +f} suffix in an AT line). */
        ADD {
            public int apply(int f) {
                return f | Opcodes.ACC_FINAL;
            }
        },
        /** Clears the {@code final} modifier ({@code -f} suffix in an AT line). */
        REMOVE {
            public int apply(int f) {
                return f & ~Opcodes.ACC_FINAL;
            }
        };

        /**
         * @throws UnsupportedOperationException always — a {@link Finality} constant only carries
         * half of a change (see the interface-level note); use {@link OpImpl#access()} instead.
         */
        @Override
        public Access access() {
            throw new UnsupportedOperationException("Access is carried by OpImpl, not the enum member");
        }
    }

    /**
     * The concrete, fully-formed change produced by {@link Builder#build()}: an {@link Access}
     * level plus a {@link Finality} decision, applied in that order.
     *
     * @param access   the visibility level to apply
     * @param finality the finality change to apply, after the access change
     */
    record OpImpl(Access access, Finality finality) implements AccessChange {
        /** Applies {@link #access}, then {@link #finality}, in that order. */
        public int apply(int f) {
            f = access.apply(f);
            return finality.apply(f);
        }
    }

    /** Builds an {@link OpImpl} from an {@link Access} level and a {@link Finality} decision. */
    final class Builder {
        private Access access = Access.DEFAULT;
        private Finality finality = Finality.NONE;

        /**
         * @param access the visibility level to apply
         * @return this builder
         */
        public Builder access(Access access) {
            this.access = access;
            return this;
        }

        /**
         * @param finality the finality change to apply
         * @return this builder
         */
        public Builder finality(Finality finality) {
            this.finality = finality;
            return this;
        }

        /** @return a new {@link OpImpl} carrying the configured access + finality. */
        public AccessChange build() {
            return new OpImpl(access, finality);
        }
    }
}
