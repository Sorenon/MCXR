package net.sorenon.mcxr.play.rendering;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
import com.mojang.math.Quaternion;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.sorenon.fart.FartUtil;
import net.sorenon.fart.RenderStateShards;
import net.sorenon.fart.RenderTypeBuilder;
import net.sorenon.mcxr.core.JOMLUtil;
import net.sorenon.mcxr.core.MCXRCore;
import net.sorenon.mcxr.core.Pose;
import net.sorenon.mcxr.play.FlatGuiManager;
import net.sorenon.mcxr.play.MCXRPlayClient;
import net.sorenon.mcxr.play.input.XrInput;
import net.sorenon.mcxr.play.openxr.XrRenderer;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.function.Function;
import java.util.function.Supplier;

import static net.sorenon.mcxr.core.JOMLUtil.convert;

//TODO third person renderer
public class VrFirstPersonRenderer {

    private static final XrRenderer XR_RENDERER = MCXRPlayClient.RENDERER;

    private final FlatGuiManager FGM;

    private final ModelPart[] slimArmModel = new ModelPart[2];
    private final ModelPart[] armModel = new ModelPart[2];

    public VrFirstPersonRenderer(FlatGuiManager flatGuiManager) {
        this.FGM = flatGuiManager;
        for (int slim = 0; slim < 2; slim++) {
            ModelPart[] arr = slim == 0 ? armModel : slimArmModel;
            for (int hand = 0; hand < 2; hand++) {
                CubeListBuilder armBuilder = CubeListBuilder.create();
                CubeListBuilder sleeveBuilder = CubeListBuilder.create();

                if (hand == 0) {
                    armBuilder.texOffs(32, 48);
                    sleeveBuilder.texOffs(48, 48);
                } else {
                    armBuilder.texOffs(40, 16);
                    sleeveBuilder.texOffs(40, 32);
                }

                if (slim == 0) {
                    armBuilder.addBox(0, 0, 0, 4, 12, 4);
                    sleeveBuilder.addBox(0, 0, 0, 4, 12, 4, new CubeDeformation(0.25F));
                } else {
                    armBuilder.addBox(0.5f, 0, 0, 3, 12, 4);
                    sleeveBuilder.addBox(0.5f, 0, 0, 3, 12, 4, new CubeDeformation(0.25F));
                }

                MeshDefinition modelData = new MeshDefinition();
                modelData.getRoot().addOrReplaceChild("arm", armBuilder, PartPose.ZERO);
                modelData.getRoot().addOrReplaceChild("sleeve", sleeveBuilder, PartPose.ZERO);

                arr[hand] = LayerDefinition.create(modelData, 64, 64).bakeRoot();
            }
        }
    }

    /**
     * This function contains a log of depth hackery so each draw call has to be done in a specific order
     */
    public void renderFirstPerson(WorldRenderContext context) {
        Camera camera = context.camera();
        Entity camEntity = camera.getEntity();
        MultiBufferSource.BufferSource consumers = (MultiBufferSource.BufferSource) context.consumers();
        PoseStack matrices = context.matrixStack();
        ClientLevel world = context.world();
        assert consumers != null;

        int light = getLight(camera, world);

        //Render gui
        if (FGM.position != null) {
            matrices.pushPose();
            Vec3 pos = FGM.position.subtract(convert(((RenderPass.World) XR_RENDERER.renderPass).eyePoses.getUnscaledPhysicalPose().getPos()));
            matrices.translate(pos.x, pos.y, pos.z);
            matrices.mulPose(new Quaternion((float) FGM.orientation.x, (float) FGM.orientation.y, (float) FGM.orientation.z, (float) FGM.orientation.w));
            renderGuiQuad(matrices.last(), consumers);
            matrices.popPose();
            consumers.endLastBatch();
        }

        if (camEntity != null) {
            renderShadow(context, camEntity);

            //Render vanilla crosshair ray if controller raytracing is disabled
            if (!FGM.isScreenOpen() && !MCXRCore.getCoreConfig().controllerRaytracing()) {
                Vec3 camPos = context.camera().getPosition();
                matrices.pushPose();

                double x = Mth.lerp(context.tickDelta(), camEntity.xOld, camEntity.getX());
                double y = Mth.lerp(context.tickDelta(), camEntity.yOld, camEntity.getY()) + camEntity.getEyeHeight();
                double z = Mth.lerp(context.tickDelta(), camEntity.zOld, camEntity.getZ());
                matrices.translate(x - camPos.x, y - camPos.y, z - camPos.z);

                matrices.mulPose(com.mojang.math.Vector3f.YP.rotationDegrees(-camEntity.getYRot() + 180.0F));
                matrices.mulPose(com.mojang.math.Vector3f.XP.rotationDegrees(90 - camEntity.getXRot()));

                Matrix4f model = matrices.last().pose();
                Matrix3f normal = matrices.last().normal();

                VertexConsumer consumer = consumers.getBuffer(LINE_CUSTOM_ALWAYS.apply(4.0));
                consumer.vertex(model, 0, 0, 0).color(0f, 0f, 0f, 1f).normal(normal, 0, -1, 0).endVertex();
                consumer.vertex(model, 0, -5, 0).color(0f, 0f, 0f, 1f).normal(normal, 0, -1, 0).endVertex();

                consumer = consumers.getBuffer(LINE_CUSTOM.apply(2.0));
                consumer.vertex(model, 0, 0, 0).color(1f, 0f, 0f, 1f).normal(normal, 0, -1, 0).endVertex();
                consumer.vertex(model, 0, -5, 0).color(0.7f, 0.7f, 0.7f, 1f).normal(normal, 0, -1, 0).endVertex();

                matrices.popPose();
            }
        }

        if (camEntity instanceof LocalPlayer player && FGM.isScreenOpen()) {
            renderHandsAndItems(player, light, matrices, consumers, context.tickDelta());
        }

        for (int hand = 0; hand < 2; hand++) {
            if (!XrInput.handsActionSet.grip.isActive[hand]) {
                continue;
            }

            //Draw the hand ray and debug lines
            matrices.pushPose(); //1

            Pose pose = XrInput.handsActionSet.gripPoses[hand].getGamePose();
            Vec3 gripPos = convert(pose.getPos());
            Vector3f eyePos = ((RenderPass.World) XR_RENDERER.renderPass).eyePoses.getGamePose().getPos();
            matrices.translate(gripPos.x - eyePos.x(), gripPos.y - eyePos.y(), gripPos.z - eyePos.z());

            float scale = MCXRPlayClient.getCameraScale();
            matrices.scale(scale, scale, scale);

            matrices.pushPose(); //2
            matrices.mulPose(
                    JOMLUtil.convert(
                            pose.getOrientation()
                                    .rotateX((float) Math.toRadians(MCXRPlayClient.handPitchAdjust), new Quaternionf())
                    )
            );

            if (hand == MCXRPlayClient.mainHand && (MCXRCore.getCoreConfig().controllerRaytracing() || FGM.isScreenOpen())) {
                Matrix4f model = matrices.last().pose();
                Matrix3f normal = matrices.last().normal();

                VertexConsumer consumer = consumers.getBuffer(LINE_CUSTOM_ALWAYS.apply(4.0));
                consumer.vertex(model, 0, 0, 0).color(0f, 0f, 0f, 1f).normal(normal, 0, -1, 0).endVertex();
                consumer.vertex(model, 0, -5, 0).color(0f, 0f, 0f, 1f).normal(normal, 0, -1, 0).endVertex();

                consumer = consumers.getBuffer(LINE_CUSTOM.apply(2.0));
                consumer.vertex(model, 0, 0, 0).color(1f, 0f, 0f, 1f).normal(normal, 0, -1, 0).endVertex();
                consumer.vertex(model, 0, -5, 0).color(0.7f, 0.7f, 0.7f, 1f).normal(normal, 0, -1, 0).endVertex();
            }

            boolean debug = Minecraft.getInstance().options.renderDebug;

            if (debug) {
                FartUtil.renderCrosshair(consumers, context.matrixStack(), 0.05f, false);
            }

            matrices.popPose(); //2

            if (debug) {
                matrices.mulPose(
                        JOMLUtil.convert(
                                pose.getOrientation()
                        )
                );
                FartUtil.renderCrosshair(consumers, context.matrixStack(), 0.1f, false);
            }

            matrices.popPose(); //1
        }

        consumers.endBatch();

        //Render HUD
        if (!FGM.isScreenOpen() && XrInput.handsActionSet.grip.isActive[0]) {
            matrices.pushPose();

            transformToHand(matrices, 0, context.tickDelta());

            matrices.mulPose(com.mojang.math.Vector3f.XP.rotationDegrees(-90.0F));
            matrices.mulPose(com.mojang.math.Vector3f.YP.rotationDegrees(180.0F));

            matrices.translate(-2 / 16f, -12 / 16f, 0);

            matrices.pushPose();
            matrices.translate(2 / 16f, 9 / 16f, -1 / 16f);
            matrices.mulPose(com.mojang.math.Vector3f.XP.rotationDegrees(-75f));
            renderGuiQuad(matrices.last(), consumers);
            consumers.endLastBatch();
            matrices.popPose();

            matrices.popPose();
        }
    }

    public static int getLight(Camera camera, Level world) {
        return LightTexture.pack(world.getBrightness(LightLayer.BLOCK, camera.getBlockPosition()), world.getBrightness(LightLayer.SKY, camera.getBlockPosition()));
    }

    public void transformToHand(PoseStack matrices, int hand, float tickDelta) {
        Pose pose = XrInput.handsActionSet.gripPoses[hand].getGamePose();
        Vec3 gripPos = convert(pose.getPos());
        Vector3f eyePos = ((RenderPass.World) XR_RENDERER.renderPass).eyePoses.getGamePose().getPos();

        //Transform to controller
        matrices.translate(gripPos.x - eyePos.x(), gripPos.y - eyePos.y(), gripPos.z - eyePos.z());
        matrices.mulPose(convert(pose.getOrientation()));

        //Apply adjustments
        matrices.mulPose(com.mojang.math.Vector3f.XP.rotationDegrees(-90.0F));
        matrices.scale(0.4f, 0.4f, 0.4f);

        float scale = MCXRPlayClient.getCameraScale(tickDelta);
        matrices.scale(scale, scale, scale);

        matrices.translate(0, 1 / 16f, -1.5f / 16f);
        matrices.mulPose(com.mojang.math.Vector3f.XP.rotationDegrees(MCXRPlayClient.handPitchAdjust));
    }

    public void renderShadow(WorldRenderContext context, Entity camEntity) {
        PoseStack matrices = context.matrixStack();
        Vec3 camPos = context.camera().getPosition();
        matrices.pushPose();
        double x = Mth.lerp(context.tickDelta(), camEntity.xOld, camEntity.getX());
        double y = Mth.lerp(context.tickDelta(), camEntity.yOld, camEntity.getY());
        double z = Mth.lerp(context.tickDelta(), camEntity.zOld, camEntity.getZ());
        matrices.translate(x - camPos.x, y - camPos.y, z - camPos.z);
        PoseStack.Pose entry = matrices.last();

        RenderType SHADOW_LAYER = RenderType.entityShadow(new ResourceLocation("textures/misc/shadow.png"));
        VertexConsumer vertexConsumer = context.consumers().getBuffer(SHADOW_LAYER);

        float alpha = Mth.clamp((float) Math.sqrt(camPos.distanceToSqr(x, y, z)) / 2f - 0.5f, 0.25f, 1);
        float radius = camEntity.getBbWidth() / 2;
        float y0 = 0.005f;

        vertexConsumer.vertex(entry.pose(), -radius, y0, -radius).color(1.0F, 1.0F, 1.0F, alpha).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(entry.normal(), 0.0F, 1.0F, 0.0F).endVertex();
        vertexConsumer.vertex(entry.pose(), -radius, y0, radius).color(1.0F, 1.0F, 1.0F, alpha).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(entry.normal(), 0.0F, 1.0F, 0.0F).endVertex();
        vertexConsumer.vertex(entry.pose(), radius, y0, radius).color(1.0F, 1.0F, 1.0F, alpha).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(entry.normal(), 0.0F, 1.0F, 0.0F).endVertex();
        vertexConsumer.vertex(entry.pose(), radius, y0, -radius).color(1.0F, 1.0F, 1.0F, alpha).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(entry.normal(), 0.0F, 1.0F, 0.0F).endVertex();

        matrices.popPose();
    }

    private void renderGuiQuad(PoseStack.Pose transform, MultiBufferSource consumers) {
        RenderTarget guiFramebuffer = FGM.frontFramebuffer;

        float x = FGM.size / 2;
        float y = FGM.size * guiFramebuffer.height / guiFramebuffer.width;

        VertexConsumer consumer;
        Matrix4f modelMatrix = transform.pose();
        Matrix3f normalMatrix = transform.normal();

//        consumer = consumers.getBuffer(GUI_SHADOW.apply(MCXRPlayClient.INSTANCE.flatGuiManager.texture));
//        consumer.vertex(modelMatrix, -x - 0.005f, y - 0.005f, 0).color(255, 255, 255, 255).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0).normal(normalMatrix, 0, 0, -1).endVertex();
//        consumer.vertex(modelMatrix, x - 0.005f, y - 0.005f, 0).color(255, 255, 255, 255).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0).normal(normalMatrix, 0, 0, -1).endVertex();
//        consumer.vertex(modelMatrix, x - 0.005f, 0 - 0.005f, 0).color(255, 255, 255, 255).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0).normal(normalMatrix, 0, 0, -1).endVertex();
//        consumer.vertex(modelMatrix, -x - 0.005f, 0 - 0.005f, 0).color(255, 255, 255, 255).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0).normal(normalMatrix, 0, 0, -1).endVertex();
        consumer = consumers.getBuffer(GUI_NO_DEPTH_TEST.apply(MCXRPlayClient.INSTANCE.flatGuiManager.texture));
        consumer.vertex(modelMatrix, -x, y, 0).color(255, 255, 255, 255).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normalMatrix, 0, 0, -1).endVertex();
        consumer.vertex(modelMatrix, x, y, 0).color(255, 255, 255, 255).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normalMatrix, 0, 0, -1).endVertex();
        consumer.vertex(modelMatrix, x, 0, 0).color(255, 255, 255, 255).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normalMatrix, 0, 0, -1).endVertex();
        consumer.vertex(modelMatrix, -x, 0, 0).color(255, 255, 255, 255).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normalMatrix, 0, 0, -1).endVertex();
        consumer = consumers.getBuffer(DEPTH_ONLY.apply(MCXRPlayClient.INSTANCE.flatGuiManager.texture));
        consumer.vertex(modelMatrix, -x, y, 0).color(255, 255, 255, 255).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normalMatrix, 0, 0, -1).endVertex();
        consumer.vertex(modelMatrix, x, y, 0).color(255, 255, 255, 255).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normalMatrix, 0, 0, -1).endVertex();
        consumer.vertex(modelMatrix, x, 0, 0).color(255, 255, 255, 255).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normalMatrix, 0, 0, -1).endVertex();
        consumer.vertex(modelMatrix, -x, 0, 0).color(255, 255, 255, 255).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(15728880).normal(normalMatrix, 0, 0, -1).endVertex();
    }

    public void renderHandsAndItems(LocalPlayer player, int light, PoseStack matrices, MultiBufferSource consumers, float deltaTick) {
        //Render held items
        for (int hand = 0; hand < 2; hand++) {
            if (!XrInput.handsActionSet.grip.isActive[hand]) {
                continue;
            }

            if (!FGM.isScreenOpen()) {
                ItemStack stack = hand == 0 ? player.getOffhandItem() : player.getMainHandItem();

                if (!stack.isEmpty()) {
                    boolean mainHand = hand == MCXRPlayClient.mainHand;
                    matrices.pushPose();
                    transformToHand(matrices, hand, deltaTick);

                    if (mainHand) {
                        float swing = -0.4f * Mth.sin((float) (Math.sqrt(player.getAttackAnim(deltaTick)) * Math.PI * 2));
                        matrices.mulPose(com.mojang.math.Vector3f.XP.rotation(swing));
                    }

                    Minecraft.getInstance().getItemInHandRenderer().renderItem(
                            player,
                            stack,
                            hand == 0 ? ItemTransforms.TransformType.THIRD_PERSON_LEFT_HAND : ItemTransforms.TransformType.THIRD_PERSON_RIGHT_HAND,
                            hand == 0,
                            matrices,
                            consumers,
                            light
                    );

                    matrices.popPose();
                }
            }

            //Draw hand
            matrices.pushPose();

            transformToHand(matrices, hand, deltaTick);

            matrices.mulPose(com.mojang.math.Vector3f.XP.rotationDegrees(-90.0F));
            matrices.mulPose(com.mojang.math.Vector3f.YP.rotationDegrees(180.0F));

            matrices.translate(-2 / 16f, -12 / 16f, 0);

            matrices.pushPose();
            ModelPart armModel;
            if (player.getModelName().equals("slim")) {
                armModel = this.slimArmModel[hand];
            } else {
                armModel = this.armModel[hand];
            }

            VertexConsumer consumer = consumers.getBuffer(RenderType.entityTranslucent(player.getSkinTextureLocation()));
            armModel.render(matrices, consumer, light, OverlayTexture.NO_OVERLAY);
            matrices.popPose();

            matrices.popPose();

            consumers.getBuffer(RenderType.LINES); //Hello I'm a hack ;)
        }
    }

    public static final Function<ResourceLocation, RenderType> DEPTH_ONLY = Util.memoize((texture) -> {
        RenderTypeBuilder renderTypeBuilder = new RenderTypeBuilder(MCXRPlayClient.id("depth_only"), DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, false);
        renderTypeBuilder.innerBuilder
                .setWriteMaskState(RenderStateShards.DEPTH_WRITE)
                .setShaderState(RenderStateShards.shader(GameRenderer::getRendertypeEntityCutoutShader))
                .setTextureState(RenderStateShards.texture(texture, false, false))
                .setTransparencyState(RenderStateShards.NO_TRANSPARENCY)
                .setLightmapState(RenderStateShards.LIGHTMAP)
                .setOverlayState(RenderStateShards.OVERLAY);
        return renderTypeBuilder.build(true);
    });

    public static final Function<ResourceLocation, RenderType> GUI_NO_DEPTH_TEST = Util.memoize((texture) -> {
        Supplier<ShaderInstance> shader = GameRenderer::getNewEntityShader;
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            shader = GameRenderer::getRendertypeEntityTranslucentShader;
        }

        RenderTypeBuilder renderTypeBuilder = new RenderTypeBuilder(MCXRPlayClient.id("gui_no_depth_test"), DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, false);
        renderTypeBuilder.innerBuilder.
                setShaderState(RenderStateShards.shader(shader))
                .setTextureState(RenderStateShards.texture(texture, false, false))
                .setTransparencyState(RenderStateShards.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShards.NO_CULL)
                .setLightmapState(RenderStateShards.LIGHTMAP)
                .setOverlayState(RenderStateShards.OVERLAY)
                .setDepthTestState(RenderStateShards.NO_DEPTH_TEST);
        return renderTypeBuilder.build(true);
    });

    public static final Function<ResourceLocation, RenderType> GUI_SHADOW = Util.memoize((texture) -> {
        RenderTypeBuilder renderTypeBuilder = new RenderTypeBuilder(MCXRPlayClient.id("gui_no_depth_test"), DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, false);
        renderTypeBuilder.innerBuilder.
                setShaderState(RenderStateShards.shader(GameRenderer::getRendertypeEntityTranslucentShader))
                .setTextureState(RenderStateShards.texture(texture, false, false))
                .setTransparencyState(RenderStateShards.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShards.NO_CULL)
                .setLightmapState(RenderStateShards.LIGHTMAP)
                .setOverlayState(RenderStateShards.OVERLAY)
                .setDepthTestState(RenderStateShards.depthTest("GL_GREATER", GL11.GL_GREATER));
        return renderTypeBuilder.build(true);
    });

    public static final Function<Double, RenderType> LINE_CUSTOM_ALWAYS = Util.memoize(aDouble -> {
        RenderTypeBuilder builder = new RenderTypeBuilder(MCXRPlayClient.id("line_always"), DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 16, false, false);
        builder.innerBuilder
                .setShaderState(RenderStateShards.shader(GameRenderer::getRendertypeLinesShader))
                .setLineState(RenderStateShards.lineWidth(aDouble))
                .setLayeringState(RenderStateShards.VIEW_OFFSET_Z_LAYERING)
                .setTransparencyState(RenderStateShards.TRANSLUCENT_TRANSPARENCY)
                .setWriteMaskState(RenderStateShards.COLOR_DEPTH_WRITE)
                .setCullState(RenderStateShards.NO_CULL)
                .setDepthTestState(RenderStateShards.NO_DEPTH_TEST);
        return builder.build(true);
    });

    public static final Function<Double, RenderType> LINE_CUSTOM = Util.memoize(aDouble -> {
        RenderTypeBuilder builder = new RenderTypeBuilder(MCXRPlayClient.id("line"), DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 16, false, false);
        builder.innerBuilder
                .setShaderState(RenderStateShards.shader(GameRenderer::getRendertypeLinesShader))
                .setLineState(RenderStateShards.lineWidth(aDouble))
                .setLayeringState(RenderStateShards.VIEW_OFFSET_Z_LAYERING)
                .setTransparencyState(RenderStateShards.TRANSLUCENT_TRANSPARENCY)
                .setWriteMaskState(RenderStateShards.COLOR_DEPTH_WRITE)
                .setCullState(RenderStateShards.NO_CULL);
        return builder.build(true);
    });
}
