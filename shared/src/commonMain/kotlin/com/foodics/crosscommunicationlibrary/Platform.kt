package com.foodics.crosscommunicationlibrary

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform