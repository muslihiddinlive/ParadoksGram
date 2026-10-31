package uz.paradoks.gram;

/** Build number of this APK. The CI replaces NUMBER with the workflow run number (= release tag build-N). */
public final class ParadoksBuild {
    public static final int NUMBER = 0;
    /** Version shown to users, e.g. "3.5.0". The CI copies it from the VERSION file. */
    public static final String VERSION = "3.6.0";

    private ParadoksBuild() {
    }
}
