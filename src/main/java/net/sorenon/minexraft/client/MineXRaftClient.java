package net.sorenon.minexraft.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.sorenon.minexraft.client.input.VanillaCompatActionSet;
import net.sorenon.minexraft.client.input.XrInput;
import net.sorenon.minexraft.client.rendering.RenderPass;
import net.sorenon.minexraft.client.rendering.VrFirstPersonRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;
import org.lwjgl.openxr.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Level;

import static org.lwjgl.system.MemoryStack.stackPointers;
import static org.lwjgl.system.MemoryUtil.NULL;

public class MineXRaftClient implements ClientModInitializer {

    public static final OpenXR OPEN_XR = new OpenXR();
    public static MineXRaftClient INSTANCE;
    public static XrInput XR_INPUT;
    public static VanillaCompatActionSet vanillaCompatActionSet;
    public VrFirstPersonRenderer vrFirstPersonRenderer = new VrFirstPersonRenderer();
    public FlatGuiManager flatGuiManager = new FlatGuiManager();

    public static RenderPass renderPass = RenderPass.VANILLA;
    public static XrRect2Di viewportRect = null; //Unused since I'm not sure of any circumstances where it's needed
    public static XrFovf fov = null;
    public static int viewIndex = 0;

    public static Pose eyePose = new Pose();
    public static final Pose viewSpacePose = new Pose();

    //    public static Vec3d xrOrigin = new Vec3d(0, 0, 0); //The center of the STAGE set at the same height of the PlayerEntity's feet
    public static Vector3f xrOffset = new Vector3f(0, 0, 0);
    public static float yawTurn = 0;

    public static float handPitchAdjust = 15;
    public static int mainHand = 1;

    private static final Logger LOGGER = LogManager.getLogger("MCXR");

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        String loaderPath = "";
        try { //TODO bundle loader binaries with the mod
            File configFile = FabricLoader.getInstance().getConfigDir().resolve("mcxr.properties").toFile();
            if (!configFile.exists()) {
                if (!configFile.createNewFile()) {
                    LOGGER.warn("[MCXR] Could not create config file: " + configFile.getAbsolutePath());
                }
            }
            Properties properties = new Properties();

            if (configFile.exists()) {
                try (FileInputStream inputStream = new FileInputStream(configFile)) {
                    properties.load(inputStream);
                }
            }

            if (properties.containsKey("loader_path")) {
                loaderPath = properties.getProperty("loader_path");
            } else {
                properties.put("loader_path", "");
            }

            if (loaderPath.length() == 0) {
                if (configFile.exists()) {
                    try (FileOutputStream stream = new FileOutputStream(configFile)) {
                        properties.store(stream, "");
                    }
                }
                throw new IllegalStateException("Set path to openxr loader in " + configFile.getAbsolutePath());
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        XR.create(loaderPath);
        OPEN_XR.createOpenXRInstance();
        OPEN_XR.initializeOpenXRSystem();

        WorldRenderEvents.LAST.register(context -> {
            if (!MinecraftClient.getInstance().options.hudHidden) {
                vrFirstPersonRenderer.renderHandsGui();
            }
        });

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            vrFirstPersonRenderer.renderHands(context);
        });
    }

    public void postRenderManagerInit() {
        OPEN_XR.eventDataBuffer = XrEventDataBuffer.calloc();
        OPEN_XR.eventDataBuffer.type(XR10.XR_TYPE_EVENT_DATA_BUFFER);

        OPEN_XR.createXRSwapchains();
        XR_INPUT = new XrInput(OPEN_XR);

        vanillaCompatActionSet = XR_INPUT.makeGameplayActionSet();
        // Attach the action set we just made to the session
        XrSessionActionSetsAttachInfo attach_info = XrSessionActionSetsAttachInfo.mallocStack().set(
                XR10.XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO,
                NULL,
                stackPointers(vanillaCompatActionSet.address())
        );
        OPEN_XR.check(XR10.xrAttachSessionActionSets(OPEN_XR.xrSession, attach_info));

        flatGuiManager.init();
    }

    public static void resetView() {
        MineXRaftClient.xrOffset = new Vector3f(0, 0, 0).sub(MineXRaftClient.viewSpacePose.getPos()).mul(1, 0, 1);
    }
}
