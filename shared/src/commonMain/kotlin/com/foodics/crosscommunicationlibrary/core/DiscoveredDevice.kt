package com.foodics.crosscommunicationlibrary.core

import ConnectionType

data class DiscoveredDevice(
    val id: String,
    val name: String,
    val addressByType: Map<ConnectionType, String>,
    val connectionTypes: Set<ConnectionType>
)
