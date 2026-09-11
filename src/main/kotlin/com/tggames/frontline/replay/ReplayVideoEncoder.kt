package com.tggames.frontline.replay

import com.tggames.frontline.config.FrontlineProperties
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption

@Component
class ReplayVideoEncoder(private val properties: FrontlineProperties) {
    fun encode(frames: Sequence<BufferedImage>, destination: Path): Int {
        val replay = properties.replay
        require(replay.width in 320..1280) { "Replay width must be between 320 and 1280" }
        require(replay.fps in 2..24) { "Replay fps must be between 2 and 24" }
        Files.createDirectories(destination.parent)
        val temporary = destination.resolveSibling("${destination.fileName}.tmp.mp4")
        Files.deleteIfExists(temporary)
        val process = ProcessBuilder(
            replay.ffmpegPath,
            "-y", "-loglevel", "error",
            "-f", "rawvideo",
            "-pixel_format", "bgr24",
            "-video_size", "${replay.width}x${replay.width}",
            "-framerate", replay.fps.toString(),
            "-i", "pipe:0",
            "-an",
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "29",
            "-maxrate", "1800k",
            "-bufsize", "3600k",
            "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            "-f", "mp4",
            temporary.toString(),
        ).redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()

        var frameCount = 0
        try {
            process.outputStream.buffered().use { output ->
                frames.forEach { frame ->
                    require(frame.width == replay.width && frame.height == replay.width) { "Unexpected replay frame size" }
                    require(frame.type == BufferedImage.TYPE_3BYTE_BGR) { "Replay frames must use BGR24" }
                    output.write((frame.raster.dataBuffer as DataBufferByte).data)
                    frameCount++
                }
            }
            val exit = process.waitFor()
            check(exit == 0 && Files.size(temporary) > 0) { "ffmpeg failed with exit code $exit" }
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            return frameCount
        } catch (error: Exception) {
            process.destroyForcibly()
            Files.deleteIfExists(temporary)
            throw error
        }
    }
}
