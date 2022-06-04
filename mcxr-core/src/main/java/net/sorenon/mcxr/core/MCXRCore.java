package net.sorenon.mcxr.core;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.sorenon.mcxr.core.accessor.PlayerExt;
import net.sorenon.mcxr.core.config.MCXRCoreConfig;
import net.sorenon.mcxr.core.config.MCXRCoreConfigImpl;
import net.sorenon.mcxr.core.mixin.ServerLoginNetworkHandlerAcc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class MCXRCore implements ModInitializer {

    public static final ResourceLocation S2C_CONFIG = new ResourceLocation("mcxr", "config");

    public static final ResourceLocation IS_XR_PLAYER = new ResourceLocation("mcxr", "is_xr_player");
    public static final ResourceLocation POSES = new ResourceLocation("mcxr", "poses");
    public static final ResourceLocation TELEPORT = new ResourceLocation("mcxr", "teleport");

    public static MCXRCore INSTANCE;

    private static final Logger LOGGER = LogManager.getLogger("MCXR Core");

    public final MCXRCoreConfigImpl config = new MCXRCoreConfigImpl();

    @Override
    public void onInitialize() {
        INSTANCE = this;
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            config.xrEnabled = true;
        }

        ServerLoginNetworking.registerGlobalReceiver(S2C_CONFIG, (server, handler, understood, buf, synchronizer, responseSender) -> {
            if (understood) {
                boolean xr = buf.readBoolean();
                var profile = ((ServerLoginNetworkHandlerAcc) handler).getGameProfile();
                if (xr) {
                    LOGGER.info("Received XR login packet from " + profile.getId());
                } else {
                    LOGGER.info("Received login packet from " + profile.getId());
                }
            }
        });

        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            LOGGER.debug("Sending login packet to " + handler.getUserName());
            var buf = PacketByteBufs.create();
            sender.sendPacket(S2C_CONFIG, buf);
        });

        ServerPlayNetworking.registerGlobalReceiver(IS_XR_PLAYER,
                (server, player, handler, buf, responseSender) -> {
                    boolean isXr = buf.readBoolean();
                    server.execute(() -> {
                        PlayerExt acc = (PlayerExt) player;
                        boolean wasXr = acc.isXR();
                        acc.setIsXr(isXr);
                        if (wasXr && !isXr) {
                            player.refreshDimensions();
                        }
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(POSES,
                (server, player, handler, buf, responseSender) -> {
                    var pose1 = new Pose();
                    var pose2 = new Pose();
                    var pose3 = new Pose();
                    pose1.read(buf);
                    pose2.read(buf);
                    pose3.read(buf);
                    server.execute(() -> setPlayerPoses(player, pose1, pose2, pose3, 0));
                });

        ServerPlayNetworking.registerGlobalReceiver(TELEPORT,
                (server, player, handler, buf, responseSender) -> {
                    double x = buf.readDouble();
                    double y = buf.readDouble();
                    double z = buf.readDouble();
                    server.execute(() -> player.setPos(x, y, z));
                });
    }

    public void setPlayerPoses(Player player, Pose headPose, Pose leftHandPose, Pose rightHandPose, float f) {
        PlayerExt acc = (PlayerExt) player;
        acc.getHeadPose().set(headPose);
        acc.getLeftHandPose().set(leftHandPose);
        acc.getRightHandPose().set(rightHandPose);

        if (f != 0) {
            acc.getLeftHandPose().orientation.rotateX(f);
            acc.getRightHandPose().orientation.rotateX(f);

            FriendlyByteBuf buf = PacketByteBufs.create();
            acc.getHeadPose().write(buf);
            acc.getLeftHandPose().write(buf);
            acc.getRightHandPose().write(buf);

            ClientPlayNetworking.send(POSES, buf);
        }
    }

    public static MCXRCoreConfig getCoreConfig() {
        return INSTANCE.config;
    }

    public static HumanoidArm handToArm(LivingEntity entity, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            return entity.getMainArm();
        } else {
            return entity.getMainArm().getOpposite();
        }
    }
}
