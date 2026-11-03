package dev.superchunk.diag.projectexplosive;

/** Launch-time, opt-in configuration for the outer terrain sweep of the direct nuclear bomb. */
public final class ProjectExplosiveNuclearTerrainScale {
    public static final String ENABLE_PROPERTY = "superchunk.projectExplosive.nuclearTerrainScale.enabled";
    public static final String MULTIPLIER_PROPERTY = "superchunk.projectExplosive.nuclearTerrainScale.multiplier";
    public static final String NUCLEAR_BOMB_TYPE = "projectexplosive:nuclear_bomb";

    private static final Configuration CURRENT = configuration(
            System.getProperty(ENABLE_PROPERTY), System.getProperty(MULTIPLIER_PROPERTY));

    private ProjectExplosiveNuclearTerrainScale() {
    }

    public static boolean enabled() {
        return CURRENT.enabled();
    }

    public static double multiplier() {
        return CURRENT.multiplier();
    }

    public static double scaleIfSelected(String entityTypeId, double originalRadius) {
        return scaleIfSelected(CURRENT, entityTypeId, originalRadius);
    }

    public static double normalizeTargetForSave(String entityTypeId, double originalRadius, double currentRadius) {
        return normalizeTargetForSave(CURRENT, entityTypeId, originalRadius, currentRadius);
    }

    static double normalizeTargetForSave(Configuration configuration, String entityTypeId,
                                         double originalRadius, double currentRadius) {
        return configuration.enabled() && NUCLEAR_BOMB_TYPE.equals(entityTypeId)
                && Double.isFinite(originalRadius) && originalRadius >= 0.0 ? originalRadius : currentRadius;
    }

    public static double restoreTargetOnLoad(boolean hasUnscaledMarker, String entityTypeId,
                                             double savedOriginalRadius, double vanillaLoadedRadius) {
        return restoreTargetOnLoad(CURRENT, hasUnscaledMarker, entityTypeId, savedOriginalRadius, vanillaLoadedRadius);
    }

    static double restoreTargetOnLoad(Configuration configuration, boolean hasUnscaledMarker, String entityTypeId,
                                      double savedOriginalRadius, double vanillaLoadedRadius) {
        if (!hasUnscaledMarker || !Double.isFinite(savedOriginalRadius) || savedOriginalRadius < 0.0) {
            return vanillaLoadedRadius;
        }
        return scaleIfSelected(configuration, entityTypeId, savedOriginalRadius);
    }

    static double scaleIfSelected(Configuration configuration, String entityTypeId, double originalRadius) {
        if (!configuration.enabled() || !NUCLEAR_BOMB_TYPE.equals(entityTypeId)
                || !Double.isFinite(originalRadius) || originalRadius < 0.0) {
            return originalRadius;
        }
        return originalRadius * configuration.multiplier();
    }

    static Configuration configuration(String enabledValue, String multiplierValue) {
        return new Configuration(parseEnabled(enabledValue), parseMultiplier(multiplierValue));
    }

    static boolean parseEnabled(String value) {
        return "true".equalsIgnoreCase(value);
    }

    static double parseMultiplier(String value) {
        if (value == null || value.isBlank()) return 0.5;
        try {
            double parsed = Double.parseDouble(value);
            // A bad launch option must preserve the original destructive distance.
            return Double.isFinite(parsed) && parsed > 0.0 && parsed <= 1.0 ? parsed : 1.0;
        } catch (NumberFormatException ignored) {
            return 1.0;
        }
    }

    record Configuration(boolean enabled, double multiplier) {
    }
}
