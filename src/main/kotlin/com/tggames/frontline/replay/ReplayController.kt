package com.tggames.frontline.replay

import org.springframework.core.io.FileSystemResource
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID

@RestController
class ReplayController(private val replays: ReplayService) {
    @GetMapping("/api/v1/replays/{kind}/{id}.mp4", produces = ["video/mp4"])
    fun replay(
        @PathVariable kind: String,
        @PathVariable id: UUID,
        @RequestParam token: String,
    ): ResponseEntity<FileSystemResource> {
        val replayKind = ReplayKind.fromPath(kind) ?: return ResponseEntity.notFound().build()
        val file = replays.open(replayKind, id, token) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("video/mp4"))
            .contentLength(file.contentLength)
            .cacheControl(CacheControl.maxAge(Duration.ofHours(48)).cachePrivate().immutable())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=frontline-replay-$id.mp4")
            .body(FileSystemResource(file.path))
    }
}
