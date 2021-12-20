package net.sorenon.mcxr.play.accessor;

import net.sorenon.mcxr.play.rendering.RenderPass;

public interface MinecraftClientExt {

    void preRender(boolean tick);

    void doRender(boolean tick, long frameStartTime, RenderPass renderPass);

    void postRender();
}
