package forge.accessibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.math.Rectangle;

import forge.Forge;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FOverlay;

/**
 * Tells a screen reader what is on screen.
 *
 * <p>The classic interface is drawn by hand, so the platform cannot see any of its controls. While a
 * screen reader is running, this class notes every display object as it is drawn, together with what
 * the object says about itself in {@link FDisplayObject#getAccessibleInfo()}, and hands the result to
 * the platform's {@link AccessibilityBridge}. Collecting at draw time means the screen reader is offered
 * exactly what a sighted player sees: nothing hidden, nothing clipped away, in the order it is painted.
 *
 * <p>Without a bridge, or while no screen reader is running, none of this does any work.
 */
public final class Accessibility {
    private static final long ACTIVE_CHECK_INTERVAL = 1000;
    private static final long MIN_COLLECT_INTERVAL = 150;
    private static final long MIN_LIVE_INTERVAL = 3000;
    private static final int MAX_LOGGED_NODES = 60;

    private static AccessibilityBridge bridge;
    private static volatile boolean active;
    private static boolean debugLogging;
    private static long lastActiveCheck;

    private static boolean collecting;
    private static long lastCollectTime;
    private static int disabledDepth;
    private static Object frameRoot;
    private static final List<AccessibleNode> frameNodes = new ArrayList<>();

    private static volatile List<AccessibleNode> shownNodes = Collections.emptyList();
    private static Object shownRoot;

    private static String lastLiveLabel;
    private static long lastLiveTime;

    private Accessibility() {
    }

    /** Called once by the platform launcher. Platforms without a screen reader connection never call this. */
    public static void setBridge(AccessibilityBridge bridge0) {
        bridge = bridge0;
        log("bridge set: " + (bridge0 == null ? "none" : bridge0.getClass().getName()));
    }

    /** Writes what is offered to the screen reader to the log. Meant for test builds. */
    public static void setDebugLogging(boolean debugLogging0) {
        debugLogging = debugLogging0;
    }

    /** @return whether a screen reader is running; cheap, and safe to call from any thread */
    public static boolean isActive() {
        return active;
    }

    /** @return whether the frame being drawn right now is recorded for the screen reader */
    public static boolean isCollecting() {
        return collecting;
    }

    /**
     * Starts recording a frame of the classic interface.
     *
     * @param root the screen being drawn; a different root than last time means the screen changed
     */
    public static void beginFrame(Object root) {
        collecting = false;
        if (bridge == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastActiveCheck >= ACTIVE_CHECK_INTERVAL) {
            lastActiveCheck = now;
            boolean nowActive;
            try {
                nowActive = bridge.isScreenReaderActive();
            } catch (Throwable t) {
                fail(t);
                return;
            }
            if (nowActive != active) {
                log("screen reader " + (nowActive ? "running" : "not running"));
                active = nowActive;
                if (!nowActive) {
                    show(Collections.<AccessibleNode>emptyList(), null, true);
                }
            }
        }
        if (!active) {
            return;
        }
        if (now - lastCollectTime < MIN_COLLECT_INTERVAL) {
            //this frame is skipped, so make sure another one follows even if nothing else asks for it
            if (!Gdx.graphics.isContinuousRendering()) {
                Gdx.graphics.requestRendering();
            }
            return;
        }
        lastCollectTime = now;
        frameNodes.clear();
        disabledDepth = 0;
        frameRoot = root;
        collecting = true;
    }

    /** An overlay that swallows input hides everything drawn before it from the screen reader as well. */
    public static void beginOverlay(FOverlay overlay) {
        if (collecting && overlay.preventInputBehindOverlay()) {
            frameNodes.clear();
            frameRoot = overlay;
        }
    }

    /**
     * Notes a display object that is about to be drawn.
     *
     * @param visibleRect the part of the object that is on screen, in the units of touch coordinates
     */
    public static void beginObject(FDisplayObject obj, Rectangle visibleRect) {
        if (!obj.isEnabled()) {
            disabledDepth++;
        }
        float screenWidth = Forge.getScreenWidth();
        float screenHeight = Forge.getScreenHeight();
        if (screenWidth <= 0 || screenHeight <= 0 || visibleRect.width <= 0 || visibleRect.height <= 0) {
            return;
        }
        AccessibleInfo info;
        try {
            info = obj.getAccessibleInfo();
        } catch (RuntimeException e) {
            e.printStackTrace();
            return;
        }
        if (info == null) {
            return;
        }
        //an object drawn twice in one frame counts once, where it was drawn last
        for (int i = frameNodes.size() - 1; i >= 0; i--) {
            if (frameNodes.get(i).getKey() == obj) {
                frameNodes.remove(i);
                break;
            }
        }
        frameNodes.add(new AccessibleNode(obj, visibleRect.x / screenWidth, visibleRect.y / screenHeight,
                visibleRect.width / screenWidth, visibleRect.height / screenHeight, info, disabledDepth == 0));
    }

    public static void endObject(FDisplayObject obj) {
        if (disabledDepth > 0 && !obj.isEnabled()) {
            disabledDepth--;
        }
    }

    /** Finishes recording a frame and passes it on if anything changed since the last one. */
    public static void endFrame() {
        if (!collecting) {
            return;
        }
        collecting = false;
        announceLive();
        //a screen that had nothing to activate and now does is as good as a new one, e.g. loading has finished
        boolean screenChanged = frameRoot != shownRoot || (hasButton(frameNodes) && !hasButton(shownNodes));
        if (!screenChanged && sameNodes(frameNodes, shownNodes)) {
            return;
        }
        show(new ArrayList<>(frameNodes), frameRoot, screenChanged);
    }

    /** Drops a frame that could not be drawn to the end. */
    public static void abortFrame() {
        collecting = false;
    }

    /** Called for frames that show something other than the classic interface, which has no elements to offer yet. */
    public static void clear() {
        collecting = false;
        if (active && !shownNodes.isEmpty()) {
            show(Collections.<AccessibleNode>emptyList(), null, true);
        }
    }

    /** Speaks a message without moving the screen reader's focus. Can be called from any thread. */
    public static void announce(final String message) {
        if (!active || message == null || message.isEmpty()) {
            return;
        }
        Gdx.app.postRunnable(() -> announceNow(message));
    }

    /**
     * Performs what a tap on the element would do. Called by the bridge on the render thread.
     *
     * @return false if the element is gone or disabled
     */
    public static boolean activate(Object key) {
        for (AccessibleNode node : shownNodes) {
            if (node.getKey() == key) {
                if (!node.isEnabled()) {
                    log("activate ignored, disabled: " + node.getInfo().getLabel());
                    return false;
                }
                final String label = node.getInfo().getLabel();
                final float x = (node.getLeft() + node.getWidth() / 2) * Forge.getScreenWidth();
                final float y = (node.getTop() + node.getHeight() / 2) * Forge.getScreenHeight();
                //run on the next frame, like any other input, rather than in the middle of a platform callback
                Gdx.app.postRunnable(() -> {
                    boolean handled = Forge.simulateTap(x, y);
                    log("activate: " + label + " at " + Math.round(x) + "," + Math.round(y) + (handled ? " handled" : " NOT handled"));
                });
                return true;
            }
        }
        log("activate ignored, element is gone");
        return false;
    }

    /**
     * Does what the Escape key does: closes the dialog or menu on top, otherwise goes back one screen.
     * Called by the bridge for the screen reader's "back" gesture.
     */
    public static void performEscape() {
        Gdx.app.postRunnable(() -> {
            InputProcessor inputProcessor = Forge.getInputProcessor();
            if (inputProcessor != null) {
                inputProcessor.keyDown(Keys.ESCAPE);
                inputProcessor.keyUp(Keys.ESCAPE);
                log("escape");
            }
        });
    }

    private static void show(List<AccessibleNode> nodes, Object root, boolean screenChanged) {
        boolean structureChanged = screenChanged || !sameKeys(nodes, shownNodes);
        shownNodes = nodes;
        shownRoot = root;
        if (structureChanged) {
            logNodes(nodes, root, screenChanged);
        }
        try {
            bridge.update(Collections.unmodifiableList(nodes), screenChanged);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void announceLive() {
        for (AccessibleNode node : frameNodes) {
            if (!node.getInfo().isLive()) {
                continue;
            }
            String label = node.getInfo().getLabel();
            long now = System.currentTimeMillis();
            if (!label.equals(lastLiveLabel) && now - lastLiveTime >= MIN_LIVE_INTERVAL) {
                lastLiveLabel = label;
                lastLiveTime = now;
                announceNow(label);
            }
            return;
        }
    }

    private static void announceNow(String message) {
        if (bridge == null) {
            return;
        }
        log("announce: " + message);
        try {
            bridge.announce(message);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static boolean sameNodes(List<AccessibleNode> a, List<AccessibleNode> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).sameAs(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasButton(List<AccessibleNode> nodes) {
        for (AccessibleNode node : nodes) {
            if (node.getInfo().getRole() == AccessibleInfo.Role.BUTTON) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameKeys(List<AccessibleNode> a, List<AccessibleNode> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getKey() != b.get(i).getKey()) {
                return false;
            }
        }
        return true;
    }

    //a failing bridge must never take rendering down with it
    private static void fail(Throwable t) {
        System.err.println("[accessibility] bridge failed and is switched off:");
        t.printStackTrace();
        bridge = null;
        active = false;
        collecting = false;
    }

    private static void log(String message) {
        System.out.println("[accessibility] " + message);
    }

    private static void logNodes(List<AccessibleNode> nodes, Object root, boolean screenChanged) {
        if (!debugLogging) {
            return;
        }
        log((screenChanged ? "screen changed" : "layout changed") + ", root "
                + (root == null ? "none" : root.getClass().getName()) + ", " + nodes.size() + " elements");
        int count = Math.min(nodes.size(), MAX_LOGGED_NODES);
        for (int i = 0; i < count; i++) {
            AccessibleNode node = nodes.get(i);
            AccessibleInfo info = node.getInfo();
            log("  " + i + " " + info.getRole() + (node.isEnabled() ? "" : " disabled") + (info.isSelected() ? " selected" : "")
                    + " [" + percent(node.getLeft()) + "," + percent(node.getTop()) + " " + percent(node.getWidth()) + "x" + percent(node.getHeight()) + "] "
                    + info.getLabel() + (info.getValue() == null ? "" : " = " + info.getValue()));
        }
        if (count < nodes.size()) {
            log("  ... and " + (nodes.size() - count) + " more");
        }
    }

    private static int percent(float fraction) {
        return Math.round(fraction * 100);
    }
}
