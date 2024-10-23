/*
 * Copyright (c) 2022-2023, CodeLaser BV, Belgium.
 * Unauthorized copying of this file, via any medium, is strictly prohibited.
 * Proprietary and confidential.
 */

plugins {
    java
    id("maven-publish")
}

group = "org.e2immu"


repositories {
    maven {
        url = uri(project.findProperty("codeartifactUri") as String)
        credentials {
            username = "aws"
            password = project.findProperty("codeartifactToken") as String
        }
    }
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    implementation("org.e2immu:e2immu-external-support:some.version")
    implementation("org.e2immu:e2immu-internal-util:some.version")
    implementation("org.e2immu:e2immu-internal-graph:some.version")
    implementation("org.e2immu:e2immu-cst-api:some.version")
    implementation("org.e2immu:e2immu-cst-io:some.version")
    implementation("org.e2immu:e2immu-cst-analysis:some.version")
    implementation("org.e2immu:e2immu-inspection-api:some.version")
    implementation("org.e2immu:e2immu-shallow-analyzer:some.version")
    implementation("org.e2immu:e2immu-modification-prepwork:some.version")
    implementation("org.e2immu:e2immu-inspection-parser:some.version")
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("org.jgrapht:jgrapht-core:1.5.2")
    implementation("org.jgrapht:jgrapht-io:1.5.2")

    testImplementation("org.jetbrains:annotations:24.1.0")
    testImplementation("org.e2immu:e2immu-cst-impl:some.version")
    testImplementation("org.e2immu:e2immu-cst-print:some.version")
    testImplementation("org.e2immu:e2immu-java-parser:some.version")
    testImplementation("org.e2immu:e2immu-java-bytecode:some.version")
    testImplementation("org.e2immu:e2immu-inspection-integration:some.version")
    testImplementation("org.e2immu:e2immu-inspection-resource:some.version")

    testImplementation("ch.qos.logback:logback-classic:1.5.8")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.3")
}

tasks.test {
    useJUnitPlatform()

    maxHeapSize = "2G"
}
