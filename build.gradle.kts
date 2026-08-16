// Top-level build file. Plugins are declared here (without applying them) so
// that the :app module can pull them in with `alias(...)` from the catalog.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.androidx.room3) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
