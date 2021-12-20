package net.sorenon.mcxr.play.rendering;

import com.mojang.math.Quaternion;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.sorenon.mcxr.core.JOMLUtil;
import net.sorenon.mcxr.play.MCXRPlayClient;
import net.sorenon.mcxr.core.Pose;
import net.sorenon.mcxr.play.mixin.accessor.CameraAcc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rather than altering Camera with mixins we instead replace the Camera instance entirely with an XrCamera instance.
 * My reasoning behind this is that this mod should have complete control of the camera. If another mod
 * wants to do alter the camera then chances are what they're doing wont translate well to an XR scenario.
 * But if they do need to alter the camera in an XR scenario then they can always mixin this class.
 */
public class XrCamera extends Camera {

    private final Quaternionf rawRotation = new Quaternionf();

    /**
     * Called just before each render tick, sets the camera to the center of the headset for updating the sound engine and updates the pitch yaw of the player
     */
    public void updateXR(BlockGetter area, Entity focusedEntity, Pose viewSpacePose) {
        CameraAcc thiz = (CameraAcc) this;
        thiz.ready(focusedEntity != null);
        thiz.area(area);
        thiz.focusedEntity(focusedEntity);
        thiz.thirdPerson(false);

        setPose(viewSpacePose);

        if (focusedEntity != null && Minecraft.getInstance().player == focusedEntity) {
            Entity player = Minecraft.getInstance().player;

            float yaw = MCXRPlayClient.viewSpacePoses.getScaledPhysicalPose().getMCYaw();
            float pitch = MCXRPlayClient.viewSpacePoses.getScaledPhysicalPose().getMCPitch();
            float dYaw = yaw - player.getYRot();
            float dPitch = pitch - player.getXRot();
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO += dYaw;
            player.xRotO = Mth.clamp(player.xRotO + dPitch, -90, 90);
            if (player.getVehicle() != null) {
                player.getVehicle().onPassengerTurned(player);
            }
        }
    }

    public void setPose(Pose pose) {
        rawRotation.set(pose.getOrientation());

        CameraAcc thiz = ((CameraAcc) this);

        thiz.pitch(pose.getMCPitch());
        thiz.yaw(pose.getMCYaw());
        this.rotation().set(0.0F, 0.0F, 0.0F, 1.0F);
        this.rotation().mul(com.mojang.math.Vector3f.YP.rotationDegrees(-pose.getMCYaw()));
        this.rotation().mul(com.mojang.math.Vector3f.XP.rotationDegrees(pose.getMCPitch()));

        Vector3f look = rawRotation.transform(new Vector3f(0, 0, -1));
        Vector3f up = rawRotation.transform(new Vector3f(0, 1, 0));
        Vector3f right = rawRotation.transform(new Vector3f(1, 0, 0));
        this.getLookVector().set(look.x, look.y, look.z);
        this.getUpVector().set(up.x, up.y, up.z);
        thiz.diagonalPlane().set(right.x, right.y, right.z);

        this.setPosition(JOMLUtil.convert(pose.getPos()));
    }

    @Override
    public void setup(BlockGetter area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta) {
        CameraAcc thiz = (CameraAcc) this;
        thiz.ready(true);
        thiz.area(area);
        thiz.focusedEntity(focusedEntity);
        thiz.thirdPerson(false);
    }

    public Quaternion getRawRotationInverted() {
        Quaternionf inv = rawRotation.invert(new Quaternionf());

        return new Quaternion(inv.x, inv.y, inv.z, inv.w);
    }
}
