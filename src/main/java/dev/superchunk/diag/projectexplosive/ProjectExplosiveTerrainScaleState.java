package dev.superchunk.diag.projectexplosive;

/** Primitive-only state shared by the optional PE scaler and its separate diagnostics mixin. */
public interface ProjectExplosiveTerrainScaleState {
    double superchunk$originalTerrainTarget();

    double superchunk$effectiveTerrainTarget();
}
