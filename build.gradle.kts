plugins {
    alias(libs.plugins.android.application) apply false
}

tasks.wrapper {
    validateDistributionUrl = false
}
