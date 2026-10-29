plugins {
    id("java-library")
    id("signing")
    id("com.vanniktech.maven.publish").version("0.36.0")
}

group = "dev.javax"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    // The foreign function API is final from Java 22.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api("dev.javax:bitstream:0.2.0-RC")
    implementation("commons-io:commons-io:2.20.0")

    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")
    testCompileOnly("org.projectlombok:lombok:1.18.46")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.46")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.apache.commons:commons-compress:1.28.0")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The tests load FFmpeg's shared libraries, which a container has (see the Dockerfile).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

signing {
    useGpgCmd()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("sonaudio")
        description.set("Reads tags, duration and decoded audio from any format through FFmpeg's shared libraries")
        url.set("https://github.com/JonathanVusich/sonaudio")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("JonathanVusich")
                name.set("Jonathan Vusich")
                email.set("jonathan@vusich.cloud")
            }
        }

        scm {
            url.set("https://github.com/JonathanVusich/sonaudio")
            connection.set("scm:git:git://github.com/JonathanVusich/sonaudio.git")
            developerConnection.set("scm:git:ssh://git@github.com/JonathanVusich/sonaudio.git")
        }
    }
}
