package com.example.discoveryservice

import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableEurekaServer
class DiscoveryServiceApplication

fun main(args: Array<String>) {
    runApplication<DiscoveryServiceApplication>(*args)
}
