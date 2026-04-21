package com.foodics.crosscommunicationlibrary.core

data class ConnectedClient(val id: String, val name: String)
data class ClientMessage(val client: ConnectedClient, val data: ByteArray)
