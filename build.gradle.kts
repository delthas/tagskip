import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.50"
	maven
    `maven-publish`
}

group = "fr.delthas"
version = "0.1.1-SNAPSHOT"

val deployerJars: Configuration by configurations.creating

repositories {
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.slf4j:slf4j-api:1.7.25")
    testImplementation("junit:junit:4.12")
    testImplementation("org.slf4j:slf4j-simple:1.7.25")
    testImplementation("commons-net:commons-net:3.6")
    deployerJars("org.apache.maven.wagon:wagon-ftp:2.2")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.test {
    testLogging {
        showStandardStreams = true
    }
}

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allJava)
}

tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from(tasks.javadoc.get().destinationDir)
}

tasks.named<Upload>("uploadArchives") {
    repositories.withGroovyBuilder {
        "mavenDeployer" {
            setProperty("configuration", deployerJars)
            "repository"("url" to "ftp://saucisseroyale.cc/public/maven_repo/") {
                "authentication"("userName" to project.properties["ftp.username"], "password" to project.properties["ftp.password"])
            }
        }
    }
}

