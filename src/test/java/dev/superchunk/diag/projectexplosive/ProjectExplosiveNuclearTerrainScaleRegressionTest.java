package dev.superchunk.diag.projectexplosive;

/** JVM-only checks for the opt-in selector and safe multiplier parsing. */
public final class ProjectExplosiveNuclearTerrainScaleRegressionTest {
    private ProjectExplosiveNuclearTerrainScaleRegressionTest() {
    }

    public static void main(String[] args) {
        configurationDefaultsOffAndTargetsHalfScale();
        oneRestoresOriginalDistance();
        persistenceRestoresOriginalWhenFeatureIsTurnedOff();
        onlyTheDirectNuclearBombIsSelected();
        invalidOptionsPreserveOriginalDistance();
        System.out.println("ProjectExplosiveNuclearTerrainScaleRegressionTest passed");
    }

    private static void configurationDefaultsOffAndTargetsHalfScale() {
        ProjectExplosiveNuclearTerrainScale.Configuration defaults =
                ProjectExplosiveNuclearTerrainScale.configuration(null, null);
        check(!defaults.enabled(), "the integration defaults OFF");
        check(defaults.multiplier() == 0.5, "the configured target multiplier defaults to 0.5");
        ProjectExplosiveNuclearTerrainScale.Configuration enabled =
                ProjectExplosiveNuclearTerrainScale.configuration("true", "0.5");
        check(ProjectExplosiveNuclearTerrainScale.scaleIfSelected(enabled,
                        ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE, 600.0) == 300.0,
                "a 600-block outer sweep target becomes 300 blocks");
    }

    private static void oneRestoresOriginalDistance() {
        ProjectExplosiveNuclearTerrainScale.Configuration original =
                ProjectExplosiveNuclearTerrainScale.configuration("true", "1.0");
        check(ProjectExplosiveNuclearTerrainScale.scaleIfSelected(original,
                        ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE, 600.0) == 600.0,
                "multiplier 1.0 restores the original distance");
        check(ProjectExplosiveNuclearTerrainScale.parseMultiplier("1.0") == 1.0,
                "explicit original multiplier is retained");
    }

    private static void onlyTheDirectNuclearBombIsSelected() {
        ProjectExplosiveNuclearTerrainScale.Configuration enabled =
                ProjectExplosiveNuclearTerrainScale.configuration("true", "0.5");
        for (String other : new String[]{"projectexplosive:icbm", "projectexplosive:big_bomb",
                "projectexplosive:small_bomb", "othermod:nuclear_bomb"}) {
            check(ProjectExplosiveNuclearTerrainScale.scaleIfSelected(enabled, other, 600.0) == 600.0,
                    "unselected entity type keeps its original target: " + other);
        }
        check(ProjectExplosiveNuclearTerrainScale.scaleIfSelected(
                        ProjectExplosiveNuclearTerrainScale.configuration("false", "0.5"),
                        ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE, 600.0) == 600.0,
                "OFF keeps the original target");
    }

    private static void persistenceRestoresOriginalWhenFeatureIsTurnedOff() {
        ProjectExplosiveNuclearTerrainScale.Configuration enabled =
                ProjectExplosiveNuclearTerrainScale.configuration("true", "0.5");
        ProjectExplosiveNuclearTerrainScale.Configuration disabled =
                ProjectExplosiveNuclearTerrainScale.configuration("false", "0.5");
        check(ProjectExplosiveNuclearTerrainScale.normalizeTargetForSave(enabled,
                        ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE, 600.0, 300.0) == 600.0,
                "enabled save stores the unscaled target for compatibility");
        check(ProjectExplosiveNuclearTerrainScale.restoreTargetOnLoad(enabled, true,
                        ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE, 600.0, 600.0) == 300.0,
                "enabled reload applies the multiplier once to the saved raw target");
        check(ProjectExplosiveNuclearTerrainScale.restoreTargetOnLoad(enabled, false,
                        ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE, 600.0, 300.0) == 300.0,
                "unmarked legacy saves are not accidentally scaled a second time");
        check(ProjectExplosiveNuclearTerrainScale.restoreTargetOnLoad(disabled, true,
                        ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE, 600.0, 600.0) == 600.0,
                "disabled startup preserves the normalized original target");
        check(ProjectExplosiveNuclearTerrainScale.normalizeTargetForSave(disabled,
                        ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE, 600.0, 300.0) == 300.0,
                "disabled save leaves the vanilla target untouched");
    }

    private static void invalidOptionsPreserveOriginalDistance() {
        check(ProjectExplosiveNuclearTerrainScale.parseEnabled("not-true") == false,
                "invalid enable value is OFF");
        for (String invalid : new String[]{"NaN", "Infinity", "-0.5", "0", "1.01", "oops"}) {
            check(ProjectExplosiveNuclearTerrainScale.parseMultiplier(invalid) == 1.0,
                    "invalid multiplier falls back to the original distance: " + invalid);
        }
        ProjectExplosiveNuclearTerrainScale.Configuration invalid =
                ProjectExplosiveNuclearTerrainScale.configuration("true", "NaN");
        check(ProjectExplosiveNuclearTerrainScale.scaleIfSelected(invalid,
                        ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE, 600.0) == 600.0,
                "malformed enabled configuration still preserves PE's radius");
        check(Double.isNaN(ProjectExplosiveNuclearTerrainScale.scaleIfSelected(
                        ProjectExplosiveNuclearTerrainScale.configuration("true", "0.5"),
                        ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE, Double.NaN)),
                "nonfinite runtime radius is passed through unchanged");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
