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
 */
public interface AccessChange {

    static Builder builder() {
        return new Builder();
    }

    int apply(int flags);

    Access access();

    enum Access implements AccessChange {
        PUBLIC {
            public int apply(int f) {
                return (f & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            }
        },
        PROTECTED {
            public int apply(int f) {
                if ((f & Opcodes.ACC_PUBLIC) != 0) return f;
                return (f & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PROTECTED;
            }
        },
        DEFAULT {
            public int apply(int f) {
                return f & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
            }
        },
        PRIVATE {
            public int apply(int f) {
                return (f & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;
            }
        };

        @Override
        public Access access() {
            throw new UnsupportedOperationException("Access is carried by OpImpl, not the enum member");
        }
    }

    enum Finality implements AccessChange {
        NONE {
            public int apply(int f) {
                return f;
            }
        },
        ADD {
            public int apply(int f) {
                return f | Opcodes.ACC_FINAL;
            }
        },
        REMOVE {
            public int apply(int f) {
                return f & ~Opcodes.ACC_FINAL;
            }
        };

        @Override
        public Access access() {
            throw new UnsupportedOperationException("Access is carried by OpImpl, not the enum member");
        }
    }

    record OpImpl(Access access, Finality finality) implements AccessChange {
        public int apply(int f) {
            f = access.apply(f);
            return finality.apply(f);
        }
    }

    final class Builder {
        private Access access = Access.DEFAULT;
        private Finality finality = Finality.NONE;

        public Builder access(Access access) {
            this.access = access;
            return this;
        }

        public Builder finality(Finality finality) {
            this.finality = finality;
            return this;
        }

        public AccessChange build() {
            return new OpImpl(access, finality);
        }
    }
}
