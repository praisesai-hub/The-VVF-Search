# JVM Coverage Instrumentation Notes

## Verified external references

Gradle documents that `JacocoTaskExtension.isIncludeNoLocationClasses` defaults to `false`, and that the property controls whether classes with no source location are instrumented. The property is supported by JaCoCo 0.7.6 and later. This matters for Robolectric because application classes can be loaded through its sandbox classloader without a normal source location.

- Gradle Kotlin DSL API: <https://docs.gradle.org/current/kotlin-dsl/gradle/org.gradle.testing.jacoco.plugins/-jacoco-task-extension/is-include-no-location-classes.html>

Gradle also documents that applying the core `jacoco` plugin adds `JacocoTaskExtension` to `Test` tasks. The project initially attempted to configure the extension without the plugin and hosted CI failed with `Extension of type 'JacocoTaskExtension' does not exist`; the explicit core plugin application addresses that task-configuration prerequisite.

- Gradle JaCoCo plugin user guide: <https://docs.gradle.org/current/userguide/jacoco_plugin.html>

Robolectric maintainers state that Robolectric supports JaCoCo, while the IDE coverage tool can be the source of failures. A tracked issue documents the historical symptom of zero JaCoCo data for `RobolectricTestRunner` and points to `includeNoLocationClasses` as a practical configuration direction.

- Robolectric issue 8484: <https://github.com/robolectric/robolectric/issues/8484>
- Robolectric issue 5575: <https://github.com/robolectric/robolectric/issues/5575>

## Local hosted evidence

Before enabling no-location instrumentation, hosted JVM report `32248989005` recorded 5.46% aggregate instruction coverage. With the JaCoCo extension enabled in run `32250196735`, the report recorded 27.11%. This confirms that the pre-existing Robolectric tests were executing but were undercounted by the former JaCoCo configuration. The production thresholds remain unchanged and are not yet met.
