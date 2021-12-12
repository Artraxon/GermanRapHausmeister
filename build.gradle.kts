import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "de.rtrx.a.derap"
version = "1.0.0"

plugins {
    id ("com.bmuschko.docker-remote-api") version("6.7.0")
    id("com.bmuschko.docker-java-application") version "6.7.0"
    kotlin("jvm") version "1.4.0"
    `maven-publish`

}
val GITHUB_USER: String = project.findProperty("gpr.user") as String;
val GITHUB_TOKEN: String = project.findProperty("gpr.token") as String;

repositories {
    jcenter()
    maven {
        name = "GithubPackages"
        url = uri("https://maven.pkg.github.com/Artraxon/unexbot")
        credentials {
            credentials {

                username = GITHUB_USER
                password = GITHUB_TOKEN
            }

        }
    }
}


dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(kotlin("stdlib"))
    implementation("de.rtrx.a:unexbot:2.3.2")
    implementation(kotlin("stdlib-jdk8"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.3.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.3.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.3.1")

    implementation("dev.misfitlabs.kotlinguice4:kotlin-guice:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.0")
    implementation( "net.dean.jraw:JRAW:1.1.0")
    implementation( "org.postgresql:postgresql:42.2.8")
    implementation( "com.google.code.gson:gson:2.8.5")
    implementation(group= "org.slf4j", name= "slf4j-api", version= "1.7.28")
    implementation(group= "org.slf4j", name= "slf4j-jdk14", version= "1.7.28")

    implementation(group="com.google.inject", name= "guice", version = "5.0.1")
    implementation(group="com.google.inject.extensions", name= "guice-assistedinject", version = "5.0.1")
    testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
    testImplementation( "org.mockito:mockito-inline:2.13.0")



    implementation("io.github.microutils:kotlin-logging:1.5.9")
    implementation("com.uchuhimo:konf-core:0.20.0")
    implementation("com.uchuhimo:konf-yaml:0.20.0")
    implementation("org.yaml:snakeyaml:1.25")

}

tasks.test {
    useJUnitPlatform()
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}

tasks.named<Jar>("jar"){
    manifest {
        attributes["Main-Class"] = "de.rtrx.a.derap.MainKt"
    }
}
tasks.register<Jar>("uberJar") {
    archiveClassifier.set("uber")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    manifest {
        attributes["Main-Class"] = "de.rtrx.a.derap.MainKt"
    }
}
docker {

    registryCredentials {

        username.set(GITHUB_USER)
        password.set(GITHUB_TOKEN)
        url.set("https://ghcr.io")
    }

    javaApplication {
        //TODO Find a better way to configure logging
        //this.jvmArgs.set(listOf("-Djava.util.logging.config.file=/app/resources/logging.properties]"))
        ports.set(listOf<Int>())
        baseImage.set("openjdk:8")
        maintainer.set("Artraxon a@rtrx.de")
        images.set(setOf("ghcr.io/artraxon/GermanRapHausmeister/derap-image:${project.version}"))
    }
}
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/artraxon/GermanRapHausmeister")
            credentials {

                username = GITHUB_USER
                password = GITHUB_TOKEN
            }
        }
    }
    publications {
        register<MavenPublication>("gpr") {
            artifactId = "tihibot"
            groupId = "de.rtrx.a.tihi"
            from(components["kotlin"])
        }
    }
}
