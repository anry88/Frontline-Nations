package com.tggames.frontline

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class FrontlineNationsApplication

fun main(args: Array<String>) {
    runApplication<FrontlineNationsApplication>(*args)
}
