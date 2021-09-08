package net.sorenon.mcxr.play.openxr;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Matrix4f;
import net.sorenon.mcxr.core.MCXRCore;
import net.sorenon.mcxr.play.FlatGuiManager;
import net.sorenon.mcxr.play.MCXRPlayClient;
import net.sorenon.mcxr.play.accessor.MinecraftClientExt;
import net.sorenon.mcxr.play.input.XrInput;
import net.sorenon.mcxr.play.rendering.MainRenderTarget;
import net.sorenon.mcxr.play.rendering.RenderPass;
import net.sorenon.mcxr.play.rendering.XrCamera;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Struct;

import java.nio.IntBuffer;
import java.util.concurrent.locks.ReentrantLock;

import static org.lwjgl.system.MemoryStack.stackCallocInt;
import static org.lwjgl.system.MemoryStack.stackPush;

public class XrRenderer {
    private static final OpenXR OPEN_XR = MCXRPlayClient.OPEN_XR;
    private static final Logger LOGGER = LogManager.getLogger();
    private MinecraftClient client;
    private MinecraftClientExt clientExt;
    private MainRenderTarget mainRenderTarget;


    private OpenXRInstance instance;
    private OpenXRSession session;

    public RenderPass renderPass = RenderPass.VANILLA;

    public Shader blitShader;
    public Shader guiBlitShader;

    public void setSession(OpenXRSession session) {
        this.session = session;
        this.instance = session.instance;

        if (this.client == null) {
            client = MinecraftClient.getInstance();
            clientExt = ((MinecraftClientExt) MinecraftClient.getInstance());
            mainRenderTarget = (MainRenderTarget) client.getFramebuffer();
        }
    }

    public boolean isXrMode() {
        return MinecraftClient.getInstance().world != null && session != null;
    }

    public void renderFrame() {
        try (MemoryStack stack = stackPush()) {
            var frameState = XrFrameState.callocStack().type(XR10.XR_TYPE_FRAME_STATE);

            if (isXrMode()) {
                GLFW.glfwSwapBuffers(MinecraftClient.getInstance().getWindow().getHandle());
            }
            instance.check(XR10.xrWaitFrame(
                    session.handle,
                    XrFrameWaitInfo.callocStack().type(XR10.XR_TYPE_FRAME_WAIT_INFO),
                    frameState
            ), "xrWaitFrame");

            instance.check(XR10.xrBeginFrame(
                    session.handle,
                    XrFrameBeginInfo.callocStack().type(XR10.XR_TYPE_FRAME_BEGIN_INFO)
            ), "xrBeginFrame");

            PointerBuffer layers = stack.callocPointer(1);

            if (frameState.shouldRender()) {
                if (this.isXrMode()) {
                    var layer = renderLayerOpenXR(frameState.predictedDisplayTime());
                    if (layer != null) {
                        layers.put(layer.address());
                    }
                } else {
                    var layer = renderLayerBlankOpenXR(frameState.predictedDisplayTime());
                    layers.put(layer.address());
                }
            }
            layers.flip();

            instance.check(XR10.xrEndFrame(
                    session.handle,
                    XrFrameEndInfo.callocStack()
                            .type(XR10.XR_TYPE_FRAME_END_INFO)
                            .displayTime(frameState.predictedDisplayTime())
                            .environmentBlendMode(XR10.XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                            .layers(layers)
            ), "xrEndFrame");
        }
    }

    private Struct renderLayerOpenXR(long predictedDisplayTime) {
//        try (MemoryStack stack = stackPush()) {

        XrViewState viewState = XrViewState.callocStack().type(XR10.XR_TYPE_VIEW_STATE);
        IntBuffer intBuf = stackCallocInt(1);

        XrViewLocateInfo viewLocateInfo = XrViewLocateInfo.callocStack();
        viewLocateInfo.set(XR10.XR_TYPE_VIEW_LOCATE_INFO,
                0,
                session.viewConfigurationType,
                predictedDisplayTime,
                session.xrAppSpace
        );

        instance.check(XR10.xrLocateViews(session.handle, viewLocateInfo, viewState, intBuf, session.views), "xrLocateViews");

        if ((viewState.viewStateFlags() & XR10.XR_VIEW_STATE_POSITION_VALID_BIT) == 0 ||
                (viewState.viewStateFlags() & XR10.XR_VIEW_STATE_ORIENTATION_VALID_BIT) == 0) {
            LOGGER.error("Invalid headset position, try restarting your device");
            return null;  // There is no valid tracking poses for the views.
        }
        int viewCountOutput = intBuf.get(0);

        var projectionLayerViews = XrCompositionLayerProjectionView.callocStack(viewCountOutput);

        float scalePreTick = MCXRPlayClient.getCameraScale();

        // Update hand position based on the predicted time of when the frame will be rendered! This
        // should result in a more accurate location, and reduce perceived lag.
        if (session.state == XR10.XR_SESSION_STATE_FOCUSED) {
            for (int i = 0; i < 2; i++) {
                if (!XrInput.handsActionSet.grip.isActive[i]) {
                    continue;
                }
                session.setPosesFromSpace(XrInput.handsActionSet.grip.spaces[i], predictedDisplayTime, XrInput.handsActionSet.gripPoses[i], scalePreTick);
                session.setPosesFromSpace(XrInput.handsActionSet.aim.spaces[i], predictedDisplayTime, XrInput.handsActionSet.aimPoses[i], scalePreTick);
            }
        }

        session.setPosesFromSpace(session.xrViewSpace, predictedDisplayTime, MCXRPlayClient.viewSpacePoses, scalePreTick);
        Entity cameraEntity = this.client.getCameraEntity() == null ? this.client.player : this.client.getCameraEntity();
        if (cameraEntity != null) { //TODO seriously need to tidy up poses
            if (MCXRCore.getCoreConfig().roomscaleMovement()) {
                MCXRPlayClient.xrOrigin.set(cameraEntity.getX() - MCXRPlayClient.roomscalePlayerOffset.x,
                        cameraEntity.getY(),
                        cameraEntity.getZ() - MCXRPlayClient.roomscalePlayerOffset.z);
            } else {
                MCXRPlayClient.xrOrigin.set(cameraEntity.getX(),
                        cameraEntity.getY(),
                        cameraEntity.getZ());
            }

            MCXRPlayClient.viewSpacePoses.updateGamePose(MCXRPlayClient.xrOrigin);
            for (var poses : XrInput.handsActionSet.gripPoses) {
                poses.updateGamePose(MCXRPlayClient.xrOrigin);
            }
            for (var poses : XrInput.handsActionSet.aimPoses) {
                poses.updateGamePose(MCXRPlayClient.xrOrigin);
            }
        }
        XrCamera camera = (XrCamera) MinecraftClient.getInstance().gameRenderer.getCamera();
        camera.updateXR(this.client.world, cameraEntity, MCXRPlayClient.viewSpacePoses.getGamePose());

        FlatGuiManager FGM = MCXRPlayClient.INSTANCE.flatGuiManager;

        if (FGM.needsReset) {
            FGM.resetTransform();
        }

        long frameStartTime = Util.getMeasuringTimeNano();
        if (MinecraftClient.getInstance().player != null && MCXRCore.getCoreConfig().xrAllowed()) {
            MCXRCore.INSTANCE.setPlayerHeadPose(
                    MinecraftClient.getInstance().player,
                    MCXRPlayClient.viewSpacePoses.getScaledPhysicalPose()
            );
        }
        clientExt.preRender(true);
        if (camera.getFocusedEntity() != null) {
            float tickDelta = client.getTickDelta();
            Entity camEntity = camera.getFocusedEntity();

            if (client.isPaused()) {
                tickDelta = 0.0f;
            }

            if (MCXRCore.getCoreConfig().roomscaleMovement()) {
                MCXRPlayClient.xrOrigin.set(MathHelper.lerp(tickDelta, camEntity.prevX, camEntity.getX()) - MCXRPlayClient.roomscalePlayerOffset.x,
                        MathHelper.lerp(tickDelta, camEntity.prevY, camEntity.getY()),
                        MathHelper.lerp(tickDelta, camEntity.prevZ, camEntity.getZ()) - MCXRPlayClient.roomscalePlayerOffset.z);
            } else {
                MCXRPlayClient.xrOrigin.set(MathHelper.lerp(tickDelta, camEntity.prevX, camEntity.getX()),
                        MathHelper.lerp(tickDelta, camEntity.prevY, camEntity.getY()),
                        MathHelper.lerp(tickDelta, camEntity.prevZ, camEntity.getZ()));
            }

            MCXRPlayClient.viewSpacePoses.updateGamePose(MCXRPlayClient.xrOrigin);
            for (var poses : XrInput.handsActionSet.gripPoses) {
                poses.updateGamePose(MCXRPlayClient.xrOrigin);
            }
            for (var poses : XrInput.handsActionSet.aimPoses) {
                poses.updateGamePose(MCXRPlayClient.xrOrigin);
            }
        }

        client.getWindow().setPhase("Render");
        client.getProfiler().push("sound");
        client.getSoundManager().updateListenerPosition(client.gameRenderer.getCamera());
        client.getProfiler().pop();

        //Render GUI
        mainRenderTarget.setFramebuffer(FGM.backFramebuffer);
        XrInput.postTick(predictedDisplayTime);
        FGM.backFramebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
        clientExt.doRender(true, frameStartTime, RenderPass.GUI);

        FGM.frontFramebuffer.beginWrite(true);
        this.guiBlitShader.addSampler("DiffuseSampler", FGM.backFramebuffer.getColorAttachment());
        this.guiBlitShader.addSampler("DepthSampler", FGM.backFramebuffer.getDepthAttachment());
        this.blit(FGM.frontFramebuffer, guiBlitShader);
        FGM.frontFramebuffer.endWrite();

        mainRenderTarget.resetFramebuffer();

        RenderPass.World worldRenderPass = RenderPass.World.create();

        // Render view to the appropriate part of the swapchain image.
        for (int viewIndex = 0; viewIndex < viewCountOutput; viewIndex++) {
            // Each view has a separate swapchain which is acquired, rendered to, and released.
            OpenXRSwapchain viewSwapchain = session.swapchains[viewIndex];

            instance.check(XR10.xrAcquireSwapchainImage(
                    viewSwapchain.handle,
                    XrSwapchainImageAcquireInfo.callocStack().type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO),
                    intBuf
            ), "xrAcquireSwapchainImage");

            int swapchainImageIndex = intBuf.get(0);

            instance.check(XR10.xrWaitSwapchainImage(viewSwapchain.handle,
                    XrSwapchainImageWaitInfo.callocStack()
                            .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO)
                            .timeout(XR10.XR_INFINITE_DURATION)
            ), "xrWaitSwapchainImage");

            var subImage = projectionLayerViews.get(viewIndex)
                    .type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
                    .pose(session.views.get(viewIndex).pose())
                    .fov(session.views.get(viewIndex).fov())
                    .subImage();
            subImage.swapchain(viewSwapchain.handle);
            subImage.imageRect().offset().set(0, 0);
            subImage.imageRect().extent().set(viewSwapchain.width, viewSwapchain.height);

            {
                XrSwapchainImageOpenGLKHR xrSwapchainImageOpenGLKHR = viewSwapchain.images.get(swapchainImageIndex);
                viewSwapchain.innerFramebuffer.setColorAttachment(xrSwapchainImageOpenGLKHR.image());
                viewSwapchain.innerFramebuffer.endWrite();
                mainRenderTarget.setXrFramebuffer(viewSwapchain.framebuffer);
                worldRenderPass.fov = session.views.get(viewIndex).fov();
                worldRenderPass.eyePoses.updatePhysicalPose(session.views.get(viewIndex).pose(), MCXRPlayClient.yawTurn, scalePreTick);
                worldRenderPass.eyePoses.updateGamePose(MCXRPlayClient.xrOrigin);
                worldRenderPass.viewIndex = viewIndex;
                camera.setPose(worldRenderPass.eyePoses.getGamePose());
                clientExt.doRender(true, frameStartTime, worldRenderPass);
            }

            viewSwapchain.innerFramebuffer.beginWrite(true);
            this.blitShader.addSampler("DiffuseSampler", viewSwapchain.framebuffer.getColorAttachment());
            GlUniform inverseScreenSize = this.blitShader.getUniform("InverseScreenSize");
            if (inverseScreenSize != null) {
                inverseScreenSize.set(1f / viewSwapchain.innerFramebuffer.textureWidth, 1f / viewSwapchain.innerFramebuffer.textureHeight);
            }
            viewSwapchain.framebuffer.setTexFilter(GlConst.GL_LINEAR);
            this.blit(viewSwapchain.innerFramebuffer, blitShader);
            viewSwapchain.innerFramebuffer.endWrite();

            if (viewIndex == viewCountOutput - 1) {
                blitToBackbuffer(viewSwapchain.framebuffer);
            }

            instance.check(XR10.xrReleaseSwapchainImage(
                    viewSwapchain.handle,
                    XrSwapchainImageReleaseInfo.callocStack()
                            .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO)
            ), "xrReleaseSwapchainImage");
        }
        mainRenderTarget.resetFramebuffer();
        camera.setPose(MCXRPlayClient.viewSpacePoses.getGamePose());
        clientExt.postRender();

        return XrCompositionLayerProjection.callocStack()
                .type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION)
                .space(session.xrAppSpace)
                .views(projectionLayerViews);
//        }
    }

    private Struct renderLayerBlankOpenXR(long predictedDisplayTime) {
//        try (MemoryStack stack = stackPush()) {
        IntBuffer intBuf = stackCallocInt(1);

        instance.check(XR10.xrLocateViews(
                session.handle,
                XrViewLocateInfo.callocStack().set(XR10.XR_TYPE_VIEW_LOCATE_INFO,
                        0,
                        session.viewConfigurationType,
                        predictedDisplayTime,
                        session.xrAppSpace
                ),
                XrViewState.callocStack().type(XR10.XR_TYPE_VIEW_STATE),
                intBuf,
                session.views
        ), "xrLocateViews");

        int viewCountOutput = intBuf.get(0);

        var projectionLayerViews = XrCompositionLayerProjectionView.callocStack(viewCountOutput);

        OpenXRSwapchain viewSwapchain = session.swapchains[0];
        instance.check(XR10.xrAcquireSwapchainImage(
                viewSwapchain.handle,
                XrSwapchainImageAcquireInfo.callocStack().type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO),
                intBuf
        ), "xrAcquireSwapchainImage");
        int swapchainImageIndex = intBuf.get(0);

        instance.check(XR10.xrWaitSwapchainImage(
                viewSwapchain.handle,
                XrSwapchainImageWaitInfo.callocStack()
                        .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO)
                        .timeout(XR10.XR_INFINITE_DURATION)
        ), "xrWaitSwapchainImage");

        for (int viewIndex = 0; viewIndex < viewCountOutput; viewIndex++) {
            XrCompositionLayerProjectionView projectionLayerView = projectionLayerViews.get(viewIndex);
            projectionLayerView.type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW);
            projectionLayerView.pose(session.views.get(viewIndex).pose());
            projectionLayerView.fov(session.views.get(viewIndex).fov());
            projectionLayerView.subImage().swapchain(viewSwapchain.handle);
            projectionLayerView.subImage().imageRect().offset().set(0, 0);
            projectionLayerView.subImage().imageRect().extent().set(viewSwapchain.width, viewSwapchain.height);
        }

        XrSwapchainImageOpenGLKHR xrSwapchainImageOpenGLKHR = viewSwapchain.images.get(swapchainImageIndex);
        viewSwapchain.innerFramebuffer.setColorAttachment(xrSwapchainImageOpenGLKHR.image());
        viewSwapchain.innerFramebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);

        instance.check(XR10.xrReleaseSwapchainImage(
                viewSwapchain.handle,
                XrSwapchainImageReleaseInfo.callocStack().type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO)
        ), "xrReleaseSwapchainImage");

        XrCompositionLayerProjection layer = XrCompositionLayerProjection.callocStack().type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION);
        layer.space(session.xrAppSpace);
        layer.views(projectionLayerViews);
        return layer;
//        }
    }

    public void blit(Framebuffer framebuffer, Shader shader) {
        MatrixStack matrixStack = RenderSystem.getModelViewStack();
        matrixStack.push();
        matrixStack.loadIdentity();
        RenderSystem.applyModelViewMatrix();

        int width = framebuffer.textureWidth;
        int height = framebuffer.textureHeight;

        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._viewport(0, 0, width, height);
        GlStateManager._disableBlend();

        Matrix4f matrix4f = Matrix4f.projectionMatrix((float) width, (float) -height, 1000.0F, 3000.0F);
        RenderSystem.setProjectionMatrix(matrix4f);
        if (shader.modelViewMat != null) {
            shader.modelViewMat.set(Matrix4f.translate(0.0F, 0.0F, -2000.0F));
        }

        if (shader.projectionMat != null) {
            shader.projectionMat.set(matrix4f);
        }

        shader.bind();
        float u = (float) framebuffer.viewportWidth / (float) framebuffer.textureWidth;
        float v = (float) framebuffer.viewportHeight / (float) framebuffer.textureHeight;
        Tessellator tessellator = RenderSystem.renderThreadTesselator();
        BufferBuilder bufferBuilder = tessellator.getBuffer();
        bufferBuilder.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR);
        bufferBuilder.vertex(0.0, height * 2, 0.0).texture(0.0F, 1 - v * 2).color(255, 255, 255, 255).next();
        bufferBuilder.vertex(width * 2, 0.0, 0.0).texture(u * 2, 1).color(255, 255, 255, 255).next();
        bufferBuilder.vertex(0.0, 0.0, 0.0).texture(0.0F, 1).color(255, 255, 255, 255).next();
        bufferBuilder.end();
        BufferRenderer.postDraw(bufferBuilder);
        shader.unbind();
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);

        matrixStack.pop();
    }

    public void blitToBackbuffer(Framebuffer framebuffer) {
        //TODO render alyx-like gui over this
        Shader shader = MinecraftClient.getInstance().gameRenderer.blitScreenShader;
        shader.addSampler("DiffuseSampler", framebuffer.getColorAttachment());

        MatrixStack matrixStack = RenderSystem.getModelViewStack();
        matrixStack.push();
        matrixStack.loadIdentity();
        RenderSystem.applyModelViewMatrix();

        int width = mainRenderTarget.windowFramebuffer.textureWidth;
        int height = mainRenderTarget.windowFramebuffer.textureHeight;

        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._viewport(0, 0, width, height);
        GlStateManager._disableBlend();

        Matrix4f matrix4f = Matrix4f.projectionMatrix((float) width, (float) (-height), 1000.0F, 3000.0F);
        RenderSystem.setProjectionMatrix(matrix4f);
        if (shader.modelViewMat != null) {
            shader.modelViewMat.set(Matrix4f.translate(0, 0, -2000.0F));
        }

        if (shader.projectionMat != null) {
            shader.projectionMat.set(matrix4f);
        }

        shader.bind();
        float widthNormalized = (float) framebuffer.textureWidth / (float) width;
        float heightNormalized = (float) framebuffer.textureHeight / (float) height;
        float v = (widthNormalized / heightNormalized) / 2;

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder bufferBuilder = tessellator.getBuffer();
        bufferBuilder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        bufferBuilder.vertex(0.0, height, 0.0).texture(0.0F, v).color(255, 255, 255, 255).next();
        bufferBuilder.vertex(width, height, 0.0).texture(1, v).color(255, 255, 255, 255).next();
        bufferBuilder.vertex(width, 0.0, 0.0).texture(1, 1 - v).color(255, 255, 255, 255).next();
        bufferBuilder.vertex(0.0, 0.0, 0.0).texture(0.0F, 1 - v).color(255, 255, 255, 255).next();
        bufferBuilder.end();
        BufferRenderer.postDraw(bufferBuilder);
        shader.unbind();
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);

        matrixStack.pop();
    }
}