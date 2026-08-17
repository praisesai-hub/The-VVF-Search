// Top-level build file where you can add configuration options common to all sub-projects/modules.

// These constraints cover transitive dependencies of Gradle build plugins. They
// intentionally precede the plugins block, because plugin resolution happens
// before project configurations are available.
buildscript {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
  dependencies {
    constraints {
      classpath("org.jdom:jdom2:2.0.6.1") {
        version { strictly("2.0.6.1") }
        because("GHSA-2363-cqg2-863c: prevent JDOM XXE in build tooling")
      }
      classpath("org.bouncycastle:bcprov-jdk18on:1.85.2") {
        version { strictly("1.85.2") }
        because("GHSA-574f-3g2m-x479 and GHSA-c3fc-8qff-9hwx")
      }
      classpath("org.bouncycastle:bcpkix-jdk18on:1.85") {
        version { strictly("1.85") }
        because("GHSA-wg6q-6289-32hp")
      }
      classpath("io.netty:netty-buffer:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("io.netty:netty-codec:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("io.netty:netty-codec-http:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("io.netty:netty-codec-http2:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("io.netty:netty-common:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("io.netty:netty-handler:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("io.netty:netty-handler-proxy:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("io.netty:netty-resolver:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("io.netty:netty-transport:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("io.netty:netty-transport-classes-epoll:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("io.netty:netty-transport-native-epoll:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("io.netty:netty-transport-native-unix-common:4.1.136.Final") { version { strictly("4.1.136.Final") } }
      classpath("org.apache.commons:commons-lang3:3.18.0") {
        version { strictly("3.18.0") }
        because("GHSA-j288-q9x7-2f5v")
      }
      classpath("org.apache.httpcomponents:httpclient:4.5.14") {
        version { strictly("4.5.14") }
        because("GHSA-7r82-7xv7-xcpj")
      }
      classpath("org.bitbucket.b_c:jose4j:0.9.6") {
        version { strictly("0.9.6") }
        because("GHSA-3677-xxcr-wjqv")
      }
    }
  }
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.firebase.crashlytics) apply false
}

// Security floor for application, unit-test, and instrumented-test dependency
// graphs. A rule is a no-op unless its module is already transitively present.
allprojects {
  configurations.configureEach {
    resolutionStrategy.force(
      "org.jdom:jdom2:2.0.6.1",
      "org.bouncycastle:bcprov-jdk18on:1.85.2",
      "org.bouncycastle:bcpkix-jdk18on:1.85",
      "io.netty:netty-buffer:4.1.136.Final",
      "io.netty:netty-codec:4.1.136.Final",
      "io.netty:netty-codec-http:4.1.136.Final",
      "io.netty:netty-codec-http2:4.1.136.Final",
      "io.netty:netty-common:4.1.136.Final",
      "io.netty:netty-handler:4.1.136.Final",
      "io.netty:netty-handler-proxy:4.1.136.Final",
      "io.netty:netty-resolver:4.1.136.Final",
      "io.netty:netty-transport:4.1.136.Final",
      "io.netty:netty-transport-classes-epoll:4.1.136.Final",
      "io.netty:netty-transport-native-epoll:4.1.136.Final",
      "io.netty:netty-transport-native-unix-common:4.1.136.Final",
      "org.apache.commons:commons-lang3:3.18.0",
      "org.apache.httpcomponents:httpclient:4.5.14",
      "org.bitbucket.b_c:jose4j:0.9.6",
    )
  }
}
