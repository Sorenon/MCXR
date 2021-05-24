package net.sorenon.minexraft.client.rendering;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.sorenon.minexraft.client.mixin.accessor.FramebufferAcc;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class provides a system to change the main framebuffer that the game renders to while providing the illusion that
 * it is always the same framebuffer. This limits compat issues while still providing a simple interface for changing the size
 * and targets of the main framebuffer.
 */
public class MainRenderTarget extends Framebuffer {

    public static final Logger LOGGER = Logger.getLogger("MCXR");

    //The framebuffer used for rendering to the window
    public final Framebuffer windowFramebuffer;

    //The framebuffer that is affected by draw calls
    private Framebuffer currentFramebuffer;

    //The current dimensions of all the vanilla framebuffers
    public int gameWidth;
    public int gameHeight;

    public MainRenderTarget(int width, int height, boolean useDepth, boolean getError) {
        super(width, height, useDepth, getError);
        windowFramebuffer = new Framebuffer(width, height, useDepth, getError);
        setFramebuffer(windowFramebuffer);
        gameWidth = width;
        gameHeight = height;
    }

    //Used to set the current framebuffer without resizing the dimensions of the other framebuffers
    //This is meant for the defaultFramebuffer and any framebuffers used in rendering gui
    public void setFramebuffer(Framebuffer framebuffer) {
        this.currentFramebuffer = framebuffer;

        this.textureWidth = framebuffer.textureWidth;
        this.textureHeight = framebuffer.textureHeight;
        this.viewportWidth = framebuffer.viewportWidth;
        this.viewportHeight = framebuffer.viewportHeight;
        this.fbo = framebuffer.fbo;
        this.clearColor[0] = framebuffer.clearColor[0];
        this.clearColor[1] = framebuffer.clearColor[1];
        this.clearColor[2] = framebuffer.clearColor[2];
        this.clearColor[3] = framebuffer.clearColor[3];
        this.texFilter = framebuffer.texFilter;

        FramebufferAcc thiz = ((FramebufferAcc) this);
        thiz.colorAttachment(framebuffer.getColorAttachment());
        thiz.depthAttachment(framebuffer.getDepthAttachment());
    }

    public void setXrFramebuffer(XrFramebuffer framebuffer) {
        setFramebuffer(framebuffer);
        if (framebuffer.textureWidth != gameWidth ||
            framebuffer.textureHeight != gameHeight) {
            MinecraftClient.getInstance().gameRenderer.onResized(framebuffer.textureWidth, framebuffer.textureHeight);
            LOGGER.log(Level.INFO, "Resizing GameRenderer");
        }
    }

    public void resetFramebuffer() {
        setFramebuffer(windowFramebuffer);
    }

    public Framebuffer getFramebuffer() {
        return currentFramebuffer;
    }

    public Framebuffer getWindowFramebuffer() {
        return windowFramebuffer;
    }

    public boolean isCustomFramebuffer() {
        return currentFramebuffer != windowFramebuffer;
    }

    public void resize(int width, int height, boolean getError) {
        if (windowFramebuffer != null) {
            windowFramebuffer.resize(width, height, getError);
        }
    }

    public void delete() {
        windowFramebuffer.delete();
    }

    public void copyDepthFrom(Framebuffer framebuffer) {
        currentFramebuffer.copyDepthFrom(framebuffer);
    }

    public void initFbo(int width, int height, boolean getError) {
        currentFramebuffer.initFbo(width, height, getError);
    }

    public void setTexFilter(int i) {
        currentFramebuffer.setTexFilter(i);
    }

    public void checkFramebufferStatus() {
        currentFramebuffer.checkFramebufferStatus();
    }

    public void beginRead() {
        currentFramebuffer.beginRead();
    }

    public void endRead() {
        currentFramebuffer.endRead();
    }

    public void beginWrite(boolean setViewport) {
        currentFramebuffer.beginWrite(setViewport);
    }

    public void endWrite() {
        currentFramebuffer.endWrite();
    }

    public void setClearColor(float r, float g, float b, float a) {
        currentFramebuffer.setClearColor(r, g, b, a);
    }

    public void draw(int width, int height) {
        currentFramebuffer.draw(width, height);
    }

    public void draw(int width, int height, boolean bl) {
        currentFramebuffer.draw(width, height, bl);
    }

    public void clear(boolean getError) {
        currentFramebuffer.clear(getError);
    }

    public int getColorAttachment() {
        return currentFramebuffer.getColorAttachment();
    }

    public int getDepthAttachment() {
        return currentFramebuffer.getDepthAttachment();
    }
}
