package net.sorenon.mcxr.play.openxr;

import net.minecraft.client.MinecraftClient;
import net.sorenon.mcxr.play.MCXRPlayClient;
import net.sorenon.mcxr.play.input.ControllerPoses;
import net.sorenon.mcxr.play.input.XrInput;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackMalloc;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * This class is where most of the OpenXR stuff happens
 */
public class OpenXR {

    public OpenXRInstance instance;
    public OpenXRSession session;

    public XrException createException;

    public static Logger LOGGER = LogManager.getLogger("MCXR");

    public static final XrPosef identityPose = XrPosef.malloc().set(
            XrQuaternionf.malloc().set(0, 0, 0, 1),
            XrVector3f.calloc()
    );

    /**
     * Creates an array of XrStructs with their types pre set to @param type
     */
    static ByteBuffer mallocAndFillBufferStack(int capacity, int sizeof, int type) {
        ByteBuffer b = stackMalloc(capacity * sizeof);

        for (int i = 0; i < capacity; i++) {
            b.position(i * sizeof);
            b.putInt(type);
        }
        b.rewind();
        return b;
    }

    static ByteBuffer mallocAndFillBufferHeap(int capacity, int sizeof, int type) {
        ByteBuffer b = memAlloc(capacity * sizeof);

        for (int i = 0; i < capacity; i++) {
            b.position(i * sizeof);
            b.putInt(type);
        }
        b.rewind();
        return b;
    }

    public void tryInitialize() {
        if (!MCXRPlayClient.resourcesInitialized) {
            return;
        }

        if (instance != null) instance.close();
        instance = null;
        MCXRPlayClient.OPEN_XR.session = null;

        try {
            instance = createOpenXRInstance();
            session = instance.createSession(XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, instance.getSystem(XR10.XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY));
            session.createXRReferenceSpaces();
            session.createSwapchains();
            XrInput.trySetSession(session);
            MCXRPlayClient.RENDERER.setSession(session);
        } catch (Exception e) {
            LOGGER.error("Exception caught while initializing OpenXR", e);
            if (e instanceof XrException xrException) {
                createException = xrException;
            }
            if (instance != null) instance.close();
            instance = null;
            MCXRPlayClient.OPEN_XR.session = null;
        }
    }

    public OpenXRInstance createOpenXRInstance() throws XrException {
        try (MemoryStack stack = stackPush()) {
            IntBuffer numExtensions = stack.mallocInt(1);
            check(XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, numExtensions, null));

            XrExtensionProperties.Buffer properties = new XrExtensionProperties.Buffer(
                    mallocAndFillBufferStack(numExtensions.get(0), XrExtensionProperties.SIZEOF, XR10.XR_TYPE_EXTENSION_PROPERTIES)
            );

            check(XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, numExtensions, properties));

            LOGGER.debug(String.format("OpenXR loaded with %d extensions", numExtensions.get(0)));
            LOGGER.debug("~~~~~~~~~~~~~~~~~~");
            PointerBuffer extensions = stack.mallocPointer(numExtensions.get(0));
            boolean missingOpenGL = true;
            while (properties.hasRemaining()) {
                XrExtensionProperties prop = properties.get();
                String extensionName = prop.extensionNameString();
                LOGGER.debug(extensionName);
                extensions.put(memASCII(extensionName));
                if (extensionName.equals(KHROpenglEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME)) {
                    missingOpenGL = false;
                }
            }
            extensions.rewind();
            LOGGER.debug("~~~~~~~~~~~~~~~~~~");

            if (missingOpenGL) {
                throw new XrException(0, "OpenXR runtime does not provide required extension: " + KHROpenglEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME);
            }

            XrApplicationInfo applicationInfo = XrApplicationInfo.mallocStack();
            applicationInfo.apiVersion(XR10.XR_CURRENT_API_VERSION);
            applicationInfo.applicationName(stack.UTF8("[MCXR] Minecraft VR"));
            applicationInfo.applicationVersion(1);
            applicationInfo.engineName(stack.UTF8("Minecraft Java Edition"));
            applicationInfo.engineVersion(117);

            XrInstanceCreateInfo createInfo = XrInstanceCreateInfo.mallocStack();
            createInfo.set(
                    XR10.XR_TYPE_INSTANCE_CREATE_INFO,
                    0,
                    0,
                    applicationInfo,
                    null,
                    extensions
            );

            PointerBuffer instancePtr = stack.mallocPointer(1);

            int xrResult = XR10.xrCreateInstance(createInfo, instancePtr);
            if (xrResult == XR10.XR_ERROR_RUNTIME_FAILURE) {
                throw new XrException(xrResult, "Failed to create xrInstance, are you sure your headset is plugged in?");
            } else if (xrResult == XR10.XR_ERROR_INSTANCE_LOST) {
                throw new XrException(xrResult, "Failed to create xrInstance due to runtime updating");
            } else if (xrResult < 0) {
                throw new XrException(xrResult, "XR method returned " + xrResult);
            }

            return new OpenXRInstance(new XrInstance(instancePtr.get(0), createInfo));
        }
    }

    /**
     * @return true if the game should just render normally
     */
    public boolean loop() {
        if (session == null) {
//            //TODO have a button and message in-game for this
//            if (!tryInitialize()) {
//                return true;
//            }
            return true;
        }

        if (instance.pollEvents()) {
            MinecraftClient.getInstance().scheduleStop();
            return false;
        }

        if (session.running) {
            session.pollActions();
            MCXRPlayClient.RENDERER.renderFrame();
            return !MCXRPlayClient.RENDERER.isXrMode();
        }
        return true;
    }

    private void check(int result) throws XrRuntimeException {
        if (result >= 0) return;

        if (instance != null) {
            ByteBuffer str = stackMalloc(XR10.XR_MAX_RESULT_STRING_SIZE);
            if (XR10.xrResultToString(instance.handle, result, str) >= 0) {
                throw new XrRuntimeException(memUTF8Safe(str));
            }
        }
        throw new XrRuntimeException("XR method returned " + result);
    }

    public void setPosesFromSpace(XrSpace handSpace, long time, ControllerPoses result) {
        try (MemoryStack ignored = stackPush()) {
            XrSpaceLocation space_location = XrSpaceLocation.callocStack().type(XR10.XR_TYPE_SPACE_LOCATION);
            int res = XR10.xrLocateSpace(handSpace, session.xrAppSpace, time, space_location);
            if (res == XR10.XR_SUCCESS &&
                    (space_location.locationFlags() & XR10.XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
                    (space_location.locationFlags() & XR10.XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0) {

                result.updatePhysicalPose(space_location.pose(), MCXRPlayClient.yawTurn);
            }
        }
    }
}