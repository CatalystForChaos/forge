package forge.accessibility;

/**
 * One element offered to a screen reader: what it is and where it is on screen.
 * Position and size are fractions of the screen, with the origin at the top left,
 * so that no platform has to know the units Forge draws in.
 */
public final class AccessibleNode {
    private static final float TOLERANCE = 0.002f;

    private final Object key;
    private final float left, top, width, height;
    private final AccessibleInfo info;
    private final boolean enabled;

    AccessibleNode(Object key0, float left0, float top0, float width0, float height0, AccessibleInfo info0, boolean enabled0) {
        key = key0;
        left = left0;
        top = top0;
        width = width0;
        height = height0;
        info = info0;
        enabled = enabled0;
    }

    /** @return an object that stays the same for as long as this element exists, compared by identity */
    public Object getKey() {
        return key;
    }

    public float getLeft() {
        return left;
    }

    public float getTop() {
        return top;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public AccessibleInfo getInfo() {
        return info;
    }

    public boolean isEnabled() {
        return enabled;
    }

    boolean sameAs(AccessibleNode other) {
        return key == other.key && enabled == other.enabled
                && Math.abs(left - other.left) < TOLERANCE && Math.abs(top - other.top) < TOLERANCE
                && Math.abs(width - other.width) < TOLERANCE && Math.abs(height - other.height) < TOLERANCE
                && info.sameAs(other.info);
    }
}
