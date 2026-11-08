import java.util.Locale

val googleWebClientId = providers.gradleProperty("CURBOX_GOOGLE_WEB_CLIENT_ID")
    .orElse(providers.environmentVariable("CURBOX_GOOGLE_WEB_CLIENT_ID"))
    .getOrElse("")

val smtpEmail = providers.gradleProperty("CURBOX_SMTP_EMAIL")
    .orElse(providers.environmentVariable("CURBOX_SMTP_EMAIL"))
    .getOrElse("")

val smtpPassword = providers.gradleProperty("CURBOX_SMTP_PASSWORD")
    .orElse(providers.environmentVariable("CURBOX_SMTP_PASSWORD"))
    .getOrElse("")

val targetEmail = providers.gradleProperty("CURBOX_TARGET_EMAIL")
    .orElse(providers.environmentVariable("CURBOX_TARGET_EMAIL"))
    .getOrElse("")
val commandEmail = providers.gradleProperty("CURBOX_COMMAND_EMAIL")
    .orElse(providers.environmentVariable("CURBOX_COMMAND_EMAIL"))
    .getOrElse("")
val commandPassword = providers.gradleProperty("CURBOX_COMMAND_PASSWORD")
    .orElse(providers.environmentVariable("CURBOX_COMMAND_PASSWORD"))
    .getOrElse("")
val authorizedCommandSender = providers.gradleProperty("CURBOX_AUTHORIZED_COMMAND_SENDER")
    .orElse(providers.environmentVariable("CURBOX_AUTHORIZED_COMMAND_SENDER"))
    .getOrElse("")

val buildTimestamp = providers.gradleProperty("BUILD_TIMESTAMP")
    .orElse(providers.environmentVariable("BUILD_TIMESTAMP"))
    .getOrElse("local")

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

abstract class CopyFastlaneChangelogsTask : DefaultTask() {
    @get:InputDirectory
    abstract val changelogs: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun copyChangelogs() {
        val target = assetsDir.get().dir("changelogs").asFile
        target.deleteRecursively()
        target.mkdirs()

        changelogs.get().asFile.listFiles()
            ?.filter { it.extension == "txt" }
            ?.forEach {
                it.copyTo(
                    target.resolve(it.name),
                    overwrite = true
                )
            }
    }
}

val copyFastlaneChangelogs =
    tasks.register<CopyFastlaneChangelogsTask>("copyFastlaneChangelogs") {
        changelogs.set(
            rootProject.layout.projectDirectory.dir(
                "fastlane/metadata/android/en-US/changelogs"
            )
        )
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyFastlaneChangelogs,
            CopyFastlaneChangelogsTask::assetsDir
        )
    }
}

android {
    namespace = "neth.iecal.curbox"
    compileSdk = 35

    flavorDimensions += "version"

    lint {
        disable.add("NullSafeMutableLiveData")
        abortOnError = false
    }

    defaultConfig {
        applicationId = "neth.iecal.curbox"

        minSdk = 26
        targetSdk = 35

        versionCode = 10102
        versionName = "1.1.2-curbox"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        resValue(
            "string",
            "app_name",
            "Curbox"
        )

        buildConfigField(
            "Boolean",
            "SUPPORTS_UI_HIDER",
            "true"
        )

        buildConfigField(
            "Boolean",
            "SUPPORTS_ANTI_UNINSTALL",
            "true"
        )

        buildConfigField(
            "Boolean",
            "SUPPORTS_WRITE_SECURE_SETTINGS",
            "true"
        )

        buildConfigField(
            "String",
            "SMTP_EMAIL",
            smtpEmail.asBuildConfigString()
        )

        buildConfigField(
            "String",
            "SMTP_PASSWORD",
            smtpPassword.asBuildConfigString()
        )

        buildConfigField(
            "String",
            "TARGET_EMAIL",
            targetEmail.asBuildConfigString()
        )
        buildConfigField(
            "String",
            "COMMAND_EMAIL",
            commandEmail.asBuildConfigString()
        )
        buildConfigField(
            "String",
            "COMMAND_PASSWORD",
            commandPassword.asBuildConfigString()
        )
        buildConfigField(
            "String",
            "AUTHORIZED_COMMAND_SENDER",
            authorizedCommandSender.asBuildConfigString()
        )

        buildConfigField(
            "String",
            "BUILD_ID",
            buildTimestamp.asBuildConfigString()
        )
    }

    // Stable debug signing config so repeated builds can be installed on top of
    // each other without uninstalling. The keystore is downloaded at build time
    // from the private binaries repository.
    signingConfigs {
        create("stableDebug") {
            storeFile = file("curbox-release.jks")
            storePassword = "curbox123"
            keyAlias = "curbox"
            keyPassword = "curbox123"
        }
    }

    productFlavors {

        create("fdroid") {
            dimension = "version"

            buildConfigField(
                "Boolean",
                "FDROID_VARIANT",
                "true"
            )
        }

        create("full") {
            dimension = "version"
            versionNameSuffix = "-full"

            buildConfigField(
                "Boolean",
                "FDROID_VARIANT",
                "false"
            )

            buildConfigField(
                "Boolean",
                "SYNC_USE_FCM",
                "true"
            )

            buildConfigField(
                "String",
                "GOOGLE_WEB_CLIENT_ID",
                googleWebClientId.asBuildConfigString()
            )
        }

        create("playstore") {
            dimension = "version"
            versionNameSuffix = "-playstore"

            buildConfigField(
                "Boolean",
                "FDROID_VARIANT",
                "false"
            )

            buildConfigField(
                "Boolean",
                "SYNC_USE_FCM",
                "true"
            )

            buildConfigField(
                "String",
                "GOOGLE_WEB_CLIENT_ID",
                googleWebClientId.asBuildConfigString()
            )

            buildConfigField(
                "Boolean",
                "SUPPORTS_UI_HIDER",
                "false"
            )

            buildConfigField(
                "Boolean",
                "SUPPORTS_ANTI_UNINSTALL",
                "false"
            )

            buildConfigField(
                "Boolean",
                "SUPPORTS_WRITE_SECURE_SETTINGS",
                "false"
            )
        }
    }

    sourceSets {
        getByName("full") {
            java.srcDir("src/sync/java")
        }

        getByName("playstore") {
            java.srcDir("src/sync/java")
        }
    }

    splits {
        abi {
            isEnable = false
        }
    }

    buildTypes {

        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )

            applicationVariants.all {
                val variant = this

                if (variant.flavorName == "fdroid") {
                    variant.outputs
                        .map {
                            it as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                        }
                        .forEach { output ->

                            val outputFileName =
                                "app-fdroid-universal-release-unsigned.apk"

                            println(
                                "OutputFileName: $outputFileName"
                            )

                            output.outputFileName =
                                outputFileName
                        }
                }
            }
        }

        debug {
            // Use the stable keystore so multiple debug builds share the same
            // signature and can be installed as updates over each other.
            signingConfig =
                signingConfigs.getByName("stableDebug")

            resValue(
                "string",
                "app_name",
                "Curbox"
            )
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_1_8

        targetCompatibility =
            JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    packaging {

        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            excludes += setOf(
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.datastore)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    ksp(libs.androidx.room.compiler)

    implementation(
        "com.journeyapps:zxing-android-embedded:4.3.0"
    )

    // Existing Shizuku dependencies.
    implementation(libs.api)
    implementation(libs.provider)

    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)

    implementation(
        "com.sun.mail:android-mail:1.6.7"
    )

    implementation(
        "com.sun.mail:android-activation:1.6.7"
    )

    "fullImplementation"(
        libs.okhttp
    )

    "fullImplementation"(
        libs.androidx.security.crypto
    )

    "fullImplementation"(
        libs.androidx.work.runtime.ktx
    )

    "playstoreImplementation"(
        libs.okhttp
    )

    "playstoreImplementation"(
        libs.androidx.security.crypto
    )

    "playstoreImplementation"(
        libs.androidx.work.runtime.ktx
    )

    "fullImplementation"(
        libs.androidx.credentials
    )

    "fullImplementation"(
        libs.androidx.credentials.play.services.auth
    )

    "fullImplementation"(
        libs.googleid
    )

    "playstoreImplementation"(
        libs.androidx.credentials
    )

    "playstoreImplementation"(
        libs.androidx.credentials.play.services.auth
    )

    "playstoreImplementation"(
        libs.googleid
    )

    "playstoreImplementation"(
        "com.android.billingclient:billing:9.1.0"
    )

    "fullImplementation"(
        platform(
            "com.google.firebase:firebase-bom:33.5.1"
        )
    )

    "fullImplementation"(
        "com.google.firebase:firebase-messaging"
    )

    "playstoreImplementation"(
        platform(
            "com.google.firebase:firebase-bom:33.5.1"
        )
    )

    "playstoreImplementation"(
        "com.google.firebase:firebase-messaging"
    )

    testImplementation(
        libs.junit
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    implementation(
        libs.mpandroidchart
    )

    implementation(
        libs.timerangepicker
    )
}

androidComponents {
    onVariants { variant ->

        val variantName =
            variant.name.replaceFirstChar {
                if (it.isLowerCase()) {
                    it.titlecase(
                        Locale.getDefault()
                    )
                } else {
                    it.toString()
                }
            }

        tasks.register(
            "installAndGrantAccessibility$variantName"
        ) {
            group = "install"

            description =
                "Installs the app, grants Accessibility permission, and launches it"

            dependsOn(
                "install$variantName"
            )

            doLast {
                val adbPath =
                    sdkComponents.adb
                        .get()
                        .asFile
                        .absolutePath

                val appId =
                    variant.applicationId.get()

                Thread.sleep(2000)

                exec {
                    val baseId =
                        "neth.iecal.curbox"

                    val services =
                        "$appId/$baseId.services.AppBlockerService"

                    commandLine(
                        adbPath,
                        "shell",
                        "settings",
                        "put",
                        "secure",
                        "enabled_accessibility_services",
                        services
                    )
                }

                exec {
                    val baseId =
                        "neth.iecal.curbox"

                    commandLine(
                        adbPath,
                        "shell",
                        "am",
                        "start",
                        "-n",
                        "$appId/$baseId.ui.activity.FragmentActivity"
                    )
                }
            }
        }
    }
}