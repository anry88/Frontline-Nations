package com.tggames.frontline.replay

import com.tggames.frontline.config.FrontlineProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

class ReplayVideoEncoderTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `encodes square bgr frames as playable mp4 when ffmpeg is installed`() {
        assumeTrue(runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        val properties = FrontlineProperties(replay = FrontlineProperties.Replay(width = 320, fps = 3))
        val encoder = ReplayVideoEncoder(properties)
        val destination = directory.resolve("replay.mp4")
        val frames = sequence<BufferedImage> {
            repeat(6) { index ->
                yield(BufferedImage(320, 320, BufferedImage.TYPE_3BYTE_BGR).apply {
                    val graphics = createGraphics()
                    graphics.color = if (index % 2 == 0) Color.BLUE else Color.RED
                    graphics.fillRect(0, 0, width, height)
                    graphics.dispose()
                })
            }
        }

        val count = encoder.encode(frames, destination)

        assertThat(count).isEqualTo(6)
        assertThat(Files.size(destination)).isGreaterThan(100)
        assertThat(Files.readAllBytes(destination).decodeToString(4, 8)).isEqualTo("ftyp")
        val probe = ProcessBuilder(
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", destination.toString(),
        ).redirectErrorStream(true).start()
        val duration = probe.inputStream.bufferedReader().readText().trim().toDouble()
        assertThat(probe.waitFor()).isZero()
        assertThat(duration).isCloseTo(2.0, offset(0.1))
        assertThat(FrontlineProperties.Replay().fps).isEqualTo(3)
    }
}
