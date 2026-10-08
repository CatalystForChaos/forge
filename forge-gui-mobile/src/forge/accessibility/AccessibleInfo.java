package forge.accessibility;

/**
 * What a display object tells assistive technology, such as a screen reader, about itself.
 * A display object that has nothing to say returns no AccessibleInfo at all.
 */
public final class AccessibleInfo {
    public enum Role {
        TEXT,
        BUTTON
    }

    private final Role role;
    private final String label;
    private String value;
    private boolean selected;
    private boolean live;

    private AccessibleInfo(Role role0, String label0) {
        role = role0;
        label = label0;
    }

    /** @return info for static text, or null if the text is empty */
    public static AccessibleInfo text(String label) {
        return create(Role.TEXT, label);
    }

    /** @return info for something that reacts to a tap, or null if the label is empty */
    public static AccessibleInfo button(String label) {
        return create(Role.BUTTON, label);
    }

    private static AccessibleInfo create(Role role, String label) {
        String cleaned = clean(label);
        if (cleaned == null) {
            return null;
        }
        return new AccessibleInfo(role, cleaned);
    }

    /** Collapses line breaks and runs of blanks, which mean nothing when spoken. */
    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Sets a value that is spoken after the label and may change without the element changing, e.g. a percentage. */
    public AccessibleInfo value(String value0) {
        value = clean(value0);
        return this;
    }

    public AccessibleInfo selected(boolean selected0) {
        selected = selected0;
        return this;
    }

    /** Marks the label as worth speaking on its own whenever it changes, e.g. the stage of a loading screen. */
    public AccessibleInfo live() {
        live = true;
        return this;
    }

    public Role getRole() {
        return role;
    }

    public String getLabel() {
        return label;
    }

    public String getValue() {
        return value;
    }

    public boolean isSelected() {
        return selected;
    }

    public boolean isLive() {
        return live;
    }

    boolean sameAs(AccessibleInfo other) {
        return role == other.role && selected == other.selected && live == other.live
                && label.equals(other.label)
                && (value == null ? other.value == null : value.equals(other.value));
    }
}
