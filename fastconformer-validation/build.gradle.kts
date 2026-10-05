import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyPinnedSherpaAar : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val aarFile: RegularFileProperty

  @TaskAction
  fun verify() {
    val file = aarFile.get().asFile
    check(file.length() == 38_691_998L) { "Run scripts/fetch_fastconformer_runtime.sh to stage the pinned sherpa AAR" }
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
      .joinToString("") { "%02x".format(it) }
    check(digest == "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471") {
      "Pinned sherpa AAR SHA-256 mismatch: $digest"
    }
  }
}

plugins {
  alias(libs.plugins.android.application)
}

val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar")
val verifySherpaAar = tasks.register<VerifyPinnedSherpaAar>("verifySherpaAar") {
  aarFile.set(sherpaAar)
}

android {
  namespace = "dev.sebastian.vozlocal.fastconformervalidation"
  compileSdk = 36
  defaultConfig {
    applicationId = "dev.sebastian.vozlocal.fastconformer.validation"
    minSdk = 26
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk { abiFilters += setOf("arm64-v8a") }
  }
  buildTypes {
    release { isMinifyEnabled = false }
  }
}

tasks.named("preBuild").configure { dependsOn(verifySherpaAar) }

dependencies {
  implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation(libs.androidx.core)
}
