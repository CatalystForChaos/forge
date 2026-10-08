package forge.accessibility;

import java.util.List;

/**
 * Connects Forge to the screen reader of one platform. All methods are called on the render thread.
 */
public interface AccessibilityBridge {
    /** @return whether a screen reader is running right now; asked about once per second */
    boolean isScreenReaderActive();

    /**
     * Replaces the elements offered to the screen reader.
     *
     * @param nodes all elements in reading order
     * @param screenChanged true if a different screen or dialog is showing than at the last call
     */
    void update(List<AccessibleNode> nodes, boolean screenChanged);

    /** Speaks a message without moving the screen reader's focus. */
    void announce(String message);
}
