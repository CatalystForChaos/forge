package forge.ios;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.foundation.NSArray;
import org.robovm.apple.foundation.NSNotification;
import org.robovm.apple.foundation.NSNotificationCenter;
import org.robovm.apple.foundation.NSString;
import org.robovm.apple.uikit.UIAccessibilityContainer;
import org.robovm.apple.uikit.UIAccessibilityContainerType;
import org.robovm.apple.uikit.UIAccessibilityElement;
import org.robovm.apple.uikit.UIAccessibilityGlobals;
import org.robovm.apple.uikit.UIAccessibilityNotification;
import org.robovm.apple.uikit.UIAccessibilityTraits;
import org.robovm.apple.uikit.UIColor;
import org.robovm.apple.uikit.UIView;
import org.robovm.apple.uikit.UIViewAutoresizing;
import org.robovm.apple.uikit.UIViewController;
import org.robovm.objc.Selector;
import org.robovm.objc.annotation.Method;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.iosrobovm.IOSApplication;

import forge.accessibility.Accessibility;
import forge.accessibility.AccessibilityBridge;
import forge.accessibility.AccessibleInfo;
import forge.accessibility.AccessibleNode;

/**
 * Offers Forge's interface to VoiceOver.
 *
 * <p>libGDX draws everything into a single OpenGL view, in which UIKit has nothing to show to VoiceOver.
 * This bridge lays a transparent view over it that takes no touches and acts as an accessibility
 * container. For every element Forge reports, the container holds a UIAccessibilityElement with the
 * same position, text and traits. Activating one is passed back to Forge, which taps itself there.
 */
final class VoiceOverBridge implements AccessibilityBridge {
    private final IOSApplication app;
    private ContainerView container;
    private final Map<Object, Element> elementsByKey = new IdentityHashMap<Object, Element>();
    private List<UIAccessibilityElement> elements = new ArrayList<UIAccessibilityElement>();
    private boolean wasRunning;

    VoiceOverBridge(IOSApplication app0) {
        app = app0;
    }

    @Override
    public boolean isScreenReaderActive() {
        if (!install()) {
            return false;
        }
        boolean running = UIAccessibilityGlobals.isVoiceOverRunning();
        if (running != wasRunning) {
            wasRunning = running;
            log("VoiceOver " + (running ? "is running" : "is not running"));
        }
        return running;
    }

    @Override
    public void update(List<AccessibleNode> nodes, boolean screenChanged) {
        if (!install()) {
            return;
        }
        CGRect bounds = container.getBounds();
        double viewWidth = bounds.getWidth();
        double viewHeight = bounds.getHeight();

        List<UIAccessibilityElement> newElements = new ArrayList<UIAccessibilityElement>(nodes.size());
        Map<Object, Element> newByKey = new IdentityHashMap<Object, Element>();
        for (AccessibleNode node : nodes) {
            //keep the element of a node that is still there, so that VoiceOver's focus stays on it
            Element element = elementsByKey.get(node.getKey());
            if (element == null) {
                element = new Element(container, this, node.getKey());
            }
            element.apply(node, viewWidth, viewHeight);
            newElements.add(element);
            newByKey.put(node.getKey(), element);
        }

        boolean structureChanged = !sameElements(newElements, elements);
        elementsByKey.clear();
        elementsByKey.putAll(newByKey);
        elements = newElements;

        if (structureChanged || screenChanged) {
            container.setElements(new NSArray<UIAccessibilityElement>(newElements));
            log((screenChanged ? "screen changed, " : "layout changed, ") + newElements.size() + " elements, view "
                    + Math.round(viewWidth) + "x" + Math.round(viewHeight) + ", " + container.describeQueries());
            UIAccessibilityGlobals.postNotification(screenChanged
                    ? UIAccessibilityNotification.ScreenChangedNotification
                    : UIAccessibilityNotification.LayoutChangedNotification, null);
        }
    }

    @Override
    public void announce(String message) {
        UIAccessibilityGlobals.postNotification(UIAccessibilityNotification.AnnouncementNotification, new NSString(message));
    }

    //the view controller only exists once libGDX has started, which is after this bridge is created
    private boolean install() {
        if (container != null) {
            return true;
        }
        UIViewController viewController = app.getUIViewController();
        if (viewController == null) {
            return false;
        }
        UIView root = viewController.getView();
        if (root == null) {
            return false;
        }
        ContainerView view = new ContainerView(root.getBounds());
        view.setUserInteractionEnabled(false); //touches must keep going to the libGDX view underneath
        view.setOpaque(false);
        view.setBackgroundColor(UIColor.clear());
        view.setAutoresizingMask(UIViewAutoresizing.FlexibleWidth.set(UIViewAutoresizing.FlexibleHeight));
        root.addSubview(view);
        //Forge stops rendering while nothing moves, so it has to be woken up to notice VoiceOver being switched on
        NSNotificationCenter.getDefaultCenter().addObserver(view, Selector.register("forgeVoiceOverStatusChanged:"),
                UIAccessibilityGlobals.VoiceOverStatusDidChangeNotification(), null);
        container = view;
        CGRect bounds = view.getBounds();
        log("installed, view " + Math.round(bounds.getWidth()) + "x" + Math.round(bounds.getHeight()));
        return true;
    }

    private boolean onActivate(Element element) {
        log("activate: " + element.label);
        return Accessibility.activate(element.key);
    }

    private void onFocus(Element element) {
        log("focus: " + element.label);
    }

    private static void log(String message) {
        System.out.println("[voiceover] " + message);
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean sameElements(List<UIAccessibilityElement> a, List<UIAccessibilityElement> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    /** One element as VoiceOver sees it. */
    static final class Element extends UIAccessibilityElement {
        private final VoiceOverBridge bridge;
        private final Object key;
        private String label, value;
        private long traitBits = -1;
        private double left = -1, top = -1, width = -1, height = -1;

        Element(UIAccessibilityContainer container, VoiceOverBridge bridge0, Object key0) {
            super(container);
            bridge = bridge0;
            key = key0;
        }

        //only touch what changed, VoiceOver may be reading the element at this moment
        void apply(AccessibleNode node, double viewWidth, double viewHeight) {
            AccessibleInfo info = node.getInfo();
            if (!same(info.getLabel(), label)) {
                label = info.getLabel();
                setAccessibilityLabel(label);
            }
            if (!same(info.getValue(), value)) {
                value = info.getValue();
                setAccessibilityValue(value);
            }

            UIAccessibilityTraits traits = info.getRole() == AccessibleInfo.Role.BUTTON
                    ? UIAccessibilityTraits.Button : UIAccessibilityTraits.StaticText;
            if (info.isSelected()) {
                traits = traits.set(UIAccessibilityTraits.Selected);
            }
            if (!node.isEnabled()) {
                traits = traits.set(UIAccessibilityTraits.NotEnabled);
            }
            if (info.isLive()) {
                traits = traits.set(UIAccessibilityTraits.UpdatesFrequently);
            }
            if (traits.value() != traitBits) {
                traitBits = traits.value();
                setAccessibilityTraits(traits);
            }

            double newLeft = node.getLeft() * viewWidth;
            double newTop = node.getTop() * viewHeight;
            double newWidth = node.getWidth() * viewWidth;
            double newHeight = node.getHeight() * viewHeight;
            if (newLeft != left || newTop != top || newWidth != width || newHeight != height) {
                left = newLeft;
                top = newTop;
                width = newWidth;
                height = newHeight;
                setAccessibilityFrameInContainerSpace(new CGRect(left, top, width, height));
            }
        }

        @Method(selector = "accessibilityActivate")
        public boolean accessibilityActivate() {
            try {
                return bridge.onActivate(this);
            } catch (Throwable t) {
                t.printStackTrace();
                return false;
            }
        }

        @Method(selector = "accessibilityElementDidBecomeFocused")
        public void accessibilityElementDidBecomeFocused() {
            try {
                bridge.onFocus(this);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    /** The transparent view that hands the elements to VoiceOver. */
    static final class ContainerView extends UIView implements UIAccessibilityContainer {
        private NSArray<UIAccessibilityElement> elements = new NSArray<UIAccessibilityElement>();
        private int arrayQueries, countQueries, indexQueries;

        ContainerView(CGRect frame) {
            super(frame);
        }

        void setElements(NSArray<UIAccessibilityElement> elements0) {
            elements = elements0;
        }

        //how often VoiceOver has asked so far; all zero means it never looked at this view
        String describeQueries() {
            return "queries so far: array " + arrayQueries + ", count " + countQueries + ", index " + indexQueries;
        }

        @Override
        public NSArray<UIAccessibilityElement> getAccessibilityElements() {
            arrayQueries++;
            return elements;
        }

        @Override
        public void setAccessibilityElements(NSArray<UIAccessibilityElement> v) {
            //the elements only ever come from Forge
        }

        @Override
        public UIAccessibilityContainerType getAccessibilityContainerType() {
            return UIAccessibilityContainerType.None;
        }

        @Override
        public void setAccessibilityContainerType(UIAccessibilityContainerType v) {
        }

        @Override
        public NSArray<?> getAutomationElements() {
            return null;
        }

        @Override
        public void setAutomationElements(NSArray<?> v) {
        }

        @Override
        public long getAccessibilityElementCount() {
            countQueries++;
            return elements.size();
        }

        @Override
        public UIAccessibilityElement getAccessibilityElement(long index) {
            indexQueries++;
            if (index < 0 || index >= elements.size()) {
                return null;
            }
            return elements.get((int) index);
        }

        @Override
        public long indexOfAccessibilityElement(UIAccessibilityElement element) {
            int index = elements.indexOf(element);
            return index < 0 ? Long.MAX_VALUE : index; //NSNotFound
        }

        //VoiceOver's two-finger scrub, its gesture for "back" or "close"
        @Method(selector = "accessibilityPerformEscape")
        public boolean accessibilityPerformEscape() {
            log("escape gesture");
            try {
                Accessibility.performEscape();
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return true;
        }

        @Method(selector = "forgeVoiceOverStatusChanged:")
        public void voiceOverStatusChanged(NSNotification notification) {
            Gdx.graphics.requestRendering();
        }
    }
}
