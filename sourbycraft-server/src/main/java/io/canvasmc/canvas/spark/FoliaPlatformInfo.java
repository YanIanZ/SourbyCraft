package io.canvasmc.canvas.spark;

import me.lucko.spark.paper.common.platform.PlatformInfo;
import org.bukkit.Server;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record FoliaPlatformInfo(Server server) implements PlatformInfo {

    @Override
    public Type getType() {
        return Type.SERVER;
    }

    @Contract(pure = true)
    @Override
    public String getName() {
        return "SourbyCraft";
    }

    @Override
    public String getBrand() {
        return this.server.getName();
    }

    @Override
    public String getVersion() {
        // SourbyCraft - one canonical build identity. This read "Build44" while the server
        // shipped build 46: a literal here is a second source of truth that drifts silently,
        // and Spark reports are the one place an operator goes to be told what they are running.
        return dev.iyanz.sourbycraft.brand.BuildInfo.load().displayVersion()
            + " (MC:" + this.server.getMinecraftVersion() + ")";
    }

    @Override
    public String getMinecraftVersion() {
        return this.server.getMinecraftVersion();
    }
}
