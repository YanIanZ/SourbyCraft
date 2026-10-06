package dev.iyanz.sourbycraft.api.world;

import java.util.Objects;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Immutable creation requests for AWF's two creation modes. Both modes persist across restarts;
 * {@code autoload} decides whether they are loaded automatically. Clone mode inherits the
 * template's terrain settings and stores only changed chunks over the frozen template.
 */
@NullMarked
public sealed interface WorldRequest permits WorldRequest.Persistent, WorldRequest.TemplateClone {
    String name();
    boolean autoload();
    WorldRequest withAutoload(boolean value);

    static Persistent persistent(final String name) {
        return new Persistent(name, World.Environment.NORMAL, WorldType.NORMAL, null, null, false);
    }

    static TemplateClone fromTemplate(final String template, final String name) {
        return new TemplateClone(template, name, false);
    }

    /** A new persistent world, with vanilla terrain unless a named generator is supplied. */
    record Persistent(String name, World.Environment environment, WorldType type, @Nullable Long seed,
                      @Nullable String generator, boolean autoload) implements WorldRequest {
        public Persistent {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(type, "type");
        }

        @Override
        public Persistent withAutoload(final boolean value) {
            return new Persistent(this.name, this.environment, this.type, this.seed, this.generator, value);
        }

        public Persistent withSeed(final long value) {
            return new Persistent(this.name, this.environment, this.type, value, this.generator, this.autoload);
        }

        public Persistent withEnvironment(final World.Environment value) {
            return new Persistent(this.name, value, this.type, this.seed, this.generator, this.autoload);
        }

        public Persistent withType(final WorldType value) {
            return new Persistent(this.name, this.environment, value, this.seed, this.generator, this.autoload);
        }

        /** {@code void}, {@code Plugin[:id]}, or {@code null} for vanilla terrain. */
        public Persistent withGenerator(final @Nullable String value) {
            return new Persistent(this.name, this.environment, this.type, this.seed, value, this.autoload);
        }
    }

    /** A persistent copy-on-write clone; terrain settings come from the named template. */
    record TemplateClone(String template, String name, boolean autoload) implements WorldRequest {
        public TemplateClone {
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(name, "name");
        }

        @Override
        public TemplateClone withAutoload(final boolean value) {
            return new TemplateClone(this.template, this.name, value);
        }
    }
}
