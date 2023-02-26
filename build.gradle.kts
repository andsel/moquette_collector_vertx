import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  java
  id("com.github.johnrengelman.shadow") version "7.1.2"
}

repositories {
  mavenCentral()
  maven {
    url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
  }
}

dependencies {
  implementation("io.vertx:vertx-core:4.2.1")
  implementation("io.vertx:vertx-web:4.2.1")
  implementation("io.vertx:vertx-web-client:4.2.1")
  implementation("ch.qos.logback:logback-classic:1.2.3")
  implementation("org.apache.commons:commons-compress:1.22")
  implementation("com.maxmind.geoip2:geoip2:4.0.0")
}

tasks {
  named<ShadowJar>("shadowJar") {
    //archiveBaseName.set("shadow")
    mergeServiceFiles()
    manifest {
      attributes(mapOf("Main-Class" to "io.moquette.utils.collector.Main"))
    }
  }
}

tasks.create<JavaExec>("run") {
  main = project.properties.getOrDefault("mainClass", "io.moquette.utils.collector.Main") as String
  classpath = sourceSets["main"].runtimeClasspath
  systemProperties["vertx.logger-delegate-factory-class-name"] = "io.vertx.core.logging.SLF4JLogDelegateFactory"
}

tasks.named<Test>("test") {
  useJUnitPlatform()
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks.wrapper {
  gradleVersion = "7.5.0"
}
