package dev.iyanz.sourbycraft.security;

import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@org.bukkit.support.environment.VanillaFeature
class SecurityNbtHookTest {
    @TempDir Path directory;
    private final SecurityGuards guards = SecurityGuards.INSTANCE;

    @BeforeAll static void bootstrap() {
        // Registries come from @VanillaFeature (shared across the suite; a second setup throws "Registry already loaded").
    }

    @AfterEach void restoreDefaults() throws Exception {
        Path config = directory.resolve("defaults.yml");
        Files.writeString(config, "");
        guards.load(config);
        assertFalse(guards.inPacket());
    }

    @Test void creativeItemCodecObservesEncodedComponentsAndPreservesItemInLogMode() {
        var buffer = encodedCreativeItem();
        long before = guards.violations(SecurityGuards.Limit.CREATIVE_BYTES);
        try {
            try (var scope = guards.open(new SecurityGuards.ConnectionState())) {
                var decoded = ServerboundSetCreativeModeSlotPacket.STREAM_CODEC.decode(buffer);
                assertEquals(17000, decoded.itemStack().get(DataComponents.CUSTOM_DATA).copyTag().getByteArray("payload").orElseThrow().length);
                assertEquals(0, buffer.readableBytes());
            }
            assertEquals(before + 1, guards.violations(SecurityGuards.Limit.CREATIVE_BYTES));
        } finally { buffer.release(); }
    }

    @Test void creativeItemCodecDoesNotApplyNetworkPolicyOutsidePacketScope() {
        var buffer = encodedCreativeItem();
        long before = guards.violations(SecurityGuards.Limit.CREATIVE_BYTES);
        try {
            assertFalse(ServerboundSetCreativeModeSlotPacket.STREAM_CODEC.decode(buffer).itemStack().isEmpty());
            assertEquals(before, guards.violations(SecurityGuards.Limit.CREATIVE_BYTES));
        } finally { buffer.release(); }
    }

    private static RegistryFriendlyByteBuf encodedCreativeItem() {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("payload", new byte[17000]);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), org.bukkit.support.RegistryHelper.registryAccess());
        // Construct the client wire layout using native field codecs. The server packet encoder
        // invokes outbound item sanitization, which requires a running MinecraftServer.
        buffer.writeShort(0); // creative slot
        buffer.writeVarInt(1); // stack count
        Item.STREAM_CODEC.encode(buffer, Items.STONE.builtInRegistryHolder());
        buffer.writeVarInt(1); // added components
        buffer.writeVarInt(0); // removed components
        DataComponentType.STREAM_CODEC.encode(buffer, DataComponents.CUSTOM_DATA);
        net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, CustomData> componentCodec = DataComponents.CUSTOM_DATA.streamCodec().cast();
        componentCodec.apply(ByteBufCodecs.registryFriendlyLengthPrefixed(Integer.MAX_VALUE))
            .encode(buffer, CustomData.of(tag));
        return buffer;
    }

    @Test void discardedPayloadCodecRejectsExplicitKickAndReleasesScope() throws Exception {
        Path config = directory.resolve("kick.yml");
        Files.writeString(config, "packet-guard:\n  custom-payload:\n    mode: KICK\n    max-bytes: 8\n");
        guards.load(config);
        var buffer = new FriendlyByteBuf(Unpooled.buffer().writeZero(9));
        long before = guards.violations(SecurityGuards.Limit.PAYLOAD_BYTES);
        try {
            try (var scope = guards.open(new SecurityGuards.ConnectionState())) {
                assertThrows(SecurityGuards.Rejected.class, () -> DiscardedPayload.<FriendlyByteBuf>codec(Identifier.parse("sourbycraft:test"), 32767).decode(buffer));
            }
            assertEquals(before + 1, guards.violations(SecurityGuards.Limit.PAYLOAD_BYTES));
        } finally { buffer.release(); }
    }

    @Test void nativeNbtReadInvokesStringAndListGuards() {
        CompoundTag tag = new CompoundTag();
        tag.putString("text", "x".repeat(4097));
        tag.putByteArray("array", new byte[65537]);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        long strings = guards.violations(SecurityGuards.Limit.NBT_STRING);
        long lists = guards.violations(SecurityGuards.Limit.NBT_LIST);
        try {
            buffer.writeNbt(tag);
            try (var scope = guards.open(new SecurityGuards.ConnectionState())) {
                CompoundTag decoded = buffer.readNbt();
                assertEquals(4097, decoded.getString("text").orElseThrow().length());
                assertEquals(65537, decoded.getByteArray("array").orElseThrow().length);
            }
            assertEquals(strings + 1, guards.violations(SecurityGuards.Limit.NBT_STRING));
            assertEquals(lists + 1, guards.violations(SecurityGuards.Limit.NBT_LIST));
        } finally { buffer.release(); }
    }
}
