package forge;

import forge.accessibility.Accessibility;
import forge.gui.error.BugReporter;
import forge.toolbox.FContainer;
import forge.toolbox.FOverlay;

public class Classic {
    private static Classic instance;
    private Classic() {
    }

    public static Classic getInstance() {
        return instance == null ? instance = new Classic() : instance;
    }

    void render(FContainer screen) {
        if (screen == null) // shouldn't be null
            return;
        try {
            if (screen == Forge.getCurrentScreen() || screen == Forge.splashScreen) {
                Accessibility.beginFrame(screen);
            } //else keep what the screen reader has until the transition is over
            Forge.getGraphics().setProjectionMatrix(Forge.camera.combined);
            Forge.getGraphics().begin(Forge.getScreenWidth(), Forge.getScreenHeight());
            screen.screenPos.setSize(Forge.getScreenWidth(), Forge.getScreenHeight());
            if (screen.getRotate180()) {
                Forge.getGraphics().startRotateTransform(Forge.getScreenWidth() / 2f, Forge.getScreenHeight() / 2f, 180);
            }
            screen.draw(Forge.getGraphics());
            if (screen.getRotate180()) {
                Forge.getGraphics().endTransform();
            }
            for (FOverlay overlay : FOverlay.getOverlays()) {
                if (overlay.isVisibleOnScreen(Forge.getCurrentScreen())) {
                    overlay.screenPos.setSize(Forge.getScreenWidth(), Forge.getScreenHeight());
                    overlay.setSize(Forge.getScreenWidth(), Forge.getScreenHeight()); //update overlay sizes as they're rendered
                    if (overlay.getRotate180()) {
                        Forge.getGraphics().startRotateTransform(Forge.getScreenWidth() / 2f, Forge.getScreenHeight() / 2f, 180);
                    }
                    Accessibility.beginOverlay(overlay);
                    overlay.draw(Forge.getGraphics());
                    if (overlay.getRotate180()) {
                        Forge.getGraphics().endTransform();
                    }
                }
            }
            //update here
            if (Forge.needsUpdate) {
                if (Forge.getAssets().manager().update(16))
                    Forge.needsUpdate = false;
            }
            //sample batch
            FrameRate.getInstance().sampleClassic(Forge.showFPS);
            Forge.getGraphics().end();
            Accessibility.endFrame();
        } catch (Exception e) {
            Accessibility.abortFrame();
            //check if sentry is enabled, if not it will call the gui interface but here we end the graphics so we only send it via sentry.
            if (BugReporter.isSentryEnabled())
                BugReporter.reportException(e);
            else
                e.printStackTrace();
            Forge.getGraphics().end();
        }
    }
}
