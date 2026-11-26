import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:session"))
    implementation(libs.jna)
    implementation(libs.dbus.core)
    // dbus-java with the junixsocket transport: the only one that passes file descriptors, which
    // BlueZ uses to hand over an RFCOMM connection. Only the classes the transport needs ship (5
    // jars with slf4j): the junixsocket-core umbrella and three annotation-only jars are left out.
    implementation(libs.dbus.junixsocket) {
        exclude(group = "com.kohlschutter.junixsocket", module = "junixsocket-core")
        exclude(group = "com.kohlschutter", module = "compiler-annotations")
    }
    runtimeOnly(libs.junixsocket.common)
    runtimeOnly(libs.junixsocket.native.common)
    testImplementation(project(":core:vendors"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
