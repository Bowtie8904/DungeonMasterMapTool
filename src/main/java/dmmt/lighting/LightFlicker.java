package dmmt.lighting;

import dmmt.model.DmProject;

/** Deterministic, cheap flicker noise per light so DM and player views flicker in sync. */
public final class LightFlicker {
    private LightFlicker() {
    }

    /** Returns flicker amount in [0, 1]; 0 = steady full light, 1 = deepest dip. */
    public static double amount(DmProject.LightSource light, long nowMillis) {
        DmProject.Flicker flicker = light.getFlicker();
        if (flicker == null || !flicker.isEnabled() || flicker.getStrength() <= 0) {
            return 0;
        }
        double phase = ((light.getId() == null ? 0 : light.getId().hashCode()) & 0xFFFF) / 1000.0;
        double t = nowMillis / 1000.0 * Math.max(0.05, flicker.getSpeed());
        double n = 0.5 * Math.sin(t * 6.3 + phase)
                + 0.3 * Math.sin(t * 13.7 + phase * 1.7)
                + 0.2 * Math.sin(t * 23.1 + phase * 2.3);
        return Math.min(1.0, flicker.getStrength() * (0.5 + 0.5 * n));
    }
}
