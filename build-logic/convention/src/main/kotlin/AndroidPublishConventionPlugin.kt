import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import github.leavesczy.alphavideoplayer.alphaVideoPlayerVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

class AndroidPublishConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(receiver = target) {
            apply(plugin = "com.vanniktech.maven.publish")
            val publication = publicationFor(projectName = name)
            extensions.configure<MavenPublishBaseExtension> {
                publishToMavenCentral()
                // Maven Central requires signing; skip when JITPACK=true (no GPG there).
                if (System.getenv("JITPACK") != "true") {
                    signAllPublications()
                }
                configure(platform = AndroidSingleVariantLibrary())
                coordinates(
                    groupId = "io.github.leavesczy",
                    artifactId = publication.artifactId,
                    version = alphaVideoPlayerVersion()
                )
                pom {
                    name.set(publication.name)
                    description.set(publication.description)
                    inceptionYear.set("2026")
                    url.set("https://github.com/leavesczy/AlphaVideoPlayer")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("leavesczy")
                            name.set("leavesczy")
                            url.set("https://github.com/leavesczy")
                        }
                    }
                    scm {
                        url.set("https://github.com/leavesczy/AlphaVideoPlayer")
                        connection.set("scm:git:git://github.com/leavesczy/AlphaVideoPlayer.git")
                        developerConnection.set("scm:git:ssh://git@github.com/leavesczy/AlphaVideoPlayer.git")
                    }
                }
            }
        }
    }

    private fun publicationFor(projectName: String): Publication {
        return when (projectName) {
            "alphavideoplayer" -> Publication(
                artifactId = "alphavideoplayer",
                name = "AlphaVideoPlayer",
                description = "Android library for playing half-frame packed RGB + alpha videos via OpenGL ES"
            )

            "exoplayer" -> Publication(
                artifactId = "alphavideoplayer-exoplayer",
                name = "AlphaVideoPlayer ExoPlayer",
                description = "Optional Media3 ExoPlayer backend for AlphaVideoPlayer"
            )

            else -> error(message = "Unknown publish module: $projectName")
        }
    }

    private data class Publication(
        val artifactId: String,
        val name: String,
        val description: String
    )

}
